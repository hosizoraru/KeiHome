package os.kei.hyper.home.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import os.kei.hyper.home.R
import os.kei.hyper.home.status.FrameworkManagerLauncher
import os.kei.hyper.home.status.FrameworkManagerProtocol
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberFrameworkManagerAction(target: Int = FrameworkManagerProtocol.MAIN): () -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var opening by remember { mutableStateOf(false) }
  var help by remember { mutableStateOf(false) }
  if (help) Dialog(onDismissRequest = { help = false }) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.scope_open_manager), style = MiuixTheme.textStyles.title3)
        Text(stringResource(R.string.framework_entry_help, FrameworkManagerProtocol.DIAL_CODE),
          modifier = Modifier.padding(top = 12.dp), style = MiuixTheme.textStyles.body2)
      }
      BasicComponent(title = stringResource(R.string.framework_entry_dial), onClick = {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", FrameworkManagerProtocol.DIAL_CODE, null))
        runCatching { context.startActivity(intent); help = false }.onFailure {
          Toast.makeText(context, R.string.framework_entry_no_dialer, Toast.LENGTH_LONG).show()
        }
      })
      BasicComponent(title = stringResource(R.string.framework_entry_copy), onClick = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
          ClipData.newPlainText("LSPosed", FrameworkManagerProtocol.DIAL_CODE))
        help = false
      })
      BasicComponent(title = stringResource(R.string.logs_cancel), onClick = { help = false })
    }
  }
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
