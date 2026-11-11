package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.Os4Dialog
import os.kei.hyper.home.ui.components.Os4DialogButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CacheBudgetEditor(customBudget: Long, effectiveBudget: Long, onChange: (Long) -> Unit) {
  var show by rememberSaveable { mutableStateOf(false) }
  var draft by rememberSaveable { mutableStateOf("") }
  val value = draft.toLongOrNull()?.takeIf { it in 50L..20_000L }?.times(1_000_000L)
  KeiHomeIconButton(onClick = {
    draft = ((customBudget.takeIf { it > 0 } ?: effectiveBudget) / 1_000_000L).toString()
    show = true
  }, modifier = Modifier.testTag("cache.budget.edit")) {
    Icon(MiuixIcons.Glass.Edit, stringResource(R.string.storage_edit_budget))
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
