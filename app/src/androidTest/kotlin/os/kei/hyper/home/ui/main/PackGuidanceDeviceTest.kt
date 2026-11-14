package os.kei.hyper.home.ui.main

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.PackGuidancePage
import os.kei.hyper.home.data.PackGuidancePreferences
import os.kei.hyper.home.iconpack.IconPackDescription
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import os.kei.hyper.home.ui.components.KeiHomeScaffold

class PackGuidanceDeviceTest {
  private val rule = createComposeRule()
  @get:Rule val lifecycle = RuleChain.outerRule(ForegroundComposeHostRule()).around(rule)
  private val context: Context = ApplicationProvider.getApplicationContext()
  private fun text(id: Int) = context.getString(id)
  private val revisions = mutableMapOf<String, Int>()
  private val preferences = PackGuidancePreferences(
    { revisions[it] ?: 0 }, { key, revision -> revisions[key] = revision; true })

  @Test fun firstVisitOpensSheetOnceAndReentryRequiresManualHelp() {
    val entered = mutableStateOf(true)
    val note = mutableStateOf("Help-only explanation")
    rule.setContent { KeiHomeTheme { KeiHomeScaffold {
      if (entered.value) PackHelpAction(listOf(note.value, "Another help step"),
        PackGuidancePage.IMPORTED, preferences = preferences)
    } } }
    rule.onNodeWithText("Help-only explanation").assertExists()
    rule.runOnIdle {
      assertTrue(preferences.hasSeen(PackGuidancePage.IMPORTED))
      assertFalse(preferences.hasSeen(PackGuidancePage.INSTALLED))
    }
    dismiss()
    rule.onNodeWithText("Help-only explanation").assertDoesNotExist()
    rule.runOnIdle { note.value = "Updated explanation" }
    rule.onNodeWithText("Updated explanation").assertDoesNotExist()
    rule.runOnIdle { entered.value = false }
    rule.runOnIdle { entered.value = true }
    rule.onNodeWithText("Updated explanation").assertDoesNotExist()
    help(PackGuidancePage.IMPORTED)
    rule.onNodeWithText("Updated explanation").assertExists()
    dismiss()
  }

  @Test fun singleNoteUsesNativeSheetDismissAndAlwaysReopensManually() {
    rule.setContent { KeiHomeTheme { KeiHomeScaffold {
      PackHelpAction(listOf("Single short explanation"), PackGuidancePage.COLOROS,
        preferences = preferences)
    } } }
    rule.onNodeWithText("Single short explanation").assertExists()
    rule.onNodeWithTag("info.popup.host").assertDoesNotExist()
    rule.onNodeWithTag("pack-guidance.sheet.coloros")
      .performSemanticsAction(SemanticsActions.Dismiss) { it() }
    rule.waitForIdle()
    rule.onNodeWithText("Single short explanation").assertDoesNotExist()
    repeat(2) {
      help(PackGuidancePage.COLOROS)
      rule.onNodeWithText("Single short explanation").assertExists()
      dismiss()
      rule.onNodeWithText("Single short explanation").assertDoesNotExist()
    }
  }

  @Test fun revisionUpdateReopensOnceAndPagesKeepIndependentSeenRecords() {
    val revision = mutableStateOf(PackGuidancePreferences.CURRENT_REVISION - 1)
    val page = mutableStateOf(PackGuidancePage.APPLE)
    rule.setContent { KeiHomeTheme { KeiHomeScaffold {
      PackHelpAction(listOf("Revision guidance"), page.value,
        preferences = preferences, revision = revision.value)
    } } }
    rule.onNodeWithText("Revision guidance").assertExists()
    dismiss()
    rule.runOnIdle { revision.value = PackGuidancePreferences.CURRENT_REVISION }
    rule.onNodeWithText("Revision guidance").assertExists()
    dismiss()
    rule.runOnIdle { page.value = PackGuidancePage.PERFECT }
    rule.onNodeWithText("Revision guidance").assertExists()
    dismiss()
    rule.runOnIdle { page.value = PackGuidancePage.APPLE }
    rule.onNodeWithText("Revision guidance").assertDoesNotExist()
    help(PackGuidancePage.APPLE)
    rule.onNodeWithText("Revision guidance").assertExists()
    dismiss()
  }

