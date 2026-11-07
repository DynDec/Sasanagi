package com.sasayaki.player.data.scan

import com.sasayaki.player.data.Doc
import com.sasayaki.player.data.Formats
import com.sasayaki.player.data.Ref
import com.sasayaki.player.data.Track
import com.sasayaki.player.data.Version
import com.sasayaki.player.data.vfs.VNode

/** Turns the file tree of one work (folder or archive) into selectable versions, cover and docs. */
object EntryAnalyzer {

    class Result(
        val rj: String?,
        val versions: List<Version>,
        val cover: Ref?,
        val coverEmbedded: Boolean,
        val images: List<Ref>,
        val docs: List<Doc>,
        val totalSize: Long,
        val modified: Long,
    )

    fun analyze(root: VNode, inArchive: Boolean, fillPartial: Boolean = true): Result {
        var node = root
        var rj = Naming.rjCode(root.name)
        // Unwrap single-folder wrappers such as "RJ330310/".
        while (true) {
            val dirs = node.children.filter { it.isDir }
            val files = node.children.filter { !it.isDir }
            if (dirs.size == 1 && files.isEmpty()) {
                node = dirs[0]
                if (rj == null) rj = Naming.rjCode(node.name)
            } else break
        }

        val audioByDir = LinkedHashMap<String, MutableList<VNode>>()
        val images = ArrayList<CoverPicker.Candidate>()
        val docs = ArrayList<Doc>()
        val subsByDirStem = HashMap<String, Ref>()
        val subsByStem = HashMap<String, Ref>()
        var total = 0L
        var modified = 0L

        if (node.children.isEmpty() && !node.isDir) return emptyResult(rj)

        node.walk { path, n ->
            if (n.isDir) return@walk
            total += n.size
            if (n.modified > modified) modified = n.modified
            val ref = n.ref ?: return@walk
            val dir = path.substringBeforeLast('/', "")
            when {
                Formats.isAudio(n.name) -> audioByDir.getOrPut(dir) { mutableListOf() } += n
                Formats.isImage(n.name) -> images += CoverPicker.Candidate(path, ref)
                Formats.isSubtitle(n.name) -> {
                    val st = subtitleStem(n.name)
                    subsByDirStem["$dir|$st"] = ref
                    subsByStem.putIfAbsent(st, ref)
                }
                Formats.isDoc(n.name) -> docs += Doc(path, ref, Formats.ext(n.name))
            }
        }

        val candidates = ArrayList<Candidate>()
        for ((dir, files) in audioByDir) {
            candidates += splitFolder(dir, files.sortedWith { a, b -> Naming.compareNatural(a.name, b.name) }) { dirPath, file ->
                val st = Naming.norm(Naming.stem(file.name))
                subsByDirStem["$dirPath|$st"] ?: subsByStem[st]
            }
        }
        stripCommonLabelPrefix(candidates)

        val sortInfo = candidates.associate { it.id to it }
        var versions = candidates.map { it.toVersion(inArchive) }
        if (fillPartial) versions = fillPartialVersions(versions)
        versions = sortVersions(versions, sortInfo)

        val sortedImages = images.sortedWith { a, b -> Naming.compareNatural(a.relPath, b.relPath) }
        val cover = CoverPicker.pick(sortedImages)
        val embedded = cover == null
        val coverRef = cover?.ref ?: versions.firstOrNull()?.tracks?.firstOrNull { it.format != "wav" }?.ref
            ?: versions.firstOrNull()?.tracks?.firstOrNull()?.ref

        return Result(
            rj = rj,
            versions = versions,
            cover = coverRef,
            coverEmbedded = embedded && coverRef != null,
            images = sortedImages.map { it.ref },
            docs = docs.sortedWith { a, b -> Naming.compareNatural(a.name, b.name) },
            totalSize = total,
            modified = modified,
        )
    }

    private fun emptyResult(rj: String?) = Result(rj, emptyList(), null, false, emptyList(), emptyList(), 0, 0)

    /** "01.mp3.vtt" and "01.lrc" both map to "01". */
    private fun subtitleStem(name: String): String {
        var s = Naming.stem(name)
        if (Formats.isAudio(s)) s = Naming.stem(s)
        return Naming.norm(s)
    }

    private class Candidate(
        val id: String,
        var label: String,
        val path: String,
        val tracks: List<Track>,
        val dirLabel: String,
        val hasTags: Boolean,
    ) {
        fun toVersion(inArchive: Boolean): Version {
            val format = tracks.groupingBy { it.format }.eachCount().maxByOrNull { it.value }?.key ?: ""
            return Version(id = id, label = label.ifBlank { "Main" }, path = path, format = format, tracks = tracks, inArchive = inArchive)
        }
    }

