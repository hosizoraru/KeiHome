package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object WallpaperPresetPreferences {
  const val GROUP = "wallpaper_presets"
  private const val LIGHT = "light"
  private const val DARK = "dark"

  fun read(prefs: SharedPreferences): WallpaperPresetChoices = prefs.all.let { values ->
    WallpaperPresetChoices(values[LIGHT] as? String, values[DARK] as? String)
  }

  suspend fun read(service: XposedService): WallpaperPresetChoices = withContext(Dispatchers.IO) {
    read(service.getRemotePreferences(GROUP))
  }

  suspend fun save(service: XposedService, dark: Boolean, value: String?) = withContext(Dispatchers.IO) {
    val editor = service.getRemotePreferences(GROUP).edit()
    val key = if (dark) DARK else LIGHT
    if (value == null) editor.remove(key) else editor.putString(key, value)
    check(editor.commit()) { "Remote wallpaper preset save is unconfirmed" }
  }
}
