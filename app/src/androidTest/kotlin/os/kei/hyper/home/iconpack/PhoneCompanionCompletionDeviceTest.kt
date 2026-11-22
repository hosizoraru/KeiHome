package os.kei.hyper.home.iconpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.RecommendedIconMappings

class PhoneCompanionCompletionDeviceTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val targets = RecommendedPackageIcons.PHONE_TARGETS.map { IconPackComponent(it) }
  private val on = RecommendedIconMappings.NONE.copy(phoneCompanions = true)
  private val off = RecommendedIconMappings.NONE
  private val dayColor = Color.rgb(211, 89, 19)
  private val nightColor = Color.rgb(19, 89, 211)
  private val qualifier = IconResourceQualifiers("drawable-xxhdpi", densityDpi = 480)
  private fun png(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return try { ByteArrayOutputStream().use {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
    } } finally { bitmap.recycle() }
  }
  private fun write(file: File, entries: Map<String, ByteArray>) {
    ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
      zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    } }
  }
  private fun entry(component: IconPackComponent, day: String, night: String? = null) =
    IconPackEntry(component.flattened, component = component,
      light = IconPackArtwork.Archive(listOf(IconArchiveVariant(day, qualifier))),
      dark = night?.let { IconPackArtwork.Archive(listOf(IconArchiveVariant(it, qualifier))) })

  private fun fixture(folder: File): IconPackDescriptor {
    val file = File(folder, "static.zip")
    write(file, mapOf("phone-day.png" to png(dayColor), "phone-night.png" to png(nightColor),
      "own.png" to png(Color.GREEN)))
    return IconPackDescriptor("fixture:phone", "Phone fixture", IconPackFormat.CLASSIC_ZIP,
      IconPackSource.Generated(file, "0".repeat(64), 0), listOf(entry(IconPackComponent(RecommendedPackageIcons.PHONE),
        "phone-day.png", "phone-night.png")))
  }
  private fun assertColor(zip: ZipFile, path: String, color: Int) {
    val bitmap = zip.getInputStream(requireNotNull(zip.getEntry(path)) { path })
      .use(BitmapFactory::decodeStream)
    try { assertEquals(color, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) }
    finally { bitmap.recycle() }
  }
  private fun assertPreviewColor(pack: IconPackDescriptor, night: Boolean,
    target: IconPackComponent, color: Int) {
    IconPackRenderer(context).open(pack, night, preservedPackages = emptySet(),
      recommendedMappings = on).use { session ->
      val bitmap = requireNotNull(session.renderTarget(null, target, 32) {})
      try { assertEquals(color, bitmap.getPixel(16, 16)); assertEquals(0, Color.alpha(bitmap.getPixel(0, 0))) }
      finally { bitmap.recycle() }
    }
  }

  @Test fun staticPreviewAndInstalledOnlyExportDiscoverBothServicePackages() {
    val folder = File(context.cacheDir, "phone-static-${System.nanoTime()}").apply { mkdirs() }
    try {
      val pack = fixture(folder)
      for (night in listOf(false, true)) {
        targets.forEach { target ->
          context.packageManager.getApplicationInfo(target.packageName, 0)
          assertPreviewColor(pack, night, target, if (night) nightColor else dayColor)
        }
        val result = IconPackExporter(context, IconPackLimits()).export(pack, emptyList(), night, 32,
          installedOnly = true, preservedPackages = emptySet(), recommendedMappings = on) {}
        try { ZipFile(result.archive).use { zip ->
          targets.forEach { assertColor(zip, "res/drawable-xxhdpi/${it.packageName}.png",
            if (night) nightColor else dayColor) }
        } } finally { result.archive.delete() }
      }
      assertThrows(IconPackException::class.java) {
        IconPackExporter(context, IconPackLimits()).export(pack, emptyList(), false, 32,
          installedOnly = true, preservedPackages = emptySet(), recommendedMappings = off) {}
      }
      val preserved = IconPackExporter(context, IconPackLimits()).export(pack, emptyList(), false, 32,
        installedOnly = true, preservedPackages = setOf(targets.first().packageName),
        recommendedMappings = on) {}
      try { ZipFile(preserved.archive).use { zip ->
        assertNull(zip.getEntry("res/drawable-xxhdpi/${targets.first().packageName}.png"))
        assertColor(zip, "res/drawable-xxhdpi/${targets.last().packageName}.png", dayColor)
      } } finally { preserved.archive.delete() }
    } finally { folder.deleteRecursively() }
  }

  @Test fun lightOnlyAndActivitySpecificAuthorshipBlockEachTargetInBothAppearances() {
    val folder = File(context.cacheDir, "phone-owned-${System.nanoTime()}").apply { mkdirs() }
    try {
      val base = fixture(folder)
      for (target in targets) for (activity in listOf(false, true)) {
        val owner = if (activity) target.copy(className = "${target.packageName}.OwnIcon") else target
        val own = entry(owner, "own.png")
        val pack = base.copy(entries = base.entries + own)
        for (night in listOf(false, true)) {
          IconPackRenderer(context).open(pack, night, preservedPackages = emptySet(),
            recommendedMappings = on).use { session ->
            assertNull(session.completedEntry(target, null))
            assertEquals(own, session.completedEntry(owner, own))
          }
          assertPreviewColor(pack, night, targets.first { it != target }, if (night) nightColor else dayColor)
        }
        for (enabled in listOf(false, true)) {
          val result = IconPackExporter(context, IconPackLimits()).export(pack, emptyList(), false, 32,
            installedOnly = true, preservedPackages = emptySet(), recommendedMappings = if (enabled) on else off) {}
          try { ZipFile(result.archive).use { zip ->
            assertColor(zip, "res/drawable-xxhdpi/${nativeLauncherActivityName(owner) ?: owner.packageName}.png",
              Color.GREEN)
            val missing = targets.first { it != target }
            val path = "res/drawable-xxhdpi/${missing.packageName}.png"
            if (enabled) assertColor(zip, path, dayColor) else assertNull(zip.getEntry(path))
          } } finally { result.archive.delete() }
        }
      }
    } finally { folder.deleteRecursively() }
  }

  @Test fun supplementalDonorDoesNotOverridePrimaryLightOnlyAuthorship() {
    val folder = File(context.cacheDir, "phone-supplement-${System.nanoTime()}").apply { mkdirs() }
    try {
      val supplement = fixture(folder)
      val target = targets.first()
      val own = entry(target, "own.png")
      val primary = supplement.copy(id = "fixture:primary", entries = listOf(own))
      IconPackRenderer(context).open(primary, true, colorOsFallback = supplement,
        preservedPackages = emptySet(), recommendedMappings = on).use { session ->
        val bitmap = session.renderTarget(own, target, 32) {}
        try { if (bitmap != null) assertNotEquals(nightColor, bitmap.getPixel(16, 16)) }
        finally { bitmap?.recycle() }
        val missing = requireNotNull(session.renderTarget(null, targets.last(), 32) {})
        try { assertEquals(nightColor, missing.getPixel(16, 16)) } finally { missing.recycle() }
      }
      val exported = IconPackExporter(context, IconPackLimits()).export(primary, emptyList(), true, 32,
        colorOsFallback = supplement, installedOnly = true, preservedPackages = emptySet(),
        recommendedMappings = on) {}
      try { ZipFile(exported.archive).use { zip ->
        assertNull(zip.getEntry("res/drawable-xxhdpi/${target.packageName}.png"))
        assertColor(zip, "res/drawable-xxhdpi/${targets.last().packageName}.png", nightColor)
      } } finally { exported.archive.delete() }
    } finally { folder.deleteRecursively() }
  }

  @Test fun nativeImportKeepsAuthoredBytesAndAddsOnlyMissingCompanion() {
    val folder = File(context.cacheDir, "phone-native-${System.nanoTime()}").apply { mkdirs() }
    try {
      val donor = RecommendedPackageIcons.PHONE
      val own = targets.first().packageName
      val day = png(dayColor); val night = png(nightColor)
      val original = mapOf("res/drawable-xxhdpi/$donor.png" to day,
        "nightmode/res/drawable-xxhdpi/$donor.png" to night,
        "res/drawable-xxhdpi/$own.png" to png(Color.GREEN)) +
        NativeDayNightIcon.entries(donor, day, night)
      val file = File(folder, "native.zip")
      write(file, original)
      val record = file.inputStream().use { IconPackStore(File(folder, "store")).import(it, file.name).record }
      val index = IconPackArchive().inspect(record.originalFile, File(folder, "inner.zip"))
      val pack = IconPackDescriptor(record.id, "Native phone", index.format,
        IconPackSource.Imported(record), index.entries)
      for (enabled in listOf(true, false)) {
        val result = IconPackExporter(context, IconPackLimits()).export(pack, emptyList(), false, 32,
          installedOnly = true, preservedPackages = emptySet(), recommendedMappings = if (enabled) on else off) {}
        try { ZipFile(result.archive).use { zip ->
          for ((name, bytes) in original)
            assertArrayEquals(name, bytes, zip.getInputStream(requireNotNull(zip.getEntry(name))).use { it.readBytes() })
          val path = "nightmode/res/drawable-xxhdpi/${targets.last().packageName}.png"
          if (enabled) {
            assertColor(zip, path, nightColor)
            assertNotNull(zip.getEntry("fancy_icons/${targets.last().packageName}/manifest.xml"))
          } else assertNull(zip.getEntry(path))
          assertNull(zip.getEntry("nightmode/res/drawable-xxhdpi/$own.png"))
        } } finally { result.archive.delete() }
      }
    } finally { folder.deleteRecursively() }
  }

  @Test fun bundledColorOsPhoneArtworkCompletesOnlyUncoveredServices() = kotlinx.coroutines.runBlocking {
    val pack = requireNotNull(IconPackRepository(context).runtimeFallback())
    val policy = RecommendedPackageIcons(pack.entries, on)
    val missing = targets.filterNot { it.packageName in policy.authoredTargets }
    assertTrue(missing.isNotEmpty())
    assertEquals(missing, policy.completionTargets())
    for (night in listOf(false, true)) {
      IconPackRenderer(context).open(pack, night, preservedPackages = emptySet(),
        recommendedMappings = on).use { session ->
        val phone = requireNotNull(session.renderTarget(
          pack.entries.first { it.component?.packageName == RecommendedPackageIcons.PHONE },
          IconPackComponent(RecommendedPackageIcons.PHONE), 96) {})
        try { missing.forEach { target ->
          val service = requireNotNull(session.renderTarget(null, target, 96) {})
          try { assertTrue(phone.sameAs(service)) } finally { service.recycle() }
        } } finally { phone.recycle() }
        for (target in targets - missing.toSet()) {
          val authored = pack.entries.first { it.component?.packageName == target.packageName }
          assertEquals(authored, session.completedEntry(target, authored))
        }
      }
      val result = IconPackExporter(context, IconPackLimits()).export(pack, emptyList(), night, 96,
        installedOnly = true, preservedPackages = emptySet(), recommendedMappings = on) {}
      try { ZipFile(result.archive).use { zip ->
        targets.forEach { target ->
          assertNotNull(zip.getEntry("res/drawable-xxhdpi/${target.packageName}.png"))
        }
      } } finally { result.archive.delete() }
    }
  }
}
