package os.kei.hyper.home.xposed

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentTaskNotificationCopyDeviceTest {
  @Test fun absentReplacementKeepsNativeCallAndDoesNotCopyArguments() {
    val native = Any()
    var nativeCalls = 0
    var copies = 0
    var changedCalls = 0
    val result = proceedRecentTaskNotification(null, {
      nativeCalls++; native
    }, {
      copies++; arrayOf<Any?>(null)
    }, {
      changedCalls++; Any()
    })
    assertSame(native, result)
    assertEquals(1, nativeCalls)
    assertEquals(0, copies)
    assertEquals(0, changedCalls)
  }

  @Test fun successfulReplacementCopiesOnceAndPreservesTypedPayloadAndOtherArguments() {
    val original = ActivityManager.RunningTaskInfo().apply { taskId = 42 }
    val replacement = ActivityManager.RunningTaskInfo().apply {
      taskId = 42
      baseIntent = Intent(Intent.ACTION_MAIN).setPackage("os.test.owner")
      taskDescription = ActivityManager.TaskDescription("native-label")
    }
    val other = Any()
    val originalArgs = arrayOf<Any?>(original, other)
    val native = Any()
    var nativeCalls = 0
    var copies = 0
    var changedCalls = 0
    val result = proceedRecentTaskNotification(replacement, {
      nativeCalls++; Any()
    }, {
      copies++; originalArgs.copyOf()
    }, { args ->
      changedCalls++
      assertNotSame(originalArgs, args)
      assertSame(replacement, args[0])
      assertEquals(ActivityManager.RunningTaskInfo::class.java, args[0]?.javaClass)
      assertEquals("native-label", (args[0] as ActivityManager.RunningTaskInfo).taskDescription?.label)
      assertEquals("os.test.owner", (args[0] as ActivityManager.RunningTaskInfo).baseIntent?.`package`)
      assertSame(other, args[1])
      native
    })
    assertSame(native, result)
    assertSame(original, originalArgs[0])
    assertEquals(0, nativeCalls)
    assertEquals(1, copies)
    assertEquals(1, changedCalls)
  }

  @Test fun nativeFailurePropagatesWithoutRetryForOriginalAndChangedPaths() {
    for (replacement in listOf(null, ActivityManager.RunningTaskInfo())) {
      val failure = IllegalStateException("native notification failure")
      var nativeCalls = 0
      var copies = 0
      assertSame(failure, assertThrows(IllegalStateException::class.java) {
        proceedRecentTaskNotification<Any?>(replacement, {
          nativeCalls++; throw failure
        }, {
          copies++; arrayOf<Any?>(null)
        }, {
          nativeCalls++; throw failure
        })
      })
      assertEquals(1, nativeCalls)
      assertEquals(if (replacement == null) 0 else 1, copies)
    }
  }

  @Test fun copyKeepsRunningTaskTypeAndNativeDescriptionMetadata() {
    // The production caller is system_server; this fixture also needs hidden-API access.
    assumeTrue(InstrumentationRegistry.getArguments().getString("surfaceSystemContracts") == "true")
    val original = ActivityManager.RunningTaskInfo().apply {
      taskId = 123
      baseActivity = ComponentName("os.kei.fixture", "os.kei.fixture.Main")
      baseIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(baseActivity)
      taskDescription = ActivityManager.TaskDescription.Builder()
        .setLabel("Kept label").setPrimaryColor(Color.RED).setBackgroundColor(Color.BLUE).build()
    }
    // Real system notifications always carry a token; reproduce that native contract.
    val creator = Class.forName("android.window.WindowContainerToken").getField("CREATOR")
      .get(null) as Parcelable.Creator<*>
    val parcel = Parcel.obtain()
    val token = try {
      parcel.writeStrongBinder(Binder())
      parcel.setDataPosition(0)
      creator.createFromParcel(parcel)
    } finally { parcel.recycle() }
    original.javaClass.superclass!!.getDeclaredField("token").apply { isAccessible = true }
      .set(original, token)
    val copied = copyRunningTask(original)
    assertEquals(original.javaClass, copied.javaClass)
    assertNotSame(original, copied)
    assertEquals(original.taskId, copied.taskId)
    assertEquals(original.baseActivity, copied.baseActivity)
    assertTrue(original.baseIntent.filterEquals(copied.baseIntent))
    assertNotSame(original.taskDescription, copied.taskDescription)
    val description = requireNotNull(copied.taskDescription)
    assertEquals("Kept label", description.label)
    assertEquals(Color.RED, description.primaryColor)
    assertEquals(Color.BLUE, description.backgroundColor)
  }
}
