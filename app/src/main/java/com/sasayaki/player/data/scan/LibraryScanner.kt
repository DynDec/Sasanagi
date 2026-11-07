package com.sasayaki.player.data.scan

import com.sasayaki.player.data.Entry
import com.sasayaki.player.data.Formats
import com.sasayaki.player.data.vfs.VNode
import com.sasayaki.player.data.vfs.Vfs
import java.io.File

/**
 * Walks library roots and decides what is a work ("entry") and what is just an organising folder
 * ("category", such as an "SFW" folder full of archives).
 */
class LibraryScanner(private val fillPartial: Boolean) {

    fun interface ProgressListener {
        fun onProgress(done: Int, total: Int, current: String)
    }

    private class Item(val file: File, val category: List<String>, val rootPath: String)

    fun scan(roots: List<File>, listener: ProgressListener? = null): List<Entry> {
        val items = ArrayList<Item>()
        for (root in roots) {
            if (!root.isDirectory) continue
            collect(root, emptyList(), root.absolutePath, items, depth = 0, isRoot = true)
        }
        val entries = ArrayList<Entry>(items.size)
        items.forEachIndexed { i, item ->
            listener?.onProgress(i, items.size, item.file.name)
            runCatching { buildEntry(item) }.getOrNull()?.takeIf { it.versions.isNotEmpty() }?.let { entries += it }
        }
        listener?.onProgress(items.size, items.size, "")
        return mergeDuplicates(entries)
    }

    private fun collect(dir: File, category: List<String>, rootPath: String, out: MutableList<Item>, depth: Int, isRoot: Boolean) {
        val children = dir.listFiles()?.filterNot { Vfs.isJunk(it.name) } ?: return
        if (!isRoot && (depth > 8 || isEntryFolder(dir, children))) {
            out += Item(dir, category, rootPath)
            return
        }
        val looseAudio = children.filter { it.isFile && Formats.isAudio(it.name) }
        for (c in children.sortedWith { a, b -> Naming.compareNatural(a.name, b.name) }) {
            when {
                c.isDirectory -> collect(c, if (isRoot) category else category + dir.name, rootPath, out, depth + 1, isRoot = false)
                Formats.isArchive(c.name) -> out += Item(c, if (isRoot) category else category + dir.name, rootPath)
            }
        }
        // Audio lying loose in a category folder: each file is its own entry.
        for (a in looseAudio) out += Item(a, if (isRoot) category else category + dir.name, rootPath)
    }

    private fun isEntryFolder(dir: File, children: List<File>): Boolean {
        val files = children.filter { it.isFile }
        val dirs = children.filter { it.isDirectory }
        val archives = files.filter { Formats.isArchive(it.name) }
        if (files.any { Formats.isAudio(it.name) }) return true
        if (Naming.rjCode(dir.name) != null && archives.isEmpty()) return true
        val hasExtras = files.any { Formats.isImage(it.name) || Formats.isDoc(it.name) }
        if (archives.isNotEmpty() && !hasExtras) return false
        if (hasExtras) return true
        if (dirs.size == 1 && archives.isEmpty()) return true
        if (dirs.isNotEmpty() && dirs.all { Naming.isStructural(it.name) }) return true
        // A folder whose sub-folders contain no audio at all is not a category either.
        return dirs.none { containsAudio(it, 0) }
    }

    private fun containsAudio(dir: File, depth: Int): Boolean {
        if (depth > 6) return false
        val c = dir.listFiles() ?: return false
        return c.any { (it.isFile && (Formats.isAudio(it.name) || Formats.isArchive(it.name))) || (it.isDirectory && containsAudio(it, depth + 1)) }
    }

    private fun buildEntry(item: Item): Entry? {
        val f = item.file
        val isArchive = f.isFile && Formats.isArchive(f.name)
        val tree: VNode = when {
            isArchive -> Vfs.fromZip(f)
            f.isDirectory -> Vfs.fromFolder(f)
            else -> VNode(f.name, true).also {
                it.children += VNode(f.name, false, f.length(), f.lastModified(), com.sasayaki.player.data.Ref(f.absolutePath, size = f.length()))
            }
        }
        val r = EntryAnalyzer.analyze(tree, isArchive, fillPartial)
        val rel = f.absolutePath.removePrefix(item.rootPath).trimStart('/', '\\')
        val title = Naming.entryTitle(if (f.isFile && !isArchive) Naming.stem(f.name) else f.name)
        return Entry(
            id = "${item.rootPath}::$rel",
            title = title,
            rj = r.rj ?: Naming.rjCode(f.name),
            category = item.category,
            sources = listOf(f.absolutePath),
            hasArchive = isArchive || r.versions.any { it.inArchive || it.tracks.any { t -> t.ref.inArchive } },
            hasFolder = !isArchive,
            versions = r.versions,
            cover = r.cover,
            coverEmbedded = r.coverEmbedded,
            images = r.images,
            docs = r.docs,
            modified = maxOf(f.lastModified(), r.modified),
            totalSize = r.totalSize,
        )
    }

    /** The same work present as both a folder and an archive becomes a single entry. */
    private fun mergeDuplicates(entries: List<Entry>): List<Entry> {
        val out = ArrayList<Entry?>()
        val byTitle = HashMap<String, Int>()
        val byRj = HashMap<String, Int>()
        fun cat(e: Entry) = e.category.joinToString("/")
        for (e in entries) {
            val tk = cat(e) + "|" + Naming.norm(e.title)
            val rk = e.rj?.let { cat(e) + "|" + it }
            val idx = byTitle[tk] ?: rk?.let { byRj[it] }
            if (idx == null) {
                out += e
                byTitle[tk] = out.lastIndex
                rk?.let { byRj[it] = out.lastIndex }
                continue
            }
            val merged = merge(out[idx]!!, e)
            out[idx] = merged
            byTitle[tk] = idx
            merged.rj?.let { byRj[cat(merged) + "|" + it] = idx }
        }
        return out.filterNotNull()
    }

    private fun merge(a: Entry, b: Entry): Entry {
        val (main, other) = if (a.hasFolder || !b.hasFolder) a to b else b to a
        fun sig(v: com.sasayaki.player.data.Version) = v.label + "@" + v.format + "#" + v.tracks.size
        val known = main.versions.map(::sig).toSet()
        val extra = other.versions.filter { sig(it) !in known }
        return main.copy(
            rj = main.rj ?: other.rj,
            sources = main.sources + other.sources,
            hasArchive = main.hasArchive || other.hasArchive,
            hasFolder = main.hasFolder || other.hasFolder,
            versions = main.versions + extra,
            cover = if (main.coverEmbedded && other.cover != null && !other.coverEmbedded) other.cover else main.cover,
            coverEmbedded = main.coverEmbedded && (other.cover == null || other.coverEmbedded),
            images = (main.images + other.images).distinctBy { it.fileName + it.size },
            docs = (main.docs + other.docs).distinctBy { it.name.substringAfterLast('/') },
            modified = maxOf(main.modified, other.modified),
        )
    }
}
