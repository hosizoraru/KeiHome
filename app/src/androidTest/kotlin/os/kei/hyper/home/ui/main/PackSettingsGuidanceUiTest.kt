package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppPreferencesState
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class PackSettingsGuidanceUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val retentionTag = "settings.keep-latest-generated-packs"

  @Test fun retentionDefaultsOnInAllLocalesWithoutCompactSettingAndSurvivesDisabledMenuMemory() {
    val locale = mutableStateOf("en")
    val state = mutableStateOf(AppPreferencesState(rememberMenuSelections = false))
    val changes = mutableListOf<Boolean>()
    rule.setContent {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(locale.value))
      }
      val localizedContext = context.createConfigurationContext(configuration)
      CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration) {
        KeiHomeTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            PackSettingsContent(state.value, {}, {}, {
              state.value = state.value.copy(rememberMenuSelections = it)
            }, {}, showAutoApply = false, showInstalledOnly = false, showPreviews = false,
              onKeepLatestGeneratedPacks = {
                changes += it
                state.value = state.value.copy(keepLatestGeneratedPacks = it)
              })
          }
        }
      }
    }
    for (tag in listOf("en", "zh-CN", "ja")) {
      val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag))
      }).resources
      rule.runOnIdle { locale.value = tag }
      rule.onNodeWithTag(retentionTag).performScrollTo()
      rule.onNodeWithText(resources.getString(R.string.generated_pack_retention_title)).assertExists()
      rule.onNodeWithText(resources.getString(R.string.generated_pack_retention_summary)).assertExists()
      rule.onNodeWithTag("settings.compact-pack-hints").assertDoesNotExist()
      retentionToggle().assertIsOn().performClick()
      retentionToggle().assertIsOff().performClick()
      retentionToggle().assertIsOn()
      rule.runOnIdle { assertFalse(state.value.rememberMenuSelections) }
      rule.onNodeWithTag("settings.remember-pack-menus").performScrollTo()
      menuToggle().assertIsOff().performClick()
      rule.runOnIdle { assertTrue(state.value.keepLatestGeneratedPacks) }
      menuToggle().assertIsOn().performClick()
    }
    rule.runOnIdle { assertEquals(listOf(false, true, false, true, false, true), changes) }
  }

  @Test fun retentionIsSearchableInPacksSectionAndUsesSharedCallback() {
    val query = mutableStateOf(context.getString(R.string.generated_pack_retention_title))
    val section = mutableStateOf<SettingsSection?>(SettingsSection.PACKS)
    val state = mutableStateOf(AppPreferencesState())
    val changes = mutableListOf<Boolean>()
    rule.setContent {
      KeiHomeTheme {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          SettingsContent(
            preferences = state.value,
            onPreviewsChange = {}, onClearIconCache = {}, onPageUserScrollChange = {},
            onPagerGestureChange = {}, onAutoApplyChange = {}, onInstalledIconsOnlyChange = {},
            onRememberMenusChange = {},
            splash = ThemeEffectsUiState(), splashInScope = false, onSplashChange = {},
            themeEffects = ThemeEffectsUiState(), framework = FrameworkUiState(),
            onThemeEffectsChange = {}, onOpenNotices = {}, launcherLabelsContent = {},
            dockGlassContent = {}, fallbackContent = {},
            query = query.value, section = section.value,
            onKeepLatestGeneratedPacksChange = {
              changes += it
              state.value = state.value.copy(keepLatestGeneratedPacks = it)
            },
          )
        }
      }
    }
    retentionToggle().assertIsOn().performClick()
    retentionToggle().assertIsOff()
    rule.onNodeWithTag("settings.compact-pack-hints").assertDoesNotExist()
    rule.runOnIdle { query.value = context.getString(R.string.generated_pack_retention_summary) }
    retentionToggle().assertIsOff()
    rule.runOnIdle { section.value = SettingsSection.APP }
    rule.onNodeWithTag(retentionTag).assertDoesNotExist()
    rule.runOnIdle { section.value = SettingsSection.PACKS }
    retentionToggle().assertIsOff()
    rule.runOnIdle { assertEquals(listOf(false), changes) }
  }

  private fun retentionToggle() = rule.onNode(
    isToggleable() and hasAnyAncestor(hasTestTag(retentionTag)), useUnmergedTree = true)

  private fun menuToggle() = rule.onNode(
    isToggleable() and hasAnyAncestor(hasTestTag("settings.remember-pack-menus")), useUnmergedTree = true)
}