    /**
     * A folder normally is one version whose files are its segments. When the folder holds parallel
     * variants of the same content (e.g. "本編.mp3" + "本編（BGMなし）.mp3", or SE/no-SE files side by side)
     * it is split so every variant becomes its own version.
     */
    private fun splitFolder(dir: String, files: List<VNode>, subtitleFor: (String, VNode) -> Ref?): List<Candidate> {
        val dirLabel = Naming.versionLabel(dir)
        val parsed = files.map { it to Naming.parse(it.name) }
        fun track(f: VNode, p: Naming.Parsed) = Track(
            title = Naming.stripOrdinal(Naming.trackTitle(f.name)),
            fileName = f.name,
            ref = f.ref!!,
            format = f.ext,
            key = Naming.trackKey(f.name),
            core = p.core,
            subtitle = subtitleFor(dir, f),
        )

        val groups = LinkedHashMap<String, MutableList<Pair<VNode, Naming.Parsed>>>()
        for (fp in parsed) {
            val key = fp.second.tags.joinToString("|") + "@" + fp.first.ext
            groups.getOrPut(key) { mutableListOf() } += fp
        }
        val split = groups.size > 1 && groups.values.all { members ->
            val cores = members.map { it.second.core }.filter { it.isNotEmpty() }.toSet()
            groups.values.any { other -> other !== members && other.any { it.second.core in cores } }
        }
        if (!split) {
            return listOf(Candidate(dir, dirLabel, dir, parsed.map { (f, p) -> track(f, p) }, dirLabel, false))
        }
        return groups.map { (key, members) ->
            val tags = members.first().second.displayTags.filterNot { Naming.tagIsRedundant(it, dirLabel) }
            val own = when {
                tags.isNotEmpty() -> tags.joinToString(" · ")
                members.size == 1 -> Naming.stripOrdinal(Naming.trackTitle(members.first().first.name))
                else -> ""
            }
            val label = listOf(dirLabel, own).filter { it.isNotBlank() }.joinToString(" › ")
            Candidate("$dir#$key", label, dir, members.map { (f, p) -> track(f, p) }, dirLabel, members.first().second.tags.isNotEmpty())
        }
    }

    /** Drops a first label segment shared by every version, e.g. "本編 › SEあり" / "本編 › SEなし". */
    private fun stripCommonLabelPrefix(candidates: List<Candidate>) {
        if (candidates.size < 2) return
        val split = candidates.map { it.label.split(" › ") }
        val first = split.first().first()
        if (first.isBlank()) return
        if (split.all { it.size > 1 && it.first() == first }) {
            candidates.forEachIndexed { i, c -> c.label = split[i].drop(1).joinToString(" › ") }
        }
    }

    /**
     * A version that only contains some tracks (e.g. "R-18シーン効果音なし" with tracks 6-7) borrows the
     * missing tracks from the most similar complete version so it can be listened to end-to-end.
     */
    private fun fillPartialVersions(versions: List<Version>): List<Version> {
        fun keys(v: Version): List<String>? {
            val k = v.tracks.map { it.key ?: return null }
            return if (k.toSet().size == k.size) k else null
        }
        val keyed = versions.associateWith { keys(it) }
        return versions.map { p ->
            val pk = keyed[p] ?: return@map p
            val pTracks = p.tracks.associateBy { it.key!! }
            val base = versions.filter { b ->
                val bk = keyed[b] ?: return@filter false
                b !== p && bk.size > pk.size && bk.containsAll(pk) &&
                    b.tracks.all { bt -> pTracks[bt.key]?.let { Naming.similarCore(it.core, bt.core) } ?: true }
            }.sortedWith(
                compareByDescending<Version> { it.format == p.format }
                    .thenByDescending { b -> versions.none { o -> o !== b && keyed[o]?.let { k -> k.size > keyed[b]!!.size && k.containsAll(keyed[b]!!) } == true } }
                    .thenByDescending { it.tracks.size }
            ).firstOrNull() ?: return@map p
            val merged = base.tracks.map { bt -> pTracks[bt.key] ?: bt.copy(fromBase = true) }
            p.copy(tracks = merged, ownCount = p.tracks.size, baseLabel = base.label)
        }
    }

    /** Main content first, bonus material last, variants after the version they derive from. */
    private fun sortVersions(versions: List<Version>, info: Map<String, Candidate>): List<Version> {
        val byTop = compareBy<Version> { Naming.sectionRank(it.path.substringBefore('/')) }
            .thenComparator { a, b -> Naming.compareNatural(a.path.substringBefore('/'), b.path.substringBefore('/')) }
            .thenBy { if (it.path.contains('/')) 1 else 0 }
            .thenBy { if (Naming.isNegativeVariant(info[it.id]?.dirLabel ?: it.label)) 1 else 0 }
            .thenComparator { a, b -> Naming.compareNatural(a.path, b.path) }
            .thenBy { if (info[it.id]?.hasTags == true) 1 else 0 }
            .thenComparator { a, b ->
                Naming.compareNatural(
                    Naming.stem(info[a.id]?.tracks?.firstOrNull()?.fileName ?: ""),
                    Naming.stem(info[b.id]?.tracks?.firstOrNull()?.fileName ?: ""),
                )
            }
            .thenBy { it.id }
        return versions.sortedWith(byTop)
    }
}
