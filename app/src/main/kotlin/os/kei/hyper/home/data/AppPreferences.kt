package os.kei.hyper.home.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal enum class AppAppearance { SYSTEM, LIGHT, DARK }
internal enum class AppColorMode { DEFAULT, MONET, FOLLOW_ICONS }
internal enum class AppPagerGesture { NATIVE, CROSS_AXIS, IOS_LIKE }
internal enum class ColorOsStyle(val key: String) {
  CLASSIC("classic"), DAYLIGHT("daylight"), NIGHT_SHADOW("nightshadow"),
  MATERIAL("material"), PEBBLE("pebble"), INSPIRATION("inspiration");
  val usesMonet: Boolean get() = this == INSPIRATION
  val artworkKey: String get() = if (usesMonet) "classic" else key
  fun importSourceId(assetId: String, appearance: IconPackAppearance): String =
    "coloros:$key:${appearance.name}:$assetId"
}

internal enum class IconPackAppearance {
  SYSTEM, LIGHT, DARK, DIM_AT_NIGHT;

  fun sourceDarkMode(systemDarkMode: Boolean): Boolean = when (this) {
    SYSTEM -> systemDarkMode
    LIGHT, DIM_AT_NIGHT -> false
    DARK -> true
  }

  fun tintColor(argb: Int, systemDarkMode: Boolean): Int {
    if (this != DIM_AT_NIGHT || !systemDarkMode) return argb
    fun channel(shift: Int) = ((argb ushr shift and 255) * 214 / 255) shl shift
    return (argb and -0x1000000) or channel(16) or channel(8) or channel(0)
  }

  companion object {
    const val DIM_COLOR_MULTIPLY = 0x00d6d6d6
  }
}

internal data class AppPreferencesState(
  val appearance: AppAppearance = AppAppearance.SYSTEM,
  val colorMode: AppColorMode = AppColorMode.FOLLOW_ICONS,
  val pageUserScroll: Boolean = true,
  val pagerGesture: AppPagerGesture = AppPagerGesture.IOS_LIKE,
  val showIconPreviews: Boolean = true,
  val iconPackAppearance: IconPackAppearance = IconPackAppearance.SYSTEM,
  val colorOsStyle: ColorOsStyle = ColorOsStyle.CLASSIC,
  val colorOsArtPlus: Boolean = true,
  val colorOsComponents: Map<String, Boolean> = emptyMap(),
  val monetInspiration: Boolean? = null,
)

internal class AppPreferences private constructor(context: Context) {
  private val preferences = context.getSharedPreferences("app_display", Context.MODE_PRIVATE)
  val state: StateFlow<AppPreferencesState>
    field = MutableStateFlow(read())
  private fun read() = AppPreferencesState(
    appearance = AppAppearance.entries.firstOrNull {
      it.name == preferences.getString("appearance", null)
    } ?: AppAppearance.SYSTEM,
    colorMode = AppColorMode.entries.firstOrNull { it.name == preferences.getString("ui_color_mode", null) }
      ?: AppColorMode.FOLLOW_ICONS,
    pageUserScroll = preferences.getBoolean("page_user_scroll", true),
    pagerGesture = AppPagerGesture.entries.firstOrNull { it.name == preferences.getString("pager_gesture", null) }
      ?: AppPagerGesture.IOS_LIKE,
    showIconPreviews = preferences.getBoolean("icon_previews", true),
    iconPackAppearance = IconPackAppearance.entries.firstOrNull {
      it.name == preferences.getString("icon_pack_appearance", null)
    } ?: IconPackAppearance.SYSTEM,
    colorOsStyle = ColorOsStyle.entries.firstOrNull {
      it.name == preferences.getString("color_os_style", null)
    } ?: ColorOsStyle.CLASSIC,
    colorOsArtPlus = preferences.getBoolean("color_os_art", true),
    colorOsComponents = preferences.all.filterKeys { it.startsWith("coloros_component_") }
      .mapNotNull { (key, value) -> (value as? Boolean)?.let { key.removePrefix("coloros_component_") to it } }.toMap(),
    monetInspiration = if (preferences.contains("monet_inspiration"))
      preferences.getBoolean("monet_inspiration", false) else null,
  )

  fun setAppearance(value: AppAppearance) {
    preferences.edit().putString("appearance", value.name).apply()
    state.update { it.copy(appearance = value) }
  }

  fun setPageUserScroll(value: Boolean) {
    preferences.edit().putBoolean("page_user_scroll", value).apply()
    state.update { it.copy(pageUserScroll = value) }
  }

  fun setPagerGesture(value: AppPagerGesture) {
    preferences.edit().putString("pager_gesture", value.name).apply()
    state.update { it.copy(pagerGesture = value) }
  }

  fun setColorMode(value: AppColorMode) {
    preferences.edit().putString("ui_color_mode", value.name).apply()
    state.update { it.copy(colorMode = value) }
  }

  fun setShowIconPreviews(value: Boolean) {
    preferences.edit().putBoolean("icon_previews", value).apply()
    state.update { it.copy(showIconPreviews = value) }
  }

  fun setIconPackAppearance(value: IconPackAppearance) {
    preferences.edit().putString("icon_pack_appearance", value.name).apply()
    state.update { it.copy(iconPackAppearance = value) }
  }

  fun setColorOsStyle(value: ColorOsStyle) {
    preferences.edit().putString("color_os_style", value.name).apply()
    state.update { it.copy(colorOsStyle = value) }
  }

  fun setColorOsArtPlus(value: Boolean) {
    preferences.edit().putBoolean("color_os_art", value).apply()
    state.update { it.copy(colorOsArtPlus = value) }
  }

  fun prepareColorOsComponent(sourceId: String, monet: Boolean) {
    preferences.edit().putBoolean("coloros_prepared_$sourceId", monet).apply()
  }

  fun setMonetInspiration(enabled: Boolean) {
    preferences.edit().putBoolean("monet_inspiration", enabled).apply()
    state.update { it.copy(monetInspiration = enabled) }
  }

  fun recordColorOsComponent(sourceId: String, localId: String) {
    val key = "coloros_prepared_$sourceId"
    if (!preferences.contains(key)) return
    val monet = preferences.getBoolean(key, false)
    preferences.edit().putBoolean("coloros_component_$localId", monet).apply()
    state.update { it.copy(colorOsComponents = it.colorOsComponents + (localId to monet)) }
  }

  companion object {
    @Volatile private var instance: AppPreferences? = null
    fun get(context: Context): AppPreferences = instance ?: synchronized(this) {
      instance ?: AppPreferences(context.applicationContext).also { instance = it }
    }
  }
}
