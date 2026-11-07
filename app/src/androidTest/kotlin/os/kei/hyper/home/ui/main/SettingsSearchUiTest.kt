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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libxposed.service.HookedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppPreferencesState
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.FrameworkTarget
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.InputField

class SettingsSearchUiTest {
  companion object {
    @BeforeClass @JvmStatic fun bringTargetToForeground() {
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
        "am start -n os.kei.hyper.home/.MainActivity",
      )).use { it.readBytes() }
      instrumentation.waitForIdleSync()
    }
  }

  @get:Rule val rule = createComposeRule()
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
          InputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false,
            onExpandedChange = {}, label = context.getString(R.string.settings_search))
          SettingsContent(AppPreferencesState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            ThemeEffectsUiState(savedChoice = true), true, {},
            ThemeEffectsUiState(savedChoice = false), framework, { requested = it }, query, null,
            onOpenNotices = {}, launcherLabelsContent = {}, dockGlassContent = {}) {}
        }
      }
    }
    val input = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
    input.performTextReplacement(context.getString(R.string.theme_effects_title))
    rule.onNodeWithTag("theme-effects.switch").assertExists().assertIsEnabled().performClick()
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
            SettingsContent(preferences.value, {}, {}, {}, {}, {}, {}, {},
              { enabled ->
                changes += enabled
                preferences.value = preferences.value.copy(installedIconsOnly = enabled)
              }, {}, {}, ThemeEffectsUiState(), false, {}, ThemeEffectsUiState(), FrameworkUiState(), {},
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
      rule.onNodeWithText(title).assertExists()
      rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOn().performClick()
      rule.onNode(isToggleable(), useUnmergedTree = true).assertIsOff()
    }
    rule.runOnIdle { assertEquals(listOf(true, false, true, false, true, false), changes) }
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
