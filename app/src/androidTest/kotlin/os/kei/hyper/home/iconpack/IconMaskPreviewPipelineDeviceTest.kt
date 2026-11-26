package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.status.SystemIconThemeReport
import os.kei.hyper.home.ui.apple.AppleCurrentThemePreviewProvider
import os.kei.hyper.home.ui.main.CurrentIconPreviewRepository
import os.kei.hyper.home.ui.main.IconBrowserSource
import os.kei.hyper.home.ui.main.IconPreviewColors

@RunWith(AndroidJUnit4::class)
class IconMaskPreviewPipelineDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val circle = "M50 0 A50 50 0 1 1 50 100 A50 50 0 1 1 50 0 Z"
  private val square = "M0 0 H100 V100 H0 Z"
  private val color = Color.rgb(20, 40, 60)

  private fun custom(path: String) = CustomIconMask(path, "Test mask",
    MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) })

  @Test fun originalOs4AndCustomKeepSeparateCacheEntriesAndClearNotifiesObservers() = runBlocking {
    fixture(NativeIconShapeConfig.generated(square)) { pack, entry, _ ->
      val repository = IconPackPreviewRepository(context)
      val originalPolicy = IconMaskPolicy(false)
      val original = requireNotNull(repository.load(pack, entry, maskPolicy = originalPolicy).light)
      val os4 = requireNotNull(repository.load(pack, entry, maskPolicy = IconMaskPolicy.hyperOs4Preview()).light)
      val round = requireNotNull(repository.load(pack, entry, maskPolicy = IconMaskPolicy(true, custom(circle))).light)
      assertEquals(255, Color.alpha(original.getPixel(0, 0)))
      assertEquals(0, Color.alpha(os4.getPixel(0, 0)))
      assertEquals(0, Color.alpha(round.getPixel(0, 0)))
      assertTrue(Color.alpha(os4.getPixel(12, 12)) > 0)
      assertEquals(0, Color.alpha(round.getPixel(12, 12)))
      assertSame(original, repository.load(pack, entry, maskPolicy = originalPolicy).light)
      assertFalse(original.isRecycled)
      assertEquals(255, Color.alpha(original.getPixel(0, 0)))
      val before = repository.revision.value
      repository.clear()
      assertEquals(before + 1, repository.revision.value)
      assertNotSame(original, repository.load(pack, entry, maskPolicy = originalPolicy).light)
    }
  }

  @Test fun rendererClipsOnceAndRawExportInputRetainsItsCorners() = runBlocking {
    fixture(null) { pack, entry, _ ->
      val policy = IconMaskPolicy(true, custom(circle))
      val renderer = IconPackRenderer(context)
      val raw = requireNotNull(renderer.open(pack, false, maskPolicy = policy, clipOutput = false).use {
        it.renderTarget(entry, null, 128) {}
      })
      val preview = requireNotNull(renderer.open(pack, false, maskPolicy = policy).use { it.render(entry, 128) })
      val expected = IconMaskPolicy.clip(raw, policy.resolve(null))
      assertTrue(preview.sameAs(expected))
      assertEquals(255, Color.alpha(raw.getPixel(0, 0)))
      assertEquals(0, Color.alpha(preview.getPixel(0, 0)))
      var antialiased = false
      for (y in 0 until preview.height) for (x in 0 until preview.width)
        if (Color.alpha(preview.getPixel(x, y)) in 1..254) antialiased = true
      assertTrue("Fixture must exercise fractional edge alpha", antialiased)
      assertFalse(raw.isRecycled)
    }
  }

  @Test fun authoredConfigIsReadOnceAcrossIconsAndAppearanceSessions() = runBlocking {
    val configReads = AtomicInteger()
    val config = NativeIconShapeConfig.generated(circle)
    val png = png()
    val builtin = BuiltinIconPack("mask-preview-probe", "mask-preview-probe",
      setOf(NativeIconShapeConfig.PATH, "probe.png")) { path, _, check ->
      check()
      if (path == NativeIconShapeConfig.PATH) { configReads.incrementAndGet(); config } else png
    }
    val artwork = artwork("probe.png")
    val entry = IconPackEntry("probe", nativeName = "probe", light = artwork, dark = artwork)
    val pack = IconPackDescriptor("mask-preview-probe", "Probe", IconPackFormat.MIUI_ICONS,
      IconPackSource.Builtin(builtin), listOf(entry))
    val renderer = IconPackRenderer(context)
    for (dark in listOf(false, true)) renderer.open(pack, dark, maskPolicy = IconMaskPolicy(true)).use { session ->
      repeat(3) {
        val bitmap = requireNotNull(session.render(entry, 64))
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        bitmap.recycle()
      }
    }
    assertEquals(1, configReads.get())
  }

  @Test fun malformedAuthoredProgramDoesNotBecomeMissingConfiguration() = runBlocking {
    fixture("<unrecognized/>".toByteArray()) { pack, entry, _ ->
      val preview = IconPackPreviewRepository(context).load(pack, entry, maskPolicy = IconMaskPolicy(true))
      assertEquals(255, Color.alpha(requireNotNull(preview.light).getPixel(0, 0)))
    }
  }

  @Test fun publishedPreviewClipsItsOwnConfigAndLeavesMonochromeAndCachedFallbackUntouched() = runBlocking {
    fixture(NativeIconShapeConfig.generated(circle), palette = true) { _, entry, source ->
      val fallback = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
      val repository = CurrentIconPreviewRepository(context, { _, _ -> IconPackPreviews(fallback, fallback) })
      val preview = repository.load(source, entry)
      assertEquals(circle, preview.maskPathData)
      assertEquals(0, Color.alpha(requireNotNull(preview.light).getPixel(0, 0)))
      assertEquals(0, Color.alpha(requireNotNull(preview.dark).getPixel(0, 0)))
      assertEquals(255, Color.alpha(requireNotNull(preview.lightMonochrome).getPixel(0, 0)))
      assertEquals(255, Color.alpha(requireNotNull(preview.backgroundMask).getPixel(0, 0)))
      assertFalse(fallback.isRecycled)
      assertEquals(255, Color.alpha(fallback.getPixel(0, 0)))
      assertSame(preview, repository.load(source, entry))
    }
  }

  @Test fun applePublishedMonochromeUsesPublishedShapeAfterRecoloring() = runBlocking {
    fixture(NativeIconShapeConfig.generated(circle), palette = true) { _, _, source ->
      val raw = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
      val theme = SystemIconThemeReport(SystemIconThemeReport.Source.THEME, "Probe", null,
        1, 1, true, "revision", previewKey = source.revision)
      val colors = IconPreviewColors(lightForeground = Color.RED, lightBackground = Color.GREEN,
        darkForeground = Color.BLUE, darkBackground = Color.BLACK)
      val repository = AppleCurrentThemePreviewProvider(theme, source, colors, true, { null },
        { _, _ -> AppIconPreviews(raw, raw, raw, raw, backgroundMask = raw, maskPathData = circle) })
      val preview = requireNotNull(repository.load("probe"))
      assertEquals(Color.RED, preview.light.getPixel(96, 96))
      assertEquals(0, Color.alpha(preview.light.getPixel(0, 0)))
      assertEquals(Color.BLUE, requireNotNull(preview.dark).getPixel(96, 96))
      assertEquals(0, Color.alpha(requireNotNull(preview.dark).getPixel(0, 0)))
      assertFalse(raw.isRecycled)
      assertEquals(Color.WHITE, raw.getPixel(0, 0))
    }
  }

  private fun artwork(path: String) = IconPackArtwork.Archive(
    listOf(IconArchiveVariant(path, IconResourceQualifiers("drawable", densityDpi = 0))))

  private fun png(): ByteArray {
    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return try {
      ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
      }
    } finally { bitmap.recycle() }
  }

  private suspend fun fixture(config: ByteArray?, palette: Boolean = false,
    block: suspend (IconPackDescriptor, IconPackEntry, IconBrowserSource) -> Unit) {
    val file = File.createTempFile("mask-preview-pipeline-", ".zip", context.cacheDir)
    try {
      ZipOutputStream(file.outputStream()).use { zip ->
        fun write(path: String, bytes: ByteArray) {
          zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        }
        val image = png()
        config?.let { write(NativeIconShapeConfig.PATH, it) }
        write("probe.png", image)
        if (palette) {
          write("fancy_icons/probe/day.png", image)
          write("fancy_icons/probe/mask.png", image)
          write("fancy_icons/probe/bg.png", image)
        }
      }
      val image = artwork("probe.png")
      val entry = IconPackEntry("probe", nativeName = "probe", light = image, dark = image)
      val record = StoredIconPack("probe", "Probe", 0, IconPackFormat.MIUI_ICONS,
        file, file, file.length(), null)
      val pack = IconPackDescriptor(file.name, "Probe", IconPackFormat.MIUI_ICONS,
        IconPackSource.Imported(record), listOf(entry))
      val source = IconBrowserSource("published-${file.name}", pack, mapOf("probe" to entry),
        if (palette) setOf("probe") else emptySet(), emptySet())
      block(pack, entry, source)
    } finally { file.delete() }
  }
}
