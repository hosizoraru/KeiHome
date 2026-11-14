package os.kei.hyper.home.ui.main

import android.app.Application
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.status.StaticScopeUiState
import os.kei.hyper.home.status.planStaticScope
import os.kei.hyper.home.status.readStaticScopeDeclaration
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class ScopeAuthorizationUiTest {
  private val compose = createComposeRule()
  @get:Rule val rules: org.junit.rules.TestRule =
    org.junit.rules.RuleChain.outerRule(ForegroundComposeHostRule()).around(compose)
  private val app = ApplicationProvider.getApplicationContext<Application>()
  private val names = listOf("system", "com.miui.home", "com.android.thememanager",
    "com.android.systemui", "com.android.intentresolver", "com.miui.securitycenter", "com.android.settings")
  private fun ready() = StaticScopeUiState(connected = true,
    plan = planStaticScope(names, names.toSet(), true) { true })

  @Test fun initialBindingAndPlanReadDoNotShowAnUnavailableSummary() {
    val state = mutableStateOf(StaticScopeUiState(checking = true))
    compose.setContent { KeiHomeTheme {
      ScopeAuthorizationPage(state.value, names, emptyMap(), false, false, {}, {}, {})
    } }
    compose.onNodeWithText(app.getString(R.string.runtime_checking)).assertIsDisplayed()
    compose.onNodeWithText(app.getString(R.string.scope_page_disconnected)).assertDoesNotExist()
    compose.runOnIdle { state.value = StaticScopeUiState(connected = true, checking = true) }
    compose.onNodeWithText(app.getString(R.string.runtime_checking)).assertIsDisplayed()
    compose.runOnIdle { state.value = ready() }
    compose.onNodeWithText(app.getString(R.string.scope_page_ready)).assertIsDisplayed()
  }

  @Test fun settingsRouteOpensTheDedicatedPageAndItsToolbar() {
    val store = ViewModelStore()
    val model = ViewModelProvider(store,
      ViewModelProvider.AndroidViewModelFactory(app))[HomeViewModel::class.java]
    try {
      compose.setContent { KeiHomeTheme {
        SettingsDestinationScreen(model, SettingsSection.HOOK.route, {}, {}, {})
      } }
      compose.onNodeWithTag("scope.page.summary").assertIsDisplayed()
      compose.onNodeWithTag("scope.page.refresh").assertIsDisplayed()
      compose.onNodeWithText(app.getString(R.string.scope_check)).assertDoesNotExist()
      compose.onNodeWithTag("scope.page.manager").performScrollTo().assertIsDisplayed()
    } finally { compose.runOnIdle { store.clear() } }
  }

  @Test fun refreshUpdatesVersionNameEvenWhenVersionCodeStaysTheSame() {
    val versions = mutableStateOf(mapOf("com.miui.home" to ScopeTargetVersion("8.01.02.8593", 8593L)))
    var refreshed = 0
    compose.setContent { KeiHomeTheme {
      ScopeAuthorizationPage(ready(), names, versions.value, false, false, {}, {
        refreshed++
        versions.value = mapOf("com.miui.home" to ScopeTargetVersion("8.01.02.8593-r2", 8593L))
      }, {})
    } }
    compose.onNodeWithText(app.getString(R.string.scope_page_ready)).assertIsDisplayed()
    compose.onNodeWithText("8.01.02.8593").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("scope.page.refresh").assertIsDisplayed().performClick()
    compose.onNodeWithText("8.01.02.8593-r2").assertIsDisplayed()
    compose.onNodeWithText("8.01.02.8593").assertDoesNotExist()
    compose.onNodeWithText(app.getString(R.string.scope_check)).assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, refreshed) }
  }

  @Test fun disconnectedAndFailedReadsNeverPresentStaleGrantsAsCurrent() {
    val state = mutableStateOf(ready().copy(connected = false))
    compose.setContent { KeiHomeTheme {
      ScopeAuthorizationPage(state.value, names, emptyMap(), false, false, {}, {}, {})
    } }
    compose.onNodeWithText(app.getString(R.string.scope_page_disconnected)).assertIsDisplayed()
    compose.onAllNodesWithText(app.getString(R.string.scope_granted)).assertCountEquals(0)
    compose.runOnIdle { state.value = ready().copy(readFailed = true) }
    compose.onNodeWithText(app.getString(R.string.scope_page_read_failed)).assertIsDisplayed()
    compose.onAllNodesWithText(app.getString(R.string.scope_granted)).assertCountEquals(0)
  }

  @Test fun absentUnknownAndUnsupportedTargetsHaveDistinctStates() {
    val partial = planStaticScope(names, setOf("system", "com.miui.home"), true) {
      when (it) {
        "com.android.settings" -> false
        "com.miui.securitycenter" -> null
        else -> true
      }
    }
    val state = mutableStateOf(StaticScopeUiState(connected = true, plan = partial))
    compose.setContent { KeiHomeTheme {
      ScopeAuthorizationPage(state.value, names, emptyMap(), false, false, {}, {}, {})
    } }
    compose.onNodeWithTag("scope.target.com.android.settings").performScrollTo()
    compose.onNodeWithText(app.getString(R.string.scope_absent)).assertIsDisplayed()
    compose.onNodeWithText(app.getString(R.string.scope_unknown)).assertExists()
    compose.runOnIdle {
      state.value = ready().copy(plan = planStaticScope(names, names.toSet(), false) { true })
    }
    compose.onNodeWithTag("scope.target.system").performScrollTo()
    compose.onNodeWithText(app.getString(R.string.scope_not_supported)).assertIsDisplayed()
    compose.onNodeWithTag("scope.page.summary").performScrollTo()
    compose.onNodeWithText(app.getString(R.string.scope_page_unsupported)).assertIsDisplayed()
  }

  @Test fun largeTextLongVersionsAndManagerRemainReachable() {
    var managerOpened = 0
    val longVersion = "8.01.02.9239-261013-11081915-RELEASE"
    compose.setContent { KeiHomeTheme {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
        ScopeAuthorizationPage(ready(), names,
          mapOf("com.miui.home" to ScopeTargetVersion(longVersion, 82349332L)),
          false, false, {}, {}, { managerOpened++ })
      }
    } }
    compose.onNodeWithText(longVersion).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("scope.page.manager").performScrollTo()
    compose.onNodeWithText(app.getString(R.string.scope_open_manager)).assertIsDisplayed().performClick()
    compose.onNodeWithText(app.getString(R.string.scope_runtime_note)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("scope.page.refresh").assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, managerOpened) }
  }

  @Test fun versionReaderMatchesInstalledPackagesAndVirtualSystem() = runBlocking {
    val declared = readStaticScopeDeclaration(app)
    val versions = readScopeTargetVersions(app, declared)
    assertEquals(declared.toSet(), versions.keys)
    assertEquals(ScopeTargetVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT.toLong()), versions["system"])
    for (name in declared.filterNot { it == "system" }) {
      val expected = try {
        val info = app.packageManager.getPackageInfo(name, 0)
        ScopeTargetVersion(info.versionName?.trim()?.takeIf(String::isNotEmpty), info.longVersionCode)
      } catch (_: Exception) { ScopeTargetVersion(null, null) }
      assertEquals(name, expected, versions[name])
    }
  }
}
