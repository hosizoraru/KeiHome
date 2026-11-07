package os.kei.hyper.home.iconpack.apple

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.NativeAppleDynamicIcon
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.xposed.GeneratedNativeIconValidator
import os.kei.hyper.home.xposed.ConvertedIconArchive

class AppleDynamicArtworkDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val artwork = AppleDynamicArtwork(context)
  private val options = AppleIconOptions(country = "us")
  private val utc = TimeZone.getTimeZone("UTC")
  private fun time(value: String) = Instant.parse(value).toEpochMilli()

  private fun different(a: ByteArray, b: ByteArray): Boolean {
    val x = checkNotNull(BitmapFactory.decodeByteArray(a, 0, a.size))
    val y = checkNotNull(BitmapFactory.decodeByteArray(b, 0, b.size))
    return try { !x.sameAs(y) } finally { x.recycle(); y.recycle() }
  }

  @Test fun genuineLayersAreBoundedAndHandsPointUpBeforeRuntimeRotation() {
    for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
      val assets = artwork.assets(NativeAppleDynamicIcon.Kind.CLOCK,
        options.copy(platform = platform, resolution = 1024))
      assertEquals(8, assets.size)
      for ((name, bytes) in assets) {
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
          assertEquals(512, bitmap.width)
          assertEquals(512, bitmap.height)
          if (name !in listOf("day.png", "night.png")) {
            var weight = 0L; var x = 0.0; var y = 0.0
            for (row in 0 until 512) for (col in 0 until 512) {
              val alpha = bitmap.getPixel(col, row) ushr 24
              weight += alpha
              x += (col + .5) * alpha
              y += (row + .5) * alpha
            }
            assertTrue(name, weight > 0)
            assertEquals(name, 256.0, x / weight, 1.0)
            assertTrue(name, y / weight < 246.0)
          }
        } finally { bitmap.recycle() }
      }
      assertTrue(different(assets.getValue("hour.png"), assets.getValue("night_hour.png")))
    }
  }

  @Test fun calendarSnapshotsFollowDayLocaleAndTimeZoneWithoutEmbeddedDateText() {
    val kind = NativeAppleDynamicIcon.Kind.CALENDAR
    val now = time("2026-11-08T00:30:00Z")
    val en = artwork.snapshot(kind, options, now, Locale.US, utc)
    for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE)) {
      assertTrue(locale.toLanguageTag(), different(en.first, artwork.snapshot(kind, options, now, locale, utc).first))
    }
    assertTrue(different(en.first, artwork.snapshot(kind, options, now, Locale.US,
      TimeZone.getTimeZone("America/Los_Angeles")).first))
    assertTrue(different(en.first, artwork.snapshot(kind, options, time("2026-11-30T00:30:00Z"), Locale.US, utc).first))
    assertTrue(different(en.first, en.second))
  }

  @Test fun clockSnapshotsAdvanceAllHandsAndPreserveGenuineDarkAppearance() {
    val kind = NativeAppleDynamicIcon.Kind.CLOCK
    val start = time("2026-11-08T03:00:00Z")
    val a = artwork.snapshot(kind, options, start, Locale.US, utc)
    for (delta in listOf(1_000L, 60_000L, 3_600_000L)) {
      assertTrue("$delta", different(a.first, artwork.snapshot(kind, options, start + delta, Locale.US, utc).first))
    }
    assertTrue(different(a.first, a.second))
    assertFalse(different(a.first,
      artwork.snapshot(kind, options, start, Locale.JAPANESE, utc).first))
  }

  @Test fun mobileDynamicMappingsNeverMasqueradeAsMacArtwork() {
    for (id in listOf("1108185179", "1584215688")) {
      assertNotNull(AppleDynamicArtwork.kind(AppleAppMatch(id, "test", "Apple"), options))
      assertNull(AppleDynamicArtwork.kind(AppleAppMatch(id, "test", "Apple"), options.copy(platform = ApplePlatform.MACOS)))
    }
    assertNull(AppleDynamicArtwork.kind(AppleAppMatch("123456", "Calendar", "Test"), options))
  }

  @Test fun offlineGenerationAndCanonicalNativeHandoffRetainOnlyTwoLiveIcons() = runBlocking {
    val name = "apple_dynamic_test_${UUID.randomUUID()}"
    val root = File(context.cacheDir, name)
    var calls = 0
    val repo = AppleIconRepository(context,
      AppleIconClient(AppleIconTransport { calls++; error("Unexpected network: $it") }, 0), root, name)
    val targets = listOf(AppleAppTarget("com.android.calendar", "日历", true),
      AppleAppTarget("com.android.deskclock", "时钟", true))
    try {
      val generated = repo.generate(targets, options.copy(resolution = 1024)) {}
      assertEquals(2, generated.mapped)
      assertEquals(0, calls)
      assertFalse(repo.needsGeneration(targets, options.copy(resolution = 1024)))
      val again = repo.generate(targets, options.copy(resolution = 1024)) {}
      assertEquals(generated.pack.id, again.pack.id)
      assertEquals(0, calls)
      val components = targets.map { IconPackComponent(it.packageName) }
      for (appearance in listOf(IconPackAppearance.SYSTEM, IconPackAppearance.LIGHT, IconPackAppearance.DARK)) {
        val handoff = IconPackHandoffs.prepare(context, IconPackRepository(context),
          IconPackPreviewRepository(context), generated.pack, components, appearance, includePalette = false)
        try {
          ConvertedIconArchive.inspect(handoff.file, "CONVERTED_CLASSIC_ZIP")
          ZipFile(handoff.file).use { mtz ->
            val icons = File(root, "icons.zip")
            mtz.getInputStream(mtz.getEntry("icons")).use { input ->
              icons.outputStream().use { input.copyTo(it) }
            }
            ZipFile(icons).use { zip ->
              val paths = zip.entries().asSequence().map { it.name }.toSet()
              assertEquals(2, paths.count { it.startsWith("fancy_icons/") && it.endsWith("/manifest.xml") })
              for (pkg in targets.map { it.packageName }) {
                val xml = zip.getInputStream(zip.getEntry("fancy_icons/$pkg/manifest.xml")).use { it.readBytes() }
                assertNotNull(NativeAppleDynamicIcon.kindOfManifest(xml))
                assertFalse(xml.toString(Charsets.UTF_8).contains("ContentProviderBinder"))
                GeneratedNativeIconValidator.validate(pkg, paths) { path, limit ->
                  val entry = checkNotNull(zip.getEntry(path))
                  assertTrue(entry.size in 1..limit)
                  zip.getInputStream(entry).use { it.readBytes() }
                }
              }
            }
          }
        } finally { handoff.file.delete() }
      }
      assertEquals(0, calls)
    } finally { root.deleteRecursively(); AppLocalStorage.open(context, name).clearAll() }
  }
}
