package os.kei.hyper.home.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Test

class LauncherGlassPreferencesTest {
  @Test fun legacyChoicesDefaultToFollowingDockWithoutWritingAMigration() = runBlocking {
    assertEquals("launcher_search_glass_style", LauncherGlassPreferences.SEARCH_KEY)
    assertEquals(LauncherGlassChoice(LauncherGlassStyle.SYSTEM), LauncherGlassPreferences.readChoice(null))
    for (dock in LauncherGlassStyle.entries) {
      val remote = Preferences()
      remote.values[LauncherGlassPreferences.KEY] = dock.nativeValue
      val choice = LauncherGlassPreferences.readChoiceConfirmed(remote.preferences)
      assertEquals(LauncherGlassChoice(dock), choice)
      assertEquals(SearchGlassStyle.FOLLOW_DOCK, choice.search)
      assertEquals(dock, choice.resolvedSearch)
      assertEquals(dock.nativeValue or (dock.nativeValue shl 2), choice.nativePolicy)
      assertFalse(remote.values.containsKey(LauncherGlassPreferences.SEARCH_KEY))
      assertEquals(0, remote.commits)
    }
  }

  @Test fun everySearchChoiceResolvesAndPacksWithEveryDockChoice() {
    assertEquals(listOf(-1, 0, 1, 2), SearchGlassStyle.entries.map { it.nativeValue })
    for (dock in LauncherGlassStyle.entries) for (search in SearchGlassStyle.entries) {
      val resolved = if (search == SearchGlassStyle.FOLLOW_DOCK) dock
        else LauncherGlassStyle.from(search.nativeValue)
      val choice = LauncherGlassChoice(dock, search)
      assertEquals(resolved, choice.resolvedSearch)
      assertEquals(dock.nativeValue, choice.nativePolicy and 3)
      assertEquals(resolved.nativeValue, (choice.nativePolicy shr 2) and 3)
    }
  }

  @Test fun malformedSearchValuesFollowDockAndExplicitSystemRemainsIndependent() {
    val remote = Preferences()
    remote.values[LauncherGlassPreferences.KEY] = LauncherGlassStyle.DARK.nativeValue
    for (invalid in listOf(-2, 3, Int.MIN_VALUE, Int.MAX_VALUE, true, "1", 1L)) {
      remote.values[LauncherGlassPreferences.SEARCH_KEY] = invalid
      assertEquals(LauncherGlassChoice(LauncherGlassStyle.DARK),
        LauncherGlassPreferences.readChoice(remote.preferences))
    }
    remote.values[LauncherGlassPreferences.SEARCH_KEY] = 0
    assertEquals(LauncherGlassStyle.SYSTEM, LauncherGlassPreferences.readChoice(remote.preferences).resolvedSearch)
    assertEquals(0, remote.commits)
  }

  @Test fun independentWritesPreserveTheOtherMaterialAndUnrelatedPreferences() = runBlocking {
    val remote = Preferences()
    remote.values["unrelated"] = "retained"
    remote.values[LauncherGlassPreferences.KEY] = LauncherGlassStyle.DARK.nativeValue
    for (search in SearchGlassStyle.entries) {
      LauncherGlassPreferences.saveSearch(remote.preferences, search)
      assertEquals(LauncherGlassChoice(LauncherGlassStyle.DARK, search),
        LauncherGlassPreferences.readChoiceConfirmed(remote.preferences))
      for (dock in LauncherGlassStyle.entries) {
        LauncherGlassPreferences.save(remote.preferences, dock)
        assertEquals(LauncherGlassChoice(dock, search),
          LauncherGlassPreferences.readChoiceConfirmed(remote.preferences))
      }
      assertEquals("retained", remote.values["unrelated"])
    }
    assertEquals(16, remote.commits)
  }

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

  @Test fun successfulOtherFieldWriteCannotClearEitherFieldsFailedCommit() {
    for (failedSearch in listOf(false, true)) {
      val remote = Preferences()
      remote.commitSucceeds = false
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          if (failedSearch) LauncherGlassPreferences.saveSearch(remote.preferences, SearchGlassStyle.LIGHT)
          else LauncherGlassPreferences.save(remote.preferences, LauncherGlassStyle.DARK)
        }
      }
      assertAllReadsUnconfirmed(remote)
      remote.commitSucceeds = true
      runBlocking {
        if (failedSearch) LauncherGlassPreferences.save(remote.preferences, LauncherGlassStyle.DARK)
        else LauncherGlassPreferences.saveSearch(remote.preferences, SearchGlassStyle.LIGHT)
      }
      assertAllReadsUnconfirmed(remote)
      runBlocking {
        if (failedSearch) LauncherGlassPreferences.saveSearch(remote.preferences, SearchGlassStyle.LIGHT)
        else LauncherGlassPreferences.save(remote.preferences, LauncherGlassStyle.DARK)
        assertEquals(LauncherGlassChoice(LauncherGlassStyle.DARK, SearchGlassStyle.LIGHT),
          LauncherGlassPreferences.readChoiceConfirmed(remote.preferences))
        assertEquals(LauncherGlassStyle.DARK, LauncherGlassPreferences.readConfirmed(remote.preferences))
      }
      assertEquals(3, remote.commits)
    }
  }

  @Test fun bothFailedKeysMustBeRetriedAndThrownCommitAlsoPoisonsThePair() {
    val remote = Preferences()
    remote.commitThrows = true
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { LauncherGlassPreferences.saveSearch(remote.preferences, SearchGlassStyle.DARK) }
    }
    remote.commitThrows = false
    remote.commitSucceeds = false
    assertThrows(IllegalStateException::class.java) {
      runBlocking { LauncherGlassPreferences.save(remote.preferences, LauncherGlassStyle.LIGHT) }
    }
    remote.commitSucceeds = true
    runBlocking { LauncherGlassPreferences.saveSearch(remote.preferences, SearchGlassStyle.FOLLOW_DOCK) }
    assertAllReadsUnconfirmed(remote)
    runBlocking {
      LauncherGlassPreferences.save(remote.preferences, LauncherGlassStyle.LIGHT)
      assertEquals(LauncherGlassChoice(LauncherGlassStyle.LIGHT),
        LauncherGlassPreferences.readChoiceConfirmed(remote.preferences))
    }
  }

  private fun assertAllReadsUnconfirmed(remote: Preferences) {
    assertThrows(IllegalStateException::class.java) { LauncherGlassPreferences.read(remote.preferences) }
    assertThrows(IllegalStateException::class.java) { LauncherGlassPreferences.readChoice(remote.preferences) }
    assertThrows(IllegalStateException::class.java) {
      runBlocking { LauncherGlassPreferences.readConfirmed(remote.preferences) }
    }
    assertThrows(IllegalStateException::class.java) {
      runBlocking { LauncherGlassPreferences.readChoiceConfirmed(remote.preferences) }
    }
  }

  private class Preferences {
    val values = mutableMapOf<String, Any>()
    var commits = 0
    var commitSucceeds = true
    var commitThrows = false
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
          "commit" -> {
            commits++
            values.putAll(pending)
            if (commitThrows) throw IllegalArgumentException("Binder failure")
            commitSucceeds
          }
          else -> error("Unexpected editor call: ${method.name}")
        }
      } as SharedPreferences.Editor
    }
  }
}
