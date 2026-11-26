package os.kei.hyper.home.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogActions
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Edit
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val cacheBudgetPresetsMb = listOf(256L to "256 MB", 512L to "512 MB", 1024L to "1 GB", 2048L to "2 GB")

@Composable
internal fun CacheBudgetEditor(customBudget: Long, effectiveBudget: Long, onChange: (Long) -> Unit) {
  var show by rememberSaveable { mutableStateOf(false) }
  val draft = rememberTextFieldState()
  val draftMb = draft.text.toString().toLongOrNull()
  val value = draftMb?.takeIf { it in 50L..20_000L }?.times(1_000_000L)
  val scheme = MiuixTheme.colorScheme
  KeiHomeIconButton(onClick = {
    val budget = (customBudget.takeIf { it > 0 } ?: effectiveBudget) / 1_000_000L
    draft.setTextAndPlaceCursorAtEnd(budget.toString())
    show = true
  }, modifier = Modifier.testTag("cache.budget.edit")) {
    Icon(MiuixIcons.Glass.Edit, stringResource(R.string.storage_edit_budget))
  }
  Os4DetailsSheet(show, onDismissRequest = { show = false }, title = stringResource(R.string.storage_budget_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        cacheBudgetPresetsMb.forEach { (mb, label) ->
          val active = draftMb == mb
          val bg by animateColorAsState(
            if (active) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHigh,
            Os4Motion.colorSpring(),
            label = "budget-preset-bg",
          )
          val fg by animateColorAsState(
            if (active) scheme.primary else scheme.onSurfaceSecondary,
            Os4Motion.colorSpring(),
            label = "budget-preset-fg",
          )
          Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            modifier = Modifier
              .squircleBackground(bg, 12.dp)
              .os4Clickable(role = Role.Button, cornerRadius = 12.dp) {
                draft.setTextAndPlaceCursorAtEnd(mb.toString())
              }
              .padding(horizontal = 12.dp, vertical = 7.dp),
          )
        }
      }
      GlassTextField(
        state = draft,
        inputTransformation = InputTransformation {
          if (length > 5 || asCharSequence().any { it !in '0'..'9' }) revertAllChanges()
        },
        label = stringResource(R.string.storage_budget_mb),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("cache.budget.input"),
      )
      Text(
        stringResource(R.string.storage_budget_range),
        style = MiuixTheme.textStyles.footnote1,
        color = if (value == null) scheme.error else scheme.onSurfaceVariantSummary,
      )
      Os4DialogActions(
        listOf(
          Os4DialogAction(
            stringResource(R.string.storage_auto),
            { onChange(0L); show = false },
            tag = "cache.budget.auto",
          ),
          Os4DialogAction(stringResource(R.string.storage_cancel), { show = false }),
          Os4DialogAction(
            stringResource(R.string.storage_save),
            { value?.let(onChange); show = false },
            enabled = value != null,
            role = GlassDialogButtonRole.Primary,
            tag = "cache.budget.save",
          ),
        ),
      )
    }
  }
}
