package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheReminderBudgetTest {
  @Test fun thresholdsUseDecimalBytesAndInclusiveCustomBounds() {
    assertEquals(520_000_000L, CacheReminderBudget.DEFAULT_BYTES)
    assertEquals(50_000_000L, CacheReminderBudget.MIN_BYTES)
    assertEquals(20_000_000_000L, CacheReminderBudget.MAX_BYTES)
    for (bytes in listOf(50_000_000L, 750_123_456L, 20_000_000_000L)) {
      assertTrue(CacheReminderBudget.isValidCustom(bytes))
    }
    for (bytes in invalidCustomValues() + 0L) {
      assertFalse(CacheReminderBudget.isValidCustom(bytes))
    }
  }

  @Test fun automaticModeUsesAFixed520MBThreshold() {
    assertEquals(520_000_000L, CacheReminderBudget.resolve(0L))
  }

  @Test fun customBudgetIsPreservedWithoutRounding() {
    for (custom in listOf(50_000_000L, 750_123_456L, 20_000_000_000L)) {
      assertEquals(custom, CacheReminderBudget.resolve(custom))
    }
  }

  @Test fun invalidCustomBudgetsResolveAsAutomatic() {
    for (custom in invalidCustomValues()) {
      assertEquals(520_000_000L, CacheReminderBudget.resolve(custom))
    }
  }

  @Test fun missingPreferenceDefaultsToAutomaticWithoutWritingStorage() {
    val store = Store()
    assertEquals(0L, AppPreferencesState().cacheReminderBytes)
    assertEquals(0L, store.open().state.value.cacheReminderBytes)
    assertTrue(store.values.isEmpty())
  }

  @Test fun validStoredBudgetsRestoreExactlyIncludingAutomaticAndBounds() {
    for (bytes in listOf(0L, 50_000_000L, 750_123_456L, 20_000_000_000L)) {
      val store = Store(mutableMapOf("cache_reminder_bytes" to bytes))
      assertEquals(bytes, store.open().state.value.cacheReminderBytes)
      assertEquals(mapOf("cache_reminder_bytes" to bytes), store.values)
    }
  }

  @Test fun invalidStoredBudgetsFallBackToAutomaticWithoutRewritingStorage() {
    for (bytes in invalidCustomValues()) {
      val store = Store(mutableMapOf("cache_reminder_bytes" to bytes))
      val preferences = store.open()
      assertEquals(0L, preferences.state.value.cacheReminderBytes)
      assertEquals(520_000_000L,
        CacheReminderBudget.resolve(preferences.state.value.cacheReminderBytes))
      assertEquals(mapOf("cache_reminder_bytes" to bytes), store.values)
    }
  }

  @Test fun setterUpdatesStateAndPersistsAcrossInstancesPreservingOtherPreferences() {
    val legacy = mapOf<String, Any>(
      "appearance" to AppAppearance.DARK.name,
      "coloros_component_com.example.app" to true,
      "unrelated_key" to "keep",
    )
    val store = Store(legacy.toMutableMap())
    var preferences = store.open()
    val initialState = preferences.state.value

    for (bytes in listOf(50_000_000L, 750_123_456L, 20_000_000_000L, 0L)) {
      preferences.setCacheReminderBytes(bytes)
      assertEquals(initialState.copy(cacheReminderBytes = bytes), preferences.state.value)
      assertEquals(legacy + ("cache_reminder_bytes" to bytes), store.values)
      preferences = store.open()
      assertEquals(initialState.copy(cacheReminderBytes = bytes), preferences.state.value)
    }
  }

  @Test fun setterRejectsInvalidInputBeforeChangingStateOrStorage() {
    val store = Store()
    val preferences = store.open()
    preferences.setCacheReminderBytes(750_123_456L)
    val beforeState = preferences.state.value
    val beforeValues = store.values.toMap()

    for (bytes in invalidCustomValues()) {
      assertThrows(IllegalArgumentException::class.java) {
        preferences.setCacheReminderBytes(bytes)
      }
      assertEquals(beforeState, preferences.state.value)
      assertEquals(beforeValues, store.values)
    }
  }

  private fun invalidCustomValues() =
    listOf(Long.MIN_VALUE, -1L, 1L, 49_999_999L, 20_000_000_001L, Long.MAX_VALUE)

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

    fun open() = AppPreferences(preferences, SystemIconFallback.entries)

    private fun editor(): SharedPreferences.Editor {
      val pending = mutableMapOf<String, Any>()
      return Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
      ) { editor, method, args ->
        when (method.name) {
          "putLong" -> {
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
