package com.sasayaki.player

import com.sasayaki.player.ui.theme.Tones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class TonesTest {
    private val seeds = listOf(0xFFE53935, 0xFF1E88E5, 0xFFFFB300, 0xFF43A047, 0xFF9C89C9, 0xFF000000, 0xFFFFFFFF, 0xFF808080)
        .map { it.toInt() }

    @Test
    fun labRoundTrips() {
        for (c in seeds) {
            val lab = Tones.toLab(c)
            assertEquals(c, Tones.fromLab(lab[0], lab[1], lab[2]))
        }
    }

    @Test
    fun toneHitsLightnessAndKeepsHue() {
        for (c in seeds) {
            val seed = Tones.toLab(c)
            for (t in listOf(0.0, 10.0, 20.0, 30.0, 40.0, 80.0, 90.0, 100.0)) {
                val lab = Tones.toLab(Tones.tone(c, t))
                assertEquals("L* of ${"%08X".format(c)} at $t", t, lab[0], 0.6)
                // Hue only matters where both colors have visible chroma.
                if (abs(lab[1]) + abs(lab[2]) > 8 && abs(seed[1]) + abs(seed[2]) > 8) {
                    val d = abs(atan2(lab[2], lab[1]) - atan2(seed[2], seed[1]))
                    assertTrue("hue of ${"%08X".format(c)} at $t", minOf(d, 2 * Math.PI - d) < 0.1)
                }
            }
        }
    }
}
