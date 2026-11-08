package com.sasayaki.player

import com.sasayaki.player.data.scan.LibraryScanner
import com.sasayaki.player.data.vfs.Vfs
import com.sasayaki.player.ui.theme.CoverSeed
import com.sasayaki.player.ui.theme.Tones
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders a contact sheet of the sample library's covers next to the dark-theme surface, accent and
 * secondary colors picked from each, to judge [CoverSeed] by eye. Written to SASAYAKI_SEEDS_OUT.
 */
class CoverSeedSampleTest {
    @Test
    fun renderSampleSeeds() {
        val root = File(System.getenv("SASAYAKI_SAMPLE") ?: "C:/Users/User/Documents/ASMR")
        assumeTrue(root.isDirectory)
        val row = 120
        // Covers are often 10+ MB; keep only downscaled copies.
        val covers = LibraryScanner(fillPartial = true).scan(listOf(root))
            .filter { !it.coverEmbedded }
            .mapNotNull { e -> e.cover?.let { ref -> runCatching { Vfs.openStream(ref).use { ImageIO.read(it) } }.getOrNull()?.let { scaled(it, 320) } } }

        val cols = 2
        val cellWidth = row + 200
        val sheet = BufferedImage(cellWidth * cols, row * ((covers.size + cols - 1) / cols), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        covers.forEachIndexed { i, img ->
            val x = (i % cols) * cellWidth
            val y = (i / cols) * row
            val seed = CoverSeed.pick(pixelsOf(img, 80))
            g.drawImage(img, x, y, row, row, null)
            g.color = Color(seed?.let { Tones.tone(it, 7.0, 10.0) } ?: 0x141216)
            g.fillRect(x + row, y, 200, row)
            g.color = Color(seed?.let { Tones.tone(it, 12.0, 10.0) } ?: 0x211E22)
            g.fillRoundRect(x + row + 12, y + 12, 176, 96, 16, 16)
            if (seed != null) {
                g.color = Color(Tones.tone(seed, 80.0, minChroma = 32.0))
                g.fillOval(x + row + 28, y + 28, 64, 64)
                g.color = Color(Tones.tone(seed, 30.0, 24.0, 16.0))
                g.fillRoundRect(x + row + 104, y + 52, 72, 16, 16, 16)
            }
        }
        g.dispose()
        ImageIO.write(sheet, "png", File(System.getenv("SASAYAKI_SEEDS_OUT") ?: "build/cover-seeds.png").apply { parentFile?.mkdirs() })
    }

    /** ARGB pixels of [img] scaled to fit [max] px, like the quarter-size thumbnail the app reads. */
    private fun pixelsOf(img: BufferedImage, max: Int): IntArray {
        val small = scaled(img, max)
        return small.getRGB(0, 0, small.width, small.height, null, 0, small.width)
    }

    private fun scaled(img: BufferedImage, max: Int): BufferedImage {
        val scale = max.toDouble() / maxOf(img.width, img.height)
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
            it.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, w, h, null)
                dispose()
            }
        }
    }
}
