package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.status.ScopeRequestFeedback
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun StaticScopeCard(model: HomeViewModel, attentionOnly: Boolean = false) {
  val state by model.scopeRequests.state.collectAsStateWithLifecycle()
  if (attentionOnly && !state.needsAttention) return
  val openManager = rememberFrameworkManagerAction()
  val plan = state.plan
  val color = MiuixTheme.colorScheme.primary
  Card(Modifier.fillMaxWidth().testTag("framework.static-scope"), insideMargin = PaddingValues(0.dp)) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.scope_title), Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
        if (plan != null) MetadataPill(stringResource(R.string.scope_count, plan.granted.size, plan.eligible.size), color)
      }
      val summary = when {
        !state.connected -> R.string.scope_disconnected
        state.readFailed -> R.string.scope_read_failed
        state.requesting -> R.string.scope_requesting
        plan == null -> R.string.scope_loading
        plan.unsupported.isNotEmpty() -> R.string.scope_unsupported
        plan.unconfirmed.isNotEmpty() -> R.string.scope_unconfirmed
        plan.complete -> R.string.scope_complete
        else -> R.string.scope_missing
      }
      Text(stringResource(summary), style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (plan != null) FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        for (name in plan.declared) {
          val label = when (name) {
            "system" -> stringResource(R.string.scope_system)
            "com.miui.home" -> stringResource(R.string.scope_launcher)
            "com.android.thememanager" -> stringResource(R.string.scope_themes)
            "com.android.systemui" -> stringResource(R.string.scope_system_ui)
            else -> name
          }
          val status = when (name) {
            in plan.granted -> R.string.scope_granted
            in plan.absent -> R.string.scope_absent
            in plan.unconfirmed -> R.string.scope_unknown
            in plan.unsupported -> R.string.scope_not_supported
            else -> R.string.scope_not_granted
          }
          MetadataPill("$label · ${stringResource(status)}",
            if (name in plan.granted) color else MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
      if (plan?.complete != true && !state.requesting) {
        val feedback = when (state.feedback) {
          ScopeRequestFeedback.NONE -> null
          ScopeRequestFeedback.APPROVED -> R.string.scope_partial
          ScopeRequestFeedback.FAILED -> R.string.scope_failed
          ScopeRequestFeedback.RETURNED -> R.string.scope_returned
          ScopeRequestFeedback.TIMEOUT -> R.string.scope_timeout
        }
        if (feedback != null) Text(stringResource(feedback), style = MiuixTheme.textStyles.footnote1)
        state.error?.let { Text(it, style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary) }
      }
      Text(stringResource(R.string.scope_runtime_note), style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(stringResource(if (state.canRequest || state.requesting) R.string.scope_request else R.string.scope_check),
          modifier = Modifier.weight(1f), enabled = state.connected && !state.requesting, onClick = {
            if (state.canRequest) model.scopeRequests.request() else model.scopeRequests.check()
          })
        TextButton(stringResource(R.string.scope_open_manager), modifier = Modifier.weight(1f), onClick = openManager)
      }
    }
  }
}
