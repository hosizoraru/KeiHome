package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ColorOsMonochromeGeometryDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val targets = listOf("com.android.contacts", "com.android.thememanager",
    "com.android.mms", "com.android.settings").map(::IconPackComponent) +
    IconPackComponent("com.android.fileexplorer", "com.android.fileexplorer.FileExplorerTabActivity")

  private suspend fun pack() = IconPackRepository(context).listBuiltins().packs
    .single { it.builtinKey == "coloros-classic-art-on" }

  private fun nativeMask(pack: IconPackDescriptor, target: IconPackComponent, size: Int): Bitmap {
    val profile = requireNotNull(pack.colorOsProfile)
    val bytes = profile.read(requireNotNull(profile.source(target)?.monochromePath))
    val raw = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    try {
      val layer = BitmapDrawable(context.resources, raw).apply { isFilterBitmap = true }
      // Independent framework oracle, including the original layer's overscan.
      AdaptiveIconDrawable(null, layer).setBounds(0, 0, size, size)
      return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        layer.draw(Canvas(it))
      }
    } finally { raw.recycle() }
  }

  private fun assertAlpha(expected: Bitmap, actual: Bitmap, label: String) {
    assertEquals(label, expected.width, actual.width)
    assertEquals(label, expected.height, actual.height)
    val size = expected.width * expected.height
    val a = IntArray(size)
    val b = IntArray(size)
    expected.getPixels(a, 0, expected.width, 0, 0, expected.width, expected.height)
    actual.getPixels(b, 0, actual.width, 0, 0, actual.width, actual.height)
    assertArrayEquals(label, a.map { Color.alpha(it) }.toIntArray(),
      b.map { Color.alpha(it) }.toIntArray())
  }

  @Test fun suppliedRawMasksUseTheSameAdaptiveViewportAsApkMonochrome() = runBlocking {
    val pack = pack()
    for (dark in listOf(false, true)) IconPackRenderer(context).open(pack, dark).use { session ->
      for (target in targets) for (size in listOf(64, 96, 192)) {
        val expected = nativeMask(pack, target, size)
        try {
          val actual = requireNotNull(session.colorOsMonochrome(target, size) {})
          try { assertAlpha(expected, actual, "$target/$dark/$size") }
          finally { actual.recycle() }
        } finally { expected.recycle() }
      }
    }
  }

  @Test fun exporterPublishesCompletedMasksWithoutAnotherInset() = runBlocking {
    val pack = pack()
    for (dark in listOf(false, true)) {
      val output = IconPackExporter(context, IconPackLimits()).export(pack, targets, dark, 192,
        installedOnly = true, preservedPackages = emptySet(), check = {})
      try {
        for (target in targets) {
          val bytes = requireNotNull(output.monochrome[target.packageName]) { "$target missing" }
          val actual = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
          val expected = nativeMask(pack, target, 192)
          try { assertAlpha(expected, actual, "$target exported/$dark") }
          finally { expected.recycle(); actual.recycle() }
        }
      } finally { output.archive.delete() }
    }
  }

  @Test fun completeSuppliedMaskCatalogRemainsRenderable() = runBlocking {
    val pack = pack()
    val profile = requireNotNull(pack.colorOsProfile)
    val sources = profile.icons.filterValues { it.monochromePath != null }
    assertTrue("Real supplied mask catalog is required", sources.size > targets.size)
    IconPackRenderer(context).open(pack, false).use { session ->
      for ((target, source) in sources) {
        val actual = session.colorOsMonochrome(target, 192) {}
        if (actual == null) {
          assertTrue("$target authored mask missing", source.runtimeMonochrome || source.requiresLiveIcon)
          continue
        }
        try { assertEquals(192, actual.width); assertEquals(192, actual.height) }
        finally { actual.recycle() }
      }
    }
  }

  @Test fun unusableOptionalSuppliedMaskDoesNotAbortThePack() = runBlocking {
    val pack = pack()
    val raw = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    raw.eraseColor(Color.WHITE)
    val file = File(context.cacheDir, "unusable-mono-${UUID.randomUUID()}.zip")
    try {
      ZipOutputStream(file.outputStream()).use {
        it.putNextEntry(ZipEntry("mask.png"))
        assertTrue(raw.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.closeEntry()
      }
      val profile = requireNotNull(pack.colorOsProfile).copy(file = file, builtin = null,
        icons = mapOf(targets.first() to ColorOsIconSource("mask.png", null, false)))
      IconPackRenderer(context).open(pack.copy(colorOsProfile = profile), false).use {
        assertNull(it.colorOsMonochrome(targets.first(), 192) {})
      }
    } finally { raw.recycle(); file.delete() }
  }
}
