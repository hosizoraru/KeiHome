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
import os.kei.hyper.home.xposed.GeneratedNativeIconValidator

@RunWith(AndroidJUnit4::class)
class ExtraIconsDeviceTest {
  @Test fun darkExtraExpandsCompactNativeSelectorWithoutOverwritingDay() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val icon = ExtraIcon.SERVICES_FEEDBACK
    val target = iconPackTargets(context).firstOrNull(icon::accepts)
    assumeTrue("Install Services & feedback", target != null)
    val name = icon.packageName
    val root = "fancy_icons/$name"
    val day = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).let { bitmap ->
      try {
        bitmap.eraseColor(Color.WHITE)
        ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
      } finally { bitmap.recycle() }
    }
    val compact = NativeDayNightIcon.entries(name, day, day)
    val file = File.createTempFile("compact-extra", ".zip", context.cacheDir)
    try {
      ZipOutputStream(file.outputStream()).use { archive ->
        for ((path, bytes) in compact) ImageArchiveZip.writeBytes(archive, path, bytes) {}
      }
      val published = ZipFile(file).use { archive ->
        compact + PublishedExtraIcons.replacements(context, archive, listOf(requireNotNull(target)), setOf(name)) {}
      }
      assertArrayEquals(day, published.getValue("$root/day.png"))
      val night = published.getValue("$root/night.png")
      assertFalse(day.contentEquals(night))
      assertArrayEquals(night, published.getValue("nightmode/res/drawable-xxhdpi/$name.png"))
      GeneratedNativeIconValidator.validate(name, published.keys) { path, _ -> published.getValue(path) }
    } finally { file.delete() }
  }

  @Test fun layeredDarkMarksSurvivePreviewAndPublication() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val installed = iconPackTargets(context)
    val icons = listOf(ExtraIcon.KEY_ATTESTATION, ExtraIcon.VPN_HOTSPOT, ExtraIcon.FILEYAN)
    assumeTrue("Install Key Attestation, VPN Hotspot, and Fileyan", icons.all { icon -> installed.any(icon::accepts) })
    val targets = icons.associateWith { icon -> installed.first(icon::accepts) }
    val renderer = ColorOsFallbackRenderer(context)
    val shape = IconMaskPolicy.hyperOs4Preview().resolve(null)
    val archiveFile = File.createTempFile("layered-dark-marks", ".zip", context.cacheDir)
    try {
      val dayImages = icons.associateWith { icon ->
        val original = requireNotNull(renderer.renderExtra(icon, targets.getValue(icon), 192, shape, original = true))
        try {
          ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { original.recycle() }
      }
      ZipOutputStream(archiveFile.outputStream()).use { archive ->
        for ((icon, day) in dayImages) {
          val path = "res/drawable-xxhdpi/${icon.packageName}.png"
          archive.putNextEntry(ZipEntry(path))
          archive.write(day)
          archive.closeEntry()
        }
      }
      ZipFile(archiveFile).use { archive ->
        val selected = icons.mapTo(hashSetOf()) { it.packageName }
        val published = PublishedExtraIcons.replacements(context, archive, targets.values.toList(), selected) {}
        val root = "fancy_icons/"
        for ((icon, target) in targets) {
          val preview = requireNotNull(renderer.renderExtra(icon, target, 192, shape))
          val night = published.getValue("$root${icon.packageName}/night.png")
          val exported = BitmapFactory.decodeByteArray(night, 0, night.size)
          try {
            assertArrayEquals(dayImages.getValue(icon), published.getValue("$root${icon.packageName}/day.png"))
            assertTrue(icon.label, preview.sameAs(exported))
            fun green(x: Int, y: Int) = Color.green(preview.getPixel(x, y))
            when (icon) {
              ExtraIcon.KEY_ATTESTATION -> {
                assertTrue("Checkmark", green(96, 152) > green(64, 152) + 70)
                assertTrue("Keyhole", green(68, 77) + 30 < green(82, 77))
              }
              ExtraIcon.VPN_HOTSPOT -> {
                assertTrue("Outer signal arc", green(85, 48) > green(85, 58) + 45)
                assertTrue("Signal dot inside the keyhole", green(80, 96) > green(80, 58) + 70)
                assertTrue("Keyhole remains visible", green(96, 96) > green(80, 96) + 35)
              }
              ExtraIcon.FILEYAN -> {
                assertTrue("Ring contrast", green(130, 126) > green(96, 24) + 70)
                val center = preview.getPixel(83, 75)
                assertTrue("Green center", Color.green(center) > Color.red(center) + 60)
              }
              else -> error("Unexpected icon $icon")
            }
          } finally {
            preview.recycle()
            exported.recycle()
          }
        }
      }
    } finally { archiveFile.delete() }
  }

  @Test fun installedArtworkProducesDistinctDarkIconsAndPreservesDayAndLiveResources() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val targets = iconPackTargets(context)
    val selected = ExtraIcon.entries.mapTo(hashSetOf()) { it.packageName }
    assumeTrue("This check uses all supported installed APKs",
      ExtraIcon.entries.all { icon -> targets.any(icon::accepts) })
    assertEquals(listOf(IconPackComponent(ExtraIcon.DOWNLOAD_PROVIDER.packageName)),
      targets.filter(ExtraIcon.DOWNLOAD_PROVIDER::accepts))
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
    assumeTrue("This check uses all supported installed APKs",
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
      val enabled = selected - setOf(ExtraIcon.MEILOX.packageName, ExtraIcon.DOWNLOAD_PROVIDER.packageName)
      val after = publish(enabled)
      val all = publish(selected)
      val alwaysLight = publish(selected, IconPackAppearance.LIGHT)
      assertEquals(enabled, IconGenerationManifest.decode(after.getValue(IconGenerationManifest.PATH)).extraIconPackages)
      for (name in selected) {
        val path = "res/drawable-xxhdpi/$name.png"
        val night = "nightmode/$path"
        val originalDay = before[path]
        val originalNight = before[night]
        if (originalDay != null) assertArrayEquals(name, originalDay, all.getValue(path))
        assertFalse(name, originalNight.contentEquals(all.getValue(night)))
        assertFalse(name, all.getValue(path).contentEquals(all.getValue(night)))
        assertArrayEquals(name, if (name in enabled) all[path] else originalDay, after[path])
        assertArrayEquals(name, if (name in enabled) all[night] else originalNight, after[night])
        assertArrayEquals(name, alwaysLight[path], alwaysLight[night])
      }
    } finally {
      settings.edit().apply {
        if (previous == null) remove(ExtraIcons.KEY) else putStringSet(ExtraIcons.KEY, previous)
      }.apply()
    }
  }
}
