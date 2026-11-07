package com.sasayaki.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.sasayaki.player.data.Cover
import com.sasayaki.player.data.Entry
import com.sasayaki.player.data.Settings
import com.sasayaki.player.data.Version
import com.sasayaki.player.playback.PlaybackEngine
import kotlinx.coroutines.delay
import java.util.Locale

fun Entry.coverFor(settings: Settings, size: Int = Cover.SIZE_THUMB): Cover? =
    settings.coverOverrides[id]?.let { Cover(it, maxSize = size) } ?: cover?.let { Cover(it, coverEmbedded, size) }

@Composable
fun CoverImage(cover: Cover?, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    val base = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
    if (cover == null) {
        Placeholder(base)
        return
    }
    SubcomposeAsyncImage(
        model = cover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = base,
        loading = { Placeholder(Modifier) },
        error = { Placeholder(Modifier) },
    )
}

@Composable
private fun Placeholder(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

fun formatTime(ms: Long?): String {
    if (ms == null || ms < 0) return "--:--"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

fun formatLabel(f: String) = f.uppercase()

/** Polls the player position while the calling composable is on screen. */
@Composable
fun rememberPosition(engine: PlaybackEngine, intervalMs: Long = 250): State<Long> {
    val pos = remember { mutableLongStateOf(engine.player.currentPosition) }
    LaunchedEffect(engine) {
        while (true) {
            pos.longValue = engine.player.currentPosition
            delay(intervalMs)
        }
    }
    return pos
}

@Composable
fun rememberDuration(engine: PlaybackEngine): State<Long?> {
    val d = remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(engine) {
        while (true) {
            d.value = engine.player.duration.takeIf { it > 0 }
            delay(500)
        }
    }
    return d
}

/** A version label with its format, e.g. "本編 · WAV". */
fun Version.fullLabel(): String = "$label · ${formatLabel(format)}"

fun formatSpeed(f: Float): String = String.format(Locale.US, "%.2f", f).trimEnd('0').trimEnd('.') + "x"
