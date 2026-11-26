package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libxposed.service.HookedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppAppearance
import os.kei.hyper.home.data.AppColorMode
import os.kei.hyper.home.data.AppPreferencesState
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.FrameworkTarget
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class SettingsSearchUiTest {
  companion object {
    @BeforeClass @JvmStatic fun bringTargetToForeground() {
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      val target = instrumentation.targetContext.packageName
      android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
        "am start -n $target/.MainActivity",
      )).use { it.readBytes() }
      instrumentation.waitForIdleSync()
    }
  }

  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test fun searchFindsAnOperableSettingAndShowsTheEmptyState() {
    var requested: Boolean? = null
    val target = FrameworkTarget(HookedTarget.State.UP_TO_DATE, BuildConfig.VERSION_CODE.toLong())
    val framework = FrameworkUiState(connected = true, snapshot = FrameworkSnapshot(
      name = "test", apiVersion = 102, systemInScope = true, target = target,
      launcherTarget = null, systemUiInScope = true, systemUiTarget = target))
    rule.setContent {
      KeiHomeTheme {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          var query by remember { mutableStateOf("") }
          LocalizedInputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false,
            onExpandedChange = {}, label = context.getString(R.string.settings_search))
          SettingsContent(AppPreferencesState(), {}, {}, {}, {}, {}, {}, {},
            ThemeEffectsUiState(savedChoice = true), true, {},
            ThemeEffectsUiState(savedChoice = false), framework, { requested = it }, query, null,
            onOpenNotices = {}, launcherLabelsContent = {}, dockGlassContent = {}) {}
        }
      }
    }
    val input = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
    input.performTextReplacement(context.getString(R.string.theme_effects_title))
    rule.onNodeWithTag("theme-effects.switch").assertExists().assertIsEnabled()
      .performSemanticsAction(SemanticsActions.OnClick)
    rule.runOnIdle { assertEquals(true, requested) }
    rule.onNodeWithText(context.getString(R.string.pack_auto_apply)).assertDoesNotExist()
    input.performTextReplacement("no-such-setting-789")
    rule.onNodeWithText(context.getString(R.string.settings_search_empty)).assertExists()
    rule.onNodeWithTag("theme-effects.switch").assertDoesNotExist()
    input.performTextReplacement("")
    rule.onNodeWithText(context.getString(R.string.pack_auto_apply)).assertExists()
  }

  @Test fun installedIconsOnlyIsSearchableAndOperableInEverySupportedLanguage() {
    val locale = mutableStateOf("en")
    val query = mutableStateOf("")
    val section = mutableStateOf<SettingsSection?>(null)
    val preferences = mutableStateOf(AppPreferencesState())
    val changes = mutableListOf<Boolean>()
    rule.setContent {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(locale.value))
      }
      val localizedContext = context.createConfigurationContext(configuration)
      CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration) {
        KeiHomeTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsContent(preferences.value, {}, {}, {}, {}, {},
              { enabled ->
                changes += enabled
                preferences.value = preferences.value.copy(installedIconsOnly = enabled)
              }, {}, ThemeEffectsUiState(), false, {}, ThemeEffectsUiState(), FrameworkUiState(), {},
              query.value, section.value, onOpenNotices = {}, launcherLabelsContent = {}, dockGlassContent = {}) {}
          }
        }
      }
    }
    for (tag in listOf("en", "zh-CN", "ja")) {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag))
      }
      val resources = context.createConfigurationContext(configuration).resources
      val title = resources.getString(R.string.pack_installed_icons_only)
      val summary = resources.getString(R.string.pack_installed_icons_only_summary)
      rule.runOnIdle {
        locale.value = tag
        query.value = title
        section.value = null
      }
      rule.onNodeWithText(title).assertExists()
      rule.onNode(isToggleable(), useUnmergedTree = true).assertIsEnabled().assertIsOff().performClick()
      rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOn()
      rule.onNodeWithText(resources.getString(R.string.pack_auto_apply)).assertDoesNotExist()
      rule.runOnIdle { query.value = summary }
      rule.onNodeWithText(title).assertExists()
      rule.onNodeWithText(resources.getString(R.string.settings_search_empty)).assertDoesNotExist()
      rule.runOnIdle { section.value = SettingsSection.HOOK }
      rule.onNodeWithText(title).assertDoesNotExist()
      rule.runOnIdle { section.value = SettingsSection.APP }
      rule.onNodeWithText(title).assertDoesNotExist()
      rule.runOnIdle { section.value = SettingsSection.PACKS }
      rule.onNodeWithText(title).assertExists()
      rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOn().performClick()
      rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOff()
    }
    rule.runOnIdle { assertEquals(listOf(true, false, true, false, true, false), changes) }
  }

  @Test fun themeSettingsKeepAppearanceAndPaletteIndependent() {
    val name = "app_theme_${UUID.randomUUID()}"
    val storage = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val preferences = AppPreferences(storage, SystemIconFallback.entries)
    preferences.setAppearance(AppAppearance.SYSTEM)
    preferences.setColorMode(AppColorMode.FOLLOW_ICONS)
    val backHost = Os4NavigationTestHost()
    try {
      rule.setContent {
        val state by preferences.state.collectAsStateWithLifecycle()
        backHost.Provide {
          KeiHomeTheme {
            CompositionLocalProvider(LocalKeiHomeDarkTheme provides (state.appearance == AppAppearance.DARK)) {
              KeiHomeScaffold(Modifier.fillMaxSize()) { padding ->
                Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                  AppThemeSettingsContent(preferences)
                }
              }
            }
          }
        }
      }
      val follow = rule.onNode(
        isToggleable() and hasAnyAncestor(hasTestTag("settings.theme.follow-system")), useUnmergedTree = true,
      )
      follow.assertIsOn()
      rule.onNodeWithTag("settings.theme.dark").performClick().assertIsSelected()
      follow.assertIsOff()
      rule.runOnIdle {
        assertEquals(AppAppearance.DARK, preferences.state.value.appearance)
        assertEquals(AppColorMode.FOLLOW_ICONS, preferences.state.value.colorMode)
      }
      follow.performClick().assertIsOn()
      follow.performClick().assertIsOff()
      rule.onNodeWithTag("settings.theme.light").assertIsSelected()
      rule.onNodeWithTag("settings.theme.palette").performClick()
      rule.onNodeWithText(context.getString(R.string.browser_ui_monet)).performClick()
      rule.runOnIdle {
        val restored = AppPreferences(storage, SystemIconFallback.entries).state.value
        assertEquals(AppAppearance.LIGHT, restored.appearance)
        assertEquals(AppColorMode.MONET, restored.colorMode)
      }
    } finally {
      context.deleteSharedPreferences(name)
    }
  }

  @Test fun themeSettingsAreSearchableUsingExistingDisplayLabels() {
    for (tag in listOf("en", "zh-CN", "ja")) {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag))
      }
      val resources = context.createConfigurationContext(configuration).resources
      for (label in listOf(R.string.browser_ui_palette, R.string.polish_appearance_title,
        R.string.app_scale_title, R.string.navigation_bar_style_title)) {
        assertTrue(appThemeSearchMatches(resources.getString(label), resources))
      }
    }
  }

  @Test fun iconPreviewsBelongToPacksAndRemainSearchableWithTheSameSavedPreference() {
    val locale = mutableStateOf("en")
    val query = mutableStateOf("")
    val section = mutableStateOf<SettingsSection?>(SettingsSection.CACHE)
    val storageName = "preview_setting_${UUID.randomUUID()}"
    val storage = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    storage.edit().putBoolean("icon_previews", false).commit()
    val preferences = AppPreferences(storage, SystemIconFallback.entries)
    try {
      rule.setContent {
        val configuration = Configuration(context.resources.configuration).apply {
          setLocales(LocaleList.forLanguageTags(locale.value))
        }
        val localizedContext = context.createConfigurationContext(configuration)
        CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration) {
          KeiHomeTheme {
            val state by preferences.state.collectAsStateWithLifecycle()
            Column(Modifier.verticalScroll(rememberScrollState())) {
              SettingsContent(state, preferences::setShowIconPreviews, {}, {}, {}, {}, {}, {},
                ThemeEffectsUiState(), false, {}, ThemeEffectsUiState(), FrameworkUiState(), {},
                query.value, section.value, onOpenNotices = {}, launcherLabelsContent = {}, dockGlassContent = {},
                ) {}
            }
          }
        }
      }
      rule.onNodeWithTag("settings.icon-previews").assertDoesNotExist()
      rule.onNodeWithText(context.getString(R.string.polish_previews_title)).assertDoesNotExist()
      for (tag in listOf("en", "zh-CN", "ja")) {
        val configuration = Configuration(context.resources.configuration).apply {
          setLocales(LocaleList.forLanguageTags(tag))
        }
        val resources = context.createConfigurationContext(configuration).resources
        val title = resources.getString(R.string.polish_previews_title)
        rule.runOnIdle {
          locale.value = tag
          query.value = title
          section.value = null
        }
        rule.onNodeWithTag("settings.icon-previews").assertExists()
        rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOff().performClick()
        rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOn()
        rule.runOnIdle {
          assertTrue(storage.getBoolean("icon_previews", false))
          assertTrue(AppPreferences(storage, SystemIconFallback.entries).state.value.showIconPreviews)
          query.value = resources.getString(R.string.polish_previews_summary)
        }
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText(resources.getString(R.string.pack_settings_experience)).assertExists()
        rule.runOnIdle { section.value = SettingsSection.CACHE }
        rule.onNodeWithTag("settings.icon-previews").assertDoesNotExist()
        rule.runOnIdle { section.value = SettingsSection.APP }
        rule.onNodeWithTag("settings.icon-previews").assertDoesNotExist()
        rule.runOnIdle { section.value = SettingsSection.PACKS }
        rule.onNodeWithTag("settings.icon-previews").assertExists()
        rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOn().performClick()
        rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOff()
        rule.runOnIdle {
          assertEquals(false, storage.getBoolean("icon_previews", true))
          assertEquals(false, AppPreferences(storage, SystemIconFallback.entries).state.value.showIconPreviews)
          query.value = resources.getString(R.string.pack_settings_experience)
        }
        rule.onNodeWithTag("settings.icon-previews").assertExists()
      }
    } finally {
      context.deleteSharedPreferences(storageName)
    }
  }

  @Test fun legacyAboutSearchResultDisplaysVersionWithoutACopyAction() {
    rule.setContent {
      KeiHomeTheme {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          SettingsContent(AppPreferencesState(), {}, {}, {}, {}, {}, {}, {},
            ThemeEffectsUiState(), false, {}, ThemeEffectsUiState(), FrameworkUiState(), {},
            context.getString(R.string.app_name), null, onOpenNotices = {}, launcherLabelsContent = {},
            dockGlassContent = {}) {}
        }
      }
    }
    rule.onNodeWithTag("settings.about-version").assertExists().assertHasNoClickAction()
      .assert(!hasAnyDescendant(hasClickAction()))
    rule.onNodeWithText(context.getString(R.string.app_name)).assertExists()
    rule.onNodeWithText(context.getString(R.string.polish_about_version,
      BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)).assertExists()
  }

  @Test fun compactAppearanceControlDoesNotReserveAnEmptySummaryLine() {
    rule.setContent {
      KeiHomeTheme { IconPackAppearanceControl(IconPackAppearance.SYSTEM, {}, true, compact = true) }
    }
    val heightDp = rule.onNodeWithTag("icon-packs.appearance").fetchSemanticsNode().boundsInRoot.height /
      rule.density.density
    assertTrue("Compact control was $heightDp dp tall", heightDp < 90f)
  }
}
