package com.sasayaki.player.ui.theme

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Material-style tones of a color: its hue, at CIE L* lightness [tone] (Material's tone scale), keeping
 * as much chroma as sRGB and [maxChroma] allow. Lab hue stands in for HCT, which is close enough for one accent color.
 */
object Tones {
    fun tone(argb: Int, tone: Double, maxChroma: Double = Double.MAX_VALUE): Int {
        val lab = toLab(argb)
        val chroma = minOf(hypot(lab[1], lab[2]), maxChroma)
        val hue = atan2(lab[2], lab[1])
        fun at(c: Double) = fromLab(tone, c * cos(hue), c * sin(hue))
        at(chroma)?.let { return it }
        var lo = 0.0
        var hi = chroma
        repeat(24) {
            val mid = (lo + hi) / 2
            if (at(mid) != null) lo = mid else hi = mid
        }
        return at(lo) ?: 0xFF808080.toInt()
    }

    fun toLab(argb: Int): DoubleArray {
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        val fx = f((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN)
        val fy = f(0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
        val fz = f((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** The sRGB color for a Lab value, or null when it falls outside the sRGB gamut. */
    fun fromLab(l: Double, a: Double, b: Double): Int? {
        val fy = (l + 16) / 116
        val x = fInv(fy + a / 500) * XN
        val y = fInv(fy)
        val z = fInv(fy - b / 200) * ZN
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        if (listOf(r, g, bl).any { it < -EPS || it > 1 + EPS }) return null
        return (0xFF shl 24) or (encode(r) shl 16) or (encode(g) shl 8) or encode(bl)
    }

    private const val XN = 0.95047
    private const val ZN = 1.08883
    private const val EPS = 1e-4
    private const val D = 6.0 / 29

    private fun f(t: Double) = if (t > D * D * D) cbrt(t) else t / (3 * D * D) + 4.0 / 29
    private fun fInv(t: Double) = if (t > D) t * t * t else 3 * D * D * (t - 4.0 / 29)

    private fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun encode(lin: Double): Int {
        val l = lin.coerceIn(0.0, 1.0)
        val v = if (l <= 0.0031308) 12.92 * l else 1.055 * l.pow(1 / 2.4) - 0.055
        return (v * 255).roundToInt().coerceIn(0, 255)
    }
}
