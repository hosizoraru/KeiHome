package os.kei.hyper.home.ui.perfect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.perfect.PerfectGitNetworkOptions
import os.kei.hyper.home.iconpack.perfect.PerfectGitNetworkPreferences
import os.kei.hyper.home.iconpack.perfect.PerfectGitProxyMode
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4BadgePalette
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.Os4DropdownPreference
import os.kei.hyper.home.ui.components.Os4IconBadge
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import os.kei.hyper.home.ui.main.MetadataPill
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PerfectConnectionSettings(enabled: Boolean = true, onSaved: () -> Unit = {}) {
  val context = LocalContext.current.applicationContext
  val preferences = remember(context) { PerfectGitNetworkPreferences(context) }
  val scope = rememberCoroutineScope()
  val currentOnSaved by rememberUpdatedState(onSaved)
  var show by rememberSaveable { mutableStateOf(false) }
  var activeOptions by remember(preferences) { mutableStateOf(preferences.read()) }
  val acceleration = rememberTextFieldState()
  var proxyMode by rememberSaveable { mutableStateOf(PerfectGitProxyMode.SYSTEM) }
  val proxyHost = rememberTextFieldState()
  val proxyPort = rememberTextFieldState()
  var invalid by rememberSaveable { mutableStateOf(false) }
  var saveFailed by rememberSaveable { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  LaunchedEffect(acceleration, proxyHost, proxyPort) {
    snapshotFlow {
      Triple(acceleration.text.toString(), proxyHost.text.toString(), proxyPort.text.toString())
    }.drop(1).collect { invalid = false }
  }
  val canEdit = enabled && !saving
  val title = stringResource(R.string.perfect_connection_title)
  val pressSource = rememberOs4PressSource()

  val modeLabels = listOf(
    stringResource(R.string.perfect_connection_proxy_system),
    stringResource(R.string.perfect_connection_proxy_direct),
    stringResource(R.string.perfect_connection_proxy_http),
    stringResource(R.string.perfect_connection_proxy_socks),
  )
  val modes = listOf(
    PerfectGitProxyMode.SYSTEM,
    PerfectGitProxyMode.DIRECT,
    PerfectGitProxyMode.HTTP,
    PerfectGitProxyMode.SOCKS,
  )
  val statusBadge = when {
    activeOptions.acceleration.isNotBlank() -> stringResource(R.string.perfect_connection_acceleration)
    activeOptions.proxyMode != PerfectGitProxyMode.SYSTEM -> modeLabels[modes.indexOf(activeOptions.proxyMode)]
    else -> null
  }

  BasicComponent(
    title = title,
    summary = stringResource(R.string.perfect_connection_summary),
    enabled = canEdit,
    holdDownState = show,
    role = Role.Button,
    onClickLabel = title,
    interactionSource = pressSource,
    modifier = Modifier.testTag("perfect.connection.open").glassPress(pressSource, canEdit),
    startAction = { Os4IconBadge(MiuixIcons.Glass.Link, Os4BadgePalette.Teal) },
    onClick = {
      if (canEdit) {
        val saved = preferences.read()
        activeOptions = saved
        acceleration.setTextAndPlaceCursorAtEnd(saved.acceleration)
        proxyMode = saved.proxyMode
        proxyHost.setTextAndPlaceCursorAtEnd(saved.proxyHost)
        proxyPort.setTextAndPlaceCursorAtEnd(saved.proxyPort.takeIf { it > 0 }?.toString().orEmpty())
        invalid = false
        saveFailed = false
        show = true
      }
    },
    endActions = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        statusBadge?.let { MetadataPill(it, Os4BadgePalette.Teal) }
        Os4TrailingChevron(
          tint = if (canEdit) MiuixTheme.colorScheme.onSurfaceVariantActions
          else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        )
      }
    },
  )

  val customProxy = proxyMode == PerfectGitProxyMode.HTTP || proxyMode == PerfectGitProxyMode.SOCKS

  Os4DetailsSheet(
    show = show,
    onDismissRequest = { if (!saving) show = false },
    title = title,
    summary = stringResource(R.string.perfect_connection_scope),
  ) {
    Column(Modifier.fillMaxWidth().os4ContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              stringResource(R.string.perfect_connection_acceleration),
              style = MiuixTheme.textStyles.body2,
              fontWeight = FontWeight.SemiBold,
            )
            Os4InfoPopup(
              title = stringResource(R.string.perfect_connection_acceleration),
              summary = stringResource(R.string.perfect_connection_acceleration_help),
              icon = MiuixIcons.Glass.UsageHelp,
            )
          }
          GlassTextField(
            state = acceleration,
            label = stringResource(R.string.perfect_connection_acceleration),
            modifier = Modifier.fillMaxWidth().testTag("perfect.connection.acceleration"),
            enabled = canEdit,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(
              capitalization = KeyboardCapitalization.None,
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Uri,
              imeAction = ImeAction.Next,
            ),
          )
          TextButton(
            text = stringResource(R.string.perfect_connection_fill_example),
            onClick = {
              acceleration.setTextAndPlaceCursorAtEnd("https://gh-proxy.org/")
              invalid = false
              saveFailed = false
            },
            enabled = canEdit,
            minHeight = 36.dp,
            cornerRadius = 18.dp,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            colors = ButtonDefaults.textButtonColors(),
            textStyle = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.testTag("perfect.connection.fill_example"),
          )
        }
      }

      GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              stringResource(R.string.perfect_connection_proxy),
              style = MiuixTheme.textStyles.body2,
              fontWeight = FontWeight.SemiBold,
            )
            Os4InfoPopup(
              title = stringResource(R.string.perfect_connection_proxy),
              summary = stringResource(R.string.perfect_connection_proxy_help),
              icon = MiuixIcons.Glass.UsageHelp,
            )
          }
          Os4DropdownPreference(
            title = stringResource(R.string.perfect_connection_proxy),
            items = modeLabels,
            selectedIndex = modes.indexOf(proxyMode),
            onSelectedIndexChange = { proxyMode = modes[it]; invalid = false },
            enabled = canEdit,
            insideMargin = PaddingValues(vertical = 6.dp),
            modifier = Modifier.testTag("perfect.connection.proxy.mode"),
          )
          Os4AnimatedVisibility(customProxy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              GlassTextField(
                state = proxyHost,
                label = stringResource(R.string.perfect_connection_proxy_host),
                modifier = Modifier.weight(1.7f).testTag("perfect.connection.proxy.host"),
                enabled = canEdit,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                  capitalization = KeyboardCapitalization.None,
                  autoCorrectEnabled = false,
                  keyboardType = KeyboardType.Uri,
                  imeAction = ImeAction.Next,
                ),
              )
              GlassTextField(
                state = proxyPort,
                inputTransformation = InputTransformation {
                  if (length > 5 || asCharSequence().any { it !in '0'..'9' }) revertAllChanges()
                },
                label = stringResource(R.string.perfect_connection_proxy_port),
                modifier = Modifier.weight(1f).testTag("perfect.connection.proxy.port"),
                enabled = canEdit,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                  keyboardType = KeyboardType.Number,
                  imeAction = ImeAction.Done,
                ),
              )
            }
          }
        }
      }

      if (invalid || saveFailed) {
        Text(
          stringResource(if (invalid) R.string.perfect_connection_invalid else R.string.perfect_connection_save_failed),
          color = MiuixTheme.colorScheme.error,
          style = MiuixTheme.textStyles.footnote1,
          modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 4.dp)
            .testTag("perfect.connection.error")
            .semantics { liveRegion = LiveRegionMode.Polite },
        )
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Os4DialogButton(
          text = stringResource(R.string.perfect_connection_cancel),
          onClick = { if (!saving) show = false },
          enabled = !saving,
          modifier = Modifier.weight(1f).testTag("perfect.connection.cancel"),
        )
        Os4DialogButton(
          text = stringResource(if (saving) R.string.perfect_connection_saving else R.string.perfect_connection_save),
          onClick = {
            if (canEdit) {
              val draft = PerfectGitNetworkOptions(
                acceleration = acceleration.text.toString().trim(),
                proxyMode = proxyMode,
                proxyHost = if (customProxy) proxyHost.text.toString().trim() else "",
                proxyPort = if (customProxy) proxyPort.text.toString().toIntOrNull() ?: 0 else 0,
              )
              saving = true
              invalid = false
              saveFailed = false
              scope.launch {
                val saved = try {
                  val checked = draft.checked()
                  withContext(Dispatchers.IO) { preferences.write(checked) }
                  activeOptions = checked
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
                if (saved) {
                  show = false
                  currentOnSaved()
                }
              }
            }
          },
          role = GlassDialogButtonRole.Primary,
          enabled = canEdit,
          modifier = Modifier.weight(1f).testTag("perfect.connection.save"),
        )
      }
    }
  }
}
