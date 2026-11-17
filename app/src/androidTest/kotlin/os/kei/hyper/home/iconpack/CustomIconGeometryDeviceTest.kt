package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.RecommendedIconMappings

@RunWith(AndroidJUnit4::class)
class CustomIconGeometryDeviceTest {
  private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val maskPath = "fancy_icons/custom.icon/mask.png"

  @Test fun regeneratesOnlyClosedGeneratedMaskAndPreservesEveryOtherResource() = runBlocking {
    Fixture().use { f ->
      val pack = IconPackRepository(f.context).listBuiltins().packs.first {
        it.colorOsProfile?.builtin?.key == "coloros-classic-art-on"
      }
      val target = IconPackComponent("com.android.settings")
      val old = NativePaletteIcon.entries("custom.icon", png(Color.RED), png(Color.BLUE),
        alphaMask(), alphaMask())
      val updated = requireNotNull(CustomIconGeometry.regenerate(f.context, pack, target, old) {})
      assertEquals(old.keys, updated.keys)
      old.filterKeys { it != maskPath }.forEach { (path, bytes) -> assertArrayEquals(path, bytes, updated[path]) }
      assertFalse(old.getValue(maskPath).contentEquals(updated.getValue(maskPath)))
      val authored = old + ("fancy_icons/custom.icon/manifest.xml" to "<Icon><Image src=\"mask.png\"/></Icon>".toByteArray())
      assertNull(CustomIconGeometry.regenerate(f.context, pack, target, authored) {})
    }
  }

  @Test fun legacyRefreshRetainsMappingAndArtworkAndRunsOnlyOnce() = runBlocking {
    Fixture().use { f ->
      val repository = IconPackRepository(f.context)
      val pack = repository.listBuiltins().packs.first {
        it.colorOsProfile?.builtin?.key == "coloros-classic-art-on"
      }
      val entry = pack.entries.first { it.component?.packageName == "com.android.settings" }
      val resources = NativePaletteIcon.entries("custom.icon", png(Color.RED), png(Color.BLUE),
        alphaMask(), alphaMask())
      CustomIconStore.save(f.context, "com.example.target", "App", pack, entry, resources)
      val prefs = f.context.getSharedPreferences("custom_icons_v1", Context.MODE_PRIVATE)
      val rows = JSONArray(prefs.getString("selections", "[]"))
      rows.getJSONObject(0).remove("captureRevision")
      assertTrue(prefs.edit().putString("selections", rows.toString()).commit())
      val previous = CustomIconStore.selections(f.context).single()
      assertEquals(0, previous.captureRevision)
      CustomIconGeometry.refresh(f.context, repository)
      val current = CustomIconStore.selections(f.context).single()
      assertEquals(previous.sourceId, current.sourceId)
      assertEquals(previous.sourceEntry, current.sourceEntry)
      assertEquals(previous.sourceTitle, current.sourceTitle)
      assertEquals(previous.packageName, current.packageName)
      assertEquals(CustomIconStore.CAPTURE_REVISION, current.captureRevision)
      assertNotEquals(previous.token, current.token)
      val actual = CustomIconStore.read(f.context).resources.getValue(current.packageName)
      resources.filterKeys { it != maskPath }.forEach { (path, bytes) -> assertArrayEquals(path, bytes, actual[path]) }
      CustomIconGeometry.refresh(f.context, repository)
      assertEquals(current, CustomIconStore.selections(f.context).single())
      assertEquals(1, File(f.context.filesDir, "custom-icons").listFiles()!!.size)
    }
  }

  @Test fun refreshCannotOverwriteAConcurrentChoiceAndCancellationPublishesNothing() = runBlocking {
    Fixture().use { f ->
      val pack = IconPackRepository(f.context).listBuiltins().packs.first { it.colorOsProfile != null }
      val entry = pack.entries.first()
      val resources = NativeDayNightIcon.entries("custom.icon", png(Color.RED), png(Color.BLUE))
      val original = CustomIconStore.save(f.context, "com.example.target", "App", pack, entry, resources)
      val replacement = CustomIconStore.save(f.context, "com.example.target", "New", pack, entry, resources)
      assertNull(CustomIconStore.readSelection(f.context, original))
      assertNotNull(CustomIconStore.readSelection(f.context, replacement))
      assertFalse(CustomIconStore.refresh(f.context, original, resources))
      assertEquals(replacement, CustomIconStore.selections(f.context).single())
      try {
        CustomIconStore.refresh(f.context, replacement, resources) { throw CancellationException("cancel") }
        fail("Cancellation must propagate")
      } catch (_: CancellationException) { }
      assertEquals(replacement, CustomIconStore.selections(f.context).single())
      assertEquals(1, File(f.context.filesDir, "custom-icons").listFiles()!!.size)
    }
  }

