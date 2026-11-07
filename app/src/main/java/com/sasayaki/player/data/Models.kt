package com.sasayaki.player.data

import kotlinx.serialization.Serializable

/**
 * Points at a file either directly on disk ([entry] == null) or inside a ZIP archive located at [path].
 * Zip location fields are a fast path for playback; they are re-validated when the archive is opened.
 */
@Serializable
data class Ref(
    val path: String,
    val entry: String? = null,
    val size: Long = 0,
    val lho: Long = -1,
    val method: Int = 0,
    val csize: Long = 0,
) {
    val inArchive: Boolean get() = entry != null
    val fileName: String get() = (entry ?: path).substringAfterLast('/')
    val cacheKey: String get() = if (entry == null) "$path|$size" else "$path!$entry|$size"
}

@Serializable
data class Track(
    val title: String,
    val fileName: String,
    val ref: Ref,
    val format: String,
    val key: String? = null,
    val core: String = "",
    val subtitle: Ref? = null,
    /** True when the track was borrowed from [Version.baseLabel] to fill a gap in a partial version. */
    val fromBase: Boolean = false,
)

@Serializable
data class Version(
    val id: String,
    val label: String,
    val path: String,
    val format: String,
    val tracks: List<Track>,
    val ownCount: Int = tracks.size,
    val baseLabel: String? = null,
    val inArchive: Boolean = false,
) {
    val isFilled: Boolean get() = baseLabel != null
}

@Serializable
data class Doc(val name: String, val ref: Ref, val kind: String)

@Serializable
data class Entry(
    val id: String,
    val title: String,
    val rj: String? = null,
    val category: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val hasArchive: Boolean = false,
    val hasFolder: Boolean = false,
    val versions: List<Version> = emptyList(),
    val cover: Ref? = null,
    /** When true, [cover] is an audio file whose embedded artwork should be used. */
    val coverEmbedded: Boolean = false,
    val images: List<Ref> = emptyList(),
    val docs: List<Doc> = emptyList(),
    val modified: Long = 0,
    val totalSize: Long = 0,
) {
    val trackCount: Int get() = versions.maxOfOrNull { it.tracks.size } ?: 0
    val formats: List<String> get() = versions.map { it.format }.distinct()
    val categoryLabel: String? get() = category.takeIf { it.isNotEmpty() }?.joinToString(" / ")
}

@Serializable
data class Library(
    val roots: List<String> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val scannedAt: Long = 0,
)

/** Where the listener left off in an entry. */
@Serializable
data class Progress(
    val entryId: String,
    val versionId: String,
    val index: Int,
    val positionMs: Long,
    val updatedAt: Long,
    /** Fraction of the whole version listened, for list indicators. */
    val fraction: Float = 0f,
    val finished: Boolean = false,
)

object Formats {
    val AUDIO = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "oga", "opus", "mka", "webm")
    val LOSSLESS = setOf("wav", "flac")
    val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    val SUBTITLE = setOf("lrc", "vtt", "srt")
    val DOC = setOf("txt", "pdf", "md", "html", "htm")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun isAudio(name: String) = ext(name) in AUDIO
    fun isImage(name: String) = ext(name) in IMAGE
    fun isSubtitle(name: String) = ext(name) in SUBTITLE
    fun isDoc(name: String) = ext(name) in DOC
    fun isArchive(name: String) = ext(name) == "zip"
}
