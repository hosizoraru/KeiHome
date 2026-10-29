package os.kei.hyper.home.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal enum class AppAppearance { SYSTEM, LIGHT, DARK }

internal data class AppPreferencesState(
  val appearance: AppAppearance = AppAppearance.SYSTEM,
  val showIconPreviews: Boolean = true,
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
  )

  fun setAppearance(value: AppAppearance) {
    preferences.edit().putString("appearance", value.name).apply()
    state.update { it.copy(appearance = value) }
  }

  fun setShowIconPreviews(value: Boolean) {
    preferences.edit().putBoolean("icon_previews", value).apply()
    state.update { it.copy(showIconPreviews = value) }
  }

  companion object {
    @Volatile private var instance: AppPreferences? = null
    fun get(context: Context): AppPreferences = instance ?: synchronized(this) {
      instance ?: AppPreferences(context.applicationContext).also { instance = it }
    }
  }
}
