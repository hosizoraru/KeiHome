package os.kei.hyper.home.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.status.AppearanceRefreshResult
import os.kei.hyper.home.status.DesktopAppearanceInbox
import os.kei.hyper.home.status.DesktopAppearanceReport
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class DesktopAppearanceUiState(
  val report: DesktopAppearanceReport? = null,
  val checking: Boolean = false,
  val failed: Boolean = false,
)

@Composable
internal fun DesktopToolsScreen(model: HomeViewModel, onBack: () -> Unit) {
  val state by model.desktopAppearance.collectAsStateWithLifecycle()
  val lastRefresh by DesktopAppearanceInbox.lastRefresh.collectAsStateWithLifecycle()
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  LaunchedEffect(model) { model.readDesktopAppearance() }
  Scaffold(modifier = Modifier.testTag("desktop.page.tools"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.desktop_tools_title), scrollBehavior = behavior, expandable = false,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } },
        actions = { IconButton(enabled = !state.checking, onClick = { model.readDesktopAppearance() }) {
          Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.desktop_tools_read))
        } })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().keiHomeTopBarScroll(behavior).verticalScroll(rememberScrollState())
        .padding(PaddingValues(start = 16.dp, end = 16.dp,
          top = insets.calculateTopPadding() + 4.dp, bottom = insets.calculateBottomPadding() + 24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          val report = state.report
          BasicComponent(title = stringResource(R.string.desktop_tools_status), summary = stringResource(when {
            state.checking -> R.string.desktop_tools_reading
            state.failed || report == null -> R.string.desktop_tools_unavailable
            report.wallpaperDark == true && report.homeDarkIcons == true -> R.string.desktop_tools_mismatch
            report.homeSubscribed == false -> R.string.desktop_tools_subscription_missing
            else -> R.string.desktop_tools_snapshot
          }))
          Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolValue(R.string.desktop_tools_mode, stringResource(when (report?.selectedMode) {
              0 -> R.string.desktop_tools_auto
              1 -> R.string.desktop_tools_light
              2 -> R.string.desktop_tools_dark
              3 -> R.string.desktop_tools_scheduled
              else -> R.string.desktop_tools_unknown
            }))
            ToolValue(R.string.desktop_tools_current, diagnosticBoolean(report?.night,
              R.string.desktop_tools_dark, R.string.desktop_tools_light))
            ToolValue(R.string.desktop_tools_wallpaper, diagnosticBoolean(report?.wallpaperDark,
              R.string.desktop_tools_dark, R.string.desktop_tools_light))
            ToolValue(R.string.desktop_tools_subscription, diagnosticBoolean(report?.homeSubscribed,
              R.string.desktop_tools_found, R.string.desktop_tools_not_found))
            ToolValue(R.string.desktop_tools_foreground, diagnosticBoolean(report?.homeDarkIcons,
              R.string.desktop_tools_dark_ink, R.string.desktop_tools_light_ink))
            ToolValue(R.string.desktop_tools_launcher,
              report?.launcherVersion?.toString() ?: stringResource(R.string.desktop_tools_unknown))
            report?.let {
              val at = System.currentTimeMillis() -
                (android.os.SystemClock.elapsedRealtime() - it.checkedAtElapsed).coerceAtLeast(0)
              Text(stringResource(R.string.desktop_tools_checked,
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(at))),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            Text(stringResource(R.string.desktop_tools_provenance),
              style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary,
              modifier = Modifier.padding(bottom = 16.dp))
          }
        }
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = stringResource(R.string.desktop_tools_refresh_title),
            summary = stringResource(R.string.desktop_tools_refresh_summary))
          Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            lastRefresh?.takeUnless { state.checking && it.result == AppearanceRefreshResult.UNCONFIRMED }?.let {
              Text(stringResource(R.string.desktop_tools_last_result,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.atMillis)),
                stringResource(refreshResultText(it.result))),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            Button(onClick = { model.readDesktopAppearance(refresh = true) }, enabled = !state.checking,
              colors = ButtonDefaults.buttonColorsPrimary(),
              modifier = Modifier.fillMaxWidth().testTag("desktop.tools.refresh")) {
              Text(stringResource(if (state.checking) R.string.desktop_tools_working
                else R.string.desktop_tools_refresh_action))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ToolValue(@StringRes title: Int, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(title), modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
    Text(value, style = MiuixTheme.textStyles.body2)
  }
}

@Composable
private fun diagnosticBoolean(value: Boolean?, @StringRes yes: Int, @StringRes no: Int): String =
  stringResource(when (value) { true -> yes; false -> no; null -> R.string.desktop_tools_unknown })

@StringRes
private fun refreshResultText(result: AppearanceRefreshResult): Int = when (result) {
  AppearanceRefreshResult.REFRESHED -> R.string.desktop_tools_sent
  AppearanceRefreshResult.UNSUPPORTED -> R.string.desktop_tools_unsupported
  AppearanceRefreshResult.FAILED -> R.string.desktop_tools_failed
  AppearanceRefreshResult.RESTORE_FAILED -> R.string.desktop_tools_restore_failed
  AppearanceRefreshResult.UNCONFIRMED -> R.string.desktop_tools_unconfirmed
}
