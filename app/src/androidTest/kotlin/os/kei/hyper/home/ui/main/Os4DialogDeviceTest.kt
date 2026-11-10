// Provenance: KeiHome-authored integration test using public Compose/MIUIX APIs.
// MIUIX: https://github.com/compose-miuix-ui/miuix (Apache-2.0),
// revision ca674992c9645395cdb0d5109d66504ff53d018c; API references recorded in Os4Dialogs.kt.
// Local harness: ForegroundComposeHostRule and the shared-scaffold pattern in Os4ChromeDeviceTest.
// No reference-app code copied or translated; no preference writes.
package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.Os4Dialog
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class Os4DialogDeviceTest {
  private val backHost = Os4NavigationTestHost()
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun glassDialogHandlesPhysicalActionsBackAndAccessibleDismissInBothThemes() {
    assumeTrue(isRuntimeShaderSupported())
    exerciseDialog(glass = true)
  }

  @Test fun nullBackdropDialogHandlesPhysicalActionsBackAndAccessibleDismissInBothThemes() {
    exerciseDialog(glass = false)
  }

  private fun exerciseDialog(glass: Boolean) {
    var dark by mutableStateOf(false)
    var stage by mutableStateOf(0)
    var deleteEnabled by mutableStateOf(false)
    var deletions = 0
    var cancellations = 0
    var dismissals = 0
    var finished = 0
    rule.setContent {
      val controller = remember(dark) {
        ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
      }
      backHost.Provide {
      CompositionLocalProvider(LocalKeiHomeDarkTheme provides dark) {
        MiuixTheme(controller = controller) {
          val source = rememberLayerBackdrop()
          KeiHomeScaffold(backdrop = source.takeIf { glass }) { padding ->
            // The recorded page is a sibling of the dialog, so neither scrim nor dialog is sampled.
            Box(Modifier.fillMaxSize().layerBackdrop(source).background(
              Brush.verticalGradient(listOf(Color(0xFF278DC4), MiuixTheme.colorScheme.surface)))) {
              Column(Modifier.padding(padding).padding(24.dp)) {
                Text("Dialog test page")
                TextButton("Open dialog", onClick = { stage = 1 })
              }
            }
            Os4Dialog(show = stage != 0,
              title = if (stage == 2) "Confirm deletion" else "Dialog help",
              modifier = Modifier.testTag("os4.dialog"),
              onDismissRequest = { dismissals++; stage = 0 },
              onDismissFinished = { finished++ }) {
              Column {
                Text(if (stage == 2) "Deletion requires a second confirmation." else "Review the action first.")
                Os4DialogButton("Cancel", modifier = Modifier.fillMaxWidth(),
                  onClick = { cancellations++; stage = 0 })
                if (stage == 2) {
                  Os4DialogButton("Delete", modifier = Modifier.fillMaxWidth(),
                    enabled = deleteEnabled, role = GlassDialogButtonRole.Destructive,
                    onClick = { deletions++; stage = 0 })
                } else {
                  Os4DialogButton("Continue", modifier = Modifier.fillMaxWidth(),
                    role = GlassDialogButtonRole.Primary, onClick = { stage = 2 })
                }
              }
            }
          }
        }
      }
      }
    }

    for ((index, night) in listOf(false, true).withIndex()) {
      rule.runOnIdle { dark = night; deleteEnabled = false }
      tap("Open dialog")
      tap("Cancel")
      assertClosed()
      rule.runOnIdle { assertEquals(index + 1, cancellations); assertEquals(index, deletions) }

      tap("Open dialog")
      tap("Continue")
      rule.onNodeWithText("Confirm deletion").assertIsDisplayed()
      rule.onNodeWithText("Delete").assertIsNotEnabled()
      tap("Delete")
      rule.runOnIdle { assertEquals(2, stage); assertEquals(index, deletions) }
      completeBackEvent()
      assertClosed()
      rule.runOnIdle { assertEquals(index * 3 + 1, dismissals); assertEquals(index, deletions) }

      tap("Open dialog")
      tap("Continue")
      rule.runOnIdle { deleteEnabled = true }
      tap("Delete")
      assertClosed()
      rule.runOnIdle { assertEquals(index + 1, deletions) }

      tap("Open dialog")
      completeBackEvent()
      assertClosed()

      tap("Open dialog")
      rule.onNodeWithTag("os4.dialog").performSemanticsAction(SemanticsActions.Dismiss) { dismiss ->
        dismiss()
      }
      assertClosed()
      rule.runOnIdle {
        assertEquals((index + 1) * 3, dismissals)
        assertEquals(index + 1, deletions)
        assertEquals((index + 1) * 5, finished)
      }
    }
  }

  private fun completeBackEvent() {
    rule.runOnIdle { backHost.completeBack() }
    rule.waitForIdle()
  }

  private fun tap(text: String) {
    rule.onNodeWithText(text).assertIsDisplayed().performTouchInput { click(center) }
    rule.waitForIdle()
  }

  private fun assertClosed() {
    rule.waitForIdle()
    rule.onNodeWithTag("os4.dialog").assertDoesNotExist()
    rule.onNodeWithText("Open dialog").assertIsDisplayed()
  }
}
