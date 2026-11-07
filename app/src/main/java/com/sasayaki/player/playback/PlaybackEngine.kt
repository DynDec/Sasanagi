package com.sasayaki.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.extractor.DefaultExtractorsFactory
import com.sasayaki.player.data.Cover
import com.sasayaki.player.data.CoverCache
import com.sasayaki.player.data.DurationRepository
import com.sasayaki.player.data.Entry
import com.sasayaki.player.data.LibraryRepository
import com.sasayaki.player.data.Progress
import com.sasayaki.player.data.ProgressRepository
import com.sasayaki.player.data.SettingsRepository
import com.sasayaki.player.data.Track
import com.sasayaki.player.data.Version
import com.sasayaki.player.data.scan.Naming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class SleepTimer(val endsAt: Long? = null, val endOfTrack: Boolean = false)

data class EngineState(
    val entry: Entry? = null,
    val version: Version? = null,
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleep: SleepTimer? = null,
    val error: String? = null,
) {
    val track: Track? get() = version?.tracks?.getOrNull(index)
}

/**
 * Owns the ExoPlayer instance shared by the UI and [PlaybackService]. A version's tracks form one
 * gapless playlist, so split works play back-to-back like a single recording.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val durations: DurationRepository,
    private val covers: CoverCache,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                RoutingDataSource.Factory(context),
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
            )
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                // Speech: pause instead of ducking under notifications so whispers aren't missed.
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setSeekBackIncrementMs(settings.current.skipBackSec * 1000L)
        .setSeekForwardIncrementMs(settings.current.skipForwardSec * 1000L)
        .build()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var saveJob: Job? = null
    private var sleepJob: Job? = null
    private var pausedAt = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startSaving() else {
                    pausedAt = System.currentTimeMillis()
                    saveProgress()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _state.update { it.copy(playWhenReady = playWhenReady) }
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _state.value.sleep?.endOfTrack == true) {
                    cancelSleep()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _state.update { it.copy(index = player.currentMediaItemIndex) }
                saveProgress()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_READY) recordTimelineDurations(player.currentTimeline)
                if (playbackState == Player.STATE_ENDED) markFinished()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) = recordTimelineDurations(timeline)

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _state.update { it.copy(speed = playbackParameters.speed) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _state.update { it.copy(repeatMode = repeatMode) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = error.localizedMessage ?: error.errorCodeName) }
            }
        })
    }

    // ---- Queue ----

    private suspend fun buildItems(entry: Entry, version: Version): List<MediaItem> {
        val override = settings.current.coverOverrides[entry.id]
        val cover = override?.let { Cover(it, maxSize = ART_SIZE) } ?: entry.cover?.let { Cover(it, entry.coverEmbedded, ART_SIZE) }
        // Artwork travels as bytes: System UI cannot open files in the app's private cache.
        val art: ByteArray? = cover?.let { withTimeoutOrNull(2500) { covers.get(it) } }
            ?.let { f -> withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() } }
        return version.tracks.mapIndexed { i, t -> mediaItem(entry, version, i, t, art) }
    }

    /** Items for system media resumption (e.g. a headset play button after the app was killed). */
    suspend fun resumptionItems(): MediaSession.MediaItemsWithStartPosition? {
        library.loaded.first { it }
        val p = progress.last ?: return null
        val entry = library.entry(p.entryId) ?: return null
        val v = entry.versions.firstOrNull { it.id == p.versionId } ?: return null
        val idx = if (p.finished) 0 else p.index.coerceIn(0, v.tracks.lastIndex)
        val pos = if (p.finished) 0 else (p.positionMs - settings.current.resumeRewindSec * 1000L).coerceAtLeast(0)
        val items = buildItems(entry, v)
        _state.update { it.copy(entry = entry, version = v, index = idx, error = null) }
        return MediaSession.MediaItemsWithStartPosition(items, idx, pos)
    }

    fun play(entry: Entry, version: Version, index: Int = 0, positionMs: Long = 0, start: Boolean = true) {
        scope.launch {
            val items = buildItems(entry, version)
            _state.update { it.copy(entry = entry, version = version, index = index, error = null) }
            player.setMediaItems(items, index.coerceIn(0, items.lastIndex.coerceAtLeast(0)), positionMs.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = start
            saveProgress()
            launch { durations.ensure(version.tracks) }
        }
    }

    /** Continues an entry where it was left, or starts its preferred version. */
    fun resume(entry: Entry, preferredVersion: Version?) {
        val p = progress[entry.id]
        val v = p?.let { pr -> entry.versions.firstOrNull { it.id == pr.versionId } }
        if (p != null && v != null && !p.finished) {
            play(entry, v, p.index.coerceIn(0, v.tracks.lastIndex), (p.positionMs - settings.current.resumeRewindSec * 1000L).coerceAtLeast(0))
        } else {
            play(entry, v ?: preferredVersion ?: entry.versions.first())
        }
    }

    /** Loads the last session paused so the mini player can pick it up. */
    fun restoreLastSession() {
        if (player.mediaItemCount > 0) return
        val p = progress.last ?: return
        val entry = library.entry(p.entryId) ?: return
        val v = entry.versions.firstOrNull { it.id == p.versionId } ?: return
        if (p.finished) return
        play(entry, v, p.index.coerceIn(0, v.tracks.lastIndex), p.positionMs, start = false)
    }

    /**
     * Switches to another version of the same work, keeping the current track and position
     * (matched by track number, then by name, then by index).
     */
    fun switchVersion(target: Version) {
        val s = _state.value
        val entry = s.entry ?: return
        val cur = s.version ?: return play(entry, target)
        val track = cur.tracks.getOrNull(player.currentMediaItemIndex)
        val pos = player.currentPosition
        val idx = track?.let { t ->
            t.key?.let { k -> target.tracks.indexOfFirst { it.key == k }.takeIf { it >= 0 } }
                ?: target.tracks.indexOfFirst { t.core.isNotEmpty() && Naming.similarCore(it.core, t.core) }.takeIf { it >= 0 }
                ?: player.currentMediaItemIndex.takeIf { cur.tracks.size == target.tracks.size }
        }
        if (idx == null) play(entry, target, 0, 0, start = player.playWhenReady)
        else play(entry, target, idx, pos, start = player.playWhenReady)
    }

    private fun mediaItem(entry: Entry, version: Version, i: Int, t: Track, art: ByteArray?): MediaItem {
        val n = version.tracks.size
        return MediaItem.Builder()
            .setMediaId("${entry.id}::${version.id}::$i")
            .setUri(RefUris.toUri(t.ref))
            .setMimeType(mimeFor(t.format))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(if (n > 1) "${i + 1}/$n  ${t.title}" else t.title)
                    .setArtist(entry.title)
                    .setAlbumTitle(version.label)
                    .setTrackNumber(i + 1)
                    .setTotalTrackCount(n)
                    .setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .build()
            )
            .build()
    }

    private fun mimeFor(ext: String) = when (ext) {
        "mp3" -> MimeTypes.AUDIO_MPEG
        "wav" -> MimeTypes.AUDIO_WAV
        "flac" -> MimeTypes.AUDIO_FLAC
        "m4a", "aac" -> MimeTypes.AUDIO_MP4
        "ogg", "oga", "opus" -> MimeTypes.AUDIO_OGG
        "mka", "webm" -> MimeTypes.AUDIO_MATROSKA
        else -> null
    }

    // ---- Transport ----

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0, 0)
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        val rewind = settings.current.resumeRewindSec * 1000L
        if (rewind > 0 && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 60_000) {
            player.seekTo((player.currentPosition - rewind).coerceAtLeast(0))
        }
        player.play()
    }

    fun seekToTrack(index: Int, positionMs: Long = 0) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, positionMs)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previous() {
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    /** Skips by [deltaMs], crossing into the neighbouring segment when needed. */
    fun skipBy(deltaMs: Long) {
        val v = _state.value.version ?: return
        var idx = player.currentMediaItemIndex
        var target = player.currentPosition + deltaMs
        val d = trackDurations(v)
        if (target < 0) {
            while (target < 0 && idx > 0) {
                idx--
                val len = d.getOrNull(idx) ?: return player.seekTo(idx, 0)
                target += len
            }
            player.seekTo(idx, target.coerceAtLeast(0))
            return
        }
        var len = d.getOrNull(idx) ?: player.duration.takeIf { it != C.TIME_UNSET }
        while (len != null && target >= len && idx < v.tracks.lastIndex) {
            target -= len
            idx++
            len = d.getOrNull(idx)
        }
        player.seekTo(idx, if (len != null) target.coerceAtMost(len - 500).coerceAtLeast(0) else target)
    }

    /** Seeks on the whole-work timeline. Requires known durations. */
    fun seekGlobal(globalMs: Long) {
        val v = _state.value.version ?: return
        val d = trackDurations(v)
        var acc = 0L
        for ((i, len) in d.withIndex()) {
            val l = len ?: return
            if (globalMs < acc + l || i == d.lastIndex) {
                player.seekTo(i, (globalMs - acc).coerceIn(0, l))
                return
            }
            acc += l
        }
    }

    fun trackDurations(v: Version): List<Long?> {
        val m = durations.map.value
        return v.tracks.map { m[it.ref.cacheKey] }
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() {
        saveProgress()
        player.stop()
        player.clearMediaItems()
        cancelSleep()
        _state.update { EngineState(speed = it.speed, repeatMode = it.repeatMode) }
    }

    // ---- Sleep timer ----

    fun setSleepMinutes(minutes: Int) {
        cancelSleep()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleep = SleepTimer(endsAt = endsAt)) }
        sleepJob = scope.launch {
            val fadeMs = 30_000L
            while (isActive) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0) {
                    player.pause()
                    player.volume = 1f
                    _state.update { it.copy(sleep = null) }
                    break
                }
                player.volume = if (left < fadeMs) (left.toFloat() / fadeMs).coerceIn(0.05f, 1f) else 1f
                delay(250)
            }
        }
    }

    fun setSleepEndOfTrack() {
        cancelSleep()
        player.pauseAtEndOfMediaItems = true
        _state.update { it.copy(sleep = SleepTimer(endOfTrack = true)) }
    }

    fun extendSleep(minutes: Int) {
        val s = _state.value.sleep?.endsAt ?: return setSleepMinutes(minutes)
        val left = ((s - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
        setSleepMinutes(left + minutes)
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        player.volume = 1f
        player.pauseAtEndOfMediaItems = false
        _state.update { it.copy(sleep = null) }
    }

    // ---- Progress ----

    private fun startSaving() {
        saveJob?.cancel()
        saveJob = scope.launch {
            while (isActive) {
                delay(5000)
                saveProgress()
            }
        }
    }

    fun saveProgress() {
        val s = _state.value
        val entry = s.entry ?: return
        val v = s.version ?: return
        if (player.mediaItemCount == 0) return
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        progress.put(
            Progress(
                entryId = entry.id, versionId = v.id, index = idx, positionMs = pos,
                updatedAt = System.currentTimeMillis(), fraction = fraction(v, idx, pos),
            )
        )
    }

    private fun markFinished() {
        val s = _state.value
        val entry = s.entry ?: return
        val v = s.version ?: return
        progress.put(Progress(entry.id, v.id, 0, 0, System.currentTimeMillis(), 1f, finished = true))
    }

    private fun fraction(v: Version, idx: Int, pos: Long): Float {
        val d = trackDurations(v)
        if (d.all { it != null }) {
            val total = d.sumOf { it!! }.toFloat()
            if (total > 0) return ((d.take(idx).sumOf { it!! } + pos) / total).coerceIn(0f, 1f)
        }
        return (idx.toFloat() / v.tracks.size).coerceIn(0f, 1f)
    }

    private fun recordTimelineDurations(timeline: Timeline) {
        val v = _state.value.version ?: return
        val w = Timeline.Window()
        for (i in 0 until minOf(timeline.windowCount, v.tracks.size)) {
            timeline.getWindow(i, w)
            if (w.durationUs != C.TIME_UNSET && !w.isPlaceholder) durations.record(v.tracks[i].ref, w.durationMs)
        }
    }
}

private const val ART_SIZE = 720
