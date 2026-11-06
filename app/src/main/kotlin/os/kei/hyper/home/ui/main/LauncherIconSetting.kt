package os.kei.hyper.home.ui.main

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.data.LauncherIconVisibility
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun LauncherIconSetting() {
  val context = LocalContext.current
  val controller = remember(context) { LauncherIconVisibility(context) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val scope = rememberCoroutineScope()
  val changes = remember(controller) { Mutex() }
  var hidden by remember(controller) { mutableStateOf(false) }
  var ready by remember(controller) { mutableStateOf(false) }
  var saving by remember(controller) { mutableStateOf(false) }
  LaunchedEffect(controller, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      try {
        changes.withLock {
          hidden = withContext(Dispatchers.IO) { controller.isHidden() }
          ready = true
        }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) { ready = false }
      awaitCancellation()
    }
  }
  SwitchPreference(title = stringResource(R.string.hide_launcher_icon),
    summary = stringResource(R.string.hide_launcher_icon_summary),
    modifier = Modifier.testTag("settings.hide-launcher-icon"),
    checked = hidden, enabled = ready && !saving, onCheckedChange = { value ->
      if (!saving) {
        saving = true
        scope.launch {
          try {
            changes.withLock {
              withContext(Dispatchers.IO) { controller.setHidden(value) }
              hidden = value
            }
          } catch (cancelled: CancellationException) { throw cancelled
          } catch (_: Exception) {
            Toast.makeText(context, R.string.hide_launcher_icon_failed, Toast.LENGTH_LONG).show()
          } finally { saving = false }
        }
      }
    })
}
