package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class LauncherLabelMode(val nativeValue: Int) {
  NATIVE(0), MARQUEE(1), TWO_LINES(2);
  companion object { fun from(value: Int) = entries.firstOrNull { it.nativeValue == value } ?: NATIVE }
}

internal object LauncherLabelPreferences {
  const val GROUP = ThemeEffectsPreferences.GROUP
  const val KEY = "launcher_label_mode"
  fun read(preferences: SharedPreferences?) = LauncherLabelMode.from(preferences?.getInt(KEY, 0) ?: 0)
  suspend fun read(service: XposedService): LauncherLabelMode = withContext(Dispatchers.IO) { read(remote(service)) }
  suspend fun save(service: XposedService, mode: LauncherLabelMode) = withContext(Dispatchers.IO) {
    check(remote(service).edit().putInt(KEY, mode.nativeValue).commit()) { "Remote save is unconfirmed" }
  }
  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L)
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    return service.getRemotePreferences(GROUP)
  }
}
