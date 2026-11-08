package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.StoredIconPack
import os.kei.hyper.home.status.SystemIconThemeReport

@RunWith(AndroidJUnit4::class)
class AppleCurrentThemePreviewDeviceTest {
  private val entry = IconPackEntry("package", nativeName = "probe.app")
  private val pack = IconPackDescriptor("applied", "Applied", IconPackFormat.MIUI_ICONS,
    IconPackSource.Imported(StoredIconPack("applied", "Applied", 0, IconPackFormat.MIUI_ICONS,
      File("unused.zip"), File("unused.zip"), 1, null)), listOf(entry))
  private val source = IconBrowserSource("snapshot", pack, mapOf("probe.app" to entry), emptySet(), emptySet())
  private val theme = SystemIconThemeReport(SystemIconThemeReport.Source.THEME, "Applied", null,
    1, 1, true, "revision", previewKey = "snapshot")
  private val colors = IconPreviewColors(lightForeground = Color.RED, lightBackground = Color.GREEN,
    darkForeground = Color.BLUE, darkBackground = Color.BLACK)

  private fun bitmap(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
  private fun provider(applied: IconBrowserSource = source, report: SystemIconThemeReport = theme,
    monochrome: Boolean = false, find: suspend (String) -> String? = { null },
    load: suspend (IconBrowserSource, IconPackEntry) -> AppIconPreviews) =
    AppleCurrentThemePreviewProvider(report, applied, colors, monochrome, find, load)

  @Test fun distinctArchiveBytesWithIdenticalPixelsProduceOneIcon() = runBlocking {
    val day = bitmap(Color.RED)
    val preview = provider { _, _ -> AppIconPreviews(day, bitmap(Color.RED)) }.load("probe.app")
    assertSame(day, preview?.light)
    assertNull(preview?.dark)
  }

  @Test fun independentArtworkKeepsBothActualThemeBitmaps() = runBlocking {
    val day = bitmap(Color.RED)
    val night = bitmap(Color.BLUE)
    val preview = provider { _, _ -> AppIconPreviews(day, night) }.load("probe.app")
    assertSame(day, preview?.light)
    assertSame(night, preview?.dark)
  }

  @Test fun completePairsAndUnavailableEntriesAreCachedWithoutMoreReads() = runBlocking {
    val calls = AtomicInteger()
    val components = AtomicInteger()
    val repository = provider(find = { components.incrementAndGet(); null }) { _, _ ->
      calls.incrementAndGet()
      delay(20)
      AppIconPreviews(bitmap(Color.RED))
    }
    val results = coroutineScope { List(8) { async { repository.load("probe.app") } }.awaitAll() }
    assertEquals(1, calls.get())
    assertEquals(1, components.get())
    results.forEach { assertSame(results.first(), it) }
    repeat(3) { assertNull(repository.load("uncovered.app")) }
    assertEquals(1, calls.get())
    assertEquals(2, components.get())
  }

  @Test fun missingDynamicAndStaleSourcesNeverRequestAnApkSubstitute() = runBlocking {
    val calls = AtomicInteger()
    val load: suspend (IconBrowserSource, IconPackEntry) -> AppIconPreviews =
      { _, _ -> calls.incrementAndGet(); AppIconPreviews(bitmap(Color.RED)) }
    assertNull(provider(load = load).load("uncovered.app"))
    assertNull(provider(applied = source.copy(dynamicNames = setOf("probe.app")), load = load).load("probe.app"))
    assertNull(provider(report = theme.copy(previewKey = "other"), load = load).load("probe.app"))
    assertEquals(0, calls.get())
  }

  @Test fun resolvedActivityUsesItsExactAppliedEntry() = runBlocking {
    val component = entry.copy(key = "component", nativeName = "probe.app.Main")
    var seen: IconPackEntry? = null
    val repository = provider(applied = source.copy(entries = source.entries + ("probe.app.Main" to component)),
      find = { "probe.app.Main" }) { _, selected ->
      seen = selected
      AppIconPreviews(bitmap(Color.RED))
    }
    assertNotNull(repository.load("probe.app"))
    assertSame(component, seen)
  }

  @Test fun appliedMonetUsesItsMaskAndCurrentPaletteWhileDisabledKeepsOriginal() = runBlocking {
    val day = bitmap(Color.WHITE)
    val applied = source.copy(paletteNames = setOf("probe.app"))
    val load: suspend (IconBrowserSource, IconPackEntry) -> AppIconPreviews =
      { _, _ -> AppIconPreviews(day, day, day, day, backgroundMask = day) }
    val colored = provider(applied = applied, load = load).load("probe.app")
    assertSame(day, colored?.light)
    assertNull(colored?.dark)
    val tinted = provider(applied = applied, monochrome = true, load = load).load("probe.app")
    assertEquals(Color.RED, tinted?.light?.getPixel(96, 96))
    assertEquals(Color.BLUE, tinted?.dark?.getPixel(96, 96))
  }

  @Test fun cancellationDoesNotPublishAnUnavailableOrIncompleteCacheEntry() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val calls = AtomicInteger()
    val repository = provider { _, _ ->
      if (calls.incrementAndGet() == 1) {
        started.complete(Unit)
        delay(Long.MAX_VALUE)
      }
      AppIconPreviews(bitmap(Color.RED))
    }
    coroutineScope {
      val cancelled = async { repository.load("probe.app") }
      started.await()
      cancelled.cancelAndJoin()
      assertNotNull(repository.load("probe.app"))
      assertEquals(2, calls.get())
    }
  }

  @Test fun atMostTwoArchiveLoadsRunAcrossSuspension() = runBlocking {
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val entries = (0..7).associate { "probe.$it" to entry.copy(key = "$it", nativeName = "probe.$it") }
    val repository = provider(applied = source.copy(entries = entries)) { _, _ ->
      val count = active.incrementAndGet()
      peak.updateAndGet { maxOf(it, count) }
      try { delay(20); AppIconPreviews(bitmap(Color.RED)) } finally { active.decrementAndGet() }
    }
    coroutineScope { entries.keys.map { async { repository.load(it) } }.awaitAll() }
    assertEquals(2, peak.get())
    assertEquals(0, active.get())
  }

  @Test fun olderBitmapsAreEvictedUnderTheByteBudget() = runBlocking {
    val calls = AtomicInteger()
    val entries = (0..2).associate { "probe.$it" to entry.copy(key = "$it", nativeName = "probe.$it") }
    val repository = provider(applied = source.copy(entries = entries)) { _, _ ->
      calls.incrementAndGet()
      AppIconPreviews(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
    }
    entries.keys.forEach { assertNotNull(repository.load(it)) }
    assertEquals(3, calls.get())
    assertNotNull(repository.load("probe.1"))
    assertEquals(3, calls.get())
    assertNotNull(repository.load("probe.0"))
    assertEquals(4, calls.get())
  }
}
