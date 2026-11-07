package com.sasayaki.player.data.vfs

import android.media.MediaDataSource
import com.sasayaki.player.data.Formats
import com.sasayaki.player.data.Ref
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/** A node of the virtual file system that unifies plain folders and ZIP archive contents. */
class VNode(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val ref: Ref? = null,
    val children: MutableList<VNode> = mutableListOf(),
) {
    val ext: String get() = Formats.ext(name)

    fun child(name: String): VNode? = children.firstOrNull { it.name == name }

    fun walk(prefix: String = "", visit: (path: String, node: VNode) -> Unit) {
        for (c in children) {
            val p = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
            visit(p, c)
            if (c.isDir) c.walk(p, visit)
        }
    }
}

object Vfs {
    private const val MAX_DEPTH = 12

    fun isJunk(name: String): Boolean =
        name.startsWith("._") || name == "__MACOSX" || name == ".DS_Store" ||
            name.equals("Thumbs.db", true) || name.equals("desktop.ini", true) || name.startsWith(".")

    /** Builds a tree for a plain folder. Zips found inside are mounted as directories. */
    fun fromFolder(dir: File, depth: Int = 0): VNode {
        val node = VNode(dir.name, true, modified = dir.lastModified())
        if (depth > MAX_DEPTH) return node
        val files = dir.listFiles() ?: return node
        for (f in files) {
            if (isJunk(f.name)) continue
            if (f.isDirectory) {
                node.children += fromFolder(f, depth + 1)
            } else if (Formats.isArchive(f.name)) {
                runCatching { fromZip(f) }.getOrNull()?.let { node.children += it }
            } else {
                node.children += VNode(
                    f.name, false, f.length(), f.lastModified(),
                    Ref(f.absolutePath, size = f.length()),
                )
            }
        }
        return node
    }

    /** Builds a tree for a zip archive. The returned node is named after the archive file. */
    fun fromZip(file: File): VNode {
        val root = VNode(file.name, true, file.length(), file.lastModified())
        val entries = ZipIndex.read(file)
        for (e in entries) {
            if (e.encrypted) continue
            val parts = e.name.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.any { isJunk(it) }) continue
            var cur = root
            val dirParts = if (e.isDirectory) parts else parts.dropLast(1)
            for (part in dirParts) {
                cur = cur.child(part)?.takeIf { it.isDir } ?: VNode(part, true, modified = e.modified).also { cur.children += it }
            }
            if (!e.isDirectory) {
                val leaf = parts.last()
                // Nested archives are not mounted; they would require extraction.
                if (Formats.isArchive(leaf)) continue
                cur.children += VNode(
                    leaf, false, e.size, e.modified,
                    Ref(
                        path = file.absolutePath, entry = e.name, size = e.size,
                        lho = e.localHeaderOffset, method = e.method, csize = e.compressedSize,
                    ),
                )
            }
        }
        return root
    }

    fun exists(ref: Ref): Boolean = File(ref.path).exists()

    fun openReader(ref: Ref): ZipEntryReader {
        require(ref.entry != null)
        val file = File(ref.path)
        return try {
            ZipEntryReader(file, ref.lho, ref.method, ref.csize, ref.size)
        } catch (e: Exception) {
            // Archive changed since it was scanned: look the entry up again.
            val info = ZipIndex.read(file).firstOrNull { it.name == ref.entry }
                ?: throw java.io.FileNotFoundException("${ref.entry} not found in ${ref.path}")
            ZipEntryReader(file, info.localHeaderOffset, info.method, info.compressedSize, info.size)
        }
    }

    fun openStream(ref: Ref): InputStream {
        if (ref.entry == null) return FileInputStream(ref.path)
        val reader = openReader(ref)
        return object : FilterInputStream(reader.openStream()) {
            override fun close() {
                super.close()
                reader.close()
            }
        }
    }

    fun readBytes(ref: Ref, limit: Long = 8L * 1024 * 1024): ByteArray =
        openStream(ref).use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (total < limit) {
                val r = s.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
                if (r < 0) break
                out.write(buf, 0, r)
                total += r
            }
            out.toByteArray()
        }

    /** A [MediaDataSource] over a zip entry, used by MediaMetadataRetriever. */
    fun mediaDataSource(ref: Ref): MediaDataSource {
        val reader = openReader(ref)
        return object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                reader.readAt(position, buffer, offset, size)

            override fun getSize(): Long = reader.size
            override fun close() = reader.close()
        }
    }
}
