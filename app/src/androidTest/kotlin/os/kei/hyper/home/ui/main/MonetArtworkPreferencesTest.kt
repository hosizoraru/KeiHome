package os.kei.hyper.home.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class MonetArtworkPreferencesTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()

  @Test fun paddingStartsOffWorksIndependentlyAndRetainsItsChoiceAcrossGenericToggles() {
    rule.setContent {
      var enabled by remember { mutableStateOf(false) }
      var padding by remember { mutableStateOf(false) }
      KeiHomeTheme { Column {
        MonetArtworkPreferences(enabled, padding, true, { enabled = it }, { padding = it })
      } }
    }
    val child = toggle("monet.fullbleed.padding")
    child.assertIsOff().assertIsEnabled().performClick().assertIsOn()
    toggle("monet.unadapted").assertIsOff()
    toggle("monet.unadapted").performClick()
    child.assertIsOn().assertIsEnabled()
    toggle("monet.unadapted").performClick()
    child.assertIsOn().assertIsEnabled()
    toggle("monet.unadapted").performClick()
    child.assertIsOn().assertIsEnabled()
  }

  @Test fun generationLocksBothPreferences() {
    rule.setContent {
      KeiHomeTheme { Column { MonetArtworkPreferences(true, false, false, {}, {}) } }
    }
    toggle("monet.unadapted").assertIsNotEnabled()
    toggle("monet.fullbleed.padding").assertIsOff().assertIsNotEnabled()
  }

  private fun toggle(tag: String) = rule.onNode(
    isToggleable() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
}
