package os.kei.hyper.home.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.kei.hyper.home.R
import os.kei.hyper.home.status.FrameworkManagerLauncher
import os.kei.hyper.home.status.FrameworkManagerProtocol
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberFrameworkManagerAction(target: Int = FrameworkManagerProtocol.MAIN): () -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var opening by remember { mutableStateOf(false) }
  var help by remember { mutableStateOf(false) }
  Os4ActionDialog(show = help, title = stringResource(R.string.scope_open_manager),
    summary = stringResource(R.string.framework_entry_help, FrameworkManagerProtocol.DIAL_CODE),
    onDismissRequest = { help = false }, icon = actionIcon(PageActionIcon.SETTINGS),
    actions = listOf(
      Os4DialogAction(stringResource(R.string.framework_entry_dial), role = GlassDialogButtonRole.Primary,
        onClick = {
          val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", FrameworkManagerProtocol.DIAL_CODE, null))
          runCatching { context.startActivity(intent); help = false }.onFailure {
            Toast.makeText(context, R.string.framework_entry_no_dialer, Toast.LENGTH_LONG).show()
          }
        }),
      Os4DialogAction(stringResource(R.string.framework_entry_copy), onClick = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
          ClipData.newPlainText("LSPosed", FrameworkManagerProtocol.DIAL_CODE))
        help = false
      }),
      Os4DialogAction(stringResource(R.string.logs_cancel), onClick = { help = false })))
  return {
    if (!opening) {
      opening = true
      scope.launch {
        try { help = !FrameworkManagerLauncher.open(context, target) }
        finally { opening = false }
      }
    }
  }
}
