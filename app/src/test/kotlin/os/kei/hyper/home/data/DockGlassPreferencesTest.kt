package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DockGlassPreferencesTest {
  @Test fun newAndLegacyPreferencesDefaultToRecentsWithoutEnablingDock() = runBlocking {
    val remote = Preferences()
    assertEquals("theme_effects", DockGlassPreferences.GROUP)
    assertEquals(DockGlassChoice(), DockGlassPreferences.readChoice(null))
    assertEquals(DockGlassChoice(), DockGlassPreferences.readConfirmed(remote.preferences))
    remote.values[DockGlassPreferences.KEY] = true
    assertEquals(DockGlassChoice(true), DockGlassPreferences.readConfirmed(remote.preferences))
    assertEquals(0, remote.commits)
  }

  @Test fun allStylesRoundTripAndOffOnPreservesGeometry() = runBlocking {
    val remote = Preferences()
    for (style in DockGlassCornerStyle.entries) {
      for (enabled in listOf(true, false, true)) {
        val choice = DockGlassChoice(enabled, style, verticalPaddingDp = 32, verticalOffsetDp = -48,
          customRadiusDp = 64, horizontalPaddingDeltaDp = 12)
        DockGlassPreferences.save(remote.preferences, choice)
        assertEquals(enabled, remote.values[DockGlassPreferences.KEY])
        assertEquals(style.nativeValue, remote.values[DockGlassPreferences.CORNER_STYLE_KEY])
        assertEquals(choice, DockGlassPreferences.readConfirmed(remote.preferences))
        val reopened = Preferences().also { it.values.putAll(remote.values) }
        assertEquals(choice, DockGlassPreferences.readConfirmed(reopened.preferences))
      }
    }
    assertEquals(DockGlassCornerStyle.entries.size * 3, remote.commits)
  }

  @Test fun invalidStoredStylesFallBackToRecentsAndAbiValuesRemainStable() {
    assertEquals(0, DockGlassCornerStyle.ROUNDED.nativeValue)
    assertEquals(1, DockGlassCornerStyle.RECENTS.nativeValue)
    val remote = Preferences()
    assertEquals(listOf(0, 1, 2, 3, 4), DockGlassCornerStyle.entries.map { it.nativeValue })
    for (invalid in listOf(-1, 5, Int.MAX_VALUE)) {
      remote.values[DockGlassPreferences.CORNER_STYLE_KEY] = invalid
      assertEquals(DockGlassCornerStyle.RECENTS, DockGlassPreferences.readChoice(remote.preferences).cornerStyle)
    }
  }

  @Test fun failedCommitRequiresRetryOfTheWholeChoiceEvenIfRemoteCacheAlreadyChanged() {
    val remote = Preferences()
    val initial = DockGlassChoice(true)
    val pending = DockGlassChoice(true, DockGlassCornerStyle.ROUNDED, 0, 96, 64)
    runBlocking { DockGlassPreferences.save(remote.preferences, initial) }
    remote.commitSucceeds = false
    assertThrows(IllegalStateException::class.java) {
      runBlocking { DockGlassPreferences.save(remote.preferences, pending) }
    }
    assertEquals(0, remote.values[DockGlassPreferences.CORNER_STYLE_KEY])
    assertThrows(IllegalStateException::class.java) {
      runBlocking { DockGlassPreferences.readConfirmed(remote.preferences) }
    }
    remote.commitSucceeds = true
    assertThrows(IllegalStateException::class.java) {
      runBlocking { DockGlassPreferences.readConfirmed(remote.preferences) }
    }
    runBlocking {
      DockGlassPreferences.save(remote.preferences, pending)
      assertEquals(pending, DockGlassPreferences.readConfirmed(remote.preferences))
    }
    assertEquals(3, remote.commits)
  }

  @Test fun invalidGeometryFallsBackAndEveryGeometryKeyTriggersTheListener() {
    val remote = Preferences()
    for (value in listOf(-100, 100, "18")) {
      remote.values[DockGlassPreferences.VERTICAL_PADDING_KEY] = value
      remote.values[DockGlassPreferences.VERTICAL_OFFSET_KEY] = value
      remote.values[DockGlassPreferences.CUSTOM_RADIUS_KEY] = value
      remote.values[DockGlassPreferences.HORIZONTAL_PADDING_KEY] = value
      assertEquals(DockGlassChoice(), DockGlassPreferences.readChoice(remote.preferences))
    }
    for (key in listOf(null, DockGlassPreferences.KEY, DockGlassPreferences.CORNER_STYLE_KEY,
      DockGlassPreferences.VERTICAL_PADDING_KEY, DockGlassPreferences.VERTICAL_OFFSET_KEY,
      DockGlassPreferences.CUSTOM_RADIUS_KEY, DockGlassPreferences.HORIZONTAL_PADDING_KEY)) {
      org.junit.Assert.assertTrue(DockGlassPreferences.isPreferenceKey(key))
    }
    org.junit.Assert.assertFalse(DockGlassPreferences.isPreferenceKey("launcher_glass_style"))
  }

  @Test fun readUsesOneSnapshotForAllFields() {
    val remote = Preferences()
    remote.values.putAll(mapOf(DockGlassPreferences.KEY to true,
      DockGlassPreferences.CORNER_STYLE_KEY to 4,
      DockGlassPreferences.VERTICAL_PADDING_KEY to 0,
      DockGlassPreferences.VERTICAL_OFFSET_KEY to -48,
      DockGlassPreferences.CUSTOM_RADIUS_KEY to 64,
      DockGlassPreferences.HORIZONTAL_PADDING_KEY to -16))
    assertEquals(DockGlassChoice(true, DockGlassCornerStyle.CUSTOM, 0, -48, 64, -16),
      DockGlassPreferences.readChoice(remote.preferences))
    assertEquals(1, remote.snapshots)
  }

  private class Preferences(var commitSucceeds: Boolean = true) {
    val values = mutableMapOf<String, Any>()
    var commits = 0
    var snapshots = 0
    val preferences: SharedPreferences

    init {
      preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
      ) { proxy, method, args ->
        when (method.name) {
          "hashCode" -> System.identityHashCode(proxy)
          "equals" -> proxy === args?.firstOrNull()
          "toString" -> "DockGlassPreferencesTest"
          "getAll" -> { snapshots++; values.toMap() }
          "contains" -> values.containsKey(args!![0] as String)
          "getInt" -> values[args!![0] as String] as? Int ?: args[1] as Int
          "getBoolean" -> {
            val callArgs = args ?: error("Missing getBoolean arguments")
            values[callArgs[0] as String] as? Boolean ?: callArgs[1] as Boolean
          }
          "edit" -> editor()
          else -> error("Unexpected preference call: ${method.name}")
        }
      } as SharedPreferences
    }

    private fun editor(): SharedPreferences.Editor {
      val pending = mutableMapOf<String, Any?>()
      return Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
      ) { editor, method, args ->
        when (method.name) {
          "putInt" -> {
            val callArgs = args ?: error("Missing putInt arguments")
            pending[callArgs[0] as String] = callArgs[1] as Int
            editor
          }
          "putBoolean" -> {
            val callArgs = args ?: error("Missing putBoolean arguments")
            pending[callArgs[0] as String] = callArgs[1] as Boolean
            editor
          }
          "commit" -> {
            commits += 1
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            commitSucceeds
          }
          else -> error("Unexpected editor call: ${method.name}")
        }
      } as SharedPreferences.Editor
    }
  }
}
