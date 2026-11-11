package os.kei.hyper.home.ui.main

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4Dialog
import os.kei.hyper.home.ui.components.Os4DialogButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CacheBudgetEditor(customBudget: Long, effectiveBudget: Long, onChange: (Long) -> Unit) {
  val context = LocalContext.current
  var show by rememberSaveable { mutableStateOf(false) }
  var draft by rememberSaveable { mutableStateOf("") }
  val value = draft.toLongOrNull()?.takeIf { it in 50L..20_000L }?.times(1_000_000L)
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
    draft = ((customBudget.takeIf { it > 0 } ?: effectiveBudget) / 1_000_000L).toString()
    show = true
  }.testTag("cache.budget.edit"), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween) {
    Column(Modifier.weight(1f)) {
      Text(stringResource(R.string.storage_budget_title), style = MiuixTheme.textStyles.body1)
      Text(stringResource(if (customBudget == 0L) R.string.storage_budget_auto else R.string.storage_budget_custom,
        Formatter.formatShortFileSize(context, effectiveBudget)), style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    Text(stringResource(R.string.storage_modify), color = MiuixTheme.colorScheme.primary)
  }
  Os4Dialog(show, onDismissRequest = { show = false }, title = stringResource(R.string.storage_budget_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      TextField(draft, onValueChange = { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) draft = it },
        label = stringResource(R.string.storage_budget_mb), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("cache.budget.input"))
      Text(stringResource(R.string.storage_budget_range), style = MiuixTheme.textStyles.footnote1,
        color = if (value == null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Os4DialogButton(stringResource(R.string.storage_auto), { onChange(0L); show = false },
          modifier = Modifier.weight(1f).testTag("cache.budget.auto"))
        Os4DialogButton(stringResource(R.string.storage_cancel), { show = false },
          modifier = Modifier.weight(1f))
        Os4DialogButton(stringResource(R.string.storage_save), { value?.let(onChange); show = false },
          enabled = value != null, role = GlassDialogButtonRole.Primary,
          modifier = Modifier.weight(1f).testTag("cache.budget.save"))
      }
    }
  }
}
