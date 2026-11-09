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
