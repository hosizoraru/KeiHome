package os.kei.hyper.home.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.perfect.PerfectIconCatalog
import os.kei.hyper.home.iconpack.perfect.PerfectIconOptions
import os.kei.hyper.home.iconpack.perfect.PerfectIconRemote
import os.kei.hyper.home.iconpack.perfect.PerfectIconStyleDetails
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import os.kei.hyper.home.ui.perfect.PerfectDownloadStatusCard
import os.kei.hyper.home.ui.perfect.PerfectPackOptions
import os.kei.hyper.home.ui.perfect.PerfectPackState
import os.kei.hyper.home.ui.perfect.PerfectResourceConfirmations
import os.kei.hyper.home.ui.perfect.PerfectResourceContent
import os.kei.hyper.home.ui.perfect.PerfectResourceStyleDialogState
import os.kei.hyper.home.ui.perfect.PerfectSyncBadges
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.BasicComponent

class PerfectGitUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun checkingRemoteMetadataDoesNotCreateOrAdvanceTheSuccessfulSyncBadge() {
    var state by mutableStateOf(PerfectPackState(
      remote = PerfectIconRemote(revision = "a".repeat(40), checkedAt = 2_000)))
    compose.setContent {
      KeiHomeTheme {
        FlowRow { PerfectSyncBadges(state, MiuixTheme.colorScheme.primary) }
      }
    }
    compose.onNodeWithTag("perfect.sync.remote-status").assertIsDisplayed()
    compose.onAllNodesWithTag("perfect.sync.success").assertCountEquals(0)
    compose.runOnIdle {
      state = state.copy(catalog = PerfectIconCatalog(
        revision = "a".repeat(40), styles = listOf("default"), updatedAt = 1_000, iconCount = 10),
        lastSuccessfulSyncAtMillis = 1_000)
    }
    val lastSuccess = compose.onNodeWithTag("perfect.sync.success")
      .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
    compose.runOnIdle {
      state = state.copy(remote = PerfectIconRemote(revision = "b".repeat(40), checkedAt = 3_000))
    }
    assertEquals(lastSuccess, compose.onNodeWithTag("perfect.sync.success")
      .fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
    compose.runOnIdle { assertEquals(1_000L, state.lastSuccessfulSyncAtMillis) }
  }

  @Test fun deletingResourcesRequiresOs4ConfirmationAndRetainsCancelWhileBusy() {
    exerciseDestructiveAction(deleteResources = true)
  }

  @Test fun clearingCachesRequiresOs4ConfirmationAndRetainsCancelWhileBusy() {
    exerciseDestructiveAction(deleteResources = false)
  }

