package os.kei.hyper.home.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import os.kei.hyper.home.BuildConfig

/** PackageManager persists component overrides, including across ordinary APK updates. */
internal class LauncherIconVisibility(context: Context) {
  private val manager = context.applicationContext.packageManager
  private val entry = ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.LauncherEntry")

  fun isHidden(): Boolean = manager.getComponentEnabledSetting(entry) in setOf(
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
  )

  fun setHidden(hidden: Boolean) {
    manager.setComponentEnabledSetting(entry,
      if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
      PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS)
  }
}
