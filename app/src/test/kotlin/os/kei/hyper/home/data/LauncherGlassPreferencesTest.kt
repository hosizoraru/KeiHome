package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LauncherGlassPreferencesTest {
  @Test fun defaultAndMalformedValuesFollowWallpaperWithoutWritingOtherThemeChoices() = runBlocking {
    val remote = Preferences()
    assertEquals("theme_effects", LauncherGlassPreferences.GROUP)
    assertEquals("launcher_glass_style", LauncherGlassPreferences.KEY)
    assertEquals(LauncherGlassStyle.SYSTEM, LauncherGlassPreferences.read(null))
    assertEquals(LauncherGlassStyle.SYSTEM, LauncherGlassPreferences.readConfirmed(remote.preferences))
    for (invalid in listOf(-1, 3, Int.MAX_VALUE, true, "2")) {
      remote.values[LauncherGlassPreferences.KEY] = invalid
      assertEquals(LauncherGlassStyle.SYSTEM, LauncherGlassPreferences.read(remote.preferences))
    }
    assertEquals(0, remote.commits)
  }

  @Test fun allThreeAbiValuesPersistAndReopenWithoutChangingDockOrMaterialPreferences() = runBlocking {
    val remote = Preferences()
    remote.values[DockGlassPreferences.KEY] = true
    remote.values[DockGlassPreferences.CORNER_STYLE_KEY] = 1
    remote.values[ThemeEffectsPreferences.KEY] = true
    val unchanged = remote.values.toMap()
    assertEquals(listOf(0, 1, 2), LauncherGlassStyle.entries.map { it.nativeValue })
    assertEquals(listOf(LauncherGlassStyle.SYSTEM, LauncherGlassStyle.LIGHT, LauncherGlassStyle.DARK),
      LauncherGlassStyle.entries)
    for (style in LauncherGlassStyle.entries) {
      LauncherGlassPreferences.save(remote.preferences, style)
      assertEquals(style.nativeValue, remote.values[LauncherGlassPreferences.KEY])
      assertEquals(style, LauncherGlassPreferences.readConfirmed(remote.preferences))
      val reopened = Preferences().also { it.values.putAll(remote.values) }
      assertEquals(style, LauncherGlassPreferences.readConfirmed(reopened.preferences))
      assertEquals(true, remote.values[DockGlassPreferences.KEY])
      assertEquals(1, remote.values[DockGlassPreferences.CORNER_STYLE_KEY])
      assertEquals(true, remote.values[ThemeEffectsPreferences.KEY])
      assertEquals(unchanged, remote.values.filterKeys { it != LauncherGlassPreferences.KEY })
    }
    assertEquals(3, remote.commits)
  }

  @Test fun failedBinderCommitCannotBecomeSavedThroughTheRemoteCache() {
    val remote = Preferences()
    runBlocking { LauncherGlassPreferences.save(remote.preferences, LauncherGlassStyle.LIGHT) }
    remote.commitSucceeds = false
    assertThrows(IllegalStateException::class.java) {
      runBlocking { LauncherGlassPreferences.save(remote.preferences, LauncherGlassStyle.DARK) }
    }
    assertEquals(2, remote.values[LauncherGlassPreferences.KEY])
    assertThrows(IllegalStateException::class.java) {
      runBlocking { LauncherGlassPreferences.readConfirmed(remote.preferences) }
    }
    remote.commitSucceeds = true
    assertThrows(IllegalStateException::class.java) {
      runBlocking { LauncherGlassPreferences.readConfirmed(remote.preferences) }
    }
    runBlocking {
      LauncherGlassPreferences.save(remote.preferences, LauncherGlassStyle.SYSTEM)
      assertEquals(LauncherGlassStyle.SYSTEM, LauncherGlassPreferences.readConfirmed(remote.preferences))
    }
    assertEquals(3, remote.commits)
  }

  private class Preferences {
    val values = mutableMapOf<String, Any>()
    var commits = 0
    var commitSucceeds = true
    val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
      arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
      when (method.name) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        "getAll" -> values.toMap()
        "edit" -> editor()
        else -> error("Unexpected preference call: ${method.name}")
      }
    } as SharedPreferences

    private fun editor(): SharedPreferences.Editor {
      val pending = mutableMapOf<String, Int>()
      return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { editor, method, args ->
        when (method.name) {
          "putInt" -> {
            val call = requireNotNull(args)
            pending[call[0] as String] = call[1] as Int
            editor
          }
          "commit" -> { commits++; values.putAll(pending); commitSucceeds }
          else -> error("Unexpected editor call: ${method.name}")
        }
      } as SharedPreferences.Editor
    }
  }
}
