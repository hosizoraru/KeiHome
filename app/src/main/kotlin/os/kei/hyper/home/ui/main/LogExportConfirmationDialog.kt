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

/** Keep composed until dismissal finishes; a new presentation always starts a fresh wait. */
@Composable
internal fun LogExportConfirmationDialog(onDismissed: () -> Unit, onExport: () -> Unit) {
  val deadline = remember { SystemClock.elapsedRealtime() + 5_000L }
  var seconds by remember { mutableIntStateOf(5) }
  var visible by remember { mutableStateOf(true) }
  var accepted by remember { mutableStateOf(false) }
  var delivered by remember { mutableStateOf(false) }
  LaunchedEffect(visible) {
    while (visible) {
      val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
      seconds = ((remaining + 999L) / 1_000L).toInt()
      if (remaining == 0L) break
      delay(remaining - (seconds - 1L) * 1_000L)
    }
  }
  Os4ActionDialog(
    show = visible,
    title = stringResource(R.string.logs_export_confirm_title),
    summary = stringResource(R.string.logs_export_confirm_summary),
    icon = actionIcon(PageActionIcon.EXPORT),
    verticalActions = true,
    onDismissRequest = { visible = false },
    onDismissFinished = {
      if (!delivered) {
        delivered = true
        if (accepted) onExport()
        onDismissed()
      }
    },
    actions = listOf(
      Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { visible = false },
        tag = "logs.export.cancel"),
      Os4DialogAction(
        text = if (seconds > 0) stringResource(R.string.logs_export_countdown, seconds)
          else stringResource(R.string.action_label_export),
        role = GlassDialogButtonRole.Primary,
        enabled = visible && seconds == 0,
        tag = "logs.export.confirm",
        onClick = {
          // Recheck time and ownership even if an old accessibility callback is invoked.
          if (visible && !accepted && SystemClock.elapsedRealtime() >= deadline) {
            accepted = true
            visible = false
          }
        },
      ),
    ),
  )
}
