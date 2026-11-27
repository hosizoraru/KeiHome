package os.kei.hyper.home.status

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.iconpack.SpoolIconEntries

class HandoffArchiveOverlayTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun excludeReplacedDropsStaleInMemoryLayerAnimatingEntries() {
    val zipFile = File(temp.root, "base.zip")
    ZipOutputStream(zipFile.outputStream()).use { out ->
      out.putNextEntry(ZipEntry("res/drawable-xxhdpi/xyz.nextalone.nnngram.png"))
      out.write(byteArrayOf(1, 2, 3))
      out.closeEntry()
    }
    ZipFile(zipFile).use { base ->
      SpoolIconEntries.open(temp.root).use { spool ->
        val overlay = HandoffArchiveOverlay(base, spool)
        // Simulate ForeignTelegramAliases adding in-memory layer_animating_icons before Monet runs.
        val aliasPaths = listOf(
          "layer_animating_icons/xyz.nextalone.nnngram/config.xml",
          "layer_animating_icons/xyz.nextalone.nnngram/0/quiet/quietImage.png",
          "nightmode/layer_animating_icons/xyz.nextalone.nnngram/config.xml",
        )
        overlay.putAllInMemory(aliasPaths.associateWith { byteArrayOf(9) })

        // Simulate PublishedGenericMonet writing fancy_icons/<pkg>/* and marking aliasPaths replaced.
        val monetOutputs = mapOf(
          "fancy_icons/xyz.nextalone.nnngram/manifest.xml" to "<Icon/>".toByteArray(),
          "fancy_icons/xyz.nextalone.nnngram/mask.png" to byteArrayOf(4, 5),
        )
        overlay.putSpooled(monetOutputs) {}
        overlay.excludeReplaced(aliasPaths, monetOutputs.keys)

        val finalPaths = overlay.paths()
        for (stale in aliasPaths) {
          assertFalse("Stale in-memory alias must be excluded: $stale", stale in finalPaths)
          assertFalse("Stale in-memory alias must be removed from inMemory: $stale", stale in overlay.inMemory)
          assertNull(overlay.read(stale, 1024))
        }
        for ((path, expected) in monetOutputs) {
          assertTrue("Monet output must remain: $path", path in finalPaths)
          assertEquals(expected.toList(), overlay.read(path, 1024)?.toList())
        }
      }
    }
  }
}
