package os.kei.hyper.home.data

import android.content.Context
import android.content.SharedPreferences
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal enum class AppAppearance { SYSTEM, LIGHT, DARK }
internal enum class AppColorMode { DEFAULT, MONET, FOLLOW_ICONS }
internal enum class AppPagerGesture { NATIVE, CROSS_AXIS, IOS_LIKE }
internal enum class AppTopBarBlur { OFF, GAUSSIAN, PROGRESSIVE }
internal enum class AppNavigationBarStyle { IOS_LIKE, OS4_GLASS }
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
  val topBarBlur: AppTopBarBlur = AppTopBarBlur.PROGRESSIVE,
  val autoApplyIconPack: Boolean = true,
  val installedIconsOnly: Boolean = false,
  val rememberMenuSelections: Boolean = true,
  val showIconPreviews: Boolean = true,
  val iconPackAppearance: IconPackAppearance = IconPackAppearance.SYSTEM,
  val systemIconFallback: SystemIconFallback = SystemIconFallback.DEFAULT,
  val completeSystemIconConfig: Boolean = true,
  val perfectFallbackExpanded: Boolean = false,
  val systemAppNameMatching: Boolean = true,
  val colorOsForOtherPacks: Boolean = false,
  val colorOsStyle: ColorOsStyle = ColorOsStyle.CLASSIC,
  val colorOsArtPlus: Boolean = true,
  val colorOsComponents: Map<String, Boolean> = emptyMap(),
  val monetInspiration: Boolean? = null,
  val compactPackHints: Boolean = false,
  val navigationBarStyle: AppNavigationBarStyle = AppNavigationBarStyle.IOS_LIKE,
  val cacheReminderBytes: Long = 0L,
)