  @Test fun batchDownloadRequiresExplicitActionAndKeepsTheCurrentPreviewStyle() {
    val state = resourceState()
    val requests = mutableListOf<Set<String>>()
    setResourceContent(state, onDownload = { requests += it })
    compose.onNodeWithTag("perfect.resources.selection-mode").performClick()
    compose.onNodeWithTag("perfect.resources.style.default").performScrollTo().performClick()
    compose.onNodeWithTag("perfect.resources.style.Square").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(emptyList<Set<String>>(), requests) }
    compose.onNodeWithTag("perfect.resources.download").performClick()
    compose.runOnIdle {
      assertEquals(listOf(setOf("default", "Square")), requests)
      assertEquals("Rounded", state.options.style)
    }
    compose.onAllNodesWithTag("perfect.resources.download").assertCountEquals(0)
    compose.onAllNodesWithTag("perfect.resources.select.default").assertCountEquals(0)
  }

  @Test fun batchRemovalOnlyIncludesDownloadedSelectionAndRequiresConfirmationAfterCancel() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val state = resourceState()
    val requests = mutableListOf<Set<String>>()
    setResourceContent(state, onRemove = { requests += it })
    compose.onNodeWithTag("perfect.resources.selection-mode").performClick()
    listOf("default", "Rounded", "Square").forEach {
      compose.onNodeWithTag("perfect.resources.style.$it").performScrollTo().performClick()
    }
    compose.onNodeWithTag("perfect.resources.remove").performClick()
    val summary = context.getString(R.string.perfect_git_remove_confirm, 2,
      context.getString(R.string.perfect_icons_style_default) + ", Rounded",
      Formatter.formatFileSize(context, state.resourceBytes)) + "\n" +
      context.getString(R.string.perfect_git_remove_all_scope)
    waitForDisplayed(hasText(summary), "batch-remove-scope")
    compose.onNodeWithText(summary).assertIsDisplayed()
    waitForDisplayed(hasTestTag("perfect.resources.cancel-remove"), "batch-remove-presented")
    waitForDisplayed(hasTestTag("perfect.resources.confirm-remove"), "batch-remove-confirm-presented")
    compose.runOnIdle { assertEquals(emptyList<Set<String>>(), requests) }
    compose.onNodeWithTag("perfect.resources.cancel-remove").performClick()
    waitForDismissed("perfect.resources.cancel-remove")
    compose.runOnIdle { assertEquals(emptyList<Set<String>>(), requests) }
    compose.onNodeWithTag("perfect.resources.remove").performClick()
    waitForDisplayed(hasTestTag("perfect.resources.confirm-remove"), "batch-remove-reopened")
    compose.onNodeWithTag("perfect.resources.confirm-remove").performClick()
    waitForDismissed("perfect.resources.confirm-remove")
    compose.runOnIdle {
      assertEquals(listOf(setOf("default", "Rounded")), requests)
      assertEquals("Rounded", state.options.style)
    }
    compose.onAllNodesWithTag("perfect.resources.remove").assertCountEquals(0)
  }

  @Test fun browsingHasNoSelectionChromeAndSingleStyleOpensActions() {
    val requests = mutableListOf<Set<String>>()
    setResourceContent(resourceState(), onDownload = { requests += it })
    compose.onNodeWithTag("perfect.resources.status").assertIsDisplayed()
    listOf("all", "none", "download", "remove", "select.default").forEach {
      compose.onAllNodesWithTag("perfect.resources.$it").assertCountEquals(0)
    }
    compose.onNodeWithTag("perfect.resources.style.default").performScrollTo().performClick()
    val context = ApplicationProvider.getApplicationContext<Context>()
    waitForDisplayed(hasText(context.getString(R.string.perfect_resource_style_ready)), "single-style-actions")
    compose.onNodeWithText(context.getString(R.string.action_label_update)).performClick()
    waitForDismissed("perfect.resources.style-download")
    compose.runOnIdle { assertEquals(listOf(setOf("default")), requests) }
  }

  @Test fun styleDialogReopenCancelsPendingHandoffAndRejectsStaleCallbacks() {
    val dialog = PerfectResourceStyleDialogState()
    dialog.open("default")
    val first = dialog.session!!
    dialog.dismiss(first.id, PerfectResourceStyleDialogState.Action.PREVIEW)
    assertEquals("default", dialog.session!!.style)
    assertEquals(false, dialog.session!!.show)
    dialog.dismiss(first.id, PerfectResourceStyleDialogState.Action.DOWNLOAD)
    dialog.dismiss(first.id)
    assertEquals(PerfectResourceStyleDialogState.Action.PREVIEW, dialog.session!!.action)

    dialog.open("default")
    val reopened = dialog.session!!
    assertTrue(reopened.show)
    assertNull(reopened.action)
    dialog.dismiss(first.id, PerfectResourceStyleDialogState.Action.DOWNLOAD)
    assertEquals(reopened, dialog.session)
    assertNull(dialog.finish(first.id))
    assertNull(dialog.finish(reopened.id))
    dialog.dismiss(reopened.id, PerfectResourceStyleDialogState.Action.REMOVE)
    dialog.dismiss(first.id, PerfectResourceStyleDialogState.Action.DOWNLOAD)
    assertNull(dialog.finish(first.id))
    val finished = dialog.finish(reopened.id)!!
    assertEquals("default", finished.style)
    assertEquals(PerfectResourceStyleDialogState.Action.REMOVE, finished.action)
    assertNull(dialog.finish(reopened.id))
    assertNull(dialog.session)
  }

  @Test fun styleDetailsBackKeepsContentUntilExitAndReopensWithoutActions() {
    val host = Os4NavigationTestHost()
    val requests = mutableListOf<Set<String>>()
    val previews = mutableListOf<String>()
    setResourceContent(resourceState(), backHost = host, onDownload = { requests += it },
      onRemove = { requests += it }, onPreview = { previews += it })
    compose.onNodeWithTag("perfect.resources.style.default").performScrollTo().performClick()
    waitForDisplayed(hasTestTag("perfect.resources.style-close"), "style-details-before-back")
    compose.mainClock.autoAdvance = false
    compose.runOnIdle { host.completeBack() }
    compose.mainClock.advanceTimeByFrame()
    compose.onAllNodesWithTag("perfect.resources.style-close").assertCountEquals(1)
    compose.mainClock.autoAdvance = true
    waitForDismissed("perfect.resources.style-close")
    compose.runOnIdle { assertTrue(requests.isEmpty()); assertTrue(previews.isEmpty()) }

    compose.onNodeWithTag("perfect.resources.style.Rounded").performScrollTo().performClick()
    waitForDisplayed(hasTestTag("perfect.resources.style-close"), "style-details-reopened")
    compose.onNodeWithTag("perfect.resources.style-close").performClick()
    waitForDismissed("perfect.resources.style-close")
    compose.runOnIdle { assertTrue(requests.isEmpty()); assertTrue(previews.isEmpty()) }
  }

  @Test fun singleStyleRemovalWaitsForDetailExitBeforePresentingConfirmation() {
    val requests = mutableListOf<Set<String>>()
    setResourceContent(resourceState(), onRemove = { requests += it })
    compose.onNodeWithTag("perfect.resources.style.default").performScrollTo().performClick()
    waitForDisplayed(hasTestTag("perfect.resources.style-remove"), "style-details-before-remove")
    compose.mainClock.autoAdvance = false
    compose.onNodeWithTag("perfect.resources.style-remove").performClick()
    compose.mainClock.advanceTimeByFrame()
    compose.onAllNodesWithTag("perfect.resources.style-download").assertCountEquals(1)
    compose.onAllNodesWithTag("perfect.resources.confirm-remove").assertCountEquals(0)
    compose.runOnIdle { assertTrue(requests.isEmpty()) }
    compose.mainClock.autoAdvance = true
    waitForDisplayed(hasTestTag("perfect.resources.confirm-remove"), "single-style-remove-presented")
    compose.onAllNodesWithTag("perfect.resources.style-download").assertCountEquals(0)
    compose.runOnIdle { assertTrue(requests.isEmpty()) }
    compose.onNodeWithTag("perfect.resources.cancel-remove").performClick()
    waitForDismissed("perfect.resources.cancel-remove")
    compose.runOnIdle { assertTrue(requests.isEmpty()) }
  }

  @Test fun selectionControlsClearHiddenItemsAndBackReturnsToBrowsing() {
    val backHost = Os4NavigationTestHost()
    var exits = 0
    setResourceContent(resourceState(), backHost = backHost, onBack = { exits++ })
    compose.onNodeWithTag("perfect.resources.selection-mode").performClick()
    compose.onNodeWithTag("perfect.resources.all").performClick()
    compose.onNodeWithTag("perfect.resources.download").assertIsDisplayed()
    compose.onNodeWithTag("perfect.resources.none").performClick()
    compose.onNodeWithTag("perfect.resources.download").assertIsNotEnabled()
    compose.onNodeWithTag("perfect.resources.all").performClick()
    val filterLabel = ApplicationProvider.getApplicationContext<Context>()
      .getString(R.string.perfect_git_filter_downloaded)
    compose.onNode(hasText(filterLabel, substring = true) and hasAnyAncestor(hasTestTag("perfect.resources.views"))).performClick()
    compose.onNodeWithTag("perfect.resources.download").assertIsNotEnabled()
    compose.onAllNodesWithTag("perfect.resources.style.Square").assertCountEquals(0)
    compose.runOnIdle { backHost.completeBack() }
    compose.onAllNodesWithTag("perfect.resources.download").assertCountEquals(0)
    compose.onAllNodesWithTag("perfect.resources.select.default").assertCountEquals(0)
    compose.runOnIdle { assertEquals(0, exits) }
    compose.onNodeWithTag("perfect.resources.selection-mode").performClick()
    compose.onNodeWithTag("perfect.resources.download").assertIsNotEnabled()
  }

  private fun resourceState() = PerfectPackState(
    options = PerfectIconOptions(style = "Rounded"),
    catalog = PerfectIconCatalog(revision = "a".repeat(40),
      styles = listOf("default", "Rounded", "Square"), downloadedStyles = listOf("default", "Rounded"),
      updatedAt = 1_000, iconCount = 10),
    remote = PerfectIconRemote(revision = "b".repeat(40),
      availableStyles = listOf("default", "Rounded", "Square")),
    resourceBytes = 8L * 1024 * 1024, cacheBytes = 1024L * 1024)

  @Test fun synchronizationDisablesNewSelectionAndRefresh() {
    var checks = 0
    setResourceContent(resourceState().copy(refreshing = true), onCheck = { checks++ })
    compose.onNodeWithTag("perfect.resources.selection-mode").assertIsNotEnabled()
    compose.onNodeWithTag("perfect.resources.refresh").assertDoesNotExist()
    compose.onNodeWithTag("perfect.resources.pull-to-refresh").performTouchInput { swipeDown() }
    compose.runOnIdle { assertEquals(0, checks) }
    compose.onAllNodesWithTag("perfect.resources.download").assertCountEquals(0)
  }

  @Test fun pullRefreshChecksResourcesAndSelectionToolbarStillHasFiveNamedActions() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var checks = 0
    setResourceContent(resourceState(), onCheck = { checks++ })
    compose.onNodeWithTag("perfect.resources.selection-mode")
      .assertContentDescriptionEquals(context.getString(R.string.perfect_resource_select))
    compose.onNodeWithTag("perfect.resources.refresh").assertDoesNotExist()
    val refresh = compose.onNodeWithTag("perfect.resources.pull-to-refresh")
      .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
    compose.runOnIdle { assertTrue(refresh.action()) }
    compose.runOnIdle { assertEquals(1, checks) }
    compose.onNodeWithTag("perfect.resources.selection-mode").performClick()
    listOf("all" to R.string.perfect_git_select_all, "none" to R.string.perfect_resource_select_none,
      "download" to R.string.action_label_download, "remove" to R.string.perfect_resource_remove_short,
      "done" to R.string.perfect_resource_done).forEach { (tag, label) ->
      compose.onNodeWithTag("perfect.resources.$tag").assertIsDisplayed()
        .assertContentDescriptionEquals(context.getString(label))
    }
    compose.onAllNodesWithTag("perfect.resources.selection-mode").assertCountEquals(0)
    compose.onNodeWithTag("perfect.resources.all").performClick()
    compose.onNodeWithTag("perfect.resources.done").performClick()
    compose.onAllNodesWithTag("perfect.resources.toolbar").assertCountEquals(0)
    compose.onNodeWithTag("perfect.resources.selection-mode").performClick()
    compose.onNodeWithTag("perfect.resources.download").assertIsNotEnabled()
  }

  @Test fun largeTextKeepsTheLastStyleAboveSelectionActions() {
    setResourceContent(resourceState(), fontScale = 1.6f)
    compose.onNodeWithTag("perfect.resources.selection-mode").performClick()
    compose.onNodeWithTag("perfect.resources.style.Square").performScrollTo()
    compose.waitForIdle()
    val row = compose.onNodeWithTag("perfect.resources.style.Square").assertIsDisplayed()
      .fetchSemanticsNode().boundsInRoot
    val controls = compose.onNodeWithTag("perfect.resources.all").assertIsDisplayed()
      .fetchSemanticsNode().boundsInRoot
    assertTrue("The selection toolbar must not cover the last style", row.bottom <= controls.top)
  }

  @Test fun largeTextStyleDialogKeepsAllFourActionsVisibleAndRequiresExplicitConfirmation() {
    val requests = mutableListOf<Set<String>>()
    setResourceContent(resourceState(), fontScale = 1.6f, onDownload = { requests += it })
    compose.onNodeWithTag("perfect.resources.style.default").performScrollTo().performClick()
    val context = ApplicationProvider.getApplicationContext<Context>()
    waitForDisplayed(hasTestTag("perfect.resources.style-download"), "large-style-dialog")
    val update = compose.onNodeWithTag("perfect.resources.style-download").assertIsDisplayed()
    compose.onNodeWithText(context.getString(R.string.perfect_resource_remove_short)).assertIsDisplayed()
    compose.onNodeWithTag("perfect.resources.style-preview").assertIsDisplayed()
    val close = compose.onNodeWithText(context.getString(R.string.perfect_resource_close)).assertIsDisplayed()
    assertTrue(update.fetchSemanticsNode().boundsInRoot.bottom <= close.fetchSemanticsNode().boundsInRoot.top)
    compose.runOnIdle { assertEquals(emptyList<Set<String>>(), requests) }
    update.performClick()
    waitForDismissed("perfect.resources.style-download")
    compose.runOnIdle { assertEquals(listOf(setOf("default")), requests) }
  }

  @Test fun downloadedStyleShowsItsSizeAndPreviewDoesNotStartDownloadOrDeletion() {
    val previews = mutableListOf<String>()
    val requests = mutableListOf<Set<String>>()
    val initial = resourceState()
    val state = initial.copy(catalog = initial.catalog!!.copy(
      styleDetails = mapOf("default" to PerfectIconStyleDetails(bytes = 4L * 1024 * 1024,
        ownBytes = 1024L * 1024, sharedBytes = 3L * 1024 * 1024))))
    setResourceContent(state, onDownload = { requests += it }, onRemove = { requests += it },
      onPreview = { previews += it })
    compose.onNodeWithTag("perfect.resources.size.default", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithTag("perfect.resources.style.default").performClick()
    waitForDisplayed(hasTestTag("perfect.resources.style-preview"), "downloaded-style-details")
    compose.onNodeWithTag("perfect.resources.style-own-size", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithTag("perfect.resources.style-shared-size", useUnmergedTree = true).assertIsDisplayed()
    compose.mainClock.autoAdvance = false
    compose.onNodeWithTag("perfect.resources.style-preview").performClick()
    compose.mainClock.advanceTimeByFrame()
    compose.onAllNodesWithTag("perfect.resources.style-preview").assertCountEquals(1)
    compose.runOnIdle { assertTrue(previews.isEmpty()); assertTrue(requests.isEmpty()) }
    compose.mainClock.autoAdvance = true
    waitForDismissed("perfect.resources.style-preview")
    compose.runOnIdle {
      assertEquals(listOf("default"), previews)
      assertTrue(requests.isEmpty())
    }
    compose.onAllNodesWithTag("perfect.resources.style-preview").assertCountEquals(0)
  }

  @Test fun transferDetailsDisappearAfterSyncCompletes() {
    var state by mutableStateOf(resourceState().copy(refreshing = true,
      syncingStyles = setOf("HyperOS4"), syncStartedAtElapsedMillis = android.os.SystemClock.elapsedRealtime() - 2_000))
    compose.setContent { KeiHomeTheme { PerfectDownloadStatusCard(state) } }
    compose.onNodeWithTag("perfect.transfer").assertIsDisplayed()
    compose.onNodeWithTag("perfect.transfer.style.HyperOS4").assertIsDisplayed()
    compose.runOnIdle { state = state.copy(refreshing = false) }
    compose.onAllNodesWithTag("perfect.transfer").assertCountEquals(0)
  }

  @Test fun importantGenerationOptionsAreEditableOnTheOverview() {
    var options by mutableStateOf(PerfectIconOptions())
    compose.setContent {
      KeiHomeTheme {
        KeiHomeScaffold { padding ->
          Box(Modifier.padding(padding)) {
            PerfectPackOptions(options, listOf("default"), listOf("default"), true) { options = it }
          }
        }
      }
    }
    val initial = options
    compose.onNodeWithTag("perfect.options.dynamic").performClick()
    compose.runOnIdle { assertEquals(!initial.dynamic, options.dynamic) }
    val fixed = compose.onNodeWithTag("perfect.options.fixed-dark")
    val night = compose.onNodeWithTag("perfect.options.night")
    val context = ApplicationProvider.getApplicationContext<Context>()
    fixed.assertIsDisplayed().assertIsNotEnabled()
    compose.onNodeWithText(context.getString(R.string.perfect_icons_fixed_dark_requires_adaptation))
      .assertIsDisplayed()
    compose.onNodeWithTag("perfect.options.fixed-dark.help.open", useUnmergedTree = true)
      .performTouchInput { click() }
    val helpTag = "perfect.options.fixed-dark.help"
    waitForDisplayed(hasTestTag(helpTag), "fixed-dark-disabled-help")
    compose.onNodeWithText(context.getString(R.string.perfect_icons_fixed_dark_help)).assertIsDisplayed()
    compose.onNodeWithTag(helpTag).performSemanticsAction(SemanticsActions.Dismiss)
    waitForDismissed(helpTag)
    compose.runOnIdle { assertEquals(initial.fixedDark, options.fixedDark) }
    night.performSemanticsAction(SemanticsActions.OnClick)
    fixed.assertIsEnabled()
    fixed.performSemanticsAction(SemanticsActions.OnClick)
    compose.runOnIdle { assertTrue(options.useFixedDark) }
    night.performSemanticsAction(SemanticsActions.OnClick)
    fixed.assertIsDisplayed().assertIsNotEnabled()
    compose.runOnIdle {
      assertTrue(options.fixedDark)
      assertEquals(false, options.useFixedDark)
    }
    night.performSemanticsAction(SemanticsActions.OnClick)
    fixed.assertIsEnabled()
    compose.runOnIdle { assertTrue(options.useFixedDark) }
  }

  private fun setResourceContent(state: PerfectPackState, onDownload: (Set<String>) -> Unit = {},
    onRemove: (Set<String>) -> Unit = {}, backHost: Os4NavigationTestHost = Os4NavigationTestHost(),
    onBack: () -> Unit = {}, onCheck: () -> Unit = {}, onPreview: (String) -> Unit = {}, fontScale: Float? = null) {
    compose.setContent {
      backHost.Provide {
        KeiHomeTheme {
          val density = LocalDensity.current
          CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
            PerfectResourceContent(state, busy = state.busy, onBack = onBack, onCheck = onCheck,
              onDownload = onDownload, onRemove = onRemove, onRepair = {}, onDelete = {}, onClearCache = {},
              onPreview = onPreview)
          }
        }
      }
    }
  }

  private fun exerciseDestructiveAction(deleteResources: Boolean) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val backHost = Os4NavigationTestHost()
    val state = PerfectPackState(resourceBytes = 8L * 1024 * 1024, cacheBytes = 1024L * 1024)
    var busy by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)
    var confirmCache by mutableStateOf(false)
    var deletions = 0
    var cacheClears = 0
    compose.setContent {
      backHost.Provide {
        KeiHomeTheme {
          KeiHomeScaffold(Modifier.fillMaxSize()) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
              BasicComponent(
                title = context.getString(R.string.perfect_icons_clear_cache),
                enabled = !busy && state.cacheBytes > 0,
                onClick = { confirmCache = true },
                modifier = Modifier.testTag("icon-packs.perfect.clear-cache"),
              )
              BasicComponent(
                title = context.getString(R.string.perfect_git_delete),
                enabled = !busy && state.resourceBytes > 0,
                onClick = { confirmDelete = true },
                modifier = Modifier.testTag("icon-packs.perfect.delete-resources"),
              )
            }
            PerfectResourceConfirmations(state, busy, confirmDelete, confirmCache,
              onDismissDelete = { confirmDelete = false },
              onDismissClearCache = { confirmCache = false },
              onDelete = { deletions++ }, onClearCache = { cacheClears++ })
          }
        }
      }
    }
    val action = compose.onNodeWithTag(if (deleteResources) "icon-packs.perfect.delete-resources"
      else "icon-packs.perfect.clear-cache")
    val confirmTag = if (deleteResources) "icon-packs.perfect.confirm-delete"
      else "icon-packs.perfect.confirm-clear-cache"
    val cancelTag = if (deleteResources) "icon-packs.perfect.cancel-delete"
      else "icon-packs.perfect.cancel-clear-cache"
    val confirm = compose.onNodeWithTag(confirmTag)
    val cancel = compose.onNodeWithTag(cancelTag)
    action.performClick()
    compose.runOnIdle { assertEquals(0, deletions + cacheClears) }
    val summary = context.getString(if (deleteResources) R.string.perfect_git_delete_confirm
      else R.string.perfect_git_clear_cache_confirm, Formatter.formatFileSize(context,
      if (deleteResources) state.resourceBytes else state.cacheBytes))
    waitForDisplayed(hasText(summary), "$confirmTag-scope")
    compose.onNodeWithText(summary).assertIsDisplayed()
    waitForDisplayed(hasTestTag(cancelTag), "$cancelTag-presented")
    cancel.performClick()
    waitForDismissed(cancelTag)
    compose.runOnIdle { assertEquals(0, deletions + cacheClears) }
    action.performClick()
    waitForDisplayed(hasTestTag(confirmTag), "$confirmTag-busy")
    compose.runOnIdle { busy = true }
    confirm.assertIsNotEnabled()
    confirm.performTouchInput { click() }
    compose.runOnIdle { assertEquals(0, deletions + cacheClears) }
    cancel.performClick()
    waitForDismissed(cancelTag)
    compose.runOnIdle { busy = false }
    action.performClick()
    waitForDisplayed(hasTestTag(confirmTag), "$confirmTag-reopened")
    confirm.performClick()
    waitForDismissed(confirmTag)
    compose.runOnIdle {
      assertEquals(if (deleteResources) 1 else 0, deletions)
      assertEquals(if (deleteResources) 0 else 1, cacheClears)
      assertEquals(false, confirmDelete)
      assertEquals(false, confirmCache)
    }
  }

  private fun waitForDisplayed(matcher: SemanticsMatcher, label: String) {
    try {
      compose.waitUntil(10_000) {
        val nodes = compose.onAllNodes(matcher)
        nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
      }
    } catch (failure: Throwable) {
      captureDialogFailure(label, failure)
      throw failure
    }
  }

  private fun waitForDismissed(tag: String) {
    try {
      compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    } catch (failure: Throwable) {
      captureDialogFailure("$tag-dismissal", failure)
      throw failure
    }
  }

  private fun captureDialogFailure(label: String, failure: Throwable) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = File(instrumentation.targetContext.cacheDir, "perfect-git-ui-failures")
    val stem = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
    runCatching {
      directory.mkdirs()
      File(directory, "$stem-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
    }.onFailure { failure.addSuppressed(it) }
    runCatching {
      directory.mkdirs()
      instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
        try {
          File(directory, "$stem-window.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
          }
        } finally {
          screenshot.recycle()
        }
      }
    }.onFailure { failure.addSuppressed(it) }
    Log.e("PerfectGitUiTest", "Dialog failure diagnostics: ${directory.absolutePath}/$stem", failure)
  }
}
