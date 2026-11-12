package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.AppPreferencesState
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class RecommendedMappingsUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val nameTag = "icon-packs.system-name-matching"
  private val telegramTag = "recommended-mappings.telegram"
  private val colorOsTag = "recommended-mappings.coloros-theme-store"

  @Test fun movedNameMatchingUsesSavedValueAndAllThreeSwitchesPersistIndependently() {
    val storageName = "recommended_mappings_${UUID.randomUUID()}"
    val storage = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    storage.edit().putBoolean("system_app_name_matching", false).commit()
    val preferences = AppPreferences(storage, SystemIconFallback.entries)
    val host = Os4NavigationTestHost()
    try {
      rule.setContent {
        host.Provide {
          KeiHomeTheme {
            val state by preferences.state.collectAsStateWithLifecycle()
            RecommendedMappingsPage(state.systemAppNameMatching, state.telegramChannelMapping,
              state.colorOsThemeStoreMapping, nativeImporting = false,
              onSystemAppNameMatchingChange = preferences::setSystemAppNameMatching,
              onTelegramChannelMappingChange = preferences::setTelegramChannelMapping,
              onColorOsThemeStoreMappingChange = preferences::setColorOsThemeStoreMapping, onBack = {})
          }
        }
      }
      rule.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(3)
      toggle(nameTag).assertIsOff()
      toggle(telegramTag).assertIsOn()
      toggle(colorOsTag).assertIsOn()
      toggle(nameTag).performScrollTo().performClick().assertIsOn()
      toggle(telegramTag).performScrollTo().performClick().assertIsOff()
      rule.runOnIdle {
        val restored = AppPreferences(storage, SystemIconFallback.entries).state.value
        assertEquals(true, storage.getBoolean("system_app_name_matching", false))
        assertEquals(true, restored.systemAppNameMatching)
        assertEquals(false, restored.telegramChannelMapping)
        assertEquals(true, restored.colorOsThemeStoreMapping)
      }
      toggle(colorOsTag).performScrollTo().performClick().assertIsOff()
      toggle(nameTag).performScrollTo().performClick().assertIsOff()
      rule.runOnIdle {
        val restored = AppPreferences(storage, SystemIconFallback.entries).state.value
        assertEquals(false, storage.getBoolean("system_app_name_matching", true))
        assertEquals(false, restored.systemAppNameMatching)
        assertEquals(false, restored.telegramChannelMapping)
        assertEquals(false, restored.colorOsThemeStoreMapping)
      }
    } finally {
      context.deleteSharedPreferences(storageName)
    }
  }

  @Test fun nativeImportDisablesEverySwitchWithoutChangingValuesAndUnlocksAfterCompletion() {
    var importing by mutableStateOf(true)
    var name by mutableStateOf(false)
    var telegram by mutableStateOf(true)
    var colorOs by mutableStateOf(false)
    val changes = mutableListOf<String>()
    val host = Os4NavigationTestHost()
    rule.setContent {
      host.Provide {
        KeiHomeTheme {
          RecommendedMappingsPage(name, telegram, colorOs, importing,
            { name = it; changes += "name:$it" },
            { telegram = it; changes += "telegram:$it" },
            { colorOs = it; changes += "coloros:$it" }, onBack = {})
        }
      }
    }
    rule.onNodeWithTag("recommended-mappings.importing").assertExists()
    for (tag in listOf(nameTag, telegramTag, colorOsTag)) {
      toggle(tag).performScrollTo().assertIsNotEnabled().performTouchInput { click(center) }
    }
    toggle(nameTag).assertIsOff()
    toggle(telegramTag).assertIsOn()
    toggle(colorOsTag).assertIsOff()
    rule.runOnIdle {
      assertEquals(emptyList<String>(), changes)
      importing = false
    }
    rule.onNodeWithTag("recommended-mappings.importing").assertDoesNotExist()
    for (tag in listOf(nameTag, telegramTag, colorOsTag)) {
      toggle(tag).performScrollTo().assertIsEnabled().performClick()
    }
    rule.runOnIdle {
      assertEquals(listOf("name:true", "telegram:false", "coloros:true"), changes)
    }
    rule.onNodeWithTag("recommended-mappings.update-note").performScrollTo().assertExists()
  }

  @Test fun searchFindsPageByLocalizedSwitchesAndPackageNamesWithoutRenderingInlineSwitches() {
    var locale by mutableStateOf("en")
    var query by mutableStateOf("")
    var navigations = 0
    rule.setContent {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(locale))
      }
      val localizedContext = context.createConfigurationContext(configuration)
      CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration) {
        KeiHomeTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsContent(AppPreferencesState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
              ThemeEffectsUiState(), false, {}, ThemeEffectsUiState(), FrameworkUiState(), {},
              query, null, onOpenNotices = {}, launcherLabelsContent = {}, dockGlassContent = {},
              onNavigationBarStyleChange = {}, onOpenRecommendedMappings = { navigations++ }) {}
          }
        }
      }
    }
    for (tag in listOf("en", "zh-CN", "ja")) {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag))
      }
      val resources = context.createConfigurationContext(configuration).resources
      val queries = listOf(R.string.recommended_mappings_title, R.string.system_name_matching_title,
        R.string.recommended_mappings_telegram_title, R.string.recommended_mappings_coloros_title)
        .map(resources::getString) + listOf("com.android.thememanager", "com.miui.themestore")
      for (text in queries) {
        rule.runOnIdle { locale = tag; query = text }
        rule.onNodeWithTag("settings.entry.recommended-mappings").performScrollTo().performClick()
        rule.onNodeWithTag(nameTag).assertDoesNotExist()
        rule.onNodeWithTag(telegramTag).assertDoesNotExist()
        rule.onNodeWithTag(colorOsTag).assertDoesNotExist()
        rule.onNodeWithText(resources.getString(R.string.settings_search_empty)).assertDoesNotExist()
      }
    }
    rule.runOnIdle { assertEquals(18, navigations); query = "no-such-mapping-789" }
    rule.onNodeWithTag("settings.entry.recommended-mappings").assertDoesNotExist()
  }

  private fun toggle(tag: String) = rule.onNode(
    isToggleable() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
}
