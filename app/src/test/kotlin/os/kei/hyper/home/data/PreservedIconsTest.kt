package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreservedIconsTest {
  @Test fun defaultsEmptyWithoutWritingOrChangingOtherSettings() {
    val store = Store()
    val before = store.values.toMap()

    assertTrue(AppPreferencesState().preservedIconPackages.isEmpty())
    assertTrue(store.open().state.value.preservedIconPackages.isEmpty())
    assertTrue(PreservedIcons.read(store.preferences).isEmpty())
    assertEquals(before, store.values)
    assertFalse(store.values.containsKey(PreservedIcons.KEY))
  }

  @Test fun selectionsUpdateImmediatelyRoundTripAndClearWithoutChangingOtherSettings() {
    val store = Store()
    val originalValues = store.values.toMap()
    val originalState = store.open().state.value
    var preferences = store.open()

    for (packages in listOf(setOf("com.example.app", "os.kei.Home"), setOf("com.other.app"), emptySet())) {
      preferences.setPreservedIconPackages(packages)
      assertEquals(originalState.copy(preservedIconPackages = packages), preferences.state.value)
      assertEquals(originalValues + (PreservedIcons.KEY to packages), store.values)
      assertEquals(packages, PreservedIcons.read(store.preferences))
      preferences = store.open()
      assertEquals(originalState.copy(preservedIconPackages = packages), preferences.state.value)
    }
  }

  @Test fun validatesExactAndroidPackageNamesWithoutRepairingOrCompletingSelections() {
    val valid = setOf("com.example.app", "os.kei.Home_2", "a.b", "android", "com.EXAMPLE.App")
    val invalid = listOf(
      "", " ", "com", "Android", ".com.example", "com.example.", "com..example",
      " com.example", "com.example ", "com.example\n", "com.ex ample", "com.example/app",
      "com.example:*", "com.example.*", "com.example-app", "com.1example", "_com.example",
      "com._example", "com.ex\u00e4mple", "com.example\u0000",
    )
    valid.forEach { assertTrue(it, PreservedIcons.isValidPackageName(it)) }
    invalid.forEach { assertFalse(it, PreservedIcons.isValidPackageName(it)) }
    assertEquals(valid, PreservedIcons.normalize(valid + invalid))

    val store = Store()
    store.open().setPreservedIconPackages((valid + invalid).toSet())
    assertEquals(valid, store.values[PreservedIcons.KEY])
    assertEquals(valid, store.open().state.value.preservedIconPackages)
    assertFalse("com.example" in PreservedIcons.read(store.preferences))
  }

  @Test fun malformedStoredEntriesDoNotDiscardValidEntriesOrRewriteStorage() {
    val stored = linkedSetOf<Any?>(
      "com.first.app", "", " com.bad.app", null, 42, "com.second.App", "com.bad..app",
    )
    val store = Store().apply { values[PreservedIcons.KEY] = stored }
    val before = store.values.toMap()
    val expected = setOf("com.first.app", "com.second.App")

    assertEquals(expected, store.open().state.value.preservedIconPackages)
    assertEquals(expected, PreservedIcons.read(store.preferences))
    assertEquals(before, store.values)
  }

  @Test fun wrongStoredTypeFallsBackWithoutPoisoningOtherPreferencesAndCanBeReplaced() {
    for (value in listOf("com.example.app", 42, true)) {
      val store = Store().apply { values[PreservedIcons.KEY] = value }
      val before = store.values.toMap()
      val preferences = store.open()

      assertTrue(preferences.state.value.preservedIconPackages.isEmpty())
      assertTrue(PreservedIcons.read(store.preferences).isEmpty())
      assertEquals(AppAppearance.DARK, preferences.state.value.appearance)
      assertFalse(preferences.state.value.completeSystemIconConfig)
      assertEquals(before, store.values)
      preferences.setPreservedIconPackages(setOf("com.example.app"))
      assertEquals(setOf("com.example.app"), store.open().state.value.preservedIconPackages)
    }
  }

  @Test fun boundsNamesAndNumberOfValidUniquePackagesForReadsAndWrites() {
    val longest = "a." + "b".repeat(PreservedIcons.MAX_PACKAGE_NAME_LENGTH - 2)
    assertTrue(PreservedIcons.isValidPackageName(longest))
    assertFalse(PreservedIcons.isValidPackageName(longest + "b"))
    assertEquals(setOf(longest), PreservedIcons.normalize(listOf(longest, longest + "b")))

    val selected = (0..PreservedIcons.MAX_PACKAGES).map { "com.example.app$it" }
    val expected = selected.take(PreservedIcons.MAX_PACKAGES).toSet()
    val noisy = listOf("", selected.first(), selected.first(), "invalid") + selected
    assertEquals(expected, PreservedIcons.normalize(noisy))
    val store = Store().apply { values[PreservedIcons.KEY] = noisy.toSet() }
    assertEquals(expected, store.open().state.value.preservedIconPackages)
    assertEquals(expected, PreservedIcons.read(store.preferences))
    store.open().setPreservedIconPackages(noisy.toSet())
    assertEquals(expected, store.values[PreservedIcons.KEY])
    assertEquals(expected, store.open().state.value.preservedIconPackages)
  }

  @Test fun setterCopiesInputAndKeepsStateSeparateFromStorage() {
    val store = Store()
    val preferences = store.open()
    val input = mutableSetOf("com.example.app")
    preferences.setPreservedIconPackages(input)
    val state = preferences.state.value.preservedIconPackages
    val stored = store.values.getValue(PreservedIcons.KEY)
    assertNotSame(input, stored)
    assertNotSame(state, stored)

    input.add("com.input.change")
    @Suppress("UNCHECKED_CAST")
    (stored as MutableSet<String>).add("com.storage.change")
    assertEquals(setOf("com.example.app"), state)
    assertImmutable(state)
    assertEquals(setOf("com.example.app", "com.storage.change"), PreservedIcons.read(store.preferences))
  }

  @Test fun restoredStateAndSnapshotsAreImmutableIndependentCopies() {
    val input = mutableSetOf("com.example.app")
    val store = Store().apply { values[PreservedIcons.KEY] = input }
    val state = store.open().state.value.preservedIconPackages
    val snapshot = PreservedIcons.read(store.preferences)
    input.add("com.later.app")

    assertEquals(setOf("com.example.app"), state)
    assertEquals(setOf("com.example.app"), snapshot)
    assertImmutable(state)
    assertImmutable(snapshot)
    assertEquals(setOf("com.example.app", "com.later.app"), PreservedIcons.read(store.preferences))
  }

  private fun assertImmutable(packages: Set<String>) {
    assertThrows(UnsupportedOperationException::class.java) {
      (packages as MutableSet<String>).add("com.mutated.app")
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (packages as MutableSet<String>).clear()
    }
  }

  private class Store {
    val values = mutableMapOf<String, Any>(
      "appearance" to AppAppearance.DARK.name,
      "complete_system_icon_config" to false,
      "auto_apply_icons" to false,
      "remember_menus" to false,
      "coloros_component_com.example.app" to true,
      "unrelated_key" to "keep",
    )
    val preferences = Proxy.newProxyInstance(
      SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
      when (method.name) {
        "getAll" -> values.toMap()
        "getString", "getBoolean", "getLong" -> values[args!![0] as String] ?: args[1]
        "getStringSet" -> (values[args!![0] as String] ?: args[1]) as Set<*>?
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
          "putStringSet" -> {
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
