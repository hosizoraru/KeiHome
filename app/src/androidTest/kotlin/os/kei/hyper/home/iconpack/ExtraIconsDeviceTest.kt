package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.status.PublishedExtraIcons
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.data.AppDisplayStorage
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.RecommendedIconMappings
import os.kei.hyper.home.data.iconPackTargets
import os.kei.hyper.home.xposed.ConvertedIconArchive

@RunWith(AndroidJUnit4::class)
class ExtraIconsDeviceTest {
  @Test fun installedArtworkProducesDistinctDarkIconsAndPreservesDayAndLiveResources() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val targets = iconPackTargets(context)
    val selected = ExtraIcon.entries.mapTo(hashSetOf()) { it.packageName }
    assumeTrue("This check uses the six installed APKs",
      ExtraIcon.entries.all { icon -> targets.any(icon::accepts) })
    fun sample(mask: Boolean) = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).let { bitmap ->
      try {
        bitmap.eraseColor(Color.WHITE)
        if (mask) bitmap.setPixel(0, 0, Color.TRANSPARENT)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
      } finally { bitmap.recycle() }
    }
    val day = sample(false)
    val oldNight = sample(true)
    val mask = sample(true)
    val existing = buildMap {
      for (name in selected) putAll(NativeDayNightIcon.entries(name, day, oldNight))
      remove("fancy_icons/${ExtraIcon.MT.packageName}/night.png")
      putAll(NativeDayNightIcon.entries(ExtraIcon.MEILOX.packageName, day, oldNight, legacyExpanded = true))
      put("layer_animating_icons/${ExtraIcon.KERNEL_SU.packageName}/manifest.xml", byteArrayOf(1))
      putAll(NativePaletteIcon.entries(ExtraIcon.MT.packageName, day, day, mask, mask))
    }
    val file = File.createTempFile("extra-icons", ".zip", context.cacheDir)
    try {
      ZipOutputStream(file.outputStream()).use { archive ->
        for ((path, bytes) in existing) {
          archive.putNextEntry(ZipEntry(path))
          archive.write(bytes)
          archive.closeEntry()
        }
      }
      ZipFile(file).use { archive ->
        assertTrue(PublishedExtraIcons.replacements(context, archive, targets, emptySet()) {}.isEmpty())
        val replacements = PublishedExtraIcons.replacements(context, archive, targets, selected) {}
        for (icon in ExtraIcon.entries) {
          val target = targets.first(icon::accepts)
          val name = icon.packageName
          val root = "fancy_icons/$name"
          if (icon == ExtraIcon.KERNEL_SU) {
            assertFalse(replacements.keys.any { CustomIconResources.owner(it) in nativeLauncherIconNames(target) })
            continue
          }
          if (icon == ExtraIcon.SCENE) {
            assertFalse(replacements.keys.any { it.contains("ActivityFreezeApps") })
          }
          assertArrayEquals(name, day, replacements["$root/day.png"] ?: existing["$root/day.png"])
          val night = replacements.getValue("$root/night.png")
          assertFalse(name, day.contentEquals(night))
          val dark = BitmapFactory.decodeByteArray(night, 0, night.size)
          try {
            assertEquals(name, 192, dark.width)
            assertTrue(name, Color.red(dark.getPixel(96, 24)) < 100)
          } finally { dark.recycle() }
          if (icon == ExtraIcon.MT) assertTrue(NativePaletteIcon.recognizes(name,
            replacements.getValue("$root/manifest.xml"), day, night,
            replacements.getValue("$root/mask.png"), replacements.getValue("$root/bg.png")))
        }
      }
    } finally { file.delete() }
  }

  @Test fun classicArtPublicationUsesOnlyEnabledChoicesAndKeepsLightArtwork() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val targets = iconPackTargets(context).filter { target -> ExtraIcon.entries.any { it.accepts(target) } }
    assumeTrue("This check uses the six installed APKs",
      ExtraIcon.entries.all { icon -> targets.any(icon::accepts) })
    val repository = IconPackRepository(context)
    val previews = IconPackPreviewRepository(context)
    val pack = repository.listBuiltins().packs.single { it.colorOsProfile?.builtin?.key == "coloros-classic-art-on" }
    val settings = AppDisplayStorage.open(context)
    val previous = settings.getStringSet(ExtraIcons.KEY, null)?.toSet()
    val selected = ExtraIcon.entries.mapTo(hashSetOf()) { it.packageName }
    suspend fun publish(packages: Set<String>, appearance: IconPackAppearance = IconPackAppearance.SYSTEM):
      Map<String, ByteArray> {
      settings.edit().putStringSet(ExtraIcons.KEY, packages).apply()
      val handoff = IconPackHandoffs.prepare(context, repository, previews, pack, targets,
        appearance, includePalette = false, installedOnly = true,
        recommendedMappings = RecommendedIconMappings())
      val icons = File.createTempFile("extra-publication", ".zip", context.cacheDir)
      return try {
        ConvertedIconArchive.inspect(handoff.file, requireNotNull(handoff.convertedOrigin))
        ZipFile(handoff.file).use { mtz ->
          mtz.getInputStream(mtz.getEntry("icons")).use { source ->
            icons.outputStream().use { source.copyTo(it) }
          }
        }
        ZipFile(icons).use { zip ->
          zip.entries().asSequence().filterNot { it.isDirectory }.filter {
            it.name == IconGenerationManifest.PATH ||
              CustomIconResources.owner(it.name) in selected
          }.associate { it.name to zip.getInputStream(it).use { bytes -> bytes.readBytes() } }
        }
      } finally {
        handoff.file.delete()
        icons.delete()
      }
    }
    try {
      val before = publish(emptySet())
      val enabled = selected - ExtraIcon.MEILOX.packageName
      val after = publish(enabled)
      val all = publish(selected)
      val alwaysLight = publish(selected, IconPackAppearance.LIGHT)
      assertEquals(enabled, IconGenerationManifest.decode(after.getValue(IconGenerationManifest.PATH)).extraIconPackages)
      for (name in selected) {
        val path = "res/drawable-xxhdpi/$name.png"
        val night = "nightmode/$path"
        assertArrayEquals(name, before.getValue(path), after.getValue(path))
        assertEquals(name, name !in enabled, before.getValue(night).contentEquals(after.getValue(night)))
        assertArrayEquals(name, before.getValue(path), all.getValue(path))
        assertFalse(name, before.getValue(night).contentEquals(all.getValue(night)))
        assertArrayEquals(name, alwaysLight.getValue(path), alwaysLight.getValue(night))
      }
    } finally {
      settings.edit().apply {
        if (previous == null) remove(ExtraIcons.KEY) else putStringSet(ExtraIcons.KEY, previous)
      }.apply()
    }
  }
}
