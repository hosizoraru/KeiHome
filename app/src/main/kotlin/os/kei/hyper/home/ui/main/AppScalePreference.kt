package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.roundToInt
import os.kei.hyper.home.R
import os.kei.hyper.home.data.APP_SCALE_RANGE
import os.kei.hyper.home.data.DEFAULT_APP_SCALE_PERCENT
import os.kei.hyper.home.ui.components.Os4Dialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogActions
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val appScaleKeyPoints = listOf(80f, 90f, 100f, 110f)

@Composable
internal fun AppScalePreference(percent: Int, onChange: (Int) -> Unit) {
  var editing by rememberSaveable { mutableStateOf(false) }
  val input = rememberTextFieldState()
  val label = stringResource(R.string.app_scale_title)
  ArrowPreference(
    title = label, summary = stringResource(R.string.app_scale_summary),
    endActions = {
      Text(stringResource(R.string.app_scale_percent, percent),
        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
    },
    onClick = {
      input.setTextAndPlaceCursorAtEnd(percent.toString())
      editing = true
    },
    holdDownState = editing,
    modifier = Modifier.testTag("settings.app-scale"),
  )
  Os4Dialog(show = editing, onDismissRequest = { editing = false }, title = label) {
    val entered = input.text.toString().toIntOrNull()?.takeIf { it in APP_SCALE_RANGE }
    fun applyScale(value: Int) {
      editing = false
      onChange(value)
    }
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Slider(
        value = (entered ?: percent).toFloat(),
        onValueChange = { input.setTextAndPlaceCursorAtEnd(it.roundToInt().toString()) },
        valueRange = APP_SCALE_RANGE.first.toFloat()..APP_SCALE_RANGE.last.toFloat(),
        keyPoints = appScaleKeyPoints, showKeyPoints = true, magnetThreshold = 0.01f,
        modifier = Modifier.fillMaxWidth().testTag("settings.app-scale.slider"),
      )
      GlassTextField(state = input, inputTransformation = InputTransformation {
        if (length > 3 || asCharSequence().any { it !in '0'..'9' }) revertAllChanges()
      }, label = stringResource(R.string.app_scale_input, APP_SCALE_RANGE.first, APP_SCALE_RANGE.last),
        lineLimits = TextFieldLineLimits.SingleLine, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("settings.app-scale.input"))
      Os4DialogActions(listOf(
        Os4DialogAction(stringResource(R.string.storage_cancel), { editing = false }),
        Os4DialogAction(stringResource(R.string.app_scale_reset, DEFAULT_APP_SCALE_PERCENT),
          { applyScale(DEFAULT_APP_SCALE_PERCENT) }),
        Os4DialogAction(stringResource(R.string.storage_save), { entered?.let(::applyScale) },
          enabled = entered != null, role = GlassDialogButtonRole.Primary, tag = "settings.app-scale.save"),
      ))
    }
  }
}
