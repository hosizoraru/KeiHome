package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetIconRole
import os.kei.hyper.home.data.MonetPaletteFamily
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.Text

class GlassInteractionsUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun inlineTabsRemainDrawableInsideTheRecordedPage() {
    compose.setContent {
      KeiHomeTheme {
        val backdrop = rememberLayerBackdrop { drawContent() }
        KeiHomeScaffold(backdrop = backdrop) {
          Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Os4ViewTabs(listOf("Light", "Dark", "Compare"), 2, {},
              Modifier.testTag("recorded.tabs"))
          }
        }
      }
    }
    val image = compose.onNodeWithTag("recorded.tabs").captureToImage()
    assertTrue(image.width > 0 && image.height > 0)
  }

  @Test fun expandedMonetFamiliesAndRolesNeverOverlapAtLargeFont() {
    val palette = MonetPaletteFamily.entries.flatMap { family ->
      (0..100).map { tone -> "${family.key}$tone" to 0xff445577.toInt() }
    }.toMap()
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
        KeiHomeTheme {
          KeiHomeScaffold(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
              MonetAdvancedColors(MonetCustomization(), 0xff445577.toInt(), palette, true, {})
            }
          }
        }
      }
    }
    compose.onNodeWithTag("monet.custom.palettes").performClick()
    assertOrdered(MonetPaletteFamily.entries.map { "monet.custom.family.${it.name}" })
    compose.onNodeWithTag("monet.custom.palettes").performClick()
    compose.onNodeWithTag("monet.custom.icons").performScrollTo().performClick()
    assertOrdered(listOf("monet.custom.backgroundFamily") +
      MonetIconRole.entries.map { "monet.custom.role.${it.name}" })
    compose.onNodeWithTag("monet.custom.icons").performScrollTo().performClick()
    compose.onNodeWithTag("monet.custom.backgroundFamily").assertDoesNotExist()
  }

  @Test fun sheetTabsPreserveSelectionAndBackDismissesWithoutInvokingAnAction() {
    var visible by mutableStateOf(true)
    var selected by mutableIntStateOf(0)
    var dismissals = 0
    var finished = 0
    val host = Os4NavigationTestHost()
    compose.setContent {
      host.Provide {
        KeiHomeTheme {
          KeiHomeScaffold(Modifier.fillMaxSize()) {
            Os4DetailsSheet(visible, { dismissals++; visible = false }, title = "Preview",
              modifier = Modifier.testTag("glass.details"), onDismissFinished = { finished++ }) {
              Os4ViewTabs(listOf("Compare", "Light", "Dark"), selected, { selected = it })
              Text("Mode $selected", Modifier.testTag("glass.details.mode"))
            }
          }
        }
      }
    }
    compose.onNodeWithText("Dark").performClick().assertIsSelected()
    compose.runOnIdle { assertEquals(2, selected) }
    compose.runOnIdle { host.completeBack() }
    compose.waitForIdle()
    compose.runOnIdle {
      assertEquals(1, dismissals)
      assertEquals(1, finished)
      assertEquals(2, selected)
    }
    compose.onNodeWithTag("glass.details").assertDoesNotExist()
  }

  private fun assertOrdered(tags: List<String>) {
    compose.waitForIdle()
    val bounds = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
    bounds.zipWithNext().forEachIndexed { index, (first, second) ->
      assertTrue("${tags[index]} overlaps ${tags[index + 1]}: $first / $second",
        first.bottom > first.top && first.bottom <= second.top)
    }
  }

  @Test fun pressCancellationAndDisabledSurfacesNeverInvokeTheAction() {
    var enabled by mutableStateOf(true)
    var actions = 0
    compose.setContent {
      KeiHomeTheme {
        Box(Modifier.size(100.dp).os4Clickable(enabled = enabled, role = Role.Button) { actions++ }
          .testTag("glass.press")) { Text("Action") }
      }
    }
    compose.onNodeWithTag("glass.press").performTouchInput {
      down(center)
      moveTo(Offset(-100f, -100f))
      up()
    }
    compose.runOnIdle { assertEquals(0, actions) }
    compose.onNodeWithTag("glass.press").performClick()
    compose.runOnIdle { assertEquals(1, actions); enabled = false }
    compose.onNodeWithTag("glass.press").assertIsNotEnabled().performTouchInput { click(center) }
    compose.runOnIdle { assertEquals(1, actions) }
  }
}
