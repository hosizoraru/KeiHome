package os.kei.hyper.home.ui.main

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.data.LauncherGlassPreferences
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.data.SearchGlassStyle
import os.kei.hyper.home.status.FrameworkConnection

class LauncherGlassControllerTest {
  @Test fun readAndSavesPublishBothFieldsFromTheCurrentRemotePair() = withController { remote, settings ->
    settings.read()
    assertEquals(LauncherGlassStyle.LIGHT, settings.state.value.saved)
    assertEquals(SearchGlassStyle.DARK, settings.state.value.savedSearch)
    remote.values[LauncherGlassPreferences.SEARCH_KEY] = SearchGlassStyle.SYSTEM.nativeValue
    settings.save(LauncherGlassStyle.DARK)
    settings.awaitSave()
    assertEquals(LauncherGlassStyle.DARK, settings.state.value.saved)
    assertEquals(SearchGlassStyle.SYSTEM, settings.state.value.savedSearch)
    remote.values[LauncherGlassPreferences.KEY] = LauncherGlassStyle.LIGHT.nativeValue
    settings.saveSearch(SearchGlassStyle.FOLLOW_DOCK)
    settings.awaitSave()
    assertEquals(LauncherGlassStyle.LIGHT, settings.state.value.saved)
    assertEquals(SearchGlassStyle.FOLLOW_DOCK, settings.state.value.savedSearch)
    assertNull(settings.state.value.pending)
    assertNull(settings.state.value.pendingSearch)
  }

  @Test fun saveBeforeInitialReadUsesThePersistedOtherField() = withController { _, settings ->
    settings.saveSearch(SearchGlassStyle.SYSTEM)
    settings.awaitSave()
    assertEquals(LauncherGlassStyle.LIGHT, settings.state.value.saved)
    assertEquals(SearchGlassStyle.SYSTEM, settings.state.value.savedSearch)
  }

  @Test fun dockAndSearchSavesBlockEachOtherWhilePending() = withController { remote, settings ->
    settings.saveSearch(SearchGlassStyle.SYSTEM)
    settings.save(LauncherGlassStyle.DARK)
    assertEquals(SearchGlassStyle.SYSTEM, settings.state.value.pendingSearch)
    assertNull(settings.state.value.pending)
    settings.awaitSave()
    assertEquals(1, remote.commits)
    assertEquals(LauncherGlassStyle.LIGHT, settings.state.value.saved)
    settings.save(LauncherGlassStyle.DARK)
    settings.saveSearch(SearchGlassStyle.LIGHT)
    settings.awaitSave()
    assertEquals(2, remote.commits)
    assertEquals(LauncherGlassStyle.DARK, settings.state.value.saved)
    assertEquals(SearchGlassStyle.SYSTEM, settings.state.value.savedSearch)
  }

  @Test fun failedSearchCommitRetainsPairAndPendingChoiceUntilTheSameFieldRetries() =
    withController { remote, settings ->
      settings.read()
      remote.commitSucceeds = false
      settings.saveSearch(SearchGlassStyle.SYSTEM)
      settings.awaitSave()
      assertTrue(settings.state.value.failed)
      assertEquals(SearchGlassStyle.SYSTEM, settings.state.value.pendingSearch)
      assertEquals(SearchGlassStyle.DARK, settings.state.value.savedSearch)
      settings.read()
      assertEquals(SearchGlassStyle.DARK, settings.state.value.savedSearch)
      remote.commitSucceeds = true
      settings.save(LauncherGlassStyle.DARK)
      settings.awaitSave()
      assertTrue(settings.state.value.failed)
      assertEquals(LauncherGlassStyle.LIGHT, settings.state.value.saved)
      assertEquals(SearchGlassStyle.DARK, settings.state.value.savedSearch)
      settings.saveSearch(SearchGlassStyle.SYSTEM)
      settings.awaitSave()
      assertFalse(settings.state.value.failed)
      assertEquals(LauncherGlassStyle.DARK, settings.state.value.saved)
      assertEquals(SearchGlassStyle.SYSTEM, settings.state.value.savedSearch)
      assertNull(settings.state.value.pending)
      assertNull(settings.state.value.pendingSearch)
    }

