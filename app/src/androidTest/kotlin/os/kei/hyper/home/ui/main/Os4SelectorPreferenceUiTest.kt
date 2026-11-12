package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.Os4DropdownPreference
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.DropdownItem

class Os4SelectorPreferenceUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun compatibilitySelectorExportsDisabledSemanticsAndPreservesIndexAndCallbacks() {
    var enabled by mutableStateOf(false)
    var selected by mutableIntStateOf(0)
    val callbacks = mutableListOf<String>()
    val backHost = Os4NavigationTestHost()
    compose.setContent {
      backHost.Provide {
        KeiHomeTheme {
          KeiHomeScaffold(Modifier.fillMaxSize()) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
              OverlaySpinnerPreference(
                title = "Selector", summary = "Shared OS4 preference",
                items = listOf(DropdownItem(text = "Current"),
                  DropdownItem(text = "Alternate", onClick = { callbacks += "item" })),
                selectedIndex = selected, enabled = enabled,
                insideMargin = PaddingValues(0.dp),
                modifier = Modifier.testTag("selector"),
                onSelectedIndexChange = { selected = it; callbacks += "index:$it" })
            }
          }
        }
      }
    }
    compose.onNodeWithTag("selector").assertIsNotEnabled().performTouchInput { click(center) }
    compose.onNodeWithText("Alternate").assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(emptyList<String>(), callbacks)
      enabled = true
    }
    compose.onNodeWithTag("selector").assertIsEnabled().performClick()
    compose.onNodeWithText("Alternate").performClick()
    compose.runOnIdle {
      assertEquals(1, selected)
      assertEquals(listOf("index:1", "item"), callbacks)
    }
  }

  @Test fun emptyCompatibilityAndNativePreferencesBothExportDisabledSemantics() {
    val backHost = Os4NavigationTestHost()
    compose.setContent {
      backHost.Provide {
        KeiHomeTheme {
          KeiHomeScaffold(Modifier.fillMaxSize()) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
              OverlaySpinnerPreference(title = "Empty compatibility", items = emptyList(), selectedIndex = 0,
                modifier = Modifier.testTag("empty.compatibility"),
                onSelectedIndexChange = { error("Empty selector must not dispatch") })
              Os4DropdownPreference(title = "Empty native", items = emptyList(), selectedIndex = 0,
                modifier = Modifier.testTag("empty.native"),
                onSelectedIndexChange = { error("Empty selector must not dispatch") })
            }
          }
        }
      }
    }
    compose.onNodeWithTag("empty.compatibility").assertIsNotEnabled()
    compose.onNodeWithTag("empty.native").assertIsNotEnabled()
  }

  @Test fun unsupportedChoicesStayVisibleDisabledAndKeepOriginalIndices() {
    var selected by mutableIntStateOf(0)
    val callbacks = mutableListOf<String>()
    val backHost = Os4NavigationTestHost()
    compose.setContent {
      backHost.Provide {
        KeiHomeTheme {
          KeiHomeScaffold(Modifier.fillMaxSize()) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
              OverlaySpinnerPreference(title = "Capability selector",
                items = listOf(DropdownItem(text = "Supported"),
                  DropdownItem(text = "Unavailable", enabled = false,
                    onClick = { error("Unavailable item must not dispatch") }),
                  DropdownItem(text = "Alternate", onClick = { callbacks += "item" })),
                selectedIndex = selected, modifier = Modifier.testTag("capability.selector"),
                onSelectedIndexChange = { selected = it; callbacks += "index:$it" })
            }
          }
        }
      }
    }
    compose.onNodeWithTag("capability.selector").performClick()
    compose.onNodeWithText("Unavailable").assertIsNotEnabled().performTouchInput { click(center) }
    compose.runOnIdle { assertEquals(emptyList<String>(), callbacks) }
    compose.onNodeWithText("Alternate").assertIsEnabled().performClick()
    compose.runOnIdle {
      assertEquals(2, selected)
      assertEquals(listOf("index:2", "item"), callbacks)
    }
    compose.onNodeWithText("Unavailable").assertDoesNotExist()
  }
}
