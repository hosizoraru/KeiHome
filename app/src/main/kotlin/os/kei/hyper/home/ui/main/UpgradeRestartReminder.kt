package os.kei.hyper.home.ui.main

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4BadgePalette
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4IconBadge
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.HomeOutline
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SYSTEM_LAUNCHER_PACKAGE = "com.miui.home"

@Composable
internal fun UpgradeRestartReminder(version: String, onAcknowledged: (String) -> Unit) {
  if (version.isEmpty()) return
  val context = LocalContext.current
  val deadline = remember(version) { SystemClock.elapsedRealtime() + 5_000L }
  var seconds by remember(version) { mutableIntStateOf(5) }
  var visible by remember(version) { mutableStateOf(true) }
  var confirmed by remember(version) { mutableStateOf(false) }
  var delivered by remember(version) { mutableStateOf(false) }
  LaunchedEffect(version, visible) {
    while (visible) {
      val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
      seconds = ((remaining + 999L) / 1_000L).toInt()
      if (remaining == 0L) break
      delay(remaining - (seconds - 1L) * 1_000L)
    }
  }
  Os4ActionDialog(
    show = visible,
    title = stringResource(R.string.upgrade_restart_title),
    summary = stringResource(R.string.upgrade_restart_body, version),
    artwork = {
      Os4IconBadge(
        icon = MiuixIcons.Glass.Refresh,
        tint = Os4BadgePalette.Blue,
        size = 44.dp,
        cornerRadius = 13.dp,
        iconSize = 23.dp,
      )
    },
    // Back/outside taps must not acknowledge or bypass the required reading time.
    onDismissRequest = {},
    dismissible = false,
    verticalActions = true,
    onDismissFinished = {
      if (confirmed && !delivered) {
        delivered = true
        onAcknowledged(version)
      }
    },
    details = {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(
          modifier = Modifier.fillMaxWidth(),
          cornerRadius = 16.dp,
          insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
          Column(Modifier.fillMaxWidth()) {
            RestartReasonSection(
              icon = MiuixIcons.Glass.Info,
              title = stringResource(R.string.upgrade_restart_reason_title),
              body = stringResource(R.string.upgrade_restart_reason_body),
            )
            HorizontalDivider(
              modifier = Modifier.padding(vertical = 12.dp),
              color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            )
            RestartReasonSection(
              icon = MiuixIcons.Glass.HomeOutline,
              title = stringResource(R.string.upgrade_restart_steps_title),
              body = stringResource(R.string.upgrade_restart_steps_body),
            )
          }
        }
        Text(
          text = stringResource(R.string.upgrade_restart_note),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
          style = MiuixTheme.textStyles.footnote1.copy(lineHeight = 18.sp, lineBreak = LineBreak.Paragraph),
          color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
      }
    },
    actions = listOf(
      Os4DialogAction(
        text = if (seconds > 0) stringResource(R.string.upgrade_restart_countdown, seconds)
          else stringResource(R.string.upgrade_restart_acknowledge),
        role = GlassDialogButtonRole.Primary,
        enabled = visible && seconds == 0 && !confirmed,
        tag = "upgrade.restart.confirm",
        onClick = {
          if (visible && !confirmed && SystemClock.elapsedRealtime() >= deadline) {
            confirmed = true
            visible = false
          }
        },
      ),
      Os4DialogAction(
        text = stringResource(R.string.upgrade_restart_open_launcher_settings),
        role = GlassDialogButtonRole.Normal,
        enabled = visible && !confirmed,
        tag = "upgrade.restart.launcher_settings",
        onClick = {
          try {
            val intent = Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.fromParts("package", SYSTEM_LAUNCHER_PACKAGE, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
          } catch (_: Throwable) {
            // Keep the reminder open if the system settings activity cannot be resolved.
          }
        },
      ),
    ),
  )
}

@Composable
private fun RestartReasonSection(
  icon: ImageVector,
  title: String,
  body: String,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.primary,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = title,
        style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
    }
    Text(
      text = body,
      style = MiuixTheme.textStyles.body2.copy(lineHeight = 20.sp, lineBreak = LineBreak.Paragraph),
      color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.82f),
      modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
    )
  }
}
