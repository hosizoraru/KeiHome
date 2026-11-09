package os.kei.hyper.home.xposed

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class CrossSurfaceIconEventsTest {
  @Test fun publisherTargetsOnlyTheTwoExistingRegisteredHosts() {
    val intents = mutableListOf<Intent>()
    val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
      override fun sendBroadcast(intent: Intent) { intents += Intent(intent) }
    }
    CrossSurfaceIconEvents.publishPaletteChanged(context)
    assertEquals(listOf("android", "com.android.systemui"), intents.map { it.`package` })
    intents.forEach {
      assertEquals(CrossSurfaceIconEvents.PALETTE_CHANGED, it.action)
      assertEquals(Intent.FLAG_RECEIVER_REGISTERED_ONLY, it.flags)
      assertNull(it.data)
      assertNull(it.extras)
    }
  }

  @Test fun paletteEventInvalidatesSnapshotsWithoutASettingsObserver() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val cache = CrossSurfaceIconCache(context) {}
    try {
      cache.start()
      val thread = CrossSurfaceIconCache::class.java.getDeclaredField("thread")
        .apply { isAccessible = true }.get(cache) as HandlerThread
      val ready = CountDownLatch(1)
      Handler(thread.looper).post { ready.countDown() }
      assertTrue(ready.await(3, TimeUnit.SECONDS))
      val revision = CrossSurfaceIconCache::class.java.getDeclaredField("revision")
        .apply { isAccessible = true }.get(cache) as AtomicLong
      val before = revision.get()
      context.sendBroadcast(
        Intent(CrossSurfaceIconEvents.PALETTE_CHANGED).setPackage(context.packageName)
          .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
      )
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
      while (revision.get() == before && System.nanoTime() < deadline) Thread.sleep(10)
      assertTrue(revision.get() > before)
      assertFalse(CrossSurfaceIconCache::class.java.declaredFields.any { it.name == "observer" })
    } finally { assertTrue(cache.stop()) }
  }
}
