package os.kei.hyper.home.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PackFileImportUiTest {
  private val backHost = Os4NavigationTestHost()
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun apkGuideHandsOffOnceAfterSheetExit() {
    var picks = 0
    rule.setContent {
      backHost.Provide {
        MiuixTheme {
          KeiHomeScaffold(floatingActionButton = {
            PackFileImportButton("installed", false, true, onChooseFile = { picks++ })
          }) {}
        }
      }
    }
    rule.onNodeWithTag("packs.file-import.action").assertIsDisplayed().performClick()
    rule.onNodeWithText(context.getString(R.string.pack_file_import_apk_title)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.pack_file_import_preview_title)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.pack_file_import_apply_title)).assertIsDisplayed()
    rule.onNodeWithTag("packs.file-import.action").assertIsNotEnabled()
    val choose = rule.onNodeWithTag("packs.file-import.choose")
    choose.assertIsDisplayed()
    val click = choose.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    rule.runOnIdle {
      assertEquals(0, picks)
      click()
      click()
      assertEquals("Picker must wait for the sheet to exit", 0, picks)
    }
    rule.waitUntil(5_000) { picks == 1 }
    rule.onNodeWithTag("packs.file-import.sheet").assertDoesNotExist()
    rule.onNodeWithTag("packs.file-import.action").performClick()
    rule.onNodeWithTag("packs.file-import.choose").assertIsDisplayed()
    rule.runOnIdle { backHost.completeBack() }
    rule.waitUntil(5_000) {
      runCatching { rule.onNodeWithTag("packs.file-import.sheet").assertDoesNotExist(); true }.getOrDefault(false)
    }
    rule.runOnIdle { assertEquals(1, picks) }
  }

  @Test fun archiveGuideIncludesScopeHelpAndBackDoesNotOpenPicker() {
    var picks = 0
    rule.setContent {
      backHost.Provide {
        MiuixTheme {
          KeiHomeScaffold(floatingActionButton = {
            PackFileImportButton("imported", false, false, onChooseFile = { picks++ })
          }) {}
        }
      }
    }
    rule.onNodeWithTag("packs.file-import.action").performClick()
    rule.onNodeWithText(context.getString(R.string.pack_file_import_archive_title)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.pack_file_import_archive_file)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.icon_pack_scope_needed)).assertIsDisplayed()
    rule.onNodeWithTag("packs.file-import.choose").assertIsDisplayed()
    rule.runOnIdle { backHost.completeBack() }
    rule.waitUntil(5_000) {
      runCatching { rule.onNodeWithTag("packs.file-import.sheet").assertDoesNotExist(); true }.getOrDefault(false)
    }
    rule.runOnIdle { assertEquals(0, picks) }
  }

  @Test fun busyStatePreventsEntryAndFileSelection() {
    var busy by mutableStateOf(true)
    var picks = 0
    rule.setContent {
      backHost.Provide {
        MiuixTheme {
          KeiHomeScaffold(floatingActionButton = {
            PackFileImportButton("imported", busy, true, onChooseFile = { picks++ })
          }) {}
        }
      }
    }
    val action = rule.onNodeWithTag("packs.file-import.action")
    action.assertIsNotEnabled().performClick()
    rule.onNodeWithTag("packs.file-import.sheet").assertDoesNotExist()
    rule.runOnIdle { busy = false }
    action.performClick()
    val click = rule.onNodeWithTag("packs.file-import.choose")
      .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    rule.runOnIdle { busy = true }
    rule.onNodeWithTag("packs.file-import.choose").assertIsNotEnabled()
    rule.runOnIdle { click(); assertEquals(0, picks); backHost.completeBack() }
    rule.waitUntil(5_000) {
      runCatching { rule.onNodeWithTag("packs.file-import.sheet").assertDoesNotExist(); true }.getOrDefault(false)
    }
    rule.runOnIdle { assertEquals(0, picks) }
  }
}
