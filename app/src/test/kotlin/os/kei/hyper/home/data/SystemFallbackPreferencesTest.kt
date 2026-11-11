package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SystemFallbackPreferencesTest {
  @Test fun blurModesRestoreLegacyNamesAndPersistOpaqueModeWithoutChangingOtherPreferences() {
    for (mode in AppTopBarBlur.entries) {
      val store = Store(mutableMapOf("top_bar_blur" to mode.name, "remember_menus" to false))
      val preferences = store.open()
      assertEquals(mode, preferences.state.value.topBarBlur)
      preferences.setTopBarBlur(AppTopBarBlur.OFF)
      assertEquals(AppTopBarBlur.OFF, store.open().state.value.topBarBlur)
      assertEquals(false, store.values["remember_menus"])
    }
    assertEquals(AppTopBarBlur.PROGRESSIVE, Store().open().state.value.topBarBlur)
    assertEquals(AppTopBarBlur.PROGRESSIVE,
      Store(mutableMapOf("top_bar_blur" to "FUTURE_MODE")).open().state.value.topBarBlur)
  }

  @Test fun freshPreferencesKeepPerfectExpansionOff() {
    val preferences = Store().open()
    assertEquals(SystemIconFallback.DEFAULT, preferences.state.value.systemIconFallback)
    assertFalse(preferences.state.value.perfectFallbackExpanded)
  }

  @Test fun compactPackHintsDefaultOffWithoutWritingToLegacyPreferences() {
    val store = Store(mutableMapOf("remember_menus" to false, "icon_previews" to false))
    val before = store.values.toMap()
    assertFalse(AppPreferencesState().compactPackHints)
    assertFalse(store.open().state.value.compactPackHints)
    assertEquals(before, store.values)
  }

  @Test fun compactPackHintsRestoreBothSavedChoicesWithoutOverwritingStorage() {
    for (enabled in listOf(false, true)) {
      val store = Store(mutableMapOf("compact_pack_hints" to enabled))
      assertEquals(enabled, store.open().state.value.compactPackHints)
      assertEquals(mapOf("compact_pack_hints" to enabled), store.values)
    }
  }

  @Test fun compactPackHintsTogglePersistsAcrossInstancesAndPreservesOtherSettings() {
    val store = Store(mutableMapOf(
      "icon_previews" to false,
      "remember_menus" to false,
      "coloros_component_com.example.app" to true,
      "system_icon_fallback" to SystemIconFallback.CLASSIC.name,
    ))
    val originalValues = store.values.toMap()
    val originalState = store.open().state.value
    var preferences = store.open()
    for (enabled in listOf(true, false, true)) {
      preferences.setCompactPackHints(enabled)
      assertEquals(originalState.copy(compactPackHints = enabled), preferences.state.value)
      assertEquals(originalValues + ("compact_pack_hints" to enabled), store.values)
      preferences = store.open()
      assertEquals(originalState.copy(compactPackHints = enabled), preferences.state.value)
    }
  }

  @Test fun legacySelectionsRestoreWhenBundledAndDegradeWithoutOverwritingSavedChoice() {
    for (fallback in SystemIconFallback.entries) {
      val store = Store(mutableMapOf("system_icon_fallback" to fallback.name))
      assertEquals(fallback, store.open().state.value.systemIconFallback)
      assertEquals(SystemIconFallback.DEFAULT, store.open(listOf(SystemIconFallback.DEFAULT)).state.value.systemIconFallback)
      assertEquals(fallback.name, store.values["system_icon_fallback"])
    }
  }

  @Test fun customPerfectSelectionsRoundTripWithoutRequiringLocalResources() {
    val store = Store()
    for (style in listOf(SystemIconFallback.DEFAULT_PERFECT_STYLE, "OriginOS", "\u81ea\u5b9a\u4e49")) {
      val fallback = SystemIconFallback.perfect(style)
      val preferences = store.open()
      preferences.setSystemIconFallback(fallback)
      assertEquals(fallback, preferences.state.value.systemIconFallback)
      assertEquals(fallback.name, store.values["system_icon_fallback"])
      assertEquals(fallback, store.open().state.value.systemIconFallback)
    }
  }

  @Test fun expansionPersistsAndCollapsingNeverChangesTheSavedStyle() {
    val store = Store()
    val preferences = store.open()
    val selected = SystemIconFallback.perfect("OriginOS")
    preferences.setSystemIconFallback(selected)
    preferences.setPerfectFallbackExpanded(true)
    assertTrue(store.open().state.value.perfectFallbackExpanded)
    val reopened = store.open()
    reopened.setPerfectFallbackExpanded(false)
    assertFalse(store.open().state.value.perfectFallbackExpanded)
    assertEquals(selected, reopened.state.value.systemIconFallback)
    assertEquals(selected, store.open().state.value.systemIconFallback)
  }

  @Test fun invalidSavedValuesUseDefaultWithoutOverwritingStorage() {
    for (name in listOf("UNKNOWN", "PERFECT:", "PERFECT:../style", "PERFECT:..", "PERFECT:\n")) {
      val store = Store(mutableMapOf("system_icon_fallback" to name))
      assertEquals(SystemIconFallback.DEFAULT, store.open().state.value.systemIconFallback)
      assertEquals(name, store.values["system_icon_fallback"])
    }
  }

  @Test fun setterRejectsUnavailableBundledChoicesWithoutWriting() {
    val store = Store()
    val preferences = store.open(listOf(SystemIconFallback.DEFAULT))
    val invalid = listOf(SystemIconFallback.CLASSIC, SystemIconFallback.BOUNDLESS)
    for (value in invalid) {
      assertThrows(IllegalArgumentException::class.java) { preferences.setSystemIconFallback(value) }
      assertEquals(SystemIconFallback.DEFAULT, preferences.state.value.systemIconFallback)
      assertFalse(store.values.containsKey("system_icon_fallback"))
    }
  }

  @Test fun perfectFactoryRejectsUnsafeStyles() {
    for (style in listOf("", " ", ".", "..", "../style", "style\\child", "\n", "x".repeat(129))) {
      assertThrows(IllegalArgumentException::class.java) { SystemIconFallback.perfect(style) }
    }
  }

  @Test fun sharedPreferencesKeysRestoreComponentsAndClearOnlyRememberedMenus() {
    val store = Store(mutableMapOf(
      "browser_show_system" to true,
      "pack_sort_order" to 2,
      "coloros_component_com.example.app" to true,
      "perfect_fallback_expanded" to true,
    ))
    val preferences = store.open()
    assertEquals(mapOf("com.example.app" to true), preferences.state.value.colorOsComponents)
    preferences.setRememberMenuSelections(false)
    assertFalse(store.values.containsKey("browser_show_system"))
    assertFalse(store.values.containsKey("pack_sort_order"))
    assertEquals(true, store.values["coloros_component_com.example.app"])
    assertTrue(store.open().state.value.perfectFallbackExpanded)
    assertFalse(store.open().state.value.rememberMenuSelections)
  }

  private class Store(val values: MutableMap<String, Any> = mutableMapOf()) {
    private val preferences = Proxy.newProxyInstance(
      SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
      when (method.name) {
        "getAll" -> values.toMap()
        "getString", "getBoolean", "getLong" -> values[args!![0] as String] ?: args[1]
        "contains" -> values.containsKey(args!![0] as String)
        "edit" -> editor()
        else -> error("Unexpected preference call: ${method.name}")
      }
    } as SharedPreferences

    fun open(bundled: List<SystemIconFallback> = SystemIconFallback.entries) =
      AppPreferences(preferences, bundled)

    private fun editor(): SharedPreferences.Editor {
      val pending = mutableMapOf<String, Any?>()
      return Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
      ) { editor, method, args ->
        when (method.name) {
          "putString", "putBoolean" -> {
            pending[args!![0] as String] = requireNotNull(args[1])
            editor
          }
          "remove" -> {
            pending[args!![0] as String] = null
            editor
          }
          "apply" -> {
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            null
          }
          else -> error("Unexpected editor call: ${method.name}")
        }
      } as SharedPreferences.Editor
    }
  }
}
