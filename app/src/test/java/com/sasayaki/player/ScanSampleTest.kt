package com.sasayaki.player

import com.sasayaki.player.data.scan.LibraryScanner
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Dumps how the analyzer interprets a real library. Point SASAYAKI_SAMPLE at a folder to run it. */
class ScanSampleTest {
    @Test
    fun dumpSampleLibrary() {
        val path = System.getenv("SASAYAKI_SAMPLE")
        assumeTrue(path != null)
        val root = File(path!!)
        assumeTrue(root.isDirectory)
        val entries = LibraryScanner(fillPartial = true).scan(listOf(root))
        val out = StringBuilder()
        for (e in entries) {
            out.append("\n## ${e.title}  [rj=${e.rj} cat=${e.categoryLabel} archive=${e.hasArchive} folder=${e.hasFolder}]\n")
            out.append("   cover: ${e.cover?.fileName} embedded=${e.coverEmbedded}  images=${e.images.size} docs=${e.docs.map { it.name }}\n")
            for (v in e.versions) {
                out.append("   * ${v.label} [${v.format}] path='${v.path}' tracks=${v.tracks.size}" +
                    (if (v.isFilled) " (own ${v.ownCount}, filled from '${v.baseLabel}')" else "") + "\n")
                for (t in v.tracks) {
                    out.append("       - ${if (t.fromBase) "(base) " else ""}${t.key ?: "?"} | ${t.title}${if (t.subtitle != null) "  [sub]" else ""}\n")
                }
            }
        }
        File(System.getenv("SASAYAKI_OUT") ?: "build/scan-dump.txt").apply { parentFile?.mkdirs() }.writeText(out.toString())
    }
}