  @Test fun inactiveTabNeitherShowsNorConsumesGuidance() {
    val active = mutableStateOf(false)
    rule.setContent { KeiHomeTheme { KeiHomeScaffold {
      PackHelpAction(listOf("Only for the active tab"), PackGuidancePage.INSTALLED,
        active = active.value, preferences = preferences)
    } } }
    rule.onNodeWithText("Only for the active tab").assertDoesNotExist()
    rule.runOnIdle {
      assertTrue(revisions.isEmpty())
      active.value = true
    }
    rule.onNodeWithText("Only for the active tab").assertExists()
    rule.runOnIdle { active.value = false }
    rule.onNodeWithText("Only for the active tab").assertDoesNotExist()
    rule.runOnIdle { active.value = true }
    rule.onNodeWithText("Only for the active tab").assertDoesNotExist()
    help(PackGuidancePage.INSTALLED)
    rule.onNodeWithText("Only for the active tab").assertExists()
    dismiss()
  }

  @Test fun nonResumedNavigationEntryWaitsUntilForeground() {
    val owner = object : LifecycleOwner {
      override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
        currentState = Lifecycle.State.STARTED
      }
    }
    rule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        KeiHomeTheme { KeiHomeScaffold {
          PackHelpAction(listOf("Foreground guidance"), PackGuidancePage.APPLE_MAPPINGS,
            preferences = preferences)
        } }
      }
    }
    rule.onNodeWithText("Foreground guidance").assertDoesNotExist()
    rule.runOnIdle {
      assertTrue(revisions.isEmpty())
      owner.lifecycle.currentState = Lifecycle.State.RESUMED
    }
    rule.onNodeWithText("Foreground guidance").assertExists()
    dismiss()
    rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
    rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    rule.onNodeWithText("Foreground guidance").assertDoesNotExist()
  }

  @Test fun failedSaveDoesNotRepeatInMountedPageAndRetriesOnFreshEntry() {
    val entered = mutableStateOf(true)
    val active = mutableStateOf(true)
    val note = mutableStateOf("Unsaved guidance")
    var writes = 0
    val failingPreferences = PackGuidancePreferences(
      { 0 }, { _, _ -> writes++; false })
    rule.setContent { KeiHomeTheme { KeiHomeScaffold {
      if (entered.value) PackHelpAction(listOf(note.value), PackGuidancePage.APPLE,
        active = active.value, preferences = failingPreferences)
    } } }
    rule.onNodeWithText("Unsaved guidance").assertExists()
    rule.runOnIdle {
      assertEquals("First automatic presentation attempts one save", 1, writes)
      assertFalse(failingPreferences.hasSeen(PackGuidancePage.APPLE))
    }
    rule.runOnIdle { note.value = "Visible recomposed guidance" }
    rule.onNodeWithText("Visible recomposed guidance").assertExists()
    rule.runOnIdle { assertEquals("Visible recomposition does not retry persistence", 1, writes) }
    dismiss()
    rule.runOnIdle { note.value = "Recomposed unsaved guidance" }
    rule.runOnIdle { active.value = false }
    rule.runOnIdle { active.value = true }
    rule.onNodeWithText("Recomposed unsaved guidance").assertDoesNotExist()
    rule.runOnIdle { assertEquals("Inactive and active transitions do not reopen or retry", 1, writes) }
    help(PackGuidancePage.APPLE)
    rule.onNodeWithText("Recomposed unsaved guidance").assertExists()
    rule.runOnIdle { assertEquals("Manual reopening attempts one additional save", 2, writes) }
    dismiss()
    rule.runOnIdle { entered.value = false }
    rule.runOnIdle { entered.value = true }
    rule.onNodeWithText("Recomposed unsaved guidance").assertExists()
    rule.runOnIdle {
      assertEquals("A fresh entry retries once while durable progress is unseen", 3, writes)
      assertFalse(failingPreferences.hasSeen(PackGuidancePage.APPLE))
    }
    dismiss()
  }

  @Test fun throwingReadsKeepHelpAvailableWithoutRepeatingUntilFreshEntry() {
    val entered = mutableStateOf(true)
    val active = mutableStateOf(false)
    var reads = 0
    var writes = 0
    val failingPreferences = PackGuidancePreferences(
      { reads++; throw IllegalStateException("Guidance storage unavailable") },
      { _, _ -> writes++; true },
    )
    rule.setContent { KeiHomeTheme { KeiHomeScaffold {
      if (entered.value) PackHelpAction(listOf("Unreadable guidance"), PackGuidancePage.PERFECT,
        active = active.value, preferences = failingPreferences)
    } } }
    rule.onNodeWithText("Unreadable guidance").assertDoesNotExist()
    rule.runOnIdle {
      assertEquals(0, reads)
      active.value = true
    }
    rule.onNodeWithText("Unreadable guidance").assertExists()
    dismiss()
    var previousReads = 0
    rule.runOnIdle {
      assertTrue(reads > 0)
      assertEquals(0, writes)
      previousReads = reads
      active.value = false
    }
    rule.runOnIdle { active.value = true }
    rule.onNodeWithText("Unreadable guidance").assertDoesNotExist()
    rule.runOnIdle { assertEquals(previousReads, reads) }
    help(PackGuidancePage.PERFECT)
    rule.onNodeWithText("Unreadable guidance").assertExists()
    dismiss()
    rule.runOnIdle {
      previousReads = reads
      entered.value = false
    }
    rule.runOnIdle { entered.value = true }
    rule.onNodeWithText("Unreadable guidance").assertExists()
    rule.runOnIdle {
      assertTrue(reads > previousReads)
      assertEquals(0, writes)
    }
    dismiss()
  }

  private fun help(page: PackGuidancePage) {
    rule.onNodeWithTag("pack-guidance.help.${page.storageKey}").performClick()
  }

  private fun dismiss() {
    rule.onNodeWithText(text(R.string.pack_guidance_done)).performClick()
    rule.waitForIdle()
  }

  @Test fun authorsAndRegenerationStayVisibleWithoutAnExplanationToggle() {
    var imports = 0
    card(author = "Mr_Bocchi", enabled = true) { imports++ }
    rule.onNodeWithText(context.getString(R.string.pack_guidance_author, "Mr_Bocchi")).assertExists()
    rule.onNodeWithText(text(R.string.pack_action_apply_short)).assertIsEnabled()
    rule.onNodeWithText(text(R.string.pack_action_update_short)).performClick()
    rule.runOnIdle { assertEquals(1, imports) }
    rule.onNodeWithText(text(R.string.icon_pack_details)).assertDoesNotExist()
    rule.onNodeWithText(text(R.string.icon_pack_appearance_preview_note)).assertDoesNotExist()
  }

  @Test fun missingAuthorIsNotInventedAndBusyRegenerationIsDisabled() {
    card(author = null, enabled = false) { error("Busy action must not run") }
    rule.onNodeWithText(text(R.string.pack_action_update_short)).assertIsNotEnabled()
    rule.onNodeWithText(context.getString(R.string.pack_guidance_author, "Unknown")).assertDoesNotExist()
  }

  private fun card(author: String?, enabled: Boolean, onImport: () -> Unit) {
    val pack = IconPackDescriptor("test.pack", "Test pack", IconPackFormat.INSTALLED_APK,
      IconPackSource.Installed("test.pack", 1, 0, "1"), emptyList(), description = IconPackDescription(author = author))
    val previews = IconPackPreviewRepository(context)
    rule.setContent { KeiHomeTheme {
      IconPackCard(IconPackCardData(pack, 0, emptyList()), previews, IconPackAppearance.SYSTEM,
        nativeId = "fixture", current = false, importing = false, enabled = enabled,
        result = null, onImport = onImport, onRecheck = {})
    } }
  }
}
