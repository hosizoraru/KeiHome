package os.kei.hyper.home.xposed

import android.app.Notification
import android.content.Context
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SystemUiNotificationRowReplayDeviceTest {
  open class FakeRow(context: Context) : FrameLayout(context) {
    var attached = true
    var reinflations = 0
    var onReinflate: (() -> Unit)? = null

    override fun isAttachedToWindow(): Boolean = attached

    fun reInflateViews() {
      reinflations++
      onReinflate?.invoke()
    }
  }

  class EqualRow(context: Context) : FakeRow(context) {
    override fun equals(other: Any?): Boolean = other is EqualRow
    override fun hashCode(): Int = 0
  }

  private class Binding(val row: FakeRow, val image: ImageView)

  private class Fixture(val user: Int = 0) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    var ready = false
    var readyReads = 0
    val replay = SystemUiNotificationRowReplay(
      FakeRow::class.java, FakeRow::class.java.getDeclaredMethod("reInflateViews"), user,
      { readyReads++; ready })

    fun binding(depth: Int = 1, row: FakeRow = FakeRow(context)): Binding {
      var parent: FrameLayout = row
      repeat(depth - 1) {
        val wrapper = FrameLayout(context)
        parent.addView(wrapper)
        parent = wrapper
      }
      val image = ImageView(context)
      parent.addView(image)
      return Binding(row, image)
    }

    fun notification(
      pkg: String = "android",
      uid: Int = user * 100000 + 1000,
      notificationUser: Int = user,
      postTime: Long = System.currentTimeMillis(),
    ) = StatusBarNotification(pkg, pkg, 1, null, uid, 0, 0, Notification(),
      UserHandle.getUserHandleForUid(notificationUser * 100000), postTime)

    fun remember(binding: Binding, notification: StatusBarNotification = notification()): Boolean =
      replay.remember(binding.image, notification)
  }

  private fun onMain(block: () -> Unit) =
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

  @Test fun gatesReadinessAndConsumesIdentitiesBeforeRecursiveNativeCallbacks() = onMain {
    val f = Fixture()
    val first = f.binding(row = EqualRow(f.context))
    val second = f.binding(row = EqualRow(f.context))
    assertEquals(first.row, second.row)
    assertNotSame(first.row, second.row)
    assertTrue(f.remember(first))
    repeat(12) { assertFalse(f.remember(first)) }
    assertTrue(f.remember(second))
    assertEquals(0, f.replay.replay())
    assertEquals(0, first.row.reinflations)
    first.row.onReinflate = {
      assertFalse(f.remember(first))
      f.replay.replay()
    }
    f.ready = true

    assertEquals(1, f.replay.replay())
    assertEquals(1, first.row.reinflations)
    assertEquals(1, second.row.reinflations)
    assertFalse(f.remember(first))
    assertFalse(f.remember(second))
    assertEquals(0, f.replay.replay())
  }

  @Test fun usesNearestRowWithinTwelveLinksAndOnlyRebindsAttachedRows() = onMain {
    val f = Fixture()
    val outer = FakeRow(f.context)
    val inner = f.binding()
    outer.addView(inner.row)
    assertTrue(f.remember(inner))
    val lastAllowed = f.binding(depth = 12)
    val tooDeep = f.binding(depth = 13)
    val detached = f.binding().apply { row.attached = false }
    assertTrue(f.remember(lastAllowed))
    assertFalse(f.remember(tooDeep))
    assertTrue(f.remember(detached))
    assertFalse(f.replay.remember(ImageView(f.context), f.notification()))
    f.ready = true

    assertEquals(2, f.replay.replay())
    assertEquals(0, outer.reinflations)
    assertEquals(1, inner.row.reinflations)
    assertEquals(1, lastAllowed.row.reinflations)
    assertEquals(0, tooDeep.row.reinflations)
    assertEquals(0, detached.row.reinflations)
    detached.row.attached = true
    assertEquals(1, f.replay.replay())
    assertEquals(1, detached.row.reinflations)
    detached.row.attached = false
    detached.row.attached = true
    assertFalse(f.remember(detached))
    assertEquals(0, f.replay.replay())
  }

  @Test fun acceptsApplicationRowsForTheCurrentUserAndRejectsOtherUsers() = onMain {
    val f = Fixture(user = 1)
    val application = f.binding()
    assertTrue(f.remember(application, f.notification(pkg = "com.example.mail")))
    val otherUser = f.binding()
    assertFalse(f.remember(otherUser, f.notification(
      uid = 1000,
      notificationUser = 0,
    )))
    f.ready = true
    assertEquals(1, f.replay.replay())
    assertEquals(1, application.row.reinflations)
    assertEquals(0, otherUser.row.reinflations)
    f.replay.markPending()
    assertFalse(f.remember(application, f.notification(pkg = "com.example.mail")))
    assertEquals(1, f.replay.replay())
    assertEquals(2, application.row.reinflations)
  }

  @Test fun capsWeakStorageAtEightRetainsConsumedRowsAndReclaimsClearedReferences() = onMain {
    val f = Fixture()
    val bindings = (0..8).map { f.binding() }
    bindings.take(8).forEach { assertTrue(f.remember(it)) }
    assertFalse(f.remember(bindings.last()))
    val references = weakRows(f.replay)
    assertEquals(8, references.size)
    references.forEachIndexed { index, reference -> assertSame(bindings[index].row, reference.get()) }
    f.ready = true
    assertEquals(8, f.replay.replay())
    bindings.take(8).forEach { assertEquals(1, it.row.reinflations) }
    bindings.forEach { assertFalse(f.remember(it)) }
    assertEquals(0, f.replay.replay())
    assertEquals(0, bindings.last().row.reinflations)

    references.first().clear()
    assertTrue(f.remember(bindings.last()))
    assertEquals(8, weakRows(f.replay).size)
    assertEquals(1, f.replay.replay())
    assertEquals(1, bindings.last().row.reinflations)
    assertEquals(1, bindings.first().row.reinflations)
  }

  @Test fun retirementCanClearDuringNativeRebindWithoutLockingOutItsThread() = onMain {
    val f = Fixture()
    val first = f.binding()
    val second = f.binding()
    assertTrue(f.remember(first))
    assertTrue(f.remember(second))
    first.row.onReinflate = {
      val retired = CountDownLatch(1)
      Thread {
        try { f.replay.clear() } finally { retired.countDown() }
      }.start()
      assertTrue("Retirement blocked by native rebind", retired.await(2, TimeUnit.SECONDS))
      assertFalse(f.remember(first))
      assertEquals(0, f.replay.replay())
    }
    f.ready = true

    assertEquals(1, f.replay.replay())
    assertEquals(1, first.row.reinflations)
    assertEquals(0, second.row.reinflations)
    assertTrue(weakRows(f.replay).isEmpty())
    val readyReads = f.readyReads
    assertFalse(f.remember(second))
    assertEquals(0, f.replay.replay())
    f.replay.clear()
    assertEquals(readyReads, f.readyReads)
  }

  @Test fun propagatesNativeExceptionAndDoesNotRetryConsumedFailure() = onMain {
    val f = Fixture()
    val first = f.binding()
    val second = f.binding()
    val failure = IllegalStateException("native requestRebind failed")
    first.row.onReinflate = { throw failure }
    assertTrue(f.remember(first))
    assertTrue(f.remember(second))
    f.ready = true

    assertSame(failure, assertThrows(IllegalStateException::class.java) { f.replay.replay() })
    assertEquals(1, first.row.reinflations)
    assertEquals(0, second.row.reinflations)
    assertFalse(f.remember(first))
    assertEquals(1, f.replay.replay())
    assertEquals(1, first.row.reinflations)
    assertEquals(1, second.row.reinflations)
    assertEquals(0, f.replay.replay())
  }

  private fun weakRows(replay: SystemUiNotificationRowReplay): List<WeakReference<*>> {
    val rows = replay.javaClass.getDeclaredField("rows").apply { isAccessible = true }
      .get(replay) as List<*>
    return rows.map { row ->
      requireNotNull(row).javaClass.getDeclaredField("view").apply { isAccessible = true }
        .get(row) as WeakReference<*>
    }
  }
}
