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
import os.kei.hyper.home.data.RecommendedIconMappings
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class RecommendedMappingsUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val nameTag = "icon-packs.system-name-matching"
  private val telegramTag = "recommended-mappings.telegram"
  private val phoneTag = "recommended-mappings.phone-companions"
  private val colorOsTag = "recommended-mappings.coloros-theme-store"

  @Test fun movedNameMatchingUsesSavedValueAndAllFourSwitchesPersistIndependently() {
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
              state.phoneCompanionMapping, state.colorOsThemeStoreMapping, nativeImporting = false,
              onSystemAppNameMatchingChange = preferences::setSystemAppNameMatching,
              onTelegramChannelMappingChange = preferences::setTelegramChannelMapping,
              onPhoneCompanionMappingChange = preferences::setPhoneCompanionMapping,
              onColorOsThemeStoreMappingChange = preferences::setColorOsThemeStoreMapping, onBack = {})
          }
        }
      }
      rule.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(4)
      toggle(nameTag).assertIsOff()
      toggle(telegramTag).assertIsOn()
      toggle(phoneTag).assertIsOn()
      toggle(colorOsTag).assertIsOn()
      rule.runOnIdle {
        assertEquals(true, AppPreferencesState().phoneCompanionMapping)
        assertEquals(false, storage.contains(RecommendedIconMappings.PHONE_COMPANION_KEY))
        assertEquals(true, preferences.state.value.recommendedIconMappings.phoneCompanions)
      }
      toggle(nameTag).performScrollTo().performClick().assertIsOn()
      toggle(telegramTag).performScrollTo().performClick().assertIsOff()
      rule.runOnIdle {
        val restored = AppPreferences(storage, SystemIconFallback.entries).state.value
        assertEquals(true, storage.getBoolean("system_app_name_matching", false))
        assertEquals(true, restored.systemAppNameMatching)
        assertEquals(false, restored.telegramChannelMapping)
        assertEquals(true, restored.phoneCompanionMapping)
        assertEquals(true, restored.colorOsThemeStoreMapping)
      }
      toggle(phoneTag).performScrollTo().performClick().assertIsOff()
      rule.runOnIdle {
        val restored = AppPreferences(storage, SystemIconFallback.entries).state.value
        assertEquals(false, storage.getBoolean("phone_companion_mapping", true))
        assertEquals(false, restored.phoneCompanionMapping)
        assertEquals(false, restored.recommendedIconMappings.phoneCompanions)
        assertEquals(true, restored.systemAppNameMatching)
        assertEquals(false, restored.telegramChannelMapping)
        assertEquals(true, restored.colorOsThemeStoreMapping)
      }
      toggle(colorOsTag).performScrollTo().performClick().assertIsOff()
      toggle(nameTag).performScrollTo().performClick().assertIsOff()
      toggle(phoneTag).performScrollTo().performClick().assertIsOn()
      rule.runOnIdle {
        val restored = AppPreferences(storage, SystemIconFallback.entries).state.value
        assertEquals(false, storage.getBoolean("system_app_name_matching", true))
        assertEquals(false, restored.systemAppNameMatching)
        assertEquals(false, restored.telegramChannelMapping)
        assertEquals(true, storage.getBoolean("phone_companion_mapping", false))
        assertEquals(true, restored.phoneCompanionMapping)
        assertEquals(true, restored.recommendedIconMappings.phoneCompanions)
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
    var phone by mutableStateOf(true)
    var colorOs by mutableStateOf(false)
    val changes = mutableListOf<String>()
    val host = Os4NavigationTestHost()
    rule.setContent {
      host.Provide {
        KeiHomeTheme {
          RecommendedMappingsPage(name, telegram, phone, colorOs, importing,
            { name = it; changes += "name:$it" },
            { telegram = it; changes += "telegram:$it" },
            { phone = it; changes += "phone:$it" },
            { colorOs = it; changes += "coloros:$it" }, onBack = {})
        }
      }
    }
    rule.onNodeWithTag("recommended-mappings.importing").assertExists()
    for (tag in listOf(nameTag, telegramTag, phoneTag, colorOsTag)) {
      toggle(tag).performScrollTo().assertIsNotEnabled().performTouchInput { click(center) }
    }
    toggle(nameTag).assertIsOff()
    toggle(telegramTag).assertIsOn()
    toggle(phoneTag).assertIsOn()
    toggle(colorOsTag).assertIsOff()
    rule.runOnIdle {
      assertEquals(emptyList<String>(), changes)
      importing = false
    }
    rule.onNodeWithTag("recommended-mappings.importing").assertDoesNotExist()
    for (tag in listOf(nameTag, telegramTag, phoneTag, colorOsTag)) {
      toggle(tag).performScrollTo().assertIsEnabled().performClick()
    }
    rule.runOnIdle {
      assertEquals(listOf("name:true", "telegram:false", "phone:false", "coloros:true"), changes)
      importing = true
    }
    toggle(phoneTag).performScrollTo().assertIsNotEnabled().performTouchInput { click(center) }
    toggle(phoneTag).assertIsOff()
    rule.runOnIdle {
      assertEquals(listOf("name:true", "telegram:false", "phone:false", "coloros:true"), changes)
      importing = false
    }
    toggle(phoneTag).performScrollTo().assertIsEnabled().performClick().assertIsOn()
    rule.runOnIdle {
      assertEquals(listOf("name:true", "telegram:false", "phone:false", "coloros:true", "phone:true"), changes)
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
        R.string.recommended_mappings_telegram_title, R.string.recommended_mappings_phone_companions_title,
        R.string.recommended_mappings_coloros_title)
        .map(resources::getString) + listOf("com.android.thememanager", "com.miui.themestore",
          "com.android.server.telecom", "com.android.phone", "com.android.incallui")
      for (text in queries) {
        rule.runOnIdle { locale = tag; query = text }
        rule.onNodeWithTag("settings.entry.recommended-mappings").performScrollTo().performClick()
        rule.onNodeWithTag(nameTag).assertDoesNotExist()
        rule.onNodeWithTag(telegramTag).assertDoesNotExist()
        rule.onNodeWithTag(phoneTag).assertDoesNotExist()
        rule.onNodeWithTag(colorOsTag).assertDoesNotExist()
        rule.onNodeWithText(resources.getString(R.string.settings_search_empty)).assertDoesNotExist()
      }
    }
    rule.runOnIdle { assertEquals(30, navigations); query = "no-such-mapping-789" }
    rule.onNodeWithTag("settings.entry.recommended-mappings").assertDoesNotExist()
  }

  private fun toggle(tag: String) = rule.onNode(
    isToggleable() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
}
