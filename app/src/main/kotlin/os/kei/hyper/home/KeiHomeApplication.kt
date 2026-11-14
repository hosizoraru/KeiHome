package os.kei.hyper.home

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.service.XposedServiceHelper
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.AppLogStore
import os.kei.hyper.home.data.OnboardingPreferences

class KeiHomeApplication : Application() {
  override fun attachBaseContext(base: Context) {
    logStartupPhase("attachBaseContext.begin")
    super.attachBaseContext(base)
    logStartupPhase("attachBaseContext.done")
  }

  override fun onCreate() {
    logStartupPhase("onCreate.begin")
    super.onCreate()
    if (!Process.isIsolated()) {
      AppLaunchAppearance.initialize(this)
      DeferredProfileInstaller(this)
      OnboardingPreferences.get(this)
      XposedServiceHelper.registerListener(FrameworkConnection)
      AppLogStore.start(this)
      AppLogStore.record(this, AppLogStore.Level.INFO, "App process started")
    }
    logStartupPhase("onCreate.done")
  }

  private fun logStartupPhase(phase: String) {
    Log.i("KeiHomeStartup",
      "$phase elapsedRealtimeNanos=${SystemClock.elapsedRealtimeNanos()} pid=${Process.myPid()}")
  }
}
