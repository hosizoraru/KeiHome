package os.kei.hyper.home.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal enum class AppAppearance { SYSTEM, LIGHT, DARK }

internal enum class IconPackAppearance {
  SYSTEM, LIGHT, DARK, DIM_AT_NIGHT;

  fun sourceDarkMode(systemDarkMode: Boolean): Boolean = when (this) {
    SYSTEM -> systemDarkMode
    LIGHT, DIM_AT_NIGHT -> false
    DARK -> true
  }

  companion object {
    const val DIM_COLOR_MULTIPLY = 0x00d6d6d6
  }
}

internal data class AppPreferencesState(
  val appearance: AppAppearance = AppAppearance.SYSTEM,
  val showIconPreviews: Boolean = true,
  val iconPackAppearance: IconPackAppearance = IconPackAppearance.SYSTEM,
)

internal class AppPreferences private constructor(context: Context) {
  private val preferences = context.getSharedPreferences("app_display", Context.MODE_PRIVATE)
  val state: StateFlow<AppPreferencesState>
    field = MutableStateFlow(read())
  private fun read() = AppPreferencesState(
    appearance = AppAppearance.entries.firstOrNull {
      it.name == preferences.getString("appearance", null)
    } ?: AppAppearance.SYSTEM,
    showIconPreviews = preferences.getBoolean("icon_previews", true),
    iconPackAppearance = IconPackAppearance.entries.firstOrNull {
      it.name == preferences.getString("icon_pack_appearance", null)
    } ?: IconPackAppearance.SYSTEM,
  )

  fun setAppearance(value: AppAppearance) {
    preferences.edit().putString("appearance", value.name).apply()
    state.update { it.copy(appearance = value) }
  }

  fun setShowIconPreviews(value: Boolean) {
    preferences.edit().putBoolean("icon_previews", value).apply()
    state.update { it.copy(showIconPreviews = value) }
  }

  fun setIconPackAppearance(value: IconPackAppearance) {
    preferences.edit().putString("icon_pack_appearance", value.name).apply()
    state.update { it.copy(iconPackAppearance = value) }
  }

  companion object {
    @Volatile private var instance: AppPreferences? = null
    fun get(context: Context): AppPreferences = instance ?: synchronized(this) {
      instance ?: AppPreferences(context.applicationContext).also { instance = it }
    }
  }
}
