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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.sasayaki.player.data.Cover
import com.sasayaki.player.data.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Minimum Lab chroma of the cover accent, so muted covers still give a color that reads as one. */
private const val ACCENT_MIN_CHROMA = 32.0

/** Lab chroma range of the cover secondary: a quieter companion to the accent, as in Material's schemes. */
private val SECONDARY_CHROMA = 16.0..24.0

/** Seed colors by cover, so returning to a work doesn't fade in from neutral again. Gray covers map to 0. */
private val seeds = LruCache<Cover, Int>(64)

private fun peekSeed(cover: Cover?): Int? = cover?.let { seeds.get(it) }?.takeIf { it != 0 }

private suspend fun loadSeed(cover: Cover, cache: CoverCache): Int? {
    seeds.get(cover)?.let { return it.takeIf { it != 0 } }
    val seed = withContext(Dispatchers.Default) { cache.get(cover)?.let(::seedOf) }
    seeds.put(cover, seed ?: 0)
    return seed
}

/** The seed of the cached thumbnail at [file], decoded at a quarter size since a few thousand pixels suffice. */
private fun seedOf(file: File): Int? {
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
    val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    bmp.recycle()
    return CoverSeed.pick(px)
}

/** Moves each surface role to the hue of [argb] at low chroma, keeping it close to its lightness so contrast holds. */
fun ColorScheme.withSurfaceTint(argb: Int?, dark: Boolean): ColorScheme {
    if (argb == null) return this
    fun Color.tinted() = Color(CoverSeed.surface(argb, Tones.toLab(toArgb())[0], dark))
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

/** Themes [content] with surfaces, accent and secondary taken from [cover], fading when the cover changes. */
@Composable
fun CoverTinted(cover: Cover?, cache: CoverCache, enabled: Boolean, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val seed by produceState(if (enabled) peekSeed(cover) else null, cover, enabled) {
        value = if (enabled && cover != null) loadSeed(cover, cache) else null
    }
    val dark = base.surface.luminance() < 0.5f
    val target = remember(base, seed, dark) {
        base.withSurfaceTint(seed, dark).withAccent(seed, dark, ACCENT_MIN_CHROMA).withSecondary(seed, dark, SECONDARY_CHROMA)
    }
    val scheme = base.copy(
        primary = animated(target.primary),
        onPrimary = animated(target.onPrimary),
        primaryContainer = animated(target.primaryContainer),
        onPrimaryContainer = animated(target.onPrimaryContainer),
        secondary = animated(target.secondary),
        onSecondary = animated(target.onSecondary),
        secondaryContainer = animated(target.secondaryContainer),
        onSecondaryContainer = animated(target.onSecondaryContainer),
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
