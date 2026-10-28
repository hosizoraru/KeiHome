package os.kei.hyper.home

import android.app.Application
import android.os.Process
import io.github.libxposed.service.XposedServiceHelper
import os.kei.hyper.home.status.FrameworkConnection

class KeiHomeApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    if (!Process.isIsolated()) {
      XposedServiceHelper.registerListener(FrameworkConnection)
    }
  }
}