  @Test fun successfulCommitWithFailedReadbackCannotPublishEitherField() = withController { remote, settings ->
    settings.read()
    remote.afterCommit = { remote.failReads = true }
    settings.save(LauncherGlassStyle.DARK)
    settings.awaitSave()
    assertTrue(settings.state.value.failed)
    assertEquals(LauncherGlassStyle.LIGHT, settings.state.value.saved)
    assertEquals(SearchGlassStyle.DARK, settings.state.value.savedSearch)
    assertEquals(LauncherGlassStyle.DARK, settings.state.value.pending)
    remote.failReads = false
    remote.afterCommit = {}
    settings.save(LauncherGlassStyle.DARK)
    settings.awaitSave()
    assertFalse(settings.state.value.failed)
    assertEquals(LauncherGlassStyle.DARK, settings.state.value.saved)
  }

  @Test fun replacedServiceCannotReceiveAQueuedSaveOrPublishItsResult() = withController { remote, settings ->
    settings.saveSearch(SearchGlassStyle.SYSTEM)
    val replacement = Remote()
    FrameworkConnection.onServiceBind(replacement.service)
    settings.reset()
    try {
      testScheduler.runCurrent()
      assertEquals(0, remote.commits)
      assertEquals(0, replacement.commits)
      assertEquals(LauncherGlassUiState(), settings.state.value)
    } finally {
      FrameworkConnection.onServiceDied(replacement.service)
    }
  }

  private fun withController(block: suspend TestScope.(Remote, LauncherGlassSettings) -> Unit) = runTest {
    val remote = Remote()
    FrameworkConnection.onServiceBind(remote.service)
    try {
      block(remote, LauncherGlassSettings(this) { true })
    } finally {
      FrameworkConnection.onServiceDied(remote.service)
    }
  }

  private suspend fun LauncherGlassSettings.awaitSave() {
    withContext(Dispatchers.Default) {
      withTimeout(5_000) { state.first { !it.saving } }
    }
  }

  private class Remote {
    val values = mutableMapOf<String, Any>(
      LauncherGlassPreferences.KEY to LauncherGlassStyle.LIGHT.nativeValue,
      LauncherGlassPreferences.SEARCH_KEY to SearchGlassStyle.DARK.nativeValue,
    )
    var commits = 0
    var commitSucceeds = true
    var failReads = false
    var afterCommit: () -> Unit = {}
    private val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
      arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
      when (method.name) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        "getAll" -> {
          check(!failReads) { "Readback unavailable" }
          values.toMap()
        }
        "edit" -> editor()
        else -> error("Unexpected preference call: ${method.name}")
      }
    } as SharedPreferences

    // Supply the real service wrapper with an in-memory preference cache, without Android Binder.
    val service: XposedService = run {
      val binderType = Class.forName("io.github.libxposed.service.IXposedService")
      val binder = Proxy.newProxyInstance(binderType.classLoader, arrayOf(binderType)) { _, method, _ ->
        when (method.name) {
          "getApiVersion" -> 102
          "getFrameworkProperties" -> XposedService.PROP_CAP_REMOTE
          else -> error("Unexpected service call: ${method.name}")
        }
      }
      val constructor = XposedService::class.java.getDeclaredConstructor(binderType)
      constructor.isAccessible = true
      (constructor.newInstance(binder) as XposedService).also { service ->
        val cache = XposedService::class.java.getDeclaredField("mRemotePrefs")
        cache.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (cache.get(service) as MutableMap<String, SharedPreferences>)[LauncherGlassPreferences.GROUP] = preferences
      }
    }

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
            afterCommit()
            commitSucceeds
          }
          else -> error("Unexpected editor call: ${method.name}")
        }
      } as SharedPreferences.Editor
    }
  }
}
