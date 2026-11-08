package com.sasayaki.player.ui.theme

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sasayaki.player.data.Cover
import com.sasayaki.player.data.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** Lab chroma of tinted surfaces: enough to read as a hue, low enough to stay a background. */
private const val TINT_CHROMA = 10.0

private const val HUE_BUCKETS = 24

/** Pixels below this Lab chroma count as gray and don't vote for a hue. */
private const val MIN_PIXEL_CHROMA = 12.0

/** The winning hue must hold this share of all pixels' chroma, or the cover counts as gray. */
private const val MIN_HUE_SHARE = 0.04

/** Seed colors by cover, so returning to a work doesn't fade in from neutral again. Gray covers map to 0. */
private val seeds = LruCache<Cover, Int>(64)

private fun peekSeed(cover: Cover?): Int? = cover?.let { seeds.get(it) }?.takeIf { it != 0 }

private suspend fun loadSeed(cover: Cover, cache: CoverCache): Int? {
    seeds.get(cover)?.let { return it.takeIf { it != 0 } }
    val seed = withContext(Dispatchers.Default) { cache.get(cover)?.let(::seedOf) }
    seeds.put(cover, seed ?: 0)
    return seed
}

/**
 * The dominant hue of an image, as the mean color of its pixels in that hue, or null when the
 * image is mostly gray. Pixels vote for one of [HUE_BUCKETS] Lab hues, weighted by chroma.
 */
private fun seedOf(file: File): Int? {
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
    val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    bmp.recycle()
    val weight = DoubleArray(HUE_BUCKETS)
    val sum = Array(HUE_BUCKETS) { DoubleArray(3) }
    var total = 0.0
    for (p in px) {
        val lab = Tones.toLab(p)
        val c = hypot(lab[1], lab[2])
        total += c
        if (c < MIN_PIXEL_CHROMA || lab[0] < 15 || lab[0] > 95) continue
        val b = (((atan2(lab[2], lab[1]) + PI) / (2 * PI) * HUE_BUCKETS).toInt()).coerceIn(0, HUE_BUCKETS - 1)
        weight[b] += c
        for (i in 0..2) sum[b][i] += lab[i] * c
    }
    val best = weight.indices.maxBy { weight[it] }
    if (total == 0.0 || weight[best] / total < MIN_HUE_SHARE) return null
    val (l, a, bb) = sum[best].map { it / weight[best] }
    return Tones.fromLab(l, a, bb) ?: Tones.fromLab(60.0, a, bb)
}

/** Moves each surface role to the hue of [argb] at low chroma, keeping its lightness so contrast is unchanged. */
fun ColorScheme.withSurfaceTint(argb: Int?): ColorScheme {
    if (argb == null) return this
    fun Color.tinted() = Color(Tones.tone(argb, Tones.toLab(toArgb())[0], TINT_CHROMA))
    return copy(
        background = background.tinted(),
        surface = surface.tinted(),
        surfaceContainerLowest = surfaceContainerLowest.tinted(),
        surfaceContainerLow = surfaceContainerLow.tinted(),
        surfaceContainer = surfaceContainer.tinted(),
        surfaceContainerHigh = surfaceContainerHigh.tinted(),
        surfaceContainerHighest = surfaceContainerHighest.tinted(),
    )
}

/** Themes [content] with surfaces tinted from [cover], fading when the cover changes. */
@Composable
fun CoverTinted(cover: Cover?, cache: CoverCache, enabled: Boolean, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val seed by produceState(if (enabled) peekSeed(cover) else null, cover, enabled) {
        value = if (enabled && cover != null) loadSeed(cover, cache) else null
    }
    val target = remember(base, seed) { base.withSurfaceTint(seed) }
    val scheme = base.copy(
        background = animated(target.background),
        surface = animated(target.surface),
        surfaceContainerLowest = animated(target.surfaceContainerLowest),
        surfaceContainerLow = animated(target.surfaceContainerLow),
        surfaceContainer = animated(target.surfaceContainer),
        surfaceContainerHigh = animated(target.surfaceContainerHigh),
        surfaceContainerHighest = animated(target.surfaceContainerHighest),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun animated(c: Color): Color = animateColorAsState(c, tween(400), label = "tint").value