  @Test fun completedCaptureAndUnavailableSourceNeverRefreshAgain() = runBlocking {
    Fixture().use { f ->
      val repository = IconPackRepository(f.context)
      val pack = repository.listBuiltins().packs.first { it.colorOsProfile != null }
      val resources = NativeDayNightIcon.entries("custom.icon", png(Color.RED), png(Color.BLUE))
      val item = CustomIconStore.save(f.context, "com.example.target", "App", pack, pack.entries.first(), resources)
      CustomIconGeometry.refresh(f.context, repository)
      assertEquals(item, CustomIconStore.selections(f.context).single())
      val prefs = f.context.getSharedPreferences("custom_icons_v1", Context.MODE_PRIVATE)
      val rows = JSONArray(prefs.getString("selections", "[]"))
      rows.getJSONObject(0).put("captureRevision", 0).put("source", "unavailable-source")
      assertTrue(prefs.edit().putString("selections", rows.toString()).commit())
      val legacy = CustomIconStore.selections(f.context).single()
      CustomIconGeometry.refresh(f.context, repository)
      assertEquals(legacy, CustomIconStore.selections(f.context).single())
      assertArrayEquals(resources.values.first(), CustomIconStore.read(f.context).resources.values.single().values.first())
    }
  }

  @Test fun savedRawPairPreviewUsesNativeViewportInsteadOfTheFirstTexture() = runBlocking {
    Fixture().use { f ->
      val pack = IconPackRepository(f.context).listBuiltins().packs.first { it.colorOsProfile != null }
      val background = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
      val foreground = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawRect(72f, 72f, 120f, 120f, android.graphics.Paint().apply { color = Color.RED })
      }
      try {
        val resources = mapOf("res/drawable-xxhdpi/custom.icon/0.png" to encode(background),
          "res/drawable-xxhdpi/custom.icon/1.png" to encode(foreground))
        val item = CustomIconStore.save(f.context, "com.example.target", "App", pack, pack.entries.first(), resources)
        val png = requireNotNull(CustomIconStore.preview(f.context, item, false))
        val preview = BitmapFactory.decodeByteArray(png, 0, png.size)
        try {
          val points = (0 until 192).filter { Color.red(preview.getPixel(it, 96)) > 200 }
          // A 48px native child expands to 72px, centered on the completed icon.
          assertEquals(72, points.size)
          assertEquals(60, points.first())
        } finally { preview.recycle() }
      } finally { background.recycle(); foreground.recycle() }
    }
  }

  @Test fun recommendedArtworkAndMonochromeBorrowTheSameSourceAndPreservedTargetsOptOut() = runBlocking {
    Fixture().use { f ->
      val builtin = IconPackRepository(f.context).listBuiltins().packs.first { it.colorOsProfile != null }
      val source = IconPackComponent(PhoneCompanionIcons.SOURCE)
      val target = IconPackComponent(PhoneCompanionIcons.TARGETS.first())
      val file = File(f.context.cacheDir, "supplied.zip")
      ZipOutputStream(file.outputStream()).use { zip ->
        for ((name, start) in listOf("donor.png" to 64, "target.png" to 84)) {
          val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
          Canvas(bitmap).drawRect(start.toFloat(), 64f, (192 - start).toFloat(), 128f,
            android.graphics.Paint().apply { color = Color.WHITE })
          zip.putNextEntry(ZipEntry(name)); zip.write(encode(bitmap)); zip.closeEntry(); bitmap.recycle()
        }
      }
      val profile = requireNotNull(builtin.colorOsProfile).copy(file = file, builtin = null,
        icons = mapOf(source to ColorOsIconSource("donor.png", null, false),
          target to ColorOsIconSource("target.png", null, false)))
      val donor = builtin.entries.first().copy(key = "donor", component = source)
      val pack = builtin.copy(entries = listOf(donor), colorOsProfile = profile)
      IconPackRenderer(f.context).open(pack, false, preservedPackages = emptySet()).use { session ->
        assertEquals(donor.key, session.completedEntry(target, null)!!.key)
        val expected = requireNotNull(session.colorOsMonochrome(source, 192) {})
        val actual = requireNotNull(session.colorOsMonochrome(target, 192) {})
        try { assertTrue(expected.sameAs(actual)) } finally { expected.recycle(); actual.recycle() }
      }
      IconPackRenderer(f.context).open(pack, false, preservedPackages = setOf(target.packageName)).use { session ->
        assertNull(session.colorOsMonochrome(target, 192) {})
      }
      IconPackRenderer(f.context).open(pack, false, preservedPackages = emptySet(),
        recommendedMappings = RecommendedIconMappings.NONE).use { session ->
        val expected = requireNotNull(session.colorOsMonochrome(source, 192) {})
        val actual = requireNotNull(session.colorOsMonochrome(target, 192) {})
        try { assertFalse(expected.sameAs(actual)) } finally { expected.recycle(); actual.recycle() }
      }
    }
  }

  /** Read-only reporter evidence. Never changes the real durable selections. */
  @Test fun recordsRealSavedMaskVersusCurrentExactBuiltinSource() = runBlocking {
    val snapshot = CustomIconStore.read(base)
    val rows = snapshot.selections.filter { it.packageName in setOf("com.taobao.taobao", "tv.danmaku.bili") }
    assumeTrue("Reporter custom selections not installed", rows.isNotEmpty())
    val packs = IconPackRepository(base).listBuiltins().packs.associateBy { it.id }
    val evidence = File(base.getExternalFilesDir(null), "custom-geometry-evidence").apply { mkdirs() }
    val report = JSONArray()
    for (item in rows) {
      val pack = requireNotNull(packs[item.sourceId])
      val entry = pack.entries.single { it.key == item.sourceEntry }
      val old = snapshot.resources.getValue(item.packageName)
      val updated = requireNotNull(CustomIconGeometry.regenerate(base, pack, requireNotNull(entry.component), old) {})
      val oldPng = old.getValue(maskPath)
      val newPng = updated.getValue(maskPath)
      File(evidence, "${item.packageName}-old.png").writeBytes(oldPng)
      File(evidence, "${item.packageName}-fresh.png").writeBytes(newPng)
      val before = BitmapFactory.decodeByteArray(oldPng, 0, oldPng.size)
      val after = BitmapFactory.decodeByteArray(newPng, 0, newPng.size)
      try {
        val row = JSONObject().put("package", item.packageName).put("source", item.sourceId)
          .put("oldBounds", bounds(before)).put("freshBounds", bounds(after)).put("samePixels", before.sameAs(after))
        report.put(row)
        println("CUSTOM_GEOMETRY $row")
      } finally { before.recycle(); after.recycle() }
      old.filterKeys { it != maskPath }.forEach { (path, bytes) -> assertArrayEquals(path, bytes, updated[path]) }
    }
    File(evidence, "masks.json").writeText(report.toString(2))
  }

  @Test fun realTelegramPublishedMaskMatchesTheApkAdaptiveMonochrome() {
    val fixture = File(base.externalCacheDir, "chain-active-icons.zip")
    assumeTrue("Read-only active theme fixture missing", fixture.isFile)
    val icon = base.packageManager.getApplicationIcon("org.telegram.messenger") as AdaptiveIconDrawable
    val foreground = requireNotNull(icon.monochrome)
    val wrapper = AdaptiveIconDrawable(null, foreground).apply { setBounds(0, 0, 192, 192) }
    val expected = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    requireNotNull(wrapper.foreground).draw(Canvas(expected))
    val png = java.util.zip.ZipFile(fixture).use { zip ->
      zip.getInputStream(zip.getEntry("fancy_icons/org.telegram.messenger/mask.png")).use { it.readBytes() }
    }
    val actual = BitmapFactory.decodeByteArray(png, 0, png.size)
    try {
      assertEquals(bounds(expected), bounds(actual))
      var delta = 0L
      for (y in 0 until 192) for (x in 0 until 192)
        delta += kotlin.math.abs(Color.alpha(expected.getPixel(x, y)) - Color.alpha(actual.getPixel(x, y)))
      assertTrue("Mean alpha error ${delta / (192.0 * 192)}", delta / (192.0 * 192) < .5)
      println("TELEGRAM_APK_GEOMETRY ${bounds(expected)}")
    } finally { expected.recycle(); actual.recycle() }
  }

  private fun bounds(bitmap: Bitmap): String {
    val points = buildList { for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
      if (Color.alpha(bitmap.getPixel(x, y)) >= 128) add(x to y) }
    return "${points.minOf { it.first }},${points.minOf { it.second }},${points.maxOf { it.first } + 1},${points.maxOf { it.second } + 1}"
  }
  private fun png(color: Int): ByteArray = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).let {
    try { it.eraseColor(color); encode(it) } finally { it.recycle() }
  }
  private fun alphaMask(): ByteArray = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).let {
    try {
      Canvas(it).drawRect(88f, 88f, 104f, 104f, android.graphics.Paint().apply { color = Color.WHITE })
      encode(it)
    } finally { it.recycle() }
  }
  private fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
    require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
  }.toByteArray()

  private inner class Fixture : AutoCloseable {
    private val id = "custom-geometry-${UUID.randomUUID()}"
    private val root = File(base.cacheDir, id).apply { mkdirs() }
    val context = object : ContextWrapper(base) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir() = File(root, "files").apply { mkdirs() }
      override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        base.getSharedPreferences("$id-$name", mode)
    }
    override fun close() { base.deleteSharedPreferences("$id-custom_icons_v1"); root.deleteRecursively() }
  }
}
