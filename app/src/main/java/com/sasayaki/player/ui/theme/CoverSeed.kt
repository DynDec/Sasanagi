package com.sasayaki.player.ui.theme

import kotlin.math.atan2
import kotlin.math.hypot

/** Picks the color a cover's theme is built from. Pure Kotlin, so sample tests can run it on real covers. */
object CoverSeed {
    private const val HUE_BUCKETS = 36

    /** Pixels below this Lab chroma count as gray and don't vote for a hue. */
    private const val MIN_PIXEL_CHROMA = 12.0

    /** A hue (with its neighbors) must cover this share of the image, so small details like eyes can't win. */
    private const val MIN_HUE_SHARE = 0.03

    /**
     * Skin, brown and blond hair, wood and warm lighting fill most illustrations and would make nearly every
     * cover brown. Pixels in that hue band ([WARM_FROM]..[WARM_TO] degrees, orange through yellow) count for
     * this much up to [WARM_MUTED] chroma. Pink skin, blush and their shading continue it down to [PINK_FROM]
     * degrees, except dark saturated pixels (L* up to [PINK_MIN_L], chroma [RED_MIN_CHROMA] or more), which are
     * reds and deep pinks the artist chose. Blush and skin shading reach well past 40 chroma, so the weight
     * only returns to full at [WARM_VIVID].
     */
    private const val WARM_WEIGHT = 0.1
    private const val WARM_FROM = 20.0
    private const val WARM_TO = 90.0
    private const val PINK_FROM = 330.0
    private const val PINK_MIN_L = 50.0
    private const val RED_MIN_CHROMA = 30.0
    private const val WARM_MUTED = 45.0
    private const val WARM_VIVID = 70.0

    /**
     * The most distinctive hue of [pixels] (ARGB), as the mean color of its pixels in that hue, or null when
     * the image is mostly gray. Pixels vote for one of [HUE_BUCKETS] Lab hues, weighted by chroma, with
     * muted warm pixels discounted by [WARM_WEIGHT].
     */
    fun pick(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val weight = DoubleArray(HUE_BUCKETS)
        val count = IntArray(HUE_BUCKETS)
        val sum = Array(HUE_BUCKETS) { DoubleArray(3) }
        for (p in pixels) {
            val lab = Tones.toLab(p)
            val c = hypot(lab[1], lab[2])
            if (c < MIN_PIXEL_CHROMA || lab[0] < 15 || lab[0] > 95) continue
            val hue = Math.toDegrees(atan2(lab[2], lab[1])).let { if (it < 0) it + 360 else it }
            val vivid = ((c - WARM_MUTED) / (WARM_VIVID - WARM_MUTED)).coerceIn(0.0, 1.0)
            val pinkish = hue >= PINK_FROM || hue < WARM_FROM
            val skinLike = hue in WARM_FROM..WARM_TO || (pinkish && (lab[0] > PINK_MIN_L || c < RED_MIN_CHROMA))
            val w = c * (if (skinLike) WARM_WEIGHT + (1 - WARM_WEIGHT) * vivid else 1.0)
            val b = (hue / 360 * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            weight[b] += w
            count[b]++
            for (i in 0..2) sum[b][i] += lab[i] * w
        }
        // Neighbors within 20 degrees join in, so a color spread over several buckets (a blue that shades
        // from sky to navy) isn't outvoted by a narrower one.
        fun around(b: Int, of: (Int) -> Double) = (-2..2).sumOf { of((b + it + HUE_BUCKETS) % HUE_BUCKETS) }
        val best = (0 until HUE_BUCKETS)
            .filter { b -> around(b) { count[it].toDouble() } / pixels.size >= MIN_HUE_SHARE && weight[b] > 0 }
            .maxByOrNull { b -> around(b) { weight[it] } } ?: return null
        val (l, a, bb) = sum[best].map { it / weight[best] }
        return Tones.fromLab(l, a, bb) ?: Tones.fromLab(60.0, a, bb)
    }
}
