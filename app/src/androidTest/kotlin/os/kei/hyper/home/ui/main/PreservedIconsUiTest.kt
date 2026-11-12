package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class PreservedIconsUiTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val reader = PreservedIconApp("os.test.reader", "Aurora Reader", 1, 1, "", 0)
  private val gallery = PreservedIconApp("os.test.photoarchive", "Gallery Studio", 1, 1, "", 0)
  private val missing = "os.test.removed"
  private val readerColor = 0xff006a60.toInt()
  private val galleryColor = 0xffc45c26.toInt()
  private var preserved by mutableStateOf(emptySet<String>())

  @Test fun emptySavedListCanSelectAnAppWithOriginalImageAndRetainItInSaved() {
    show()
    rule.onNodeWithTag("preserved-icons.filter").assertIsDisplayed()
    rule.onNodeWithTag("preserved-icons.empty").assertIsDisplayed()
    row(reader.packageName).assertDoesNotExist()
    row(gallery.packageName).assertDoesNotExist()

    selectMode("all")
    scrollTo(reader.packageName)
    assertAppIdentityAndOriginalImage(reader, readerColor)
    row(reader.packageName).assertIsOff().performClick().assertIsOn()
    rule.runOnIdle { assertEquals(setOf(reader.packageName), preserved) }

    controls()
    selectMode("saved")
    scrollTo(reader.packageName)
    row(reader.packageName).assertIsOn()
    assertAppIdentityAndOriginalImage(reader, readerColor)
    row(gallery.packageName).assertDoesNotExist()
    screenshot("saved-original-icon")
  }

  @Test fun searchMatchesLabelAndPackageAndClearRestoresAllAppsWithoutSelectingThem() {
    show()
    selectMode("all")
    rule.onNodeWithTag("preserved-icons.search").performTextInput("AURORA")
    scrollTo(reader.packageName)
    assertAppIdentityAndOriginalImage(reader, readerColor)
    row(gallery.packageName).assertDoesNotExist()

    clearSearch()
    scrollTo(gallery.packageName)
    row(gallery.packageName).assertIsOff()
    scrollTo(reader.packageName)
    row(reader.packageName).assertIsOff()
    controls()
    rule.onNodeWithTag("preserved-icons.search").performTextInput("PHOTOARCHIVE")
    scrollTo(gallery.packageName)
    assertAppIdentityAndOriginalImage(gallery, galleryColor)
    row(reader.packageName).assertDoesNotExist()

    clearSearch()
    scrollTo(reader.packageName)
    row(reader.packageName).assertIsOff()
    scrollTo(gallery.packageName)
    row(gallery.packageName).assertIsOff()
    rule.runOnIdle { assertEquals(emptySet<String>(), preserved) }
    controls()
    selectMode("saved")
    rule.onNodeWithTag("preserved-icons.empty").assertIsDisplayed()
  }

  @Test fun missingSavedPackageCanBeRemovedWithoutChangingTheInstalledSelection() {
    show(setOf(reader.packageName, missing))
    scrollTo(missing)
    rule.onNode(
      hasText(missing) and hasAnyAncestor(hasTestTag(rowTag(missing))), useUnmergedTree = true,
    ).assertIsDisplayed()
    rule.onNode(
      hasText(context.getString(R.string.preserved_icons_uninstalled)) and
        hasAnyAncestor(hasTestTag(rowTag(missing))), useUnmergedTree = true,
    ).assertIsDisplayed()
    row(missing).assertIsOn().performClick().assertDoesNotExist()
    rule.runOnIdle { assertEquals(setOf(reader.packageName), preserved) }

    scrollTo(reader.packageName)
    row(reader.packageName).assertIsOn()
    assertAppIdentityAndOriginalImage(reader, readerColor)
    controls()
    selectMode("all")
    row(missing).assertDoesNotExist()
  }

  @Test fun combinedOriginSearchAndSortCanResetWithoutLosingSavedSelections() {
    val other = reader.copy(packageName = "os.test.alternate", label = "Zenith Reader")
    val system = reader.copy(packageName = "os.test.system", label = "System Reader", systemApp = true)
    val saved = setOf(reader.packageName, gallery.packageName, system.packageName)
    show(saved, listOf(reader, gallery, other, system))
    selectMode("all")
    rule.onNodeWithTag("preserved-icons.search").performTextInput("Reader")
    selectMenuItem("preserved-icons.filter",
      context.getString(R.string.browser_origin), context.getString(R.string.browser_origin_user))
    row(system.packageName).assertDoesNotExist()
    selectMenuItem("preserved-icons.sort", context.getString(R.string.browser_sort_package))
    assertRowOrder(other.packageName, reader.packageName)
    row(gallery.packageName).assertDoesNotExist()
    row(other.packageName).assertIsOff()
    row(reader.packageName).assertIsOn()

    selectMenuItem("preserved-icons.sort", context.getString(R.string.browser_sort_reverse))
    assertRowOrder(reader.packageName, other.packageName)
    selectMenuItem("preserved-icons.filter", context.getString(R.string.browser_reset))
    rule.runOnIdle { assertEquals(saved, preserved) }
    // Reset restores Saved/origin-all while retaining the query and reverse package order.
    assertRowOrder(system.packageName, reader.packageName)
    row(reader.packageName).assertIsOn()
    row(system.packageName).assertIsOn()
    row(other.packageName).assertDoesNotExist()
    row(gallery.packageName).assertDoesNotExist()

    clearSearch()
    scrollTo(gallery.packageName)
    row(gallery.packageName).assertIsOn()
    row(other.packageName).assertDoesNotExist()
  }

  private fun show(
    initial: Set<String> = emptySet(),
    apps: List<PreservedIconApp> = listOf(reader, gallery),
  ) {
    preserved = initial
    val host = Os4NavigationTestHost()
    val originalIcon: (PreservedIconApp) -> Drawable = { app ->
      ColorDrawable(if (app.packageName == reader.packageName) readerColor else galleryColor)
    }
    rule.setContent {
      host.Provide {
        KeiHomeTheme {
          PreservedIconsPage(
            apps = apps, preserved = preserved, loading = false, failed = false,
            onToggle = { pkg -> preserved = if (pkg in preserved) preserved - pkg else preserved + pkg },
            onRetry = {}, onBack = {},
            appIcon = { app ->
              if (app != null) {
                val bitmap = remember(app) { originalIcon(app).toBitmap(48, 48) }
                Image(
                  bitmap.asImageBitmap(), contentDescription = "Original icon: ${app.packageName}",
                  modifier = Modifier.size(44.dp).testTag("preserved-icons.test-icon:${app.packageName}"),
                )
              }
            },
          )
        }
      }
    }
  }

  private fun assertAppIdentityAndOriginalImage(app: PreservedIconApp, color: Int) {
    val parent = hasAnyAncestor(hasTestTag(rowTag(app.packageName)))
    rule.onNode(hasText(app.label) and parent, useUnmergedTree = true).assertIsDisplayed()
    rule.onNode(hasText(app.packageName) and parent, useUnmergedTree = true).assertIsDisplayed()
    val image = rule.onNode(
      hasTestTag("preserved-icons.test-icon:${app.packageName}") and parent, useUnmergedTree = true,
    ).assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
    val pixels = image.captureToImage().asAndroidBitmap()
    assertEquals("The displayed Image must contain the original fixture Drawable",
      color, pixels.getPixel(pixels.width / 2, pixels.height / 2))
  }

  private fun rowTag(pkg: String) = "preserved-icons.app:$pkg"

  private fun row(pkg: String) = rule.onNodeWithTag(rowTag(pkg))

  private fun scrollTo(pkg: String) {
    rule.onNodeWithTag("preserved-icons.list").performScrollToNode(hasTestTag(rowTag(pkg)))
  }

  private fun controls() {
    rule.onNodeWithTag("preserved-icons.list").performScrollToIndex(0)
  }

  private fun assertRowOrder(first: String, second: String) {
    scrollTo(first)
    row(first).assertIsDisplayed()
    row(second).assertIsDisplayed()
    assertTrue("Expected $first before $second",
      row(first).fetchSemanticsNode().boundsInRoot.top < row(second).fetchSemanticsNode().boundsInRoot.top)
  }

  private fun selectMode(mode: String) {
    val label = if (mode == "saved") context.getString(R.string.preserved_icons_saved, preserved.size)
      else context.getString(R.string.preserved_icons_all)
    selectMenuItem("preserved-icons.filter", label)
  }

  private fun selectMenuItem(trigger: String, vararg labels: String) {
    rule.onNodeWithTag(trigger).assertIsDisplayed().performClick()
    labels.forEach(::clickMenuItem)
    val label = labels.last()
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isEmpty()
    }
  }

  private fun clickMenuItem(label: String) {
    val matcher = hasText(label) and hasClickAction()
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(matcher).fetchSemanticsNodes().size == 1
    }
    rule.onNode(matcher).assertIsDisplayed().performClick()
  }

  private fun clearSearch() {
    controls()
    rule.onNodeWithContentDescription(context.getString(R.string.search_clear_text)).performClick()
  }

  private fun screenshot(name: String) {
    rule.waitForIdle()
    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
    File(context.cacheDir, "preserved-icons-ui-$name.png").outputStream().use {
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
  }
}
