package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real worker/ZIP/Bitmap work; no privileged hooks, remote services or user theme changes. */
class CrossSurfaceIconCacheWorkloadTest {
  @Test fun firstSuccessfulInsertionIsUsableAndNeverBecomesANegativeResult() = fixture { file ->
    val cache = CrossSurfaceIconCache(RecordingContext(), themeFile = file,
      paletteState = { 0L }, eagerReload = false)
    try {
      cache.start()
      assertNull(cache.get(name(0)))
      awaitSettled(cache)
      val first = requireNotNull(cache.get(name(0)))
      assertFalse("LruCache.put returns the old value, not insertion success", cache.isMissing(name(0)))
      assertEquals(0, size(cache, "misses"))
      repeat(40) {
        assertSame(first, cache.get(name(0)))
        assertFalse(cache.isMissing(name(0)))
      }
      cache.invalidate()
      assertNull(cache.get(name(0)))
      awaitSettled(cache)
      assertNotNull(cache.get(name(0)))
      assertFalse(cache.isMissing(name(0)))
    } finally { assertTrue(cache.stop()) }
  }

  @Test fun lazyMissNotifiesOwnerOnceSoItCanStopShowingAnOlderSnapshot() = fixture { file ->
    val notifications = AtomicInteger()
    val cache = CrossSurfaceIconCache(RecordingContext(), themeFile = file,
      paletteState = { 0L }, eagerReload = false, onReady = { notifications.incrementAndGet() })
    try {
      cache.start()
      assertNull(cache.get("os.kei.missing.fixture"))
      awaitSettled(cache)
      assertTrue(cache.isMissing("os.kei.missing.fixture"))
      assertEquals(1, notifications.get())
      repeat(100) { assertNull(cache.get("os.kei.missing.fixture")) }
      awaitSettled(cache)
      assertEquals(1, notifications.get())
      assertEquals(0, size(cache, "pending"))
    } finally { assertTrue(cache.stop()) }
  }

  @Test fun repeatedInvalidationsCoalesceAndReadPaletteOnceForTheRetainedBatch() = fixture { file ->
    val reads = AtomicInteger()
    val cache = CrossSurfaceIconCache(RecordingContext(), themeFile = file,
      paletteState = { reads.incrementAndGet(); 0L })
    try {
      cache.start()
      val remaining = AtomicInteger(-1)
      block(cache) { handler ->
        repeat(32) { cache.get(name(it)) }
        repeat(100) { cache.invalidate() }
        // A single drain runs eight items, then yields to this sentinel.
        val sentinel = CountDownLatch(1)
        handler.post {
          remaining.set(size(cache, "pending"))
          sentinel.countDown()
        }
        sentinel
      }
      assertEquals(24, remaining.get())
      awaitIdle(cache)
      assertEquals(1, reads.get())
      repeat(32) { index -> repeat(10) { assertNotNull(cache.get(name(index))) } }
      assertEquals("Memory hits must not re-read palette state", 1, reads.get())
      assertEquals(32, size(cache, "wanted"))
    } finally { assertTrue(cache.stop()) }
  }

  @Test fun notificationWorkingSetIsBoundedAndNewAppsReplaceOldEntriesWithoutRecyclingOwners() = fixture { file ->
    val reads = AtomicInteger()
    val cache = CrossSurfaceIconCache(RecordingContext(), maxEntries = 32, themeFile = file,
      paletteState = { reads.incrementAndGet(); 0L })
    try {
      cache.start()
      block(cache) {
        repeat(32) { cache.get(name(it)) }
        null
      }
      awaitIdle(cache)
      val ownedByNative = requireNotNull(cache.get(name(0)))
      block(cache) {
        (32 until 160).forEach { cache.get(name(it)) }
        null
      }
      awaitIdle(cache)
      assertEquals(32, size(cache, "wanted"))
      assertEquals(0, size(cache, "pending"))
      assertEquals(32, bitmapCache(cache).snapshot().size)
      assertTrue(bitmapCache(cache).size() <= 32 * 192 * 192 * 4)
      assertNotNull(cache.get(name(159)))
      assertFalse("A native owner can still use an evicted snapshot", ownedByNative.isRecycled)
      assertEquals("No empty ready callback should be scheduled", null, field(cache, "notifyReady"))
      assertEquals(2, reads.get())
    } finally { assertTrue(cache.stop()) }
  }

