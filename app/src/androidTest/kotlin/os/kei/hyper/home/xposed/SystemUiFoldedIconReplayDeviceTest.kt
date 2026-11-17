package os.kei.hyper.home.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemUiFoldedIconReplayDeviceTest {
  @Test fun nativePayloadRefreshCoalescesAndRunsOnTheMainThread() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val called = CountDownLatch(1)
    val count = AtomicInteger()
    val replay = SystemUiFoldedIconReplay(Handler(Looper.getMainLooper())) {
      assertSame(Looper.getMainLooper(), Looper.myLooper())
      count.incrementAndGet()
      called.countDown()
    }
    try {
      instrumentation.runOnMainSync { repeat(32) { replay.request() } }
      assertTrue(called.await(5, TimeUnit.SECONDS))
      instrumentation.waitForIdleSync()
      assertEquals(1, count.get())
    } finally { replay.stop() }
  }

  @Test fun retirementCancelsQueuedNativeWork() {
    val called = CountDownLatch(1)
    val replay = SystemUiFoldedIconReplay(Handler(Looper.getMainLooper())) { called.countDown() }
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      replay.request()
      replay.stop()
      replay.request()
    }
    assertFalse(called.await(300, TimeUnit.MILLISECONDS))
  }

  @Test fun installedHostProvesBothTheProducerAndCompositeNotificationConsumer() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val host = context.createPackageContext("com.android.systemui",
      Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
    val owner = Class.forName("com.miui.systemui.graphics.AppIconsManager", false, host.classLoader)
    val profile = SystemUiFoldedIconProfile.resolve(context, owner)
    assertSame(owner, profile.producer.declaringClass)
    assertEquals("sendFoldNotification", profile.send.name)
  }
}
