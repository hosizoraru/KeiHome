package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppDisplayPreferencesTest {
  @Test fun monetDisclosuresDefaultToExpandedAndRestoreIndependently() {
    val store = Store(legacyValues())
    val originalValues = store.values.toMap()
    val preferences = store.open()
    val defaults = preferences.state.value
    assertEquals(true, defaults.monetPalettesExpanded)
    assertEquals(true, defaults.monetIconColorsExpanded)
    assertEquals(originalValues, store.values)

    preferences.setMonetPalettesExpanded(false)
    val palettesCollapsed = defaults.copy(monetPalettesExpanded = false)
    assertEquals(palettesCollapsed, preferences.state.value)
    assertEquals(palettesCollapsed, store.open().state.value)

    preferences.setMonetIconColorsExpanded(false)
    preferences.setMonetPalettesExpanded(true)
    val iconColorsCollapsed = defaults.copy(monetIconColorsExpanded = false)
    assertEquals(iconColorsCollapsed, preferences.state.value)
    assertEquals(iconColorsCollapsed, store.open().state.value)
    assertEquals(originalValues + mapOf("monet_palettes_expanded" to true,
      "monet_icon_colors_expanded" to false), store.values)
  }

  @Test fun displayControlsUseDefaultsWithoutMigratingExistingChoices() {
    val store = Store(legacyValues())
    val before = store.values.toMap()
    val state = store.open().state.value

    assertEquals(100, state.appScalePercent)
    assertEquals(true, state.squircleShapes)
    assertEquals(true, state.scrollEndHaptic)
    assertEquals(AppTopBarBlur.OFF, state.topBarBlur)
    assertEquals(before, store.values)
    assertEquals(AppTopBarBlur.PROGRESSIVE, Store(mutableMapOf()).open().state.value.topBarBlur)
  }

  @Test fun scaleAndDisabledEffectsRestoreWithOtherAppearancePreferences() {
    val store = Store(legacyValues())
    val initial = store.open().state.value
    val preferences = store.open()
    preferences.setSquircleShapes(false)
    preferences.setScrollEndHaptic(false)
    preferences.setAppScalePercent(107)
    val expected = initial.copy(squircleShapes = false, scrollEndHaptic = false, appScalePercent = 107)

    assertEquals(expected, preferences.state.value)
    assertEquals(expected, store.open().state.value)
  }

  @Test fun scaleStaysWithinSupportedRangeWhenRestoredAndSaved() {
    val store = Store(mutableMapOf("ui_scale_percent" to 70))
    val preferences = store.open()
    assertEquals(80, preferences.state.value.appScalePercent)
    assertEquals(70, store.values["ui_scale_percent"])

    preferences.setAppScalePercent(120)
    assertEquals(110, preferences.state.value.appScalePercent)
    assertEquals(110, store.open().state.value.appScalePercent)
  }

  @Test fun missingStyleDefaultsToOs4GlassWithoutWritingPreferences() {
    assertEquals(AppNavigationBarStyle.OS4_GLASS, AppPreferencesState().navigationBarStyle)
    for (values in listOf(mutableMapOf<String, Any>(), legacyValues())) {
      val store = Store(values)
      val before = store.values.toMap()

      assertEquals(AppNavigationBarStyle.OS4_GLASS, store.open().state.value.navigationBarStyle)
      assertEquals(before, store.values)
      assertFalse(store.values.containsKey("navigation_bar_style"))
    }
  }

  @Test fun bothStylesRestoreWithoutOverwritingOtherPreferences() {
    for (style in AppNavigationBarStyle.entries) {
      val store = Store(legacyValues().apply { put("navigation_bar_style", style.name) })
      val before = store.values.toMap()

      assertEquals(style, store.open().state.value.navigationBarStyle)
      assertEquals(before, store.values)
    }
  }

  @Test fun changesUpdateStateImmediatelyAndPersistAcrossInstancesPreservingOtherKeys() {
    val store = Store(legacyValues())
    val originalValues = store.values.toMap()
    val originalState = store.open().state.value
    var preferences = store.open()

    for (style in listOf(AppNavigationBarStyle.OS4_GLASS, AppNavigationBarStyle.IOS_LIKE,
        AppNavigationBarStyle.OS4_GLASS)) {
      preferences.setNavigationBarStyle(style)
      assertEquals(originalState.copy(navigationBarStyle = style), preferences.state.value)
      assertEquals(originalValues + ("navigation_bar_style" to style.name), store.values)
      preferences = store.open()
      assertEquals(originalState.copy(navigationBarStyle = style), preferences.state.value)
    }
  }

  @Test fun unknownStylesFallBackWithoutRewritingStorageAndCanBeReplaced() {
    for (name in listOf("FUTURE_STYLE", "", "ios_like", " OS4_GLASS ")) {
      val store = Store(legacyValues().apply { put("navigation_bar_style", name) })
      val before = store.values.toMap()
      val preferences = store.open()

      assertEquals(AppNavigationBarStyle.OS4_GLASS, preferences.state.value.navigationBarStyle)
      assertEquals(before, store.values)
      preferences.setNavigationBarStyle(AppNavigationBarStyle.OS4_GLASS)
      assertEquals(AppNavigationBarStyle.OS4_GLASS, preferences.state.value.navigationBarStyle)
      assertEquals(AppNavigationBarStyle.OS4_GLASS, store.open().state.value.navigationBarStyle)
      assertEquals(before + ("navigation_bar_style" to AppNavigationBarStyle.OS4_GLASS.name), store.values)
    }
  }

  private fun legacyValues(): MutableMap<String, Any> = mutableMapOf(
    "appearance" to AppAppearance.DARK.name,
    "ui_color_mode" to AppColorMode.MONET.name,
    "remember_menus" to false,
    "icon_previews" to false,
    "top_bar_blur" to AppTopBarBlur.OFF.name,
    "coloros_component_com.example.app" to true,
    "browser_show_system" to true,
    "pack_sort_order" to 2,
    "unrelated_key" to "keep",
  )

  private class Store(val values: MutableMap<String, Any>) {
    private val preferences = Proxy.newProxyInstance(
      SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
      when (method.name) {
        "getAll" -> values.toMap()
        "getString", "getBoolean", "getLong", "getInt" -> values[args!![0] as String] ?: args[1]
        "contains" -> values.containsKey(args!![0] as String)
        "edit" -> editor()
        else -> error("Unexpected preference call: ${method.name}")
      }
    } as SharedPreferences

    fun open() = AppPreferences(preferences, SystemIconFallback.entries)

    private fun editor(): SharedPreferences.Editor {
      val pending = mutableMapOf<String, Any>()
      return Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
      ) { editor, method, args ->
        when (method.name) {
          "putString", "putBoolean", "putInt" -> {
            pending[args!![0] as String] = requireNotNull(args[1])
            editor
          }
          "apply" -> {
            values.putAll(pending)
            null
          }
          else -> error("Unexpected editor call: ${method.name}")
        }
      } as SharedPreferences.Editor
    }
  }
}
