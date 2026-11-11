package os.kei.hyper.home.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.perfect.PerfectIconCatalog
import os.kei.hyper.home.iconpack.perfect.PerfectIconOptions
import os.kei.hyper.home.iconpack.perfect.PerfectIconRemote
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    compose.onNodeWithTag("perfect.resources.style.default").performScrollTo().performClick()
    compose.onNodeWithTag("perfect.resources.style.Square").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(emptyList<Set<String>>(), requests) }
    compose.onNodeWithTag("perfect.resources.download").performClick()
    compose.runOnIdle {
      assertEquals(listOf(setOf("default", "Square")), requests)
      assertEquals("Rounded", state.options.style)
    }
  }

  @Test fun batchRemovalOnlyIncludesDownloadedSelectionAndRequiresConfirmationAfterCancel() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val state = resourceState()
    val requests = mutableListOf<Set<String>>()
    setResourceContent(state, onRemove = { requests += it })
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
  }

  private fun resourceState() = PerfectPackState(
    options = PerfectIconOptions(style = "Rounded"),
    catalog = PerfectIconCatalog(revision = "a".repeat(40),
      styles = listOf("default", "Rounded", "Square"), downloadedStyles = listOf("default", "Rounded"),
      updatedAt = 1_000, iconCount = 10),
    remote = PerfectIconRemote(revision = "b".repeat(40),
      availableStyles = listOf("default", "Rounded", "Square")),
    resourceBytes = 8L * 1024 * 1024, cacheBytes = 1024L * 1024)

  private fun setResourceContent(state: PerfectPackState, onDownload: (Set<String>) -> Unit = {},
    onRemove: (Set<String>) -> Unit = {}) {
    val backHost = Os4NavigationTestHost()
    compose.setContent {
      backHost.Provide {
        KeiHomeTheme {
          PerfectResourceContent(state, busy = false, onBack = {}, onCheck = {},
            onDownload = onDownload, onRemove = onRemove, onRepair = {}, onDelete = {}, onClearCache = {})
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
            Box(Modifier.fillMaxSize().padding(padding)) {
              PerfectResourceStorageCard(state, busy,
                onRequestClearCache = { confirmCache = true },
                onRequestDelete = { confirmDelete = true })
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