internal class AppPreferences internal constructor(
  private val preferences: SharedPreferences,
  val systemIconFallbackChoices: List<SystemIconFallback>,
) {
  private constructor(context: Context) : this(
    AppDisplayStorage.open(context),
    context.assets.list("system-icon-fallback").orEmpty().toSet().let { assets ->
      SystemIconFallback.entries.filter { it == SystemIconFallback.DEFAULT || "${it.name.lowercase()}.zip" in assets }
    },
  )
  val state: StateFlow<AppPreferencesState>
    field = MutableStateFlow(read())
  // MMKV cannot enumerate typed values through SharedPreferences.getAll().
  private fun storedKeys(): Collection<String> =
    if (preferences is MMKV) preferences.allKeys().orEmpty().asList() else preferences.all.keys

  private fun read() = AppPreferencesState(
    appearance = AppAppearance.entries.firstOrNull {
      it.name == preferences.getString("appearance", null)
    } ?: AppAppearance.SYSTEM,
    colorMode = AppColorMode.entries.firstOrNull { it.name == preferences.getString("ui_color_mode", null) }
      ?: AppColorMode.FOLLOW_ICONS,
    pageUserScroll = preferences.getBoolean("page_user_scroll", true),
    autoApplyIconPack = preferences.getBoolean("auto_apply_icons", true),
    installedIconsOnly = preferences.getBoolean("installed_icons_only", false),
    rememberMenuSelections = preferences.getBoolean("remember_menus", true),
    pagerGesture = AppPagerGesture.entries.firstOrNull { it.name == preferences.getString("pager_gesture", null) }
      ?: AppPagerGesture.IOS_LIKE,
    topBarBlur = AppTopBarBlur.entries.firstOrNull { it.name == preferences.getString("top_bar_blur", null) }
      ?: AppTopBarBlur.PROGRESSIVE,
    showIconPreviews = preferences.getBoolean("icon_previews", true),
    iconPackAppearance = IconPackAppearance.entries.firstOrNull {
      it.name == preferences.getString("icon_pack_appearance", null)
    } ?: IconPackAppearance.SYSTEM,
    systemIconFallback = preferences.getString("system_icon_fallback", null)?.let { name ->
      runCatching { SystemIconFallback.valueOf(name) }.getOrNull()
        ?.takeIf { it.isPerfect || it in systemIconFallbackChoices }
    } ?: SystemIconFallback.DEFAULT,
    completeSystemIconConfig = preferences.getBoolean("complete_system_icon_config", true),
    perfectFallbackExpanded = preferences.getBoolean("perfect_fallback_expanded", false),
    systemAppNameMatching = preferences.getBoolean("system_app_name_matching", true),
    colorOsForOtherPacks = preferences.getBoolean("coloros_other_packs", false),
    colorOsStyle = ColorOsStyle.entries.firstOrNull {
      it.name == preferences.getString("color_os_style", null)
    } ?: ColorOsStyle.CLASSIC,
    colorOsArtPlus = preferences.getBoolean("color_os_art", true),
    colorOsComponents = storedKeys().filter { it.startsWith("coloros_component_") }
      .associate { it.removePrefix("coloros_component_") to preferences.getBoolean(it, false) },
    monetInspiration = if (preferences.contains("monet_inspiration"))
      preferences.getBoolean("monet_inspiration", false) else null,
    compactPackHints = preferences.getBoolean("compact_pack_hints", false),
    navigationBarStyle = AppNavigationBarStyle.entries.firstOrNull {
      it.name == preferences.getString("navigation_bar_style", null)
    } ?: AppNavigationBarStyle.IOS_LIKE,
    cacheReminderBytes = preferences.getLong("cache_reminder_bytes", 0L)
      .takeIf(CacheReminderBudget::isValidCustom) ?: 0L,
  )

  fun setAppearance(value: AppAppearance) {
    preferences.edit().putString("appearance", value.name).apply()
    state.update { it.copy(appearance = value) }
  }

  fun setTopBarBlur(value: AppTopBarBlur) {
    preferences.edit().putString("top_bar_blur", value.name).apply()
    state.update { it.copy(topBarBlur = value) }
  }

  fun setNavigationBarStyle(value: AppNavigationBarStyle) {
    preferences.edit().putString("navigation_bar_style", value.name).apply()
    state.update { it.copy(navigationBarStyle = value) }
  }

  fun setCacheReminderBytes(value: Long) {
    require(value == 0L || CacheReminderBudget.isValidCustom(value)) {
      "Cache reminder must be automatic (0) or between ${CacheReminderBudget.MIN_BYTES} and ${CacheReminderBudget.MAX_BYTES} bytes"
    }
    preferences.edit().putLong("cache_reminder_bytes", value).apply()
    state.update { it.copy(cacheReminderBytes = value) }
  }

  fun setPageUserScroll(value: Boolean) {
    preferences.edit().putBoolean("page_user_scroll", value).apply()
    state.update { it.copy(pageUserScroll = value) }
  }

  fun setAutoApplyIconPack(value: Boolean) {
    preferences.edit().putBoolean("auto_apply_icons", value).apply()
    state.update { it.copy(autoApplyIconPack = value) }
  }

  fun setInstalledIconsOnly(value: Boolean) {
    preferences.edit().putBoolean("installed_icons_only", value).apply()
    state.update { it.copy(installedIconsOnly = value) }
  }

  fun setRememberMenuSelections(value: Boolean) {
    preferences.edit().apply {
      putBoolean("remember_menus", value)
      if (!value) storedKeys()
        .filter { it.startsWith("browser_") || it.startsWith("pack_sort_") }.forEach(::remove)
    }.apply()
    state.update { it.copy(rememberMenuSelections = value) }
  }

  fun readBrowserOptions(): IconBrowserOptions = if (!state.value.rememberMenuSelections) IconBrowserOptions()
    else IconBrowserOptions.restore(IconBrowserOptions().stored().mapValues { (key, default) ->
      when (default) {
        is Int -> preferences.getInt("browser_$key", default)
        is Boolean -> preferences.getBoolean("browser_$key", default)
        else -> error("Unsupported menu value")
      }
    })

  fun saveBrowserOptions(value: IconBrowserOptions) {
    if (!state.value.rememberMenuSelections) return
    preferences.edit().apply {
      value.stored().forEach { (key, item) ->
        when (item) {
          is Int -> putInt("browser_$key", item)
          is Boolean -> putBoolean("browser_$key", item)
        }
      }
    }.apply()
  }

  fun readPackSort(): Pair<Int, Boolean> = if (!state.value.rememberMenuSelections) 0 to false else
    preferences.getInt("pack_sort_order", 0).coerceIn(0, 3) to preferences.getBoolean("pack_sort_reverse", false)

  fun savePackSort(order: Int, reverse: Boolean) {
    if (!state.value.rememberMenuSelections) return
    preferences.edit().putInt("pack_sort_order", order.coerceIn(0, 3)).putBoolean("pack_sort_reverse", reverse).apply()
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

  fun setCompactPackHints(value: Boolean) {
    preferences.edit().putBoolean("compact_pack_hints", value).apply()
    state.update { it.copy(compactPackHints = value) }
  }

  fun setIconPackAppearance(value: IconPackAppearance) {
    preferences.edit().putString("icon_pack_appearance", value.name).apply()
    state.update { it.copy(iconPackAppearance = value) }
  }

  fun setSystemIconFallback(value: SystemIconFallback) {
    require(SystemIconFallback.valueOf(value.name) == value &&
      (value in systemIconFallbackChoices || value.isPerfect))
    preferences.edit().putString("system_icon_fallback", value.name).apply()
    state.update { it.copy(systemIconFallback = value) }
  }

  fun setCompleteSystemIconConfig(value: Boolean) {
    preferences.edit().putBoolean("complete_system_icon_config", value).apply()
    state.update { it.copy(completeSystemIconConfig = value) }
  }

  fun setPerfectFallbackExpanded(value: Boolean) {
    preferences.edit().putBoolean("perfect_fallback_expanded", value).apply()
    state.update { it.copy(perfectFallbackExpanded = value) }
  }

  fun setSystemAppNameMatching(value: Boolean) {
    preferences.edit().putBoolean("system_app_name_matching", value).apply()
    state.update { it.copy(systemAppNameMatching = value) }
  }

  fun setColorOsForOtherPacks(value: Boolean) {
    preferences.edit().putBoolean("coloros_other_packs", value).apply()
    state.update { it.copy(colorOsForOtherPacks = value) }
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

  fun recordColorOsComponent(sourceId: String, localId: String, confirmedPalette: Boolean? = null,
    onlyIfMissing: Boolean = false) {
    if (onlyIfMissing && preferences.contains("coloros_component_$localId")) return
    val key = "coloros_prepared_$sourceId"
    if (confirmedPalette == null && !preferences.contains(key)) return
    val monet = confirmedPalette ?: preferences.getBoolean(key, false)
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
