package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UpgradeRestartReminder(version: String, onAcknowledged: (String) -> Unit) {
  if (version.isEmpty()) return
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
    // Back/outside taps must not acknowledge or bypass the required reading time.
    onDismissRequest = {},
    onDismissFinished = {
      if (confirmed && !delivered) {
        delivered = true
        onAcknowledged(version)
      }
    },
    details = {
      top.yukonga.miuix.kmp.basic.Text(stringResource(R.string.upgrade_restart_note),
        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
    ),
  )
}
