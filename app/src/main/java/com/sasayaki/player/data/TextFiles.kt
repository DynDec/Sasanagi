package com.sasayaki.player.data

import com.sasayaki.player.data.vfs.Vfs
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decodes text that may be UTF-8, UTF-16 or Shift-JIS (common for Japanese readme files). */
object TextFiles {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
        }
        val sjis = runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }
        return String(bytes, sjis)
    }

    fun read(ref: Ref, limit: Long = 2L * 1024 * 1024): String = decode(Vfs.readBytes(ref, limit)).replace("\r\n", "\n")
}

/** A timed subtitle line. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Parses LRC, WebVTT and SRT subtitle files. */
object Subtitles {
    private val LRC_TIME = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val VTT_TIME = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})")

    fun load(ref: Ref): List<Cue> = runCatching { parse(TextFiles.read(ref), Formats.ext(ref.fileName)) }.getOrDefault(emptyList())

    fun parse(text: String, ext: String): List<Cue> = if (ext == "lrc") parseLrc(text) else parseBlocks(text)

    private fun frac(s: String?): Long = when (s?.length) {
        null, 0 -> 0
        1 -> s.toLong() * 100
        2 -> s.toLong() * 10
        else -> s.take(3).toLong()
    }

    private fun parseLrc(text: String): List<Cue> {
        val raw = ArrayList<Pair<Long, String>>()
        for (line in text.lineSequence()) {
            val times = LRC_TIME.findAll(line).toList()
            if (times.isEmpty()) continue
            val body = line.substring(times.last().range.last + 1).trim()
            for (m in times) {
                val ms = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + frac(m.groupValues[3])
                raw += ms to body
            }
        }
        raw.sortBy { it.first }
        return raw.mapIndexedNotNull { i, (start, body) ->
            if (body.isBlank()) return@mapIndexedNotNull null
            val end = raw.getOrNull(i + 1)?.first ?: (start + 10_000)
            Cue(start, end, body)
        }
    }

    private fun parseBlocks(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val m = VTT_TIME.find(lines[i])
            if (m == null) { i++; continue }
            val g = m.groupValues
            fun t(h: String, mi: String, s: String, f: String) =
                (h.toLongOrNull() ?: 0) * 3_600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + frac(f)
            val start = t(g[1], g[2], g[3], g[4])
            val end = t(g[5], g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].replace(Regex("<[^>]+>"), ""))
                i++
            }
            if (body.isNotBlank()) out += Cue(start, end, body.toString())
        }
        return out.sortedBy { it.startMs }
    }
}
