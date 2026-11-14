package os.kei.hyper.home

import android.app.UiModeManager
import android.content.Context
import android.util.Log
import os.kei.hyper.home.data.AppAppearance
import os.kei.hyper.home.data.AppDisplayStorage

internal object AppLaunchAppearance {
  private var applied: AppAppearance? = null

  fun initialize(context: Context) {
    val saved = AppDisplayStorage.open(context).getString("appearance", null)
    synchronize(context, AppAppearance.entries.firstOrNull { it.name == saved } ?: AppAppearance.SYSTEM)
  }

  fun synchronize(context: Context, appearance: AppAppearance) {
    if (applied == appearance) return
    val manager = context.getSystemService(UiModeManager::class.java) ?: return
    val mode = when (appearance) {
      AppAppearance.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
      AppAppearance.LIGHT -> UiModeManager.MODE_NIGHT_NO
      AppAppearance.DARK -> UiModeManager.MODE_NIGHT_YES
    }
    try {
      // The system persists this app-local override for the next starting window.
      // AUTO clears the override, allowing system schedules and toggles to apply.
      manager.setApplicationNightMode(mode)
      applied = appearance
    } catch (error: RuntimeException) {
      Log.w("KeiHomeStartup", "Could not synchronize app launch appearance", error)
    }
  }
}
