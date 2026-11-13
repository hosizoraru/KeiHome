package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.perfect.PerfectGitNetworkOptions
import os.kei.hyper.home.iconpack.perfect.PerfectGitNetworkPreferences
import os.kei.hyper.home.iconpack.perfect.PerfectGitProxyMode
import os.kei.hyper.home.ui.components.Os4ActionDialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DropdownPreference
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PerfectConnectionSettings(enabled: Boolean = true, onSaved: () -> Unit = {}) {
  val context = LocalContext.current.applicationContext
  val preferences = remember(context) { PerfectGitNetworkPreferences(context) }
  val scope = rememberCoroutineScope()
  val currentOnSaved by rememberUpdatedState(onSaved)
  var show by rememberSaveable { mutableStateOf(false) }
  var acceleration by rememberSaveable { mutableStateOf("") }
  var proxyMode by rememberSaveable { mutableStateOf(PerfectGitProxyMode.SYSTEM) }
  var proxyHost by rememberSaveable { mutableStateOf("") }
  var proxyPort by rememberSaveable { mutableStateOf("") }
  var invalid by rememberSaveable { mutableStateOf(false) }
  var saveFailed by rememberSaveable { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  val canEdit = enabled && !saving
  val title = stringResource(R.string.perfect_connection_title)
  val pressSource = rememberOs4PressSource()
  BasicComponent(
    title = title,
    summary = stringResource(R.string.perfect_connection_summary),
    enabled = canEdit,
    role = Role.Button,
    onClickLabel = title,
    interactionSource = pressSource,
    modifier = Modifier.testTag("perfect.connection.open").glassPress(pressSource, canEdit),
    onClick = {
      if (canEdit) {
        val saved = preferences.read()
        acceleration = saved.acceleration
        proxyMode = saved.proxyMode
        proxyHost = saved.proxyHost
        proxyPort = saved.proxyPort.takeIf { it > 0 }?.toString().orEmpty()
        invalid = false
        saveFailed = false
        show = true
      }
    },
    endActions = {
      Icon(MiuixIcons.Glass.ChevronForward, contentDescription = null,
        tint = if (canEdit) MiuixTheme.colorScheme.onSurfaceVariantActions
          else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
    },
  )

  val customProxy = proxyMode == PerfectGitProxyMode.HTTP || proxyMode == PerfectGitProxyMode.SOCKS
  val modes = listOf(PerfectGitProxyMode.SYSTEM, PerfectGitProxyMode.DIRECT,
    PerfectGitProxyMode.HTTP, PerfectGitProxyMode.SOCKS)
  val modeLabels = listOf(
    stringResource(R.string.perfect_connection_proxy_system),
    stringResource(R.string.perfect_connection_proxy_direct),
    stringResource(R.string.perfect_connection_proxy_http),
    stringResource(R.string.perfect_connection_proxy_socks),
  )
  Os4ActionDialog(
    show = show,
    onDismissRequest = { if (!saving) show = false },
    title = title,
    summary = stringResource(R.string.perfect_connection_scope),
    actions = listOf(
      Os4DialogAction(stringResource(R.string.perfect_connection_cancel), { if (!saving) show = false },
        enabled = !saving, tag = "perfect.connection.cancel"),
      Os4DialogAction(stringResource(if (saving) R.string.perfect_connection_saving
        else R.string.perfect_connection_save), {
        if (canEdit) {
          val draft = PerfectGitNetworkOptions(
            acceleration = acceleration.trim(),
            proxyMode = proxyMode,
            proxyHost = if (customProxy) proxyHost.trim() else "",
            proxyPort = if (customProxy) proxyPort.toIntOrNull() ?: 0 else 0,
          )
          saving = true
          invalid = false
          saveFailed = false
          scope.launch {
            val saved = try {
              withContext(Dispatchers.IO) { preferences.write(draft.checked()) }
              true
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: IllegalArgumentException) {
              invalid = true
              false
            } catch (_: IllegalStateException) {
              saveFailed = true
              false
            } finally {
              saving = false
            }
            // Callback failures must not be reported as preference validation failures.
            if (saved) {
              show = false
              currentOnSaved()
            }
          }
        }
      }, role = GlassDialogButtonRole.Primary, enabled = canEdit, tag = "perfect.connection.save"),
    ),
    details = {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(acceleration, onValueChange = { acceleration = it; invalid = false },
          label = stringResource(R.string.perfect_connection_acceleration),
          modifier = Modifier.fillMaxWidth().testTag("perfect.connection.acceleration"),
          enabled = canEdit, singleLine = true,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        TextButton(stringResource(R.string.perfect_connection_fill_example), onClick = {
          acceleration = "https://gh-proxy.org/"
          invalid = false
          saveFailed = false
        }, enabled = canEdit, modifier = Modifier.testTag("perfect.connection.fill_example"))
        Text(stringResource(R.string.perfect_connection_acceleration_help),
          style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Os4DropdownPreference(
          title = stringResource(R.string.perfect_connection_proxy),
          items = modeLabels,
          selectedIndex = modes.indexOf(proxyMode),
          onSelectedIndexChange = { proxyMode = modes[it]; invalid = false },
          enabled = canEdit,
          insideMargin = PaddingValues(vertical = 8.dp),
          modifier = Modifier.testTag("perfect.connection.proxy.mode"),
        )
        if (customProxy) {
          TextField(proxyHost, onValueChange = { proxyHost = it; invalid = false },
            label = stringResource(R.string.perfect_connection_proxy_host),
            modifier = Modifier.fillMaxWidth().testTag("perfect.connection.proxy.host"),
            enabled = canEdit, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
              autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
          TextField(proxyPort, onValueChange = { input ->
            if (input.length <= 5 && input.all { it in '0'..'9' }) {
              proxyPort = input
              invalid = false
            }
          }, label = stringResource(R.string.perfect_connection_proxy_port),
            modifier = Modifier.fillMaxWidth().testTag("perfect.connection.proxy.port"),
            enabled = canEdit, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
          Text(stringResource(R.string.perfect_connection_proxy_help),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (invalid || saveFailed) {
          Text(stringResource(if (invalid) R.string.perfect_connection_invalid else R.string.perfect_connection_save_failed),
            color = MiuixTheme.colorScheme.error,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.fillMaxWidth().testTag("perfect.connection.error")
              .semantics { liveRegion = LiveRegionMode.Polite })
        }
      }
    },
  )
}
