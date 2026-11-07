package com.sasayaki.player.data.vfs

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
    val encrypted: Boolean,
    val modified: Long,
)

/**
 * Minimal ZIP central-directory reader. Supports ZIP64, the UTF-8 flag, the Info-ZIP unicode path
 * extra field and falls back to Shift-JIS (CP932) for legacy Japanese archives.
 * Nothing is extracted: entries are later read in place via [ZipEntryReader].
 */
object ZipIndex {
    private const val EOCD_SIG = 0x06054b50
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val CEN_SIG = 0x02014b50
    private const val LOC_SIG = 0x04034b50

    private val cp932: Charset by lazy {
        runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }
    }

    fun read(file: File): List<ZipEntryInfo> = RandomAccessFile(file, "r").use { raf -> read(raf) }

    private fun read(raf: RandomAccessFile): List<ZipEntryInfo> {
        val len = raf.length()
        if (len < 22) throw IOException("Not a zip file")
        val tailLen = minOf(len, 22L + 65535L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen)
        raf.readFully(tail)
        val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (tb.getInt(i) == EOCD_SIG) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("End of central directory not found")
        var total = (tb.getShort(eocd + 10).toInt() and 0xFFFF).toLong()
        var cdSize = tb.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = tb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        val eocdAbs = len - tailLen + eocd

        if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val locPos = eocdAbs - 20
            if (locPos >= 0) {
                val loc = readBuf(raf, locPos, 20)
                if (loc.getInt(0) == ZIP64_LOCATOR_SIG) {
                    val z64Pos = loc.getLong(8)
                    val z = readBuf(raf, z64Pos, 56)
                    if (z.getInt(0) == ZIP64_EOCD_SIG) {
                        total = z.getLong(32)
                        cdSize = z.getLong(40)
                        cdOffset = z.getLong(48)
                    }
                }
            }
        }
        // Self-extracting or prefixed archives: central directory offset is relative to an unknown base.
        var base = 0L
        if (cdOffset + cdSize <= eocdAbs) {
            val probe = readBuf(raf, cdOffset, 4)
            if (probe.getInt(0) != CEN_SIG) {
                base = eocdAbs - cdSize - cdOffset
            }
        }
        if (cdSize > Int.MAX_VALUE) throw IOException("Central directory too large")
        val cd = readBuf(raf, cdOffset + base, cdSize.toInt())
        val out = ArrayList<ZipEntryInfo>(total.coerceAtMost(100_000).toInt())
        var p = 0
        while (p + 46 <= cd.limit() && cd.getInt(p) == CEN_SIG) {
            val flags = cd.getShort(p + 8).toInt() and 0xFFFF
            val method = cd.getShort(p + 10).toInt() and 0xFFFF
            val time = cd.getShort(p + 12).toInt() and 0xFFFF
            val date = cd.getShort(p + 14).toInt() and 0xFFFF
            var csize = cd.getInt(p + 20).toLong() and 0xFFFFFFFFL
            var usize = cd.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cd.getShort(p + 32).toInt() and 0xFFFF
            var lho = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val nameBytes = ByteArray(nameLen)
            cd.position(p + 46); cd.get(nameBytes)
            var unicodeName: String? = null
            var e = p + 46 + nameLen
            val extraEnd = e + extraLen
            while (e + 4 <= extraEnd) {
                val id = cd.getShort(e).toInt() and 0xFFFF
                val sz = cd.getShort(e + 2).toInt() and 0xFFFF
                var q = e + 4
                if (id == 0x0001) {
                    if (usize == 0xFFFFFFFFL && q + 8 <= e + 4 + sz) { usize = cd.getLong(q); q += 8 }
                    if (csize == 0xFFFFFFFFL && q + 8 <= e + 4 + sz) { csize = cd.getLong(q); q += 8 }
                    if (lho == 0xFFFFFFFFL && q + 8 <= e + 4 + sz) { lho = cd.getLong(q) }
                } else if (id == 0x7075 && sz > 5) {
                    val b = ByteArray(sz - 5)
                    cd.position(e + 9); cd.get(b)
                    unicodeName = String(b, Charsets.UTF_8)
                }
                e += 4 + sz
            }
            val name = (unicodeName ?: decodeName(nameBytes, flags and 0x800 != 0)).replace('\\', '/')
            out += ZipEntryInfo(
                name = name,
                isDirectory = name.endsWith("/"),
                method = method,
                compressedSize = csize,
                size = usize,
                localHeaderOffset = lho + base,
                encrypted = flags and 1 != 0,
                modified = dosToMillis(date, time),
            )
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    private fun decodeName(bytes: ByteArray, utf8Flag: Boolean): String {
        if (utf8Flag) return String(bytes, Charsets.UTF_8)
        if (bytes.all { it >= 0 }) return String(bytes, Charsets.US_ASCII)
        // Many tools write UTF-8 without setting the flag, so try a strict decode first.
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
        }
        return String(bytes, cp932)
    }

    private fun dosToMillis(date: Int, time: Int): Long {
        if (date == 0) return 0
        val cal = java.util.Calendar.getInstance()
        cal.set(
            ((date shr 9) and 0x7F) + 1980, ((date shr 5) and 0x0F) - 1, date and 0x1F,
            (time shr 11) and 0x1F, (time shr 5) and 0x3F, (time and 0x1F) * 2,
        )
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun readBuf(raf: RandomAccessFile, pos: Long, len: Int): ByteBuffer {
        val b = ByteArray(len)
        raf.seek(pos)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** Absolute offset of an entry's data, derived from its local header. */
    fun dataOffset(raf: RandomAccessFile, localHeaderOffset: Long): Long {
        val h = readBuf(raf, localHeaderOffset, 30)
        if (h.getInt(0) != LOC_SIG) throw IOException("Bad local header")
        val n = h.getShort(26).toInt() and 0xFFFF
        val x = h.getShort(28).toInt() and 0xFFFF
        return localHeaderOffset + 30 + n + x
    }
}

/**
 * Random access to a single entry inside an archive without extracting it.
 * Stored entries support true random access; deflated entries are inflated forward
 * (seeking backwards restarts the inflater).
 */
class ZipEntryReader(
    file: File,
    private val localHeaderOffset: Long,
    val method: Int,
    private val compressedSize: Long,
    val size: Long,
) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val dataStart = ZipIndex.dataOffset(raf, localHeaderOffset)

    private var inflaterStream: InputStream? = null
    private var inflaterPos = 0L

    init {
        if (method != 0 && method != 8) {
            raf.close()
            throw IOException("Unsupported zip compression method $method")
        }
    }

    /** Reads up to [len] bytes at [position]; returns -1 at end of entry. */
    fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        val n = minOf(len.toLong(), size - position).toInt()
        if (n == 0) return 0
        return if (method == 0) {
            synchronized(raf) {
                raf.seek(dataStart + position)
                raf.read(buf, off, n)
            }
        } else {
            synchronized(this) {
                if (inflaterStream == null || position < inflaterPos) restartInflater()
                skipInflated(position - inflaterPos)
                val r = inflaterStream!!.read(buf, off, n)
                if (r > 0) inflaterPos += r
                r
            }
        }
    }

    private fun restartInflater() {
        inflaterStream?.close()
        val bounded = RafRangeStream(raf, dataStart, compressedSize)
        inflaterStream = InflaterInputStream(bounded, Inflater(true), 64 * 1024)
        inflaterPos = 0
    }

    private fun skipInflated(count: Long) {
        var left = count
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            val r = inflaterStream!!.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (r < 0) throw IOException("Unexpected end of deflated entry")
            left -= r
            inflaterPos += r
        }
    }

    fun openStream(): InputStream = if (method == 0) {
        RafRangeStream(raf, dataStart, size)
    } else {
        InflaterInputStream(RafRangeStream(raf, dataStart, compressedSize), Inflater(true), 64 * 1024)
    }

    override fun close() {
        inflaterStream?.close()
        raf.close()
    }
}

private class RafRangeStream(
    private val raf: RandomAccessFile,
    private val start: Long,
    private val length: Long,
) : InputStream() {
    private var pos = 0L

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) == 1) b[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val n = minOf(len.toLong(), length - pos).toInt()
        val r = synchronized(raf) {
            raf.seek(start + pos)
            raf.read(b, off, n)
        }
        if (r > 0) pos += r
        return r
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), length - pos).toInt()
}
