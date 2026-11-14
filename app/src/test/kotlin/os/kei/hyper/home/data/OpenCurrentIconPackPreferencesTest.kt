package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCurrentIconPackPreferencesTest {
  @Test fun stateDefaultsToOpeningTheCurrentIconPack() {
    assertTrue(AppPreferencesState().openCurrentIconPack)
  }

  @Test fun missingKeyDefaultsOnForNewAndExistingPreferencesWithoutWriting() {
    for (values in listOf(mutableMapOf<String, Any>(), existingValues())) {
      val store = Store(values)
      val original = store.values.toMap()

      assertTrue(store.open().state.value.openCurrentIconPack)
      assertTrue(store.open().state.value.openCurrentIconPack)
      assertEquals(original, store.values)
      assertFalse(store.values.containsKey(KEY))
      assertEquals(0, store.writes)
    }
  }

  @Test fun explicitFalseAndTrueRestoreWithoutOverwritingExistingPreferences() {
    for (value in listOf(false, true)) {
      val store = Store(existingValues().apply { put(KEY, value) })
      val original = store.values.toMap()
      val expected = Store(existingValues()).open().state.value.copy(openCurrentIconPack = value)

      assertEquals(expected, store.open().state.value)
      assertEquals(original, store.values)
      assertEquals(0, store.writes)
    }
  }

  @Test fun falseAndTrueUpdateImmediatelyAndPersistAcrossInstancesPreservingAllOtherState() {
    val store = Store(existingValues())
    val originalValues = store.values.toMap()
    var preferences = store.open()
    val originalState = preferences.state.value

    for ((index, value) in listOf(false, true, false, true).withIndex()) {
      preferences.setOpenCurrentIconPack(value)
      val expected = originalState.copy(openCurrentIconPack = value)
      assertEquals(expected, preferences.state.value)
      assertEquals(originalValues + (KEY to value), store.values)
      assertEquals(index + 1, store.writes)

      preferences = store.open()
      assertEquals(expected, preferences.state.value)
      assertEquals(originalValues + (KEY to value), store.values)
      assertEquals(index + 1, store.writes)
    }
  }

  private fun existingValues(): MutableMap<String, Any> = mutableMapOf(
    "appearance" to AppAppearance.DARK.name,
    "ui_color_mode" to AppColorMode.MONET.name,
    "auto_apply_icons" to false,
    "installed_icons_only" to true,
    "remember_menus" to false,
    "icon_previews" to false,
    "top_bar_blur" to AppTopBarBlur.OFF.name,
    "navigation_bar_style" to AppNavigationBarStyle.IOS_LIKE.name,
    PreservedIcons.KEY to setOf("com.example.saved"),
    "coloros_component_com.example.app" to true,
    "pack_sort_order" to 2,
    "unrelated_key" to "keep",
  )

  private class Store(val values: MutableMap<String, Any>) {
    var writes = 0
      private set

    private val preferences = Proxy.newProxyInstance(
      SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
      when (method.name) {
        "getAll" -> values.toMap()
        "getString", "getBoolean", "getLong", "getStringSet" -> values[args!![0] as String] ?: args[1]
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
          "putBoolean" -> {
            pending[args!![0] as String] = requireNotNull(args[1])
            editor
          }
          "apply" -> {
            values.putAll(pending)
            writes++
            null
          }
          else -> error("Unexpected editor call: ${method.name}")
        }
      } as SharedPreferences.Editor
    }
  }

  private companion object {
    const val KEY = "open_current_icon_pack"
  }
}
