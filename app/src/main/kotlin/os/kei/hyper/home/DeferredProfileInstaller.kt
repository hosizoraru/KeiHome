package os.kei.hyper.home

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.profileinstaller.ProfileInstallerInitializer
import androidx.startup.AppInitializer

/**
 * Defers profile scheduling past provider publication and the first activity resume.
 * Keeps ProfileInstaller's first-frame callback, jittered delay, and background write.
 *
 * Source: profileinstaller 1.4.0, ProfileInstallerInitializer.java:74-85,97-136:
 * https://dl.google.com/dl/android/maven2/androidx/profileinstaller/profileinstaller/1.4.0/profileinstaller-1.4.0-sources.jar
 */
internal class DeferredProfileInstaller(
  private val application: Application,
  private val postToMain: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
  private val initialize: () -> Unit = {
    AppInitializer.getInstance(application)
      .initializeComponent(ProfileInstallerInitializer::class.java)
  },
) : Application.ActivityLifecycleCallbacks {
  private var scheduled = false

  init {
    application.registerActivityLifecycleCallbacks(this)
  }

  override fun onActivityPostResumed(activity: Activity) {
    if (scheduled) return
    scheduled = true
    application.unregisterActivityLifecycleCallbacks(this)
    postToMain(Runnable { initialize() })
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
  override fun onActivityStarted(activity: Activity) = Unit
  override fun onActivityResumed(activity: Activity) = Unit
  override fun onActivityPaused(activity: Activity) = Unit
  override fun onActivityStopped(activity: Activity) = Unit
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  override fun onActivityDestroyed(activity: Activity) = Unit
}
