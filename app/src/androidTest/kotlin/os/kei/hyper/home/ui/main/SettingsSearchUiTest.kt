package os.kei.hyper.home.ui.main

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
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
          SettingsContent(AppPreferencesState(), {}, {}, {}, {}, {}, {}, {}, {}, {},
            ThemeEffectsUiState(savedChoice = true), true, {},
            ThemeEffectsUiState(savedChoice = false), framework, { requested = it }, query, null, {}, {}) {}
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

  @Test fun compactAppearanceControlDoesNotReserveAnEmptySummaryLine() {
    rule.setContent {
      KeiHomeTheme { IconPackAppearanceControl(IconPackAppearance.SYSTEM, {}, true, compact = true) }
    }
    val heightDp = rule.onNodeWithTag("icon-packs.appearance").fetchSemanticsNode().boundsInRoot.height /
      rule.density.density
    assertTrue("Compact control was $heightDp dp tall", heightDp < 90f)
  }
}
