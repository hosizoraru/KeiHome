package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class TelegramChannelCompletionDeviceTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val targets = TelegramChannelIcons.offlineTargets().map {
    IconPackComponent(it.packageName, "org.telegram.messenger.DefaultIcon")
  }
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
  private fun assertColor(zip: ZipFile, name: String, color: Int) {
    val entry = requireNotNull(zip.getEntry(name)) { name }
    val bitmap = zip.getInputStream(entry).use(BitmapFactory::decodeStream)
    try { assertEquals(color, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
      assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
    } finally { bitmap.recycle() }
  }

  @Test fun apkStyleExportMapsBothAppearancesAndPreservationWinsOverExplicitCoverage() {
    val folder = File(context.cacheDir, "telegram-channel-fixture").apply { mkdirs() }
    try {
      val input = File(folder, "input.zip")
      write(input, mapOf("day.png" to png(Color.RED), "night.png" to png(Color.BLUE), "beta.png" to png(Color.GREEN)))
      val qualifiers = IconResourceQualifiers("drawable-nodpi", densityDpi = 0)
      val light = IconPackArtwork.Archive(listOf(IconArchiveVariant("day.png", qualifiers)))
      val dark = IconPackArtwork.Archive(listOf(IconArchiveVariant("night.png", qualifiers)))
      val pack = IconPackDescriptor("telegram-fixture", "Fixture", IconPackFormat.APK,
        IconPackSource.Generated(input, "a".repeat(64), 1),
        listOf(IconPackEntry("telegram", IconPackComponent("org.telegram.messenger"), light = light, dark = dark)))
      for (night in listOf(false, true)) {
        val result = IconPackExporter(context, IconPackLimits()).export(pack, targets, night, 32,
          installedOnly = true, maskPolicy = IconMaskPolicy(true),
          preservedPackages = setOf("org.telegram.messenger.web")) {}
        try { ZipFile(result.archive).use { zip ->
          for (target in targets) {
            val name = nativeLauncherActivityName(target)!!
            val path = "res/drawable-xxhdpi/$name.png"
            if (target.packageName == "org.telegram.messenger.web") assertNull(zip.getEntry(path))
            else assertColor(zip, path, if (night) Color.BLUE else Color.RED)
          }
        } } finally { result.archive.delete() }
      }
      val own = IconPackEntry("beta-day", component = targets[2],
        light = IconPackArtwork.Archive(listOf(IconArchiveVariant("beta.png", qualifiers))))
      val partial = pack.copy(entries = listOf(pack.entries.single(), own))
      for (night in listOf(false, true)) {
        val result = IconPackExporter(context, IconPackLimits()).export(partial, listOf(targets[2]), night, 32,
          installedOnly = true, preservedPackages = emptySet()) {}
        try { ZipFile(result.archive).use { zip ->
          assertColor(zip, "res/drawable-xxhdpi/${nativeLauncherActivityName(targets[2])}.png",
            if (night) Color.BLUE else Color.GREEN)
        } } finally { result.archive.delete() }
      }
      val alternate = IconPackComponent("org.telegram.messenger.beta", "org.telegram.messenger.NoxIcon")
      val result = IconPackExporter(context, IconPackLimits()).export(pack, listOf(alternate), true, 32,
        installedOnly = false, preservedPackages = emptySet()) {}
      try { ZipFile(result.archive).use { zip ->
        assertNull(zip.getEntry("res/drawable-xxhdpi/org.telegram.messenger.beta.png"))
        assertNull(zip.getEntry("res/drawable-xxhdpi/${nativeLauncherActivityName(alternate)}.png"))
      } } finally { result.archive.delete() }
    } finally { folder.deleteRecursively() }
  }

  @Test fun bundledColorOsResourcesCompleteOtherChannelsWithoutApplyingATheme() = kotlinx.coroutines.runBlocking {
    val pack = requireNotNull(IconPackRepository(context).runtimeFallback())
    val result = IconPackExporter(context, IconPackLimits()).export(pack, targets, false, 96,
      installedOnly = true, preservedPackages = emptySet()) {}
    val darkResult = IconPackExporter(context, IconPackLimits()).export(pack, targets, true, 96,
      installedOnly = true, preservedPackages = emptySet()) {}
    try { ZipFile(result.archive).use { zip -> ZipFile(darkResult.archive).use { darkZip ->
      for (target in targets.drop(1)) {
        val name = nativeLauncherActivityName(target)!!
        val day = zip.getInputStream(requireNotNull(zip.getEntry("res/drawable-xxhdpi/$name.png")))
          .use { BitmapFactory.decodeStream(it) }!!
        val darkPath = (if (darkResult.preservesNativeResources) "nightmode/" else "") + "res/drawable-xxhdpi/$name.png"
        val night = darkZip.getInputStream(requireNotNull(darkZip.getEntry(darkPath)))
          .use { BitmapFactory.decodeStream(it) }!!
        try { assertFalse(day.sameAs(night)) } finally { day.recycle(); night.recycle() }
      }
    } } } finally { result.archive.delete(); darkResult.archive.delete() }
  }

  @Test fun nativeExportCompletesUncoveredChannelsAndPrunesOnlyThePreservedOwners() {
    val folder = File(context.cacheDir, "telegram-native-fixture").apply { mkdirs() }
    val input = File(folder, "native.zip")
    val day = png(Color.RED); val night = png(Color.BLUE)
    val donor = "org.telegram.messenger"
    val original = mapOf("res/drawable-xxhdpi/$donor.png" to day,
      "nightmode/res/drawable-xxhdpi/$donor.png" to night) +
      NativeDayNightIcon.entries(donor, day, night)
    write(input, original)
    val store = IconPackStore(File(folder, "store"))
    try {
      val record = input.inputStream().use { store.import(it, input.name).record }
      val index = IconPackArchive().inspect(record.originalFile, File(folder, "inner.zip"))
      val pack = IconPackDescriptor(record.id, "Native fixture", index.format,
        IconPackSource.Imported(record), index.entries)
      val result = IconPackExporter(context, IconPackLimits()).export(pack, targets, false, 32,
        installedOnly = true, maskPolicy = IconMaskPolicy(true),
        preservedPackages = setOf("org.telegram.messenger.web")) {}
      try { ZipFile(result.archive).use { zip ->
        for ((name, bytes) in original) assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
        val beta = targets.last()
        assertArrayEquals(night, zip.getInputStream(requireNotNull(
          zip.getEntry("nightmode/res/drawable-xxhdpi/${nativeLauncherActivityName(beta)}.png"))).use { it.readBytes() })
        for ((name, bytes) in original) {
          for (completed in listOf(beta.packageName, requireNotNull(nativeLauncherActivityName(beta)))) {
            val copied = name.replace(donor, completed)
            assertArrayEquals(copied, bytes, zip.getInputStream(requireNotNull(zip.getEntry(copied))).use { it.readBytes() })
          }
        }
        assertNotNull(zip.getEntry("fancy_icons/${beta.packageName}/manifest.xml"))
        assertFalse(zip.entries().asSequence().any { "org.telegram.messenger.web" in it.name })
      } } finally { result.archive.delete() }
      val pruned = IconPackExporter(context, IconPackLimits()).export(pack, targets, false, 32,
        installedOnly = true, preservedPackages = setOf(donor)) {}
      try { ZipFile(pruned.archive).use { zip ->
        val exclusions = PreservedIconResources(setOf(donor), targets)
        assertFalse(zip.entries().asSequence().any { exclusions.containsPath(it.name) })
      } } finally { pruned.archive.delete() }
    } finally { folder.deleteRecursively() }
  }

  @Test fun explicitNativeDayOnlyRemainsExcludedAndAuthoredNightStaysExact() {
    val beta = targets.last()
    val alias = requireNotNull(nativeLauncherActivityName(beta))
    for (authoredNight in listOf(false, true)) {
      val folder = File(context.cacheDir, "telegram-explicit-${System.nanoTime()}").apply { mkdirs() }
      try {
        val ownDay = "res/drawable-xxhdpi/$alias.png"
        val ownNight = "nightmode/res/drawable-xxhdpi/$alias.png"
        val original = LinkedHashMap(NativeDayNightIcon.entries("org.telegram.messenger",
          png(Color.RED), png(Color.BLUE))).apply {
          put(ownDay, png(Color.GREEN))
          if (authoredNight) put(ownNight, png(Color.YELLOW))
        }
        val input = File(folder, "native-explicit.zip")
        write(input, original)
        val record = input.inputStream().use { IconPackStore(File(folder, "store")).import(it, input.name).record }
        val index = IconPackArchive().inspect(record.originalFile, File(folder, "nested.zip"))
        val pack = IconPackDescriptor(record.id, "Explicit native target", index.format,
          IconPackSource.Imported(record), index.entries)
        val result = IconPackExporter(context, IconPackLimits()).export(pack, listOf(beta), false, 32,
          installedOnly = true, maskPolicy = IconMaskPolicy(true)) {}
        try { ZipFile(result.archive).use { zip ->
          for ((name, bytes) in original) assertArrayEquals(name, bytes,
            zip.getInputStream(requireNotNull(zip.getEntry(name))).use { it.readBytes() })
          if (!authoredNight) assertNull(zip.getEntry(ownNight))
          assertNull(zip.getEntry("fancy_icons/$alias/manifest.xml"))
        } } finally { result.archive.delete() }
      } finally { folder.deleteRecursively() }
    }
  }
}
