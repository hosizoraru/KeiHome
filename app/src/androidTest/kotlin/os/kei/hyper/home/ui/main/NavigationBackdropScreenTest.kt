package os.kei.hyper.home.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.core.content.pm.PackageInfoCompat
import os.kei.hyper.home.R
import os.kei.hyper.home.data.BILIBILI_PACKAGE
import os.kei.hyper.home.data.NavigationBackdropChoice
import os.kei.hyper.home.status.NavigationBackdropStatus
import os.kei.hyper.home.status.BilibiliArtworkOutcome
import os.kei.hyper.home.status.BilibiliArtworkReceipt
import os.kei.hyper.home.status.BilibiliInstallation
import os.kei.hyper.home.ui.theme.KeiHomeTheme

/** Pure presentation fixtures never write framework preferences or alter native app rules. */
class NavigationBackdropScreenTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val installedVersion get() = PackageInfoCompat.getLongVersionCode(
    context.packageManager.getPackageInfo(context.packageName, 0))
  private val apps = listOf(NavigationBackdropApp("test.first", "First"),
    NavigationBackdropApp("test.second", "Second", systemApp = true))

  private fun show(
    page: NavigationBackdropPage = NavigationBackdropPage.OVERVIEW,
    choice: NavigationBackdropChoice = NavigationBackdropChoice(),
    state: NavigationBackdropUiState = NavigationBackdropUiState(choice, apps, loading = false),
    backend: NavigationBackdropStatus? =
      NavigationBackdropStatus(installedVersion, true, choice.fingerprint()),
    onToggle: (String) -> Unit = {},
    onEnabled: (Boolean) -> Unit = {},
    onGlassHandle: (Boolean) -> Unit = {},
    onRefresh: () -> Unit = {},
    onBilibiliArtwork: (Boolean) -> Unit = {},
    onOpenManager: () -> Unit = {},
    bilibili: BilibiliInstallation? = null,
    receipt: BilibiliArtworkReceipt? = null,
  ) {
    rule.setContent {
      KeiHomeTheme {
        NavigationBackdropContent(page, state, backend, {}, {}, onEnabled, onToggle, onRefresh,
          onBilibiliArtwork, onOpenManager, bilibili, receipt, onGlassHandle)
      }
    }
  }

  @Test fun artworkPillsUseCurrentInstallationAndIgnoreAnOlderReceipt() {
    val choice = NavigationBackdropChoice(bilibiliArtwork = true)
    val state = NavigationBackdropUiState(choice, apps, loading = false, bilibiliInScope = true)
    show(choice = choice, state = state, bilibili = BilibiliInstallation("9.20.0", 9200300, 20L),
      receipt = BilibiliArtworkReceipt(9200300, 10L, BilibiliArtworkOutcome.DRAWN))
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_bilibili_version, "9.20.0"))
      .assertExists()
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_artwork_waiting)).assertExists()
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_artwork_drawn)).assertDoesNotExist()
  }

  @Test fun overviewDoesNotEmbedLongInstructionsOrAppInventory() {
    show()
    rule.onNodeWithTag("nav-backdrop.enabled").performScrollTo().assertIsDisplayed().assertIsEnabled()
    rule.onNode(hasText(context.getString(R.string.nav_backdrop_enable_summary)) and
      hasAnyAncestor(hasTestTag("nav-backdrop.enabled")), useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithTag("nav-backdrop.open.apps").performScrollTo().assertIsDisplayed()
    rule.onNodeWithTag("nav-backdrop.help").assertIsDisplayed()
    rule.onNodeWithTag("nav-backdrop.search").assertDoesNotExist()
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_restart)).assertDoesNotExist()
    rule.onNodeWithTag("nav-backdrop.app:test.first").assertDoesNotExist()
  }

  @Test fun bilibiliWithoutScopeLinksToManagerAndCannotEnableArtwork() {
    val choice = NavigationBackdropChoice()
    val state = NavigationBackdropUiState(choice, apps + NavigationBackdropApp(BILIBILI_PACKAGE, "Bilibili"),
      loading = false, bilibiliInScope = false)
    var opened = false
    show(choice = choice, state = state, onOpenManager = { opened = true })
    scrollToArtwork()
    rule.onNodeWithTag("nav-backdrop.bilibili").assertIsNotEnabled()
    rule.onNodeWithTag("nav-backdrop.bilibili.scope").performScrollTo().performClick()
    rule.runOnIdle { assertEquals(true, opened) }
  }

  @Test fun grantedBilibiliScopeAllowsArtworkWithGeneralCompatibilityOff() {
    val choice = NavigationBackdropChoice()
    val state = NavigationBackdropUiState(choice, apps + NavigationBackdropApp(BILIBILI_PACKAGE, "Bilibili"),
      loading = false, bilibiliInScope = true)
    var enabled = false
    show(choice = choice, state = state, onBilibiliArtwork = { enabled = it })
    scrollToArtwork()
    rule.onNodeWithTag("nav-backdrop.bilibili").assertIsEnabled().performScrollTo()
      .performSemanticsAction(SemanticsActions.OnClick)
    rule.runOnIdle { assertEquals(true, enabled) }
  }

  @Test fun artworkSelectionAppearsInSelectedAppsAndCannotBeManuallyRemoved() {
    val choice = NavigationBackdropChoice(bilibiliArtwork = true)
    val state = NavigationBackdropUiState(choice, apps + NavigationBackdropApp(BILIBILI_PACKAGE, "Bilibili"),
      loading = false, bilibiliInScope = true)
    show(NavigationBackdropPage.APPS, choice = choice, state = state)
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_saved, 1)).performClick()
    rule.onNodeWithTag("nav-backdrop.app:$BILIBILI_PACKAGE").assertIsOn().assertIsNotEnabled()
    rule.onNodeWithTag("nav-backdrop.app:test.first").assertDoesNotExist()
  }

  private fun scrollToArtwork() {
    rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("nav-backdrop.list")))
      .performScrollToNode(hasTestTag("nav-backdrop.bilibili"))
  }

  @Test fun statusCardRefreshesAndHelpOpensAsADismissibleSheet() {
    var refreshes = 0
    show(onRefresh = { refreshes++ })
    rule.onNodeWithTag("nav-backdrop.status").performClick()
    rule.runOnIdle { assertEquals(1, refreshes) }
    rule.onNodeWithTag("nav-backdrop.help").performClick()
    rule.onNodeWithTag("nav-backdrop.help.sheet").assertIsDisplayed()
      .performSemanticsAction(SemanticsActions.Dismiss)
    rule.onNodeWithTag("nav-backdrop.help.sheet").assertDoesNotExist()
    rule.onNodeWithTag("nav-backdrop.status").assertIsDisplayed()
  }

  @Test fun downwardDragAtTopRevealsSearchWithoutChangingSelection() {
    val choice = NavigationBackdropChoice(packages = setOf("test.second"))
    show(NavigationBackdropPage.APPS, choice = choice)
    rule.onNodeWithTag("nav-backdrop.search").assertDoesNotExist()
    rule.onNodeWithTag("nav-backdrop.list").performTouchInput {
      swipe(Offset(center.x, height * 0.45f), Offset(center.x, height * 0.83f))
    }
    rule.onNodeWithTag("nav-backdrop.search").assertIsDisplayed()
    rule.onNodeWithTag("nav-backdrop.app:test.second").assertIsOn()
  }

  @Test fun appOriginFilterDoesNotChangeSelectedCount() {
    show(NavigationBackdropPage.APPS, choice = NavigationBackdropChoice(packages = setOf("test.first")))
    rule.onNodeWithTag("nav-backdrop.filter").performClick()
    rule.onNodeWithText(context.getString(R.string.browser_origin_system)).performClick()
    rule.onNodeWithTag("nav-backdrop.app:test.first").assertDoesNotExist()
    rule.onNodeWithTag("nav-backdrop.app:test.second").assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_saved, 1)).assertIsDisplayed()
    rule.onNodeWithTag("nav-backdrop.sort").performClick()
    rule.onNodeWithText(context.getString(R.string.browser_sort_reverse)).performClick()
    rule.onNodeWithTag("nav-backdrop.app:test.second").assertIsDisplayed()
  }

  @Test fun glassHandleCanBeEnabledWithoutSelectingApps() {
    var glass = false
    show(backend = null, onGlassHandle = { glass = it })
    rule.onNodeWithTag("nav-backdrop.glass").assertIsEnabled().performClick()
    rule.runOnIdle { assertEquals(true, glass) }
  }

  @Test fun failedReadBlocksEditsEvenWithPreviouslyLoadedChoice() {
    val choice = NavigationBackdropChoice()
    show(choice = choice, state = NavigationBackdropUiState(choice, apps, loading = false, failed = true))
    rule.onNodeWithTag("nav-backdrop.enabled").assertIsNotEnabled()
  }

  @Test fun unavailableBackendStillAllowsAnEnabledChoiceToBeTurnedOff() {
    val choice = NavigationBackdropChoice(enabled = true)
    var disabled = false
    show(choice = choice, backend = null, onEnabled = { disabled = !it })
    rule.onNodeWithTag("nav-backdrop.enabled").assertIsEnabled().performClick()
    rule.runOnIdle { assertEquals(true, disabled) }
  }

  @Test fun appSelectionFiltersCachedRowsAndKeepsMissingSelectionsRemovable() {
    val choice = NavigationBackdropChoice(packages = setOf("test.missing"))
    var toggled: String? = null
    show(NavigationBackdropPage.APPS, choice = choice, onToggle = { toggled = it })
    rule.onNodeWithTag("nav-backdrop.open.search").performClick()
    rule.onNodeWithTag("nav-backdrop.search").performTextInput("test.missing")
    rule.onNodeWithTag("nav-backdrop.search").assertTextContains("test.missing")
    rule.onNodeWithTag("nav-backdrop.app:test.first").assertDoesNotExist()
    rule.onNodeWithTag("nav-backdrop.app:test.missing").assertIsOn().performClick()
    rule.runOnIdle { assertEquals("test.missing", toggled) }
  }

  @Test fun selectionLimitDisablesNewChoicesButAllowsRemoval() {
    val packages = (1 until 64).map { "test.missing$it" }.toSet() + "test.first"
    val choice = NavigationBackdropChoice(packages = packages)
    show(NavigationBackdropPage.APPS, choice = choice)
    rule.onNodeWithTag("nav-backdrop.app:test.first").assertIsEnabled()
    rule.onNodeWithTag("nav-backdrop.app:test.second").assertIsNotEnabled()
  }

  @Test fun selectedSegmentUsesTheSameInventoryAndShowsOnlyCurrentChoices() {
    val choice = NavigationBackdropChoice(packages = setOf("test.second"))
    show(NavigationBackdropPage.APPS, choice = choice)
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_saved, 1)).performClick()
    rule.onNodeWithTag("nav-backdrop.app:test.second").assertIsDisplayed().assertIsOn()
    rule.onNodeWithTag("nav-backdrop.app:test.first").assertDoesNotExist()
  }

  @Test fun oldVersionReceiptDoesNotAppearAsCurrentSystemConfirmation() {
    val choice = NavigationBackdropChoice()
    show(choice = choice,
      backend = NavigationBackdropStatus(installedVersion - 1, true, choice.fingerprint()))
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_pending_short)).assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_ready_short)).assertDoesNotExist()
  }

  @Test fun differentChoiceReceiptShowsWaitingInsteadOfReady() {
    show(backend = NavigationBackdropStatus(installedVersion, true, "old-choice"))
    rule.onNodeWithText(context.getString(R.string.nav_backdrop_waiting_short)).assertIsDisplayed()
  }
}
