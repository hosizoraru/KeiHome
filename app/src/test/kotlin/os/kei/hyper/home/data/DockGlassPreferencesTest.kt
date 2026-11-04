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

  @Test fun bothStylesRoundTripAndOffOnPreservesTheSelectedStyle() = runBlocking {
    val remote = Preferences()
    for (style in DockGlassCornerStyle.entries) {
      for (enabled in listOf(true, false, true)) {
        val choice = DockGlassChoice(enabled, style)
        DockGlassPreferences.save(remote.preferences, choice)
        assertEquals(enabled, remote.values[DockGlassPreferences.KEY])
        assertEquals(style.nativeValue, remote.values[DockGlassPreferences.CORNER_STYLE_KEY])
        assertEquals(choice, DockGlassPreferences.readConfirmed(remote.preferences))
        val reopened = Preferences().also { it.values.putAll(remote.values) }
        assertEquals(choice, DockGlassPreferences.readConfirmed(reopened.preferences))
      }
    }
    assertEquals(6, remote.commits)
  }

  @Test fun invalidStoredStylesFallBackToRecentsAndAbiValuesRemainStable() {
    assertEquals(0, DockGlassCornerStyle.ROUNDED.nativeValue)
    assertEquals(1, DockGlassCornerStyle.RECENTS.nativeValue)
    val remote = Preferences()
    for (invalid in listOf(-1, 2, Int.MAX_VALUE)) {
      remote.values[DockGlassPreferences.CORNER_STYLE_KEY] = invalid
      assertEquals(DockGlassCornerStyle.RECENTS, DockGlassPreferences.readChoice(remote.preferences).cornerStyle)
    }
  }

  @Test fun failedCommitRequiresRetryOfTheWholeChoiceEvenIfRemoteCacheAlreadyChanged() {
    val remote = Preferences()
    val initial = DockGlassChoice(true)
    val pending = DockGlassChoice(true, DockGlassCornerStyle.ROUNDED)
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

  private class Preferences(var commitSucceeds: Boolean = true) {
    val values = mutableMapOf<String, Any>()
    var commits = 0
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
          "getAll" -> values.toMap()
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
