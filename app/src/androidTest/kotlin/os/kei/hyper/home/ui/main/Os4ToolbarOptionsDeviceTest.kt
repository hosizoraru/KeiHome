package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info

class Os4ToolbarOptionsDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun explicitLegacySizesStillMatchInfoAndDemoActionSpacing() {
    var density = 1f
    compose.setContent {
      density = LocalDensity.current.density
      KeiHomeTheme {
        val backdrop = rememberLayerBackdrop()
        KeiHomeScaffold(backdrop = backdrop, topBar = {
          KeiHomeTopAppBar(title = "Toolbar", actions = {
            Os4InfoPopup("Info", "Summary", modifier = Modifier.testTag("toolbar.info"))
            KeiHomeIconButton({}, Modifier.testTag("toolbar.legacy"), minWidth = 48.dp, minHeight = 48.dp) {
              Icon(MiuixIcons.Glass.Info, "Legacy")
            }
            KeiHomeIconButton({}, Modifier.testTag("toolbar.disabled"), enabled = false,
              minWidth = 48.dp, minHeight = 48.dp) {
              Icon(MiuixIcons.Glass.Info, "Disabled")
            }
          })
        }) { padding ->
          Column(Modifier.fillMaxSize().layerBackdrop(backdrop).padding(padding)) {
            KeiHomeIconButton({}, Modifier.testTag("standalone")) {
              Icon(MiuixIcons.Glass.Info, "Standalone")
            }
          }
        }
      }
    }
    val tags = listOf("toolbar.info", "toolbar.legacy", "toolbar.disabled")
    tags.forEach { compose.onNodeWithTag(it).assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp) }
    val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }.sortedBy { it.left }
    bounds.zipWithNext().forEach { (first, second) ->
      assertEquals(8f * density, second.left - first.right, 1f)
      assertEquals(first.center.y, second.center.y, 1f)
    }
    compose.onNodeWithTag("toolbar.disabled").assertIsNotEnabled()
    compose.onNodeWithTag("standalone").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
  }

  @Test fun groupedSortAndNestedFiltersKeepSummarySelectionAndCollapse() {
    var selected by mutableIntStateOf(0)
    var nightOnly by mutableStateOf(false)
    var changes = 0
    compose.setContent {
      KeiHomeTheme {
        val backdrop = rememberLayerBackdrop()
        KeiHomeScaffold(backdrop = backdrop, topBar = {
          KeiHomeTopAppBar(title = "Catalogue", actions = {
            PerfectListSortMenu(listOf(R.string.perfect_list_original, R.string.perfect_list_name),
              selected, false, onSort = { selected = it; changes++ }, onReverse = {},
              modifier = Modifier.testTag("options"), filtersActive = nightOnly,
              filterEntries = listOf(DropdownEntry(items = listOf(DropdownItem(
                text = "Artwork filter", summary = if (nightOnly) "Night-only" else "All artwork",
                children = listOf(
                  DropdownItem(text = "Night-only", selected = nightOnly,
                    onClick = { nightOnly = !nightOnly; changes++ }),
                  DropdownItem(text = "Unavailable", enabled = false,
                    onClick = { error("Disabled filter invoked") }),
                ),
              )))))
          })
        }) { Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) }
      }
    }
    compose.onNodeWithTag("options").performClick()
    compose.onNodeWithText("All artwork").assertIsDisplayed()
    compose.onNodeWithText("Artwork filter").performClick()
    compose.onNodeWithText("Unavailable").assertIsNotEnabled()
    // The native submenu header returns to its parent without selecting or dismissing it.
    compose.onAllNodesWithText("Artwork filter").onLast().performClick()
    compose.onNodeWithText("All artwork").assertIsDisplayed()
    compose.runOnIdle { assertEquals(0, changes) }
    compose.onNodeWithText("Artwork filter").performClick()
    compose.onNodeWithText("Night-only").performClick()
    compose.onNodeWithText("Artwork filter").assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, changes) }
    compose.onNodeWithTag("options").performClick()
    compose.onNodeWithText("Night-only").assertIsDisplayed()
    compose.onNodeWithText("Artwork filter").performClick()
    compose.onAllNodesWithText("Night-only").onLast().assertIsSelected()
    compose.onAllNodesWithText("Artwork filter").onLast().performClick()
    val name = InstrumentationRegistry.getInstrumentation()
      .targetContext.getString(R.string.perfect_list_name)
    compose.onNodeWithText(name).performClick()
    compose.onNodeWithText("Artwork filter").assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, selected); assertEquals(2, changes) }
    compose.onNodeWithTag("options").performClick()
    compose.onNodeWithText(name).assertIsSelected()
  }
}
