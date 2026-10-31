package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEffectsPreferencesTest {
  @Test
  fun defaultsFalseAndStoresOnlyTheProtocolKey() = runBlocking {
    val remote = Preferences()

    assertEquals("theme_effects", ThemeEffectsPreferences.GROUP)
    assertEquals("preserve_system_materials", ThemeEffectsPreferences.KEY)
    assertFalse(ThemeEffectsPreferences.read(remote.preferences))
    ThemeEffectsPreferences.save(remote.preferences, true)

    assertTrue(ThemeEffectsPreferences.read(remote.preferences))
    assertEquals(mapOf(ThemeEffectsPreferences.KEY to true), remote.values)
    assertEquals(1, remote.commits)
    remote.values[ThemeEffectsPreferences.KEY] = false
    assertFalse(ThemeEffectsPreferences.read(remote.preferences))
  }

  @Test
  fun failedCommitIsNotConfirmedByAReadAndCanBeRetried() {
    val remote = Preferences(commitSucceeds = false)

    assertThrows(IllegalStateException::class.java) {
      ThemeEffectsPreferences.saveBlocking(remote.preferences, true)
    }
    assertEquals(mapOf(ThemeEffectsPreferences.KEY to true), remote.values)
    assertThrows(IllegalStateException::class.java) {
      ThemeEffectsPreferences.readBlocking(remote.preferences)
    }
    assertFalse(ThemeEffectsPreferences.readBlocking(Preferences().preferences))

    remote.commitSucceeds = true
    ThemeEffectsPreferences.saveBlocking(remote.preferences, true)
    assertTrue(ThemeEffectsPreferences.readBlocking(remote.preferences))
    assertEquals(2, remote.commits)
  }

  @Test
  fun explicitOffCommitRecoversTheSameQuarantinedInstance() {
    val remote = Preferences()
    ThemeEffectsPreferences.saveBlocking(remote.preferences, true)
    remote.commitSucceeds = false
    assertThrows(IllegalStateException::class.java) {
      ThemeEffectsPreferences.saveBlocking(remote.preferences, false)
    }

    remote.commitSucceeds = true
    assertThrows(IllegalStateException::class.java) {
      ThemeEffectsPreferences.readBlocking(remote.preferences)
    }
    assertEquals(2, remote.commits)
    // The cached value is already false; recovery still requires a new commit.
    assertEquals(false, remote.values[ThemeEffectsPreferences.KEY])
    ThemeEffectsPreferences.saveBlocking(remote.preferences, false)
    assertEquals(3, remote.commits)
    assertFalse(ThemeEffectsPreferences.readBlocking(remote.preferences))
  }

  private fun ThemeEffectsPreferences.saveBlocking(
    preferences: SharedPreferences,
    enabled: Boolean,
  ) = runBlocking { save(preferences, enabled) }

  private fun ThemeEffectsPreferences.readBlocking(preferences: SharedPreferences) =
    runBlocking { read(preferences) }

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
          "toString" -> "ThemeEffectsPreferencesTest"
          "getAll" -> values.toMap()
          "contains" -> values.containsKey(args!![0] as String)
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
