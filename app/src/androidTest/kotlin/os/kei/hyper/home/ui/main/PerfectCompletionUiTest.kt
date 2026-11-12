// KeiHome-authored tests of local selection gating through public Compose/MIUIX APIs.
// The injected catalog reader never performs network or filesystem operations.
package os.kei.hyper.home.ui.main

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class PerfectCompletionUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val originOs = SystemIconFallback.perfect("OriginOS")
  private val hyperOs4 = SystemIconFallback.perfect("HyperOS4")

  @Test fun enableRechecksLocalCatalogOffMainAndResourceNavigationNeverSavesOrDownloads() {
    var selected by mutableStateOf(SystemIconFallback.CLASSIC)
    val fresh = AtomicReference<List<String>>(emptyList())
    val reads = AtomicInteger()
    val verified = AtomicReference<List<String>>(emptyList())
    val readOnMain = AtomicBoolean()
    var navigations = 0
    var saves = 0
    setContent(selected = { selected }, downloaded = { listOf("OriginOS", "HyperOS4") },
      onSelect = { selected = it; saves++ }, readStyles = {
        reads.incrementAndGet()
        readOnMain.set(Looper.myLooper() == Looper.getMainLooper())
        fresh.get()
      }, verify = { style ->
        readOnMain.set(Looper.myLooper() == Looper.getMainLooper())
        verified.set(verified.get() + style)
      }, onResources = { navigations++ })

    click("icon-packs.perfect-completion")
    waitForResourcesDialog()
    compose.runOnIdle {
      assertEquals(SystemIconFallback.CLASSIC, selected)
      assertEquals(0, saves)
      assertEquals(1, reads.get())
      assertEquals(emptyList<String>(), verified.get())
      assertFalse(readOnMain.get())
    }
    compose.onNodeWithTag("perfect.completion.manage-resources").performClick()
    compose.runOnIdle {
      assertEquals(1, navigations)
      assertEquals(1, reads.get())
      assertEquals(0, saves)
      fresh.set(listOf("HyperOS4", "OriginOS"))
    }
    click("icon-packs.perfect-completion")
    compose.waitUntil(5_000) { selected == originOs }
    compose.onNodeWithTag("icon-packs.system-fallback").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithText(context.getString(R.string.system_fallback_off)).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(2, reads.get())
      assertEquals(listOf("OriginOS"), verified.get())
      assertFalse(readOnMain.get())
      assertEquals(1, saves)
    }
    click("icon-packs.perfect-completion")
    compose.runOnIdle {
      assertEquals(SystemIconFallback.CLASSIC, selected)
      assertEquals(2, reads.get())
      assertEquals(2, saves)
      assertEquals(listOf("OriginOS"), verified.get())
    }
    compose.onNodeWithTag("icon-packs.system-fallback").performScrollTo().assertIsEnabled()
  }

  @Test fun explicitPerfectSelectionRejectsAStyleDeletedSinceTheMenuWasRendered() {
    var selected by mutableStateOf(hyperOs4)
    val reads = AtomicInteger()
    setContent(selected = { selected }, downloaded = { listOf("HyperOS4", "OriginOS") },
      onSelect = { selected = it }, readStyles = {
        reads.incrementAndGet()
        listOf("HyperOS4")
      })
    click("icon-packs.perfect-completion-style")
    compose.onNodeWithText("OriginOS").performClick()
    waitForResourcesDialog()
    compose.runOnIdle {
      assertEquals(1, reads.get())
      assertEquals(hyperOs4, selected)
    }
    compose.onNodeWithTag("perfect.completion.cancel").performClick()
    compose.runOnIdle { assertEquals(hyperOs4, selected) }
  }

  @Test fun resourceRemovalKeepsSavedPerfectIntentVisibleUntilExplicitlyDisabled() {
    var selected by mutableStateOf(originOs)
    var downloaded by mutableStateOf(listOf("OriginOS"))
    var saves = 0
    setContent(selected = { selected }, downloaded = { downloaded },
      onSelect = { selected = it; saves++ }, readStyles = { emptyList() })
    compose.runOnIdle { downloaded = emptyList() }
    compose.onNodeWithTag("icon-packs.system-fallback-unavailable").performScrollTo().assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(originOs, selected)
      assertEquals(0, saves)
    }
    click("icon-packs.perfect-completion")
    compose.runOnIdle {
      assertEquals(SystemIconFallback.DEFAULT, selected)
      assertEquals(1, saves)
    }
  }

  @Test fun perfectIsIndependentAndNativeDropdownShowsLocalizedOffWithoutResourceReadsOnRender() {
    setContent(selected = { hyperOs4 }, downloaded = { listOf("HyperOS4") },
      onSelect = {}, readStyles = { error("Rendering must not scan resources") },
      verify = { error("Rendering must not open resources") })
    compose.onNodeWithTag("icon-packs.system-fallback").performScrollTo()
    compose.onNodeWithText(context.getString(R.string.system_fallback_title)).assertIsDisplayed()
    compose.onNodeWithText(context.getString(R.string.system_fallback_off)).assertIsDisplayed()
    compose.onNodeWithTag("icon-packs.system-fallback").assertIsNotEnabled()
    compose.onNodeWithTag("icon-packs.perfect-completion-style").assertIsEnabled()
  }

  @Test fun corruptListedResourceIsRejectedAndOnlyTheChosenStyleIsVerified() {
    var selected by mutableStateOf(SystemIconFallback.CLASSIC)
    val styles = listOf("HyperOS4", "OriginOS", "ColorOS")
    val verified = AtomicReference<List<String>>(emptyList())
    var saves = 0
    setContent(selected = { selected }, downloaded = { styles },
      onSelect = { selected = it; saves++ }, readStyles = { styles },
      verify = { style ->
        verified.set(verified.get() + style)
        if (style == "OriginOS") throw IOException("Corrupt local resource")
      })
    click("icon-packs.perfect-completion")
    waitForResourcesDialog()
    compose.runOnIdle {
      assertEquals(SystemIconFallback.CLASSIC, selected)
      assertEquals(0, saves)
      assertEquals(listOf("OriginOS"), verified.get())
    }
    compose.onNodeWithTag("perfect.completion.cancel").performClick()
    click("icon-packs.perfect-completion-style")
    compose.onNodeWithText("ColorOS").performClick()
    val colorOs = SystemIconFallback.perfect("ColorOS")
    compose.waitUntil(5_000) { selected == colorOs }
    compose.runOnIdle {
      assertEquals(listOf("OriginOS", "ColorOS"), verified.get())
      assertEquals(1, saves)
    }
    click("icon-packs.perfect-completion")
    compose.runOnIdle { assertEquals(SystemIconFallback.CLASSIC, selected) }
  }

  private fun setContent(
    selected: () -> SystemIconFallback,
    downloaded: () -> List<String>,
    onSelect: (SystemIconFallback) -> Unit,
    readStyles: suspend () -> List<String>,
    verify: suspend (String) -> Unit = {},
    onResources: () -> Unit = {},
  ) {
    val backHost = Os4NavigationTestHost()
    var nativeSelected by mutableStateOf(selected().takeUnless { it.isPerfect } ?: SystemIconFallback.DEFAULT)
    compose.setContent {
      backHost.Provide {
        KeiHomeTheme {
          KeiHomeScaffold(Modifier.fillMaxSize()) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
              PerfectCompletionPreferences(
                bundled = SystemIconFallback.entries, selected = selected(), nativeSelected = nativeSelected,
                savedStyle = "OriginOS", downloadedStyles = downloaded(), enabled = true,
                onSelectNative = { nativeSelected = it; onSelect(it) },
                onSelectPerfect = { style ->
                  onSelect(style?.let { SystemIconFallback.perfect(it) } ?: nativeSelected)
                },
                readDownloadedStyles = readStyles, verifyStyle = verify, onOpenPerfectIcons = onResources)
            }
          }
        }
      }
    }
  }

  private fun click(tag: String) {
    compose.onNodeWithTag(tag).performScrollTo().performClick()
  }

  private fun waitForResourcesDialog() {
    val title = context.getString(R.string.perfect_completion_resources_required_title)
    compose.waitUntil(5_000) {
      compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText(context.getString(R.string.perfect_completion_resources_required_summary))
      .assertIsDisplayed()
  }
}
