package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.data.AppNavigationBarStyle
import os.kei.hyper.home.ui.components.KeiHomeNavigationBar
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MiuixSnapshotDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun floatingActionKeepsTouchAndDisabledSemanticsWithGlassAndOpaqueMaterials() {
    var dark by mutableStateOf(false)
    var blur by mutableStateOf(false)
    var enlarged by mutableStateOf(false)
    var enabled by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var taps = 0
    lateinit var cache: File
    rule.setContent {
      cache = LocalContext.current.cacheDir
      val density = LocalDensity.current
      val controller = remember(dark) {
        ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
      }
      CompositionLocalProvider(
        LocalKeiHomeDarkTheme provides dark,
        LocalDensity provides Density(density.density, if (enlarged) 1.6f else 1f),
      ) {
        MiuixTheme(controller = controller) {
          val source = rememberLayerBackdrop()
          KeiHomeScaffold(backdrop = source.takeIf { blur }, topBar = {},
            floatingToolbar = {
              PageActionFab("Install ColorOS icons", MiuixIcons.Glass.Info, { taps++ },
                modifier = Modifier.testTag("snapshot.action"), enabled = enabled, busy = busy)
            }) { padding ->
            Box(Modifier.fillMaxSize().layerBackdrop(source)
              .background(Brush.verticalGradient(listOf(Color(0xFF278DC4), MiuixTheme.colorScheme.surface)))) {
              Text("Page content", Modifier.padding(padding))
            }
          }
        }
      }
    }
    for (night in listOf(false, true)) for (glass in listOf(false, true))
      for (large in listOf(false, true)) {
      rule.runOnIdle { dark = night; blur = glass; enlarged = large; enabled = true; busy = false }
      val action = rule.onNodeWithTag("snapshot.action")
      action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
      action.assertIsDisplayed().performTouchInput { down(center); cancel() }
      val previous = taps
      action.performTouchInput { click(center) }
      rule.runOnIdle { assertEquals(previous + 1, taps); enabled = false }
      action.assertIsNotEnabled().performTouchInput { click(center) }
      rule.runOnIdle { assertEquals(previous + 1, taps); enabled = true; busy = true }
      action.assertIsNotEnabled().performTouchInput { click(center) }
      rule.runOnIdle { assertEquals(previous + 1, taps); busy = false }
      val bounds = action.fetchSemanticsNode().boundsInRoot
      val viewport = rule.onRoot().fetchSemanticsNode().boundsInRoot
      assertTrue("Action must remain inside the viewport", viewport.contains(bounds.topLeft) &&
        viewport.contains(bounds.bottomRight.copy(x = bounds.right - 1, y = bounds.bottom - 1)))
      rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(cache, "miuix-floating-$night-$glass-$large.png").outputStream().use {
          check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
      }
    }
    rule.runOnIdle { assertEquals(8, taps) }
  }

  @Test fun nativeNavigationBadgesFollowTheirOwnDestinationsAndUpdateWithoutSelectionEvents() {
    var rtl by mutableStateOf(false)
    var badges by mutableStateOf(true)
    var selected by mutableStateOf(2)
    var selections = 0
    rule.setContent {
      CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MiuixTheme {
          Column {
            KeiHomeNavigationBar(AppNavigationBarStyle.OS4_GLASS,
              List(5) { NavigationItem("Tab $it", MiuixIcons.Glass.Info) }, selected,
              { selected = it; selections++ }, badge = { index ->
                if (badges && index in setOf(0, 4)) ({
                  Badge(Modifier.semantics { contentDescription = "Badge $index" })
                }) else null
              })
          }
        }
      }
    }
    for (direction in listOf(false, true)) {
      rule.runOnIdle { rtl = direction; badges = true }
      for (index in listOf(0, 4)) {
        val badge = rule.onNodeWithContentDescription("Badge $index").assertIsDisplayed()
          .fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithText("Tab $index").fetchSemanticsNode().boundsInRoot
        val other = rule.onNodeWithText("Tab ${4 - index}").fetchSemanticsNode().boundsInRoot
        assertTrue("Badge belongs to its destination in either direction",
          kotlin.math.abs(badge.center.x - label.center.x) < kotlin.math.abs(badge.center.x - other.center.x))
      }
      rule.runOnIdle { badges = false }
      rule.onNodeWithContentDescription("Badge 0").assertDoesNotExist()
      rule.onNodeWithContentDescription("Badge 4").assertDoesNotExist()
      rule.runOnIdle { assertEquals(2, selected); assertEquals(0, selections) }
    }
  }
}
