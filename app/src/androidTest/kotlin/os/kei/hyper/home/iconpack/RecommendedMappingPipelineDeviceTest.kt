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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.RecommendedIconMappings
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.status.IconPackHandoffs

class RecommendedMappingPipelineDeviceTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val targets = TelegramChannelIcons.offlineTargets()
  private val disabled = RecommendedIconMappings(telegramChannels = false)

  @Test fun legacyNameChoiceAndNewIndependentChoicesSurviveReopeningPreferences() {
    val id = "recommended_preferences_${java.util.UUID.randomUUID()}"
    val storage = context.getSharedPreferences(id, Context.MODE_PRIVATE)
    try {
      storage.edit().putBoolean("system_app_name_matching", false).commit()
      val preferences = AppPreferences(storage, listOf(SystemIconFallback.DEFAULT))
      assertFalse(preferences.state.value.systemAppNameMatching)
      assertEquals(RecommendedIconMappings(), preferences.state.value.recommendedIconMappings)
      preferences.setTelegramChannelMapping(false)
      assertTrue(preferences.state.value.colorOsThemeStoreMapping)
      preferences.setColorOsThemeStoreMapping(false)
      val reopened = AppPreferences(storage, listOf(SystemIconFallback.DEFAULT)).state.value
      assertFalse(reopened.systemAppNameMatching)
      assertEquals(RecommendedIconMappings.NONE, reopened.recommendedIconMappings)
    } finally { context.deleteSharedPreferences(id) }
  }

  private fun png(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return try { ByteArrayOutputStream().use {
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
    } } finally { bitmap.recycle() }
  }

  private fun input(folder: File, native: Boolean): IconPackDescriptor {
    val file = File(folder, "source.zip")
    val donor = targets.first().packageName
    val entries = if (native) mapOf("res/drawable-xxhdpi/$donor.png" to png(Color.RED),
      "nightmode/res/drawable-xxhdpi/$donor.png" to png(Color.BLUE)) +
      NativeDayNightIcon.entries(donor, png(Color.RED), png(Color.BLUE))
    else mapOf("day.png" to png(Color.RED), "night.png" to png(Color.BLUE))
    ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, bytes) ->
      zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
    } }
    if (native) {
      val record = file.inputStream().use { IconPackStore(File(folder, "store")).import(it, file.name).record }
      val index = IconPackArchive().inspect(record.originalFile, File(folder, "inner.zip"))
      return IconPackDescriptor(record.id, "Mapping QA", index.format, IconPackSource.Imported(record), index.entries)
    }
    val qualifiers = IconResourceQualifiers("drawable-nodpi", densityDpi = 0)
    fun artwork(path: String) = IconPackArtwork.Archive(listOf(IconArchiveVariant(path, qualifiers)))
    return IconPackDescriptor("recommended-mapping-qa", "Mapping QA", IconPackFormat.APK,
      IconPackSource.Generated(file, "a".repeat(64), 1), listOf(IconPackEntry("donor", targets.first(),
        light = artwork("day.png"), dark = artwork("night.png"))))
  }

  @Test fun telegramOptOutAffectsBothStaticAndNativeArchivesWithoutChangingAuthoredResources() {
    for (native in listOf(false, true)) {
      val folder = File.createTempFile("recommended-mapping-", "", context.cacheDir).apply { delete(); mkdirs() }
      try {
        val pack = input(folder, native)
        for (dark in listOf(false, true)) for (enabled in listOf(false, true)) {
          val result = IconPackExporter(context, IconPackLimits()).export(pack, targets, dark, 32,
            installedOnly = true, maskPolicy = IconMaskPolicy(false), preservedPackages = emptySet(),
            recommendedMappings = RecommendedIconMappings(telegramChannels = enabled)) {}
          try { ZipFile(result.archive).use { zip ->
            val prefix = if (native && dark) "nightmode/" else ""
            val own = requireNotNull(zip.getEntry("${prefix}res/drawable-xxhdpi/${targets.first().packageName}.png"))
            val bitmap = zip.getInputStream(own).use { BitmapFactory.decodeStream(it) }!!
            try { assertEquals(if (dark) Color.BLUE else Color.RED, bitmap.getPixel(16, 16)) }
            finally { bitmap.recycle() }
            for (target in targets.drop(1)) {
              val path = "${prefix}res/drawable-xxhdpi/${nativeLauncherActivityName(target) ?: target.packageName}.png"
              assertEquals("native=$native dark=$dark enabled=$enabled $path", enabled, zip.getEntry(path) != null)
            }
          } } finally { result.archive.delete() }
        }
      } finally { folder.deleteRecursively() }
    }
  }

  @Test fun previewCacheSeparatesMappingChoicesAndPublishedArtworkIgnoresPendingChoices() = runBlocking<Unit> {
    val folder = File.createTempFile("recommended-preview-", "", context.cacheDir).apply { delete(); mkdirs() }
    try {
      val pack = input(folder, false)
      val target = IconPackEntry("missing-web", targets[1])
      val previews = IconPackPreviewRepository(context)
      val on = previews.load(pack, target, 32, maskPolicy = IconMaskPolicy(false), preservedPackages = emptySet(),
        recommendedMappings = RecommendedIconMappings())
      assertEquals(Color.BLUE, requireNotNull(on.dark).getPixel(16, 16))
      val off = previews.load(pack, target, 32, maskPolicy = IconMaskPolicy(false), preservedPackages = emptySet(),
        recommendedMappings = disabled)
      assertNotSame(on, off)
      assertTrue(off.dark == null || off.dark.getPixel(16, 16) != Color.BLUE)
      assertSame(on, previews.load(pack, target, 32, maskPolicy = IconMaskPolicy(false), preservedPackages = emptySet(),
        recommendedMappings = RecommendedIconMappings()))
      val applied = previews.loadApplied(pack, target, 32)
      assertTrue(applied.dark == null || applied.dark.getPixel(16, 16) != Color.BLUE)
      previews.clear()
    } finally { folder.deleteRecursively() }
  }

  @Test fun colorOsOptOutRemovesSyntheticThemeArtworkEvenForStalePreviewEntries() = runBlocking<Unit> {
    val pack = BuiltinColorOsMappings.withAliases(
      IconPackRepository(context).listBuiltins().packs.first { it.recommendedAliases.isNotEmpty() })
    val entry = pack.entries.first { it.component?.packageName == "com.android.thememanager" }
    val options = RecommendedIconMappings(colorOsThemeStores = false)
    assertNull(SystemAppNameMatcher(context, pack.entries, true,
      BuiltinColorOsMappings.excludedNameMatches(pack, false)).match(requireNotNull(entry.component)))
    val renderer = IconPackRenderer(context)
    val on = renderer.open(pack, true, maskPolicy = IconMaskPolicy(false), preservedPackages = emptySet(),
      recommendedMappings = RecommendedIconMappings()).use { it.render(entry, 96) }
    val off = renderer.open(pack, true, maskPolicy = IconMaskPolicy(false), preservedPackages = emptySet(),
      recommendedMappings = options).use { it.render(entry, 96) }
    try { assertNotNull(on); assertTrue(off == null || !on!!.sameAs(off)) }
    finally { on?.recycle(); off?.recycle() }
  }

  @Test fun handoffRecordsTheSelectedRulesAndKeepsBothModesConsistent() = runBlocking<Unit> {
    val folder = File.createTempFile("recommended-handoff-", "", context.cacheDir).apply { delete(); mkdirs() }
    try {
      val pack = input(folder, false)
      val handoff = IconPackHandoffs.prepare(context, IconPackRepository(context), IconPackPreviewRepository(context),
        pack, targets, IconPackAppearance.SYSTEM, false, installedOnly = true, completeSystemIconConfig = false,
        recommendedMappings = disabled)
      try { ZipFile(handoff.file).use { mtz ->
        val module = File(folder, "icons.zip")
        mtz.getInputStream(mtz.getEntry("icons")).use { input -> module.outputStream().use { input.copyTo(it) } }
        ZipFile(module).use { zip ->
          val manifest = IconGenerationManifest.decode(zip.getInputStream(zip.getEntry(IconGenerationManifest.PATH))
            .use { it.readBytes() })
          assertEquals(disabled, manifest.recommendedMappings)
          assertEquals(8, manifest.schemaVersion)
          for (target in targets.drop(1)) {
            assertNull(zip.getEntry("res/drawable-xxhdpi/${nativeLauncherActivityName(target) ?: target.packageName}.png"))
            assertNull(zip.getEntry("nightmode/res/drawable-xxhdpi/${nativeLauncherActivityName(target) ?: target.packageName}.png"))
          }
        }
      } } finally { handoff.file.delete() }
    } finally { folder.deleteRecursively() }
  }
}
