package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.libxposed.service.HookedTarget
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.MonochromeIconSettings
import os.kei.hyper.home.data.MonetColor
import os.kei.hyper.home.data.MonetColorFamily
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetPaletteSource
import os.kei.hyper.home.data.MonetReadState
import os.kei.hyper.home.data.WallpaperColorSnapshot
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class MonetStatusContentTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun distinguishesWallpaperColorsFromGeneratedShades() {
    composeTestRule.setContent {
      KeiHomeTheme {
        Box(Modifier.verticalScroll(rememberScrollState())) {
          MonetDetailCard(snapshot = snapshot(), readFailed = false)
        }
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_colors))
      .assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_colors_value, 2))
      .assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_state_available, 110))
      .assertExists()
    composeTestRule.onNodeWithText(
      context.getString(R.string.monet_icon_rendering_note),
    ).assertExists()
    composeTestRule.onNodeWithText("TONAL_SPOT", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithContentDescription(
      context.getString(R.string.monet_wallpaper_color_description, "#123456"),
      useUnmergedTree = true,
    ).assertExists()
    composeTestRule.onNodeWithContentDescription(
      context.getString(
        R.string.monet_swatch_tone_description,
        context.getString(R.string.monet_family_primary),
        0,
        "#FF000000",
      ),
      useUnmergedTree = true,
    ).assertExists()
  }

  @Test
  fun paletteSwatchLabelsFollowTheSelectedLanguage() {
    var localeTag by mutableStateOf("en")
    composeTestRule.setContent {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(localeTag))
      }
      val localizedContext = context.createConfigurationContext(configuration)
      CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
      ) {
        KeiHomeTheme {
          Box(Modifier.verticalScroll(rememberScrollState())) {
            MonetDetailCard(snapshot = snapshot(), readFailed = false)
          }
        }
      }
    }

    listOf("en", "zh-CN", "ja").forEach { tag ->
      composeTestRule.runOnIdle { localeTag = tag }
      val configuration = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(tag))
      }
      val localizedContext = context.createConfigurationContext(configuration)
      val family = localizedContext.getString(R.string.monet_family_primary)
      composeTestRule.onNodeWithText(family).assertExists()
      composeTestRule.onNodeWithContentDescription(
        localizedContext.getString(
          R.string.monet_swatch_tone_description,
          family,
          0,
          "#FF000000",
        ),
        useUnmergedTree = true,
      ).assertExists()
    }
  }

  @Test
  fun statusIsReadyOnlyWhenBothTargetsAreCurrent() {
    val current = HookedTarget.State.UP_TO_DATE
    assertEquals(StatusTone.READY, statusTone(true, false, true, current, current))
    listOf(
      null,
      HookedTarget.State.STALE,
      HookedTarget.State.RELOADING,
    ).forEach { launcher ->
      assertEquals(StatusTone.WARNING, statusTone(true, false, true, current, launcher))
    }
    assertEquals(StatusTone.WARNING, statusTone(true, false, true, null, current))
    assertEquals(StatusTone.WARNING, statusTone(true, false, null, current, current))
    assertEquals(StatusTone.ERROR, statusTone(false, false, true, current, current))
    assertEquals(StatusTone.ERROR, statusTone(true, true, true, current, current))
    assertEquals(StatusTone.ERROR, statusTone(true, false, false, current, current))
    assertEquals(StatusTone.ERROR,
      statusTone(true, false, true, current, HookedTarget.State.FAILED))
    assertEquals(
      StatusTone.ERROR,
      statusTone(true, false, true, HookedTarget.State.FAILED, current),
    )
  }

  @Test
  fun summaryPillsFollowCurrentSettingsAndClearStalePaletteSuccess() {
    var current by mutableStateOf(snapshot())
    var readFailed by mutableStateOf(false)
    composeTestRule.setContent {
      KeiHomeTheme {
        Box(Modifier.verticalScroll(rememberScrollState())) {
          StatusBody(connected = true, snapshot = null, readFailed = false,
            monetSnapshot = current, monetReadFailed = readFailed, onRefresh = {},
            onOpenMonet = {})
        }
      }
    }
    fun pill(label: Int, value: Int) =
      context.getString(R.string.dashboard_state_pill, context.getString(label), context.getString(value))
    val icons = composeTestRule.onNodeWithTag("status.monet.icons", useUnmergedTree = true)
    val palette = composeTestRule.onNodeWithTag("status.monet.palette", useUnmergedTree = true)
    composeTestRule.onNodeWithTag("status.runtime.system", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithTag("status.runtime.launcher", useUnmergedTree = true).assertExists()
    icons.assertTextContains(pill(R.string.dashboard_pill_icons, R.string.dashboard_on))
    palette.assertTextContains(pill(R.string.dashboard_pill_palette, R.string.dashboard_palette_available))
    composeTestRule.runOnIdle {
      current = snapshot(launcherState = MonetReadState.UNAVAILABLE,
        frameworkState = MonetReadState.NOT_PROVIDED, themedIcons = false)
    }
    icons.assertTextContains(pill(R.string.dashboard_pill_icons, R.string.dashboard_off))
    palette.assertTextContains(pill(R.string.dashboard_pill_palette, R.string.monet_state_not_provided))
    composeTestRule.runOnIdle { readFailed = true }
    icons.assertTextContains(pill(R.string.dashboard_pill_icons, R.string.status_unknown))
    palette.assertTextContains(pill(R.string.dashboard_pill_palette, R.string.monet_state_unavailable))
  }

  @Test
  fun statusActionsHaveAccessibleLabels() {
    var refreshed = false
    var openedMonet = false
    var openedEnvironment = false
    var onOpenEnvironment by mutableStateOf<(() -> Unit)?>(null)
    val backHost = Os4NavigationTestHost()
    composeTestRule.setContent {
      backHost.Provide {
        KeiHomeTheme {
          KeiHomeScaffold {
            Box(Modifier.verticalScroll(rememberScrollState())) {
              StatusBody(
                connected = false,
                snapshot = null,
                readFailed = false,
                monetSnapshot = null,
                monetReadFailed = false,
                onRefresh = { refreshed = true },
                onOpenMonet = { openedMonet = true },
                onOpenEnvironment = onOpenEnvironment,
              )
            }
          }
        }
      }
    }

    val statusCard = composeTestRule.onNode(
      hasText(context.getString(R.string.status_overview_error_title)) and hasClickAction(),
    )
    composeTestRule.onNodeWithTag("status.overview.version", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithText(
      BuildConfig.VERSION_NAME.substringBefore('+'), useUnmergedTree = true,
    ).assertExists()
    assertEquals(
      context.getString(R.string.status_refresh),
      statusCard.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
    )
    statusCard.performClick()

    val monetCard = composeTestRule.onNode(
      hasText(context.getString(R.string.settings_monet_title)) and hasClickAction(),
    )
    assertEquals(
      context.getString(R.string.status_sheet_open, context.getString(R.string.settings_monet_title)),
      monetCard.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
    )
    val monetDetails = composeTestRule.onNodeWithTag("status.monet.settings")
    monetDetails.assertDoesNotExist()
    monetCard.performClick()
    composeTestRule.runOnIdle { assertFalse(openedMonet) }
    composeTestRule.onNodeWithText(context.getString(R.string.action_label_details)).assertDoesNotExist()
    monetDetails.performScrollTo()
    composeTestRule.onNodeWithText(context.getString(R.string.status_sheet_monet_settings),
      useUnmergedTree = true).assertExists()
    monetDetails.performClick()
    composeTestRule.waitUntil(5_000) { openedMonet }
    monetDetails.assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertTrue(refreshed)
      assertTrue(openedMonet)
      refreshed = false
      onOpenEnvironment = { openedEnvironment = true }
    }
    assertEquals(
      context.getString(R.string.status_open_environment),
      statusCard.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
    )
    statusCard.performClick()
    composeTestRule.runOnIdle {
      assertTrue(openedEnvironment)
      assertFalse(refreshed)
    }
  }

  @Test
  fun hidesOldPaletteOnFailureAndUpdatesAfterRecovery() {
    var currentSnapshot by mutableStateOf(snapshot())
    var readFailed by mutableStateOf(false)
    composeTestRule.setContent {
      KeiHomeTheme {
        Box(Modifier.verticalScroll(rememberScrollState())) {
          MonetDetailCard(snapshot = currentSnapshot, readFailed = readFailed)
        }
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.monet_state_available, 110))
      .assertExists()
    composeTestRule.runOnIdle { readFailed = true }
    composeTestRule.onNodeWithText(context.getString(R.string.monet_state_available, 110))
      .assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_colors_value, 2))
      .assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_unavailable))
      .assertExists()

    composeTestRule.runOnIdle {
      currentSnapshot = snapshot(
        wallpaperColors = listOf(0xFF123456.toInt()),
        launcherState = MonetReadState.UNAVAILABLE,
        frameworkState = MonetReadState.AVAILABLE,
        shadeCount = 6,
        themedIcons = false,
      )
      readFailed = false
    }
    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_colors_value, 1))
      .assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_state_available, 6))
      .assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_themed_icons_off))
      .assertExists()
    composeTestRule.onNodeWithText(
      context.getString(
        R.string.monet_source,
        context.getString(R.string.monet_framework_palette),
      ),
    ).assertExists()
  }

  @Test
  fun unavailablePalettesAreNotReportedAsReady() {
    composeTestRule.setContent {
      KeiHomeTheme {
        Box(Modifier.verticalScroll(rememberScrollState())) {
          MonetDetailCard(
            snapshot = snapshot(
              launcherState = MonetReadState.NOT_PROVIDED,
              frameworkState = MonetReadState.UNAVAILABLE,
              wallpaperState = MonetReadState.UNAVAILABLE,
              themedIcons = null,
            ),
            readFailed = false,
          )
        }
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.monet_palette_available))
      .assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_state_not_provided))
      .assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_state_unavailable))
      .assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_unavailable))
      .assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_themed_icons_unknown))
      .assertExists()
  }

  @Test
  fun distinguishesMissingWallpaperColorsFromReadFailure() {
    var wallpaperState by mutableStateOf(MonetReadState.NOT_PROVIDED)
    composeTestRule.setContent {
      KeiHomeTheme {
        Box(Modifier.verticalScroll(rememberScrollState())) {
          MonetDetailCard(snapshot = snapshot(wallpaperState = wallpaperState), readFailed = false)
        }
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_not_provided))
      .assertExists()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_unavailable))
      .assertDoesNotExist()
    composeTestRule.runOnIdle { wallpaperState = MonetReadState.UNAVAILABLE }
    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_not_provided))
      .assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.monet_wallpaper_unavailable))
      .assertExists()
  }

  @Test
  fun hidesOldFrameworkStatusOnFailureOrDisconnectAndShowsRecovery() {
    var connected by mutableStateOf(true)
    var readFailed by mutableStateOf(false)
    val snapshot = FrameworkSnapshot(
      name = "Test framework",
      apiVersion = 102,
      systemInScope = true,
      target = null,
      launcherTarget = null,
    )
    val identity = context.getString(
      R.string.dashboard_framework_identity, "Test framework",
      context.getString(R.string.dashboard_version_unknown),
    )
    composeTestRule.setContent {
      KeiHomeTheme {
        Box(Modifier.verticalScroll(rememberScrollState())) {
          StatusBody(
            connected = connected,
            snapshot = snapshot,
            readFailed = readFailed,
            monetSnapshot = null,
            monetReadFailed = false,
            onRefresh = {},
            onOpenMonet = {},
          )
        }
      }
    }

    composeTestRule.onNodeWithText(identity).assertExists()
    composeTestRule.runOnIdle { readFailed = true }
    composeTestRule.onNodeWithText(identity).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.status_framework_error))
      .assertExists()
    composeTestRule.runOnIdle { readFailed = false }
    composeTestRule.onNodeWithText(identity).assertExists()
    composeTestRule.runOnIdle { connected = false }
    composeTestRule.onNodeWithText(identity).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.status_framework_disconnected))
      .assertExists()
  }

  @Test
  fun statusLabelsAndTargetsFitNarrowLayoutInEachLanguage() {
    var localeTag by mutableStateOf("en")
    composeTestRule.setContent {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(localeTag))
      }
      val localizedContext = context.createConfigurationContext(configuration)
      val density = LocalDensity.current
      CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
        LocalDensity provides Density(density.density, fontScale = 1.3f),
      ) {
        KeiHomeTheme {
          Box(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
            StatusOverviewCard(
              title = stringResource(R.string.status_overview_warning_title),
              titleBadge = BuildConfig.VERSION_NAME.substringBefore('+'),
              summary = stringResource(R.string.status_overview_warning_summary),
              details = listOf(
                StatusDetail(stringResource(R.string.status_framework), "LSPosed"),
                StatusDetail(
                  stringResource(R.string.status_scope),
                  stringResource(R.string.status_scope_enabled),
                ),
                StatusDetail(
                  stringResource(R.string.status_target_system),
                  stringResource(R.string.status_target_reloading),
                ),
                StatusDetail(
                  stringResource(R.string.status_target_launcher),
                  stringResource(R.string.status_target_failed),
                ),
                StatusDetail(
                  stringResource(R.string.status_recent),
                  stringResource(R.string.status_recent_unreported),
                ),
              ),
              tone = StatusTone.WARNING,
              onRefresh = {},
            )
          }
        }
      }
    }

    listOf("en", "zh-CN", "ja").forEach { tag ->
      composeTestRule.runOnIdle { localeTag = tag }
      val configuration = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(tag))
      }
      val localizedContext = context.createConfigurationContext(configuration)
      listOf(
        R.string.status_framework,
        R.string.status_scope,
        R.string.status_target_system,
        R.string.status_target_launcher,
        R.string.status_target_reloading,
        R.string.status_target_failed,
        R.string.status_recent,
        R.string.status_recent_unreported,
      ).forEach { id ->
        val text = localizedContext.getString(id)
        val node = composeTestRule.onNodeWithText(text, useUnmergedTree = true)
          .fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.runOnIdle {
          assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        }
        assertTrue("No text layout for $tag: $text", layouts.isNotEmpty())
        assertFalse("Clipped text for $tag: $text", layouts.any { it.hasVisualOverflow })
      }
      composeTestRule.onNodeWithText(BuildConfig.VERSION_NAME.substringBefore('+'), useUnmergedTree = true)
        .assertExists()
    }
  }

  private fun snapshot(
    wallpaperColors: List<Int> = listOf(0xFF123456.toInt(), 0xFFABCDEF.toInt()),
    wallpaperState: MonetReadState = MonetReadState.AVAILABLE,
    launcherState: MonetReadState = MonetReadState.AVAILABLE,
    frameworkState: MonetReadState = MonetReadState.NOT_PROVIDED,
    shadeCount: Int = 110,
    themedIcons: Boolean? = true,
  ): MonetPaletteSnapshot {
    val names = listOf("primary", "secondary", "tertiary", "neutral", "neutral-variant")
    val families = (0 until shadeCount).groupBy { it % names.size }.map { (family, indices) ->
      MonetColorFamily(
        name = names[family],
        colors = indices.map { index ->
          MonetColor(tone = index / names.size * 4, value = 0xFF000000.toInt() or index)
        },
      )
    }
    fun palette(name: String, state: MonetReadState) = MonetPaletteSource(
      name = name,
      state = state,
      families = if (state == MonetReadState.AVAILABLE) families else emptyList(),
    )
    return MonetPaletteSnapshot(
      source = null,
      style = null,
      wallpaper = WallpaperColorSnapshot(
        wallpaperState,
        if (wallpaperState == MonetReadState.AVAILABLE) wallpaperColors else emptyList(),
      ),
      launcherPalette = palette("HyperOS launcher", launcherState),
      frameworkPalette = palette("Android framework", frameworkState),
      monochrome = MonochromeIconSettings(themedIcons, null),
    )
  }
}
