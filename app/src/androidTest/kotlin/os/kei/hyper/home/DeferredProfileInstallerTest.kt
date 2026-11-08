package os.kei.hyper.home

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeferredProfileInstallerTest {
  @Test
  fun mergedManifestKeepsLifecycleAndEmojiButDefersProfileInstaller() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val provider = context.packageManager.getProviderInfo(
      ComponentName(context.packageName, "androidx.startup.InitializationProvider"),
      PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
    )
    val metadata = requireNotNull(provider.metaData)
    assertEquals("androidx.startup", metadata.getString("androidx.lifecycle.ProcessLifecycleInitializer"))
    assertEquals("androidx.startup", metadata.getString("androidx.emoji2.text.EmojiCompatInitializer"))
    assertTrue(!metadata.containsKey("androidx.profileinstaller.ProfileInstallerInitializer"))
  }

  @Test
  fun registeringWithoutAnActivityDoesNotScheduleInitialization() = onMain {
    val application = RecordingApplication()
    val pending = mutableListOf<Runnable>()
    var initializations = 0

    DeferredProfileInstaller(application, { pending.add(it) }, { initializations++ })

    assertEquals(1, application.callbacks.size)
    assertTrue(pending.isEmpty())
    assertEquals(0, initializations)
    assertEquals(0, application.unregistrations)
  }

  @Test
  fun firstPostResumeDefersInitializationAndUnregistersExactlyOnce() = onMain {
    val application = RecordingApplication()
    val pending = mutableListOf<Runnable>()
    var initializations = 0
    val deferred = DeferredProfileInstaller(
      application, { pending.add(it) }, { initializations++ },
    )
    val firstActivity = Activity()
    val secondActivity = Activity()

    deferred.onActivityCreated(firstActivity, null)
    deferred.onActivityStarted(firstActivity)
    deferred.onActivityResumed(firstActivity)
    assertTrue(pending.isEmpty())
    assertEquals(0, initializations)

    application.callbacks.toList().forEach { it.onActivityPostResumed(firstActivity) }
    // An already captured dispatch or another resume must not enqueue a second installation.
    deferred.onActivityPostResumed(firstActivity)
    deferred.onActivityPostResumed(secondActivity)

    assertEquals(1, pending.size)
    assertEquals(0, initializations)
    assertEquals(1, application.unregistrations)
    assertTrue(application.callbacks.isEmpty())

    pending.single().run()
    deferred.onActivityPostResumed(secondActivity)
    assertEquals(1, initializations)
    assertEquals(1, pending.size)
    assertEquals(1, application.unregistrations)
  }

  private fun onMain(block: () -> Unit) {
    val task = FutureTask<Unit> { block() }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
    task.get()
  }

  private class RecordingApplication : Application() {
    val callbacks = mutableListOf<ActivityLifecycleCallbacks>()
    var unregistrations = 0

    override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
      callbacks.add(callback)
    }

    override fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
      unregistrations++
      callbacks.remove(callback)
    }
  }
}
