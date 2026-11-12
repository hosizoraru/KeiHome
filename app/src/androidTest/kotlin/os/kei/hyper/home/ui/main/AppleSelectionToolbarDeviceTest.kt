package os.kei.hyper.home.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.iconpack.apple.AppleAppMatch
import os.kei.hyper.home.iconpack.apple.AppleAppTarget
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class AppleSelectionToolbarDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()
  private val mapped = AppleMappingRow(AppleAppTarget("test.mapped", "Mapped", false),
    AppleAppMatch("123456", "Apple app", "Publisher"), custom = false)
  private val excluded = AppleMappingRow(AppleAppTarget("test.excluded", "Excluded", false), null, custom = true)
  private val unmatched = AppleMappingRow(AppleAppTarget("test.unmatched", "Unmatched", false), null, custom = false)

  @Test fun selectAllAndClearKeepMatchAndExportTargetsSeparate() {
    val rows = listOf(mapped, excluded, unmatched)
    var chosen by mutableStateOf(emptyList<String>())
    var matched = emptySet<String>()
    var exported = emptySet<String>()
    compose.setContent {
      KeiHomeTheme {
        val selection = appleMappingSelection(rows, chosen, allowMatch = true, now = 0)
        AppleSelectionToolbar(selection, allowMatch = true, enabled = true, matchingEnabled = true,
          onSelectAll = { chosen = selection.selectable.toList() },
          onClear = { chosen = emptyList() }, onMatch = { matched = selection.match },
          onExport = { exported = selection.export.map { it.target.packageName }.toSet() },
          onDone = { chosen = emptyList() })
      }
    }
    compose.onNodeWithTag("apple.selection.match").assertIsNotEnabled()
    compose.onNodeWithTag("apple.selection.export").assertIsNotEnabled()
    compose.onNodeWithTag("apple.selection.all").performClick()
    compose.onNodeWithTag("apple.selection.all").assertIsNotEnabled()
    compose.onNodeWithTag("apple.selection.match").performClick()
    compose.onNodeWithTag("apple.selection.export").performClick()
    compose.runOnIdle {
      assertEquals(setOf("test.unmatched"), matched)
      assertEquals(setOf("test.mapped", "test.excluded"), exported)
    }
    compose.onNodeWithTag("apple.selection.none").performClick()
    compose.onNodeWithTag("apple.selection.match").assertIsNotEnabled()
    compose.onNodeWithTag("apple.selection.export").assertIsNotEnabled()
    compose.runOnIdle { assertTrue(chosen.isEmpty()) }
  }

  @Test fun matchedPreviewGalleryOffersExportWithoutAnUnsupportedMatchOrRemoveAction() {
    val selection = appleMappingSelection(listOf(mapped), listOf("test.mapped"), allowMatch = false, now = 0)
    var exported = false
    compose.setContent {
      KeiHomeTheme {
        AppleSelectionToolbar(selection, allowMatch = false, enabled = true, matchingEnabled = true,
          onSelectAll = {}, onClear = {}, onMatch = { error("Gallery must not expose auto-match") },
          onExport = { exported = true }, onDone = {})
      }
    }
    compose.onNodeWithTag("apple.selection.match").assertDoesNotExist()
    compose.onNodeWithTag("apple.selection.remove").assertDoesNotExist()
    compose.onNodeWithTag("apple.selection.export").assertIsEnabled().performClick()
    compose.runOnIdle { assertTrue(exported) }
  }

  @Test fun cooldownBlocksMatchingButKeepsExportAvailableAndBusySelectionCanAlwaysClose() {
    val rows = listOf(mapped, unmatched)
    val selection = appleMappingSelection(rows, rows.map { it.target.packageName }, allowMatch = true, now = 0)
    var enabled by mutableStateOf(true)
    var closed = false
    compose.setContent {
      KeiHomeTheme {
        AppleSelectionToolbar(selection, allowMatch = true, enabled = enabled, matchingEnabled = false,
          onSelectAll = {}, onClear = {}, onMatch = { error("Cooldown must block matching") },
          onExport = {}, onDone = { closed = true })
      }
    }
    compose.onNodeWithTag("apple.selection.match").assertIsNotEnabled()
    compose.onNodeWithTag("apple.selection.export").assertIsEnabled()
    compose.runOnIdle { enabled = false }
    compose.onNodeWithTag("apple.selection.export").assertIsNotEnabled()
    compose.onNodeWithTag("apple.selection.none").assertIsNotEnabled()
    compose.onNodeWithTag("apple.selection.done").assertIsEnabled().performClick()
    compose.runOnIdle { assertTrue(closed) }
  }
}
