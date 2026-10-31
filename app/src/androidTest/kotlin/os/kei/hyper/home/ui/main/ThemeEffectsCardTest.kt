package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.libxposed.service.HookedTarget
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.FrameworkTarget
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class ThemeEffectsCardTest {
  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun checkedValueIsTheSavedChoiceAndClickRequestsAWrite() {
    var requested: Boolean? = null
    var state by mutableStateOf(ThemeEffectsUiState(savedChoice = false))
    composeTestRule.setContent {
      KeiHomeTheme {
        ThemeEffectsCard(
          state = state,
          framework = framework(systemUiInScope = true),
          onCheckedChange = { requested = it },
        )
      }
    }

    toggle().assertIsOff().assertIsEnabled()
    composeTestRule.onNodeWithTag("theme-effects.switch").performClick()
    composeTestRule.runOnIdle { assertEquals(true, requested) }
    toggle().assertIsOff()
    composeTestRule.runOnIdle { state = state.copy(saving = true) }
    toggle().assertIsOff().assertIsNotEnabled()
    composeTestRule.runOnIdle { state = state.copy(saving = false, saveFailed = true) }
    toggle().assertIsOff().assertIsEnabled()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_save_failed)).assertExists()
    composeTestRule.runOnIdle { state = ThemeEffectsUiState(savedChoice = true) }
    toggle().assertIsOn()
  }

  @Test
  fun unknownChoiceRecoveryOnlyWritesOnClickAndRemainsRetryable() {
    val requests = mutableListOf<Boolean>()
    var state by mutableStateOf(ThemeEffectsUiState(readFailed = true))
    composeTestRule.setContent {
      KeiHomeTheme {
        ThemeEffectsCard(state, framework(systemUiInScope = true), onCheckedChange = {
          requests.add(it)
          state = state.copy(saving = true)
        })
      }
    }

    composeTestRule.runOnIdle { assertEquals(emptyList<Boolean>(), requests) }
    toggle().assertIsNotEnabled()
    val recovery = composeTestRule.onNodeWithTag("theme-effects.resave-off")
    recovery.assertIsEnabled().performClick()
    recovery.assertIsNotEnabled()
    composeTestRule.runOnIdle {
      assertEquals(listOf(false), requests)
      state = state.copy(saving = false, saveFailed = true)
    }
    recovery.assertIsEnabled().performClick()
    composeTestRule.runOnIdle {
      assertEquals(listOf(false, false), requests)
      state = ThemeEffectsUiState(savedChoice = false)
    }
    recovery.assertDoesNotExist()
    toggle().assertIsOff().assertIsEnabled()
  }

  @Test
  fun recoveryRequiresConnectionScopeAndRemotePreferences() {
    var state by mutableStateOf(ThemeEffectsUiState(readFailed = true))
    var status by mutableStateOf(framework(systemUiInScope = false, systemInScope = true).copy(connected = false))
    composeTestRule.setContent {
      KeiHomeTheme {
        ThemeEffectsCard(state, status, onCheckedChange = {})
      }
    }

    val recovery = composeTestRule.onNodeWithTag("theme-effects.resave-off")
    recovery.assertIsNotEnabled()
    composeTestRule.runOnIdle { status = framework(systemUiInScope = false) }
    recovery.assertIsNotEnabled()
    composeTestRule.runOnIdle { status = framework(systemUiInScope = false, systemInScope = true) }
    recovery.assertIsEnabled()
    composeTestRule.runOnIdle { state = state.copy(unavailable = true) }
    recovery.assertIsNotEnabled()
    composeTestRule.runOnIdle { state = ThemeEffectsUiState(savedChoice = false) }
    recovery.assertDoesNotExist()
  }

  @Test
  fun unavailableFrameworkOrScopeDisablesTheSwitch() {
    var status by mutableStateOf(framework(systemUiInScope = true).copy(connected = false))
    composeTestRule.setContent {
      KeiHomeTheme {
        ThemeEffectsCard(
          state = ThemeEffectsUiState(savedChoice = false),
          framework = status,
          onCheckedChange = {},
        )
      }
    }

    toggle().assertIsNotEnabled()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_disconnected)).assertExists()
    composeTestRule.runOnIdle { status = framework(systemUiInScope = false) }
    toggle().assertIsNotEnabled()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_scope_needed)).assertExists()
    composeTestRule.runOnIdle { status = framework(systemUiInScope = true).copy(readFailed = true) }
    toggle().assertIsNotEnabled()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_framework_failed)).assertExists()
    composeTestRule.runOnIdle { status = framework(systemUiInScope = true) }
    toggle().assertIsEnabled()
  }

  @Test
  fun eitherScopeAllowsSavingWithoutWaitingForTargetLoad() {
    val requests = mutableListOf<Boolean>()
    var state by mutableStateOf(ThemeEffectsUiState(savedChoice = false))
    var status by mutableStateOf(framework(systemUiInScope = false, systemInScope = true))
    composeTestRule.setContent {
      KeiHomeTheme {
        ThemeEffectsCard(state, status, onCheckedChange = { requests.add(it) })
      }
    }

    toggle().assertIsEnabled()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_system_ui_scope_needed)).assertExists()
    composeTestRule.onNodeWithText(savedStatus(
      false, moduleStatus(R.string.theme_effects_system_target_status, R.string.theme_effects_target_pending),
    )).assertExists()
    composeTestRule.onNodeWithTag("theme-effects.switch").performClick()

    composeTestRule.runOnIdle {
      status = framework(systemUiInScope = true, systemUiTarget = null)
    }
    toggle().assertIsEnabled()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_system_scope_needed)).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_system_ui_scope_needed)).assertDoesNotExist()
    composeTestRule.onNodeWithTag("theme-effects.switch").performClick()
    composeTestRule.runOnIdle {
      assertEquals(listOf(true, true), requests)
      state = state.copy(unavailable = true)
    }
    toggle().assertIsNotEnabled()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_unavailable)).assertExists()
  }

  @Test
  fun loadedStatusRequiresCurrentModuleVersion() {
    var status by mutableStateOf(framework(
      systemUiInScope = true, loadedVersionCode = BuildConfig.VERSION_CODE.toLong() - 1,
    ))
    composeTestRule.setContent {
      KeiHomeTheme {
        ThemeEffectsCard(
          state = ThemeEffectsUiState(savedChoice = true),
          framework = status,
          onCheckedChange = {},
        )
      }
    }

    composeTestRule.onNodeWithText(savedStatus(
      true, moduleStatus(R.string.theme_effects_system_ui_target_status, R.string.theme_effects_target_pending),
    )).assertExists()
    composeTestRule.runOnIdle { status = framework(systemUiInScope = true) }
    composeTestRule.onNodeWithText(savedStatus(
      true, moduleStatus(R.string.theme_effects_system_ui_target_status, R.string.theme_effects_target_loaded),
    )).assertExists()
  }

  @Test
  fun eachScopedModuleReportsItsOwnState() {
    val current = BuildConfig.VERSION_CODE.toLong()
    var status by mutableStateOf(framework(
      systemUiInScope = true, systemInScope = true,
      systemTarget = FrameworkTarget(HookedTarget.State.UP_TO_DATE, current),
      systemUiTarget = FrameworkTarget(HookedTarget.State.FAILED, current),
    ))
    composeTestRule.setContent {
      KeiHomeTheme {
        ThemeEffectsCard(ThemeEffectsUiState(savedChoice = true), status, onCheckedChange = {})
      }
    }

    toggle().assertIsEnabled()
    composeTestRule.onNodeWithText(savedStatus(
      true, moduleStatus(R.string.theme_effects_system_target_status, R.string.theme_effects_target_loaded),
      moduleStatus(R.string.theme_effects_system_ui_target_status, R.string.theme_effects_target_failed),
    )).assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_system_ui_scope_needed)).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.theme_effects_system_scope_needed)).assertDoesNotExist()
    composeTestRule.runOnIdle {
      status = framework(systemUiInScope = true, systemInScope = true,
        systemTarget = FrameworkTarget(HookedTarget.State.STALE, current))
    }
    composeTestRule.onNodeWithText(savedStatus(
      true, moduleStatus(R.string.theme_effects_system_target_status, R.string.theme_effects_target_pending),
      moduleStatus(R.string.theme_effects_system_ui_target_status, R.string.theme_effects_target_loaded),
    )).assertExists()
  }

  @Test
  fun titleAndModuleStatusFollowSelectedLanguage() {
    var localeTag by mutableStateOf("en")
    composeTestRule.setContent {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(localeTag))
      }
      CompositionLocalProvider(
        LocalContext provides context.createConfigurationContext(configuration),
        LocalConfiguration provides configuration,
      ) {
        KeiHomeTheme {
          ThemeEffectsCard(
            state = ThemeEffectsUiState(savedChoice = false),
            framework = framework(systemUiInScope = true),
            onCheckedChange = {},
          )
        }
      }
    }
    for ((tag, title, status) in listOf(
      Triple("en", "Keep system materials", "Off · System UI: Module loaded"),
      Triple("zh-CN", "保留系统材质效果", "已关闭 · 系统界面：模块已加载"),
      Triple("ja", "システムの質感効果を保持", "オフ · システム UI：モジュール読込済み"),
    )) {
      composeTestRule.runOnIdle { localeTag = tag }
      composeTestRule.onNodeWithText(title).assertExists()
      composeTestRule.onNodeWithText(status).assertExists()
    }
  }

  private fun toggle() = composeTestRule.onNode(isToggleable(), useUnmergedTree = true)

  private fun moduleStatus(target: Int, status: Int) = context.getString(target, context.getString(status))

  private fun savedStatus(enabled: Boolean, vararg targets: String) = context.getString(
    R.string.theme_effects_status,
    context.getString(if (enabled) R.string.theme_effects_saved_on else R.string.theme_effects_saved_off),
    targets.joinToString(" · "),
  )

  private fun framework(
    systemUiInScope: Boolean,
    loadedVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    systemInScope: Boolean = false,
    systemTarget: FrameworkTarget? = null,
    systemUiTarget: FrameworkTarget? = FrameworkTarget(HookedTarget.State.UP_TO_DATE, loadedVersionCode),
  ) = FrameworkUiState(
    connected = true,
    snapshot = FrameworkSnapshot(
      name = "Test framework",
      apiVersion = 102,
      systemInScope = systemInScope,
      target = systemTarget,
      launcherTarget = null,
      systemUiInScope = systemUiInScope,
      systemUiTarget = systemUiTarget,
    ),
  )
}