  @Test fun fontAndOrientationChangesDoNotDiscardFixedPixelIconsButNightModeDoes() = fixture { file ->
    val context = RecordingContext()
    val reads = AtomicInteger()
    val cache = CrossSurfaceIconCache(context, themeFile = file,
      paletteState = { reads.incrementAndGet(); 0L })
    try {
      cache.start()
      block(cache) { cache.get(name(0)); null }
      awaitIdle(cache)
      val original = requireNotNull(cache.get(name(0)))
      val config = Configuration(context.resources.configuration).apply {
        fontScale += 0.25f
        orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE)
          Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
      }
      context.callback.onConfigurationChanged(config)
      assertSame(original, cache.get(name(0)))
      assertEquals(1, reads.get())
      config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
          Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
      context.callback.onConfigurationChanged(config)
      awaitIdle(cache)
      assertNotSame(original, cache.get(name(0)))
      assertEquals(2, reads.get())
    } finally { assertTrue(cache.stop()) }
  }

  @Test fun startDoesNotPrewarmUnrequestedApps() = fixture { file ->
    val reads = AtomicInteger()
    val cache = CrossSurfaceIconCache(RecordingContext(), themeFile = file,
      paletteState = { reads.incrementAndGet(); 0L })
    try {
      cache.start()
      awaitIdle(cache)
      assertEquals(0, reads.get())
      assertEquals(0, size(cache, "wanted"))
      assertEquals(0, bitmapCache(cache).size())
    } finally { assertTrue(cache.stop()) }
  }

  @Test fun lazyInvalidationCoalescesWithoutDecodingHiddenTargetsAndReloadsOnlyRequestedIcons() = fixture { file ->
    val reads = AtomicInteger()
    val notifications = AtomicInteger()
    val cache = CrossSurfaceIconCache(RecordingContext(), themeFile = file,
      paletteState = { reads.incrementAndGet(); 0L }, eagerReload = false,
      onReady = { notifications.incrementAndGet() })
    try {
      cache.start()
      block(cache) { repeat(32) { cache.get(name(it)) }; null }
      awaitSettled(cache)
      assertEquals(1, reads.get())
      assertEquals(32, bitmapCache(cache).snapshot().size)
      notifications.set(0)

      block(cache) { repeat(100) { cache.invalidate() }; null }
      awaitSettled(cache)
      assertEquals("Hidden targets must not cause archive/palette work", 1, reads.get())
      assertEquals(0, size(cache, "pending"))
      assertEquals(0, bitmapCache(cache).size())
      assertEquals("Notify the owner once so it can request its visible targets", 1, notifications.get())

      assertNull(cache.get(name(0)))
      awaitSettled(cache)
      assertNotNull(cache.get(name(0)))
      assertEquals(2, reads.get())
      assertEquals("Previously requested hidden targets stay cold", 1, bitmapCache(cache).snapshot().size)
    } finally { assertTrue(cache.stop()) }
  }

  @Test fun retainedHostContextCannotOverrideAnExplicitSystemAppearance() = fixture { file ->
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val mode = context.getSystemService(android.app.UiModeManager::class.java).nightMode
    org.junit.Assume.assumeTrue(mode in listOf(android.app.UiModeManager.MODE_NIGHT_YES,
      android.app.UiModeManager.MODE_NIGHT_NO))
    val dark = mode == android.app.UiModeManager.MODE_NIGHT_YES
    val config = android.content.res.Configuration(context.resources.configuration).apply {
      uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
        if (dark) android.content.res.Configuration.UI_MODE_NIGHT_NO
        else android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    fun png(color: Int): ByteArray {
      val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
      return try {
        bitmap.eraseColor(color)
        ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
          .toByteArray()
      } finally { bitmap.recycle() }
    }
    val entries = os.kei.hyper.home.iconpack.NativeDayNightIcon.entries(
      name(0), png(Color.RED), png(Color.BLUE))
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((path, bytes) in entries) {
        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
      }
    }
    val pinned = context.createConfigurationContext(config)
    val cache = CrossSurfaceIconCache(pinned, themeFile = file, paletteState = { 0L })
    try {
      cache.start()
      assertNull(cache.get(name(0)))
      awaitSettled(cache)
      assertEquals(if (dark) Color.BLUE else Color.RED,
        requireNotNull(cache.get(name(0))).getPixel(96, 96))
    } finally { assertTrue(cache.stop()) }
  }

  private fun name(index: Int) = "com.keihome.workload.app$index"

  private fun fixture(body: (File) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File.createTempFile("surface-workload-", ".zip", context.cacheDir)
    try {
      val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
      val png = try {
        bitmap.eraseColor(Color.BLUE)
        ByteArrayOutputStream().use {
          assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
          it.toByteArray()
        }
      } finally { bitmap.recycle() }
      ZipOutputStream(file.outputStream()).use { zip ->
        repeat(160) {
          zip.putNextEntry(ZipEntry("res/drawable-xxhdpi/${name(it)}.png"))
          zip.write(png)
          zip.closeEntry()
        }
      }
      body(file)
    } finally { file.delete() }
  }

  private fun field(cache: CrossSurfaceIconCache, name: String): Any? =
    CrossSurfaceIconCache::class.java.getDeclaredField(name).apply { isAccessible = true }.get(cache)
  private fun size(cache: CrossSurfaceIconCache, name: String): Int =
    when (val value = field(cache, name)) {
      is Map<*, *> -> value.size
      is Collection<*> -> value.size
      else -> error("Not a cache collection: $name")
    }
  @Suppress("UNCHECKED_CAST")
  private fun bitmapCache(cache: CrossSurfaceIconCache) =
    field(cache, "cache") as android.util.LruCache<Any, Bitmap>
  private fun handler(cache: CrossSurfaceIconCache) =
    Handler((field(cache, "thread") as HandlerThread).looper)

  private fun block(cache: CrossSurfaceIconCache, work: (Handler) -> CountDownLatch?) {
    val handler = handler(cache)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    handler.post { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    val sentinel = try { work(handler) } finally { release.countDown() }
    if (sentinel != null) assertTrue(sentinel.await(5, TimeUnit.SECONDS))
  }

  private fun awaitIdle(cache: CrossSurfaceIconCache) {
    val done = CountDownLatch(1)
    val handler = handler(cache)
    val probe = object : Runnable {
      override fun run() {
        if (field(cache, "drainScheduled") == true) handler.post(this) else done.countDown()
      }
    }
    assertTrue(handler.post(probe))
    assertTrue("Cache work did not drain", done.await(5, TimeUnit.SECONDS))
  }

  private fun awaitSettled(cache: CrossSurfaceIconCache) {
    val done = CountDownLatch(1)
    val handler = handler(cache)
    val probe = object : Runnable {
      override fun run() {
        if (field(cache, "drainScheduled") == true || field(cache, "notifyReady") != null)
          handler.postDelayed(this, 5)
        else done.countDown()
      }
    }
    assertTrue(handler.post(probe))
    assertTrue("Cache work and notification did not settle", done.await(5, TimeUnit.SECONDS))
  }

  private class RecordingContext : ContextWrapper(
    InstrumentationRegistry.getInstrumentation().targetContext,
  ) {
    lateinit var callback: ComponentCallbacks
    override fun registerComponentCallbacks(callback: ComponentCallbacks) { this.callback = callback }
    override fun unregisterComponentCallbacks(callback: ComponentCallbacks) {}
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
      broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? = null
    override fun unregisterReceiver(receiver: BroadcastReceiver) {}
  }
}
