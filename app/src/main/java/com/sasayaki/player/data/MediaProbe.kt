package com.sasayaki.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import com.sasayaki.player.data.vfs.Vfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Reads metadata (durations, embedded art) straight from the files, in place. */
object MediaProbe {
    fun <T> withRetriever(ref: Ref, block: (MediaMetadataRetriever) -> T): T? {
        val r = MediaMetadataRetriever()
        return try {
            if (ref.entry == null) r.setDataSource(ref.path) else r.setDataSource(Vfs.mediaDataSource(ref))
            block(r)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    fun durationMs(ref: Ref): Long? = withRetriever(ref) {
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }?.takeIf { it > 0 } ?: wavDurationMs(ref)

    fun embeddedPicture(ref: Ref): ByteArray? = withRetriever(ref) { it.embeddedPicture }

    /** Header-only fallback for WAV files the retriever can't handle. */
    private fun wavDurationMs(ref: Ref): Long? = runCatching {
        Vfs.openStream(ref).use { s ->
            val h = ByteArray(4096)
            var n = 0
            while (n < h.size) { val r = s.read(h, n, h.size - n); if (r < 0) break; n += r }
            val bb = java.nio.ByteBuffer.wrap(h, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (n < 44 || String(h, 0, 4) != "RIFF" || String(h, 8, 4) != "WAVE") return null
            var p = 12
            var byteRate = 0L
            while (p + 8 <= n) {
                val id = String(h, p, 4)
                val size = bb.getInt(p + 4).toLong() and 0xFFFFFFFFL
                if (id == "fmt ") byteRate = bb.getInt(p + 12).toLong() and 0xFFFFFFFFL
                if (id == "data" && byteRate > 0) {
                    val dataSize = if (size == 0xFFFFFFFFL || size == 0L) ref.size - p - 8 else size
                    return dataSize * 1000 / byteRate
                }
                p += 8 + size.toInt() + (size.toInt() and 1)
            }
            null
        }
    }.getOrNull()
}

/** Cache of track durations, filled lazily when an entry is opened or played. */
@OptIn(FlowPreview::class)
class DurationRepository(context: Context, scope: CoroutineScope) {
    private val store = JsonStore(File(context.filesDir, "durations.json"), MapSerializer(String.serializer(), Long.serializer()))
    private val _map = MutableStateFlow(store.load() ?: emptyMap())
    val map: StateFlow<Map<String, Long>> = _map.asStateFlow()
    private val mutex = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            _map.drop(1).debounce(2000).collect { runCatching { store.save(it) } }
        }
    }

    operator fun get(ref: Ref): Long? = _map.value[ref.cacheKey]

    fun record(ref: Ref, ms: Long) {
        if (ms <= 0) return
        if (_map.value[ref.cacheKey] == ms) return
        _map.value = _map.value + (ref.cacheKey to ms)
    }

    suspend fun ensure(tracks: List<Track>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val missing = tracks.filter { _map.value[it.ref.cacheKey] == null }.distinctBy { it.ref.cacheKey }
            if (missing.isEmpty()) return@withLock
            val found = HashMap<String, Long>()
            for (t in missing) {
                MediaProbe.durationMs(t.ref)?.let { found[t.ref.cacheKey] = it }
                if (found.size >= 8) { _map.value = _map.value + found; found.clear() }
            }
            if (found.isNotEmpty()) _map.value = _map.value + found
        }
    }
}
