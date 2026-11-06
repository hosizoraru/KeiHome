package os.kei.hyper.home

import android.app.Application
import android.os.Process
import io.github.libxposed.service.XposedServiceHelper
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.AppLogStore
import os.kei.hyper.home.data.OnboardingPreferences

class KeiHomeApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    if (!Process.isIsolated()) {
      OnboardingPreferences.get(this)
      XposedServiceHelper.registerListener(FrameworkConnection)
      AppLogStore.start(this)
      AppLogStore.record(this, AppLogStore.Level.INFO, "App process started")
    }
  }
}
