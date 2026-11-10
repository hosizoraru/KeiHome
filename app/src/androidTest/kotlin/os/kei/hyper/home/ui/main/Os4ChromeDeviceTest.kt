package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.data.AppTopBarBlur
import os.kei.hyper.home.data.AppNavigationBarStyle
import os.kei.hyper.home.ui.components.KeiHomeBarSurface
import os.kei.hyper.home.ui.components.KeiHomeNavigationBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class Os4ChromeDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun bothNavigationStylesKeepSelectionAndBoundsAcrossTabsThemesAndFontSizes() {
    var selected by mutableStateOf(0)
    var dark by mutableStateOf(false)
    var mode by mutableStateOf(AppTopBarBlur.PROGRESSIVE)
    var large by mutableStateOf(false)
    var style by mutableStateOf(AppNavigationBarStyle.IOS_LIKE)
    var refreshes = 0
    lateinit var screenshots: File
    rule.setContent {
      screenshots = LocalContext.current.cacheDir
      val controller = remember(dark) {
        ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
      }
      val density = LocalDensity.current
      CompositionLocalProvider(LocalKeiHomeDarkTheme provides dark,
        LocalDensity provides Density(density.density, if (large) 1.6f else 1f)) {
        MiuixTheme(controller = controller) {
          val source = rememberLayerBackdrop()
          val backdrop = source.takeIf { mode != AppTopBarBlur.OFF }
          KeiHomeScaffold(backdrop = backdrop, topBar = {
            KeiHomeBarSurface(backdrop, backdrop != null, mode) {
              KeiHomeTopAppBar(title = "OS4", actions = {
                KeiHomeIconButton(onClick = { refreshes++ }) { Icon(MiuixIcons.Refresh, "Refresh") }
                KeiHomeIconButton(onClick = { error("Disabled control clicked") }, enabled = false) {
                  Icon(MiuixIcons.Info, "Unavailable")
                }
              })
            }
          }, bottomBar = {
            KeiHomeNavigationBar(style, List(5) { NavigationItem("Tab $it", MiuixIcons.Info) },
              selected, { selected = it }, Modifier.testTag("os4.navigation"), backdrop,
              badge = { index -> if (index == 2) ({
                Badge(Modifier.semantics { contentDescription = "Update" })
              }) else null })
          }) { padding ->
            Box(Modifier.fillMaxSize().layerBackdrop(source)
              .background(Brush.verticalGradient(listOf(Color(0xFF278DC4), MiuixTheme.colorScheme.surface)))) {
              Column(Modifier.fillMaxWidth().padding(padding).padding(16.dp)) { Text("Page $selected") }
            }
          }
        }
      }
    }
    for (barStyle in AppNavigationBarStyle.entries) for (enlarged in listOf(false, true))
      for (night in listOf(false, true)) for (blur in AppTopBarBlur.entries) {
      val previousSelection = selected
      rule.runOnIdle { style = barStyle; large = enlarged; dark = night; mode = blur }
      rule.waitForIdle()
      rule.runOnIdle { assertEquals(previousSelection, selected) }
      val bounds = rule.onNodeWithTag("os4.navigation").fetchSemanticsNode().boundsInRoot
      for (index in listOf(4, 1, 3, 0, 2)) {
        rule.onNodeWithText("Tab $index").assertIsDisplayed().performTouchInput { click(center) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(index, selected) }
        assertEquals(bounds, rule.onNodeWithTag("os4.navigation").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithText("Page $index").assertIsDisplayed()
        rule.onAllNodesWithText("Tab $index").assertCountEquals(1)
      }
      rule.onNodeWithContentDescription("Update").assertIsDisplayed()
      rule.onAllNodesWithContentDescription("Update").assertCountEquals(1)
      rule.onNodeWithContentDescription("Unavailable").assertIsNotEnabled()
      rule.onNodeWithContentDescription("Refresh").performTouchInput { click(center) }
      val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
      File(screenshots, "os4-chrome-$barStyle-$night-$blur-$enlarged.png").outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    }
    rule.runOnIdle { assertEquals(24, refreshes) }
  }
}
