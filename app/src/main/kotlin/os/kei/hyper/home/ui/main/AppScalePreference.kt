package os.kei.hyper.home.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import os.kei.hyper.home.R
import os.kei.hyper.home.data.APP_SCALE_RANGE
import os.kei.hyper.home.data.DEFAULT_APP_SCALE_PERCENT
import os.kei.hyper.home.ui.components.Os4BadgePalette
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogActions
import os.kei.hyper.home.ui.components.Os4IconBadge
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Theme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val appScaleKeyPoints = listOf(80f, 90f, 100f, 110f)
private val appScalePresets = listOf(85, 95, 100, 110)

@Composable
internal fun AppScalePreference(percent: Int, onChange: (Int) -> Unit) {
  var editing by rememberSaveable { mutableStateOf(false) }
  val input = rememberTextFieldState()
  val label = stringResource(R.string.app_scale_title)
  ArrowPreference(
    title = label,
    summary = stringResource(R.string.app_scale_summary),
    endActions = {
      Text(
        stringResource(R.string.app_scale_percent, percent),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
      )
    },
    onClick = {
      input.setTextAndPlaceCursorAtEnd(percent.toString())
      editing = true
    },
    holdDownState = editing,
    modifier = Modifier.testTag("settings.app-scale"),
  )
  Os4DetailsSheet(
    show = editing,
    onDismissRequest = { editing = false },
    title = label,
    summary = stringResource(R.string.app_scale_summary),
  ) {
    val entered = input.text.toString().toIntOrNull()?.takeIf { it in APP_SCALE_RANGE }
    val previewPercent = entered ?: percent
    val baseDensity = LocalDensity.current
    val previewRatio = (previewPercent.toFloat() / percent.coerceAtLeast(1).toFloat()).coerceIn(0.65f, 1.45f)
    val scheme = MiuixTheme.colorScheme
    fun applyScale(value: Int) {
      editing = false
      onChange(value)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              stringResource(R.string.app_scale_preview_title),
              style = MiuixTheme.textStyles.footnote1,
              color = scheme.onSurfaceSecondary,
            )
            MetadataPill(stringResource(R.string.app_scale_percent, previewPercent), scheme.primary)
          }
          CompositionLocalProvider(
            LocalDensity provides Density(
              density = baseDensity.density * previewRatio,
              fontScale = baseDensity.fontScale,
            ),
          ) {
            Row(
              Modifier.fillMaxWidth()
                .squircleBackground(scheme.surfaceContainerHigh, 14.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Os4IconBadge(MiuixIcons.Glass.Theme, Os4BadgePalette.Pink, size = 32.dp, cornerRadius = 10.dp, iconSize = 17.dp)
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.app_name), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Text(
                  stringResource(R.string.app_scale_summary),
                  style = MiuixTheme.textStyles.footnote2,
                  color = scheme.onSurfaceSecondary,
                  maxLines = 1,
                )
              }
            }
          }
        }
      }

      FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        appScalePresets.forEach { preset ->
          val active = previewPercent == preset
          val bg by animateColorAsState(
            if (active) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHigh,
            Os4Motion.colorSpring(),
            label = "scale-preset-bg",
          )
          val fg by animateColorAsState(
            if (active) scheme.primary else scheme.onSurfaceSecondary,
            Os4Motion.colorSpring(),
            label = "scale-preset-fg",
          )
          Text(
            stringResource(R.string.app_scale_percent, preset),
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            modifier = Modifier
              .squircleBackground(bg, 12.dp)
              .os4Clickable(role = Role.Button, cornerRadius = 12.dp) {
                input.setTextAndPlaceCursorAtEnd(preset.toString())
              }
              .padding(horizontal = 12.dp, vertical = 7.dp),
          )
        }
      }

      Slider(
        value = previewPercent.toFloat(),
        onValueChange = { input.setTextAndPlaceCursorAtEnd(it.roundToInt().toString()) },
        valueRange = APP_SCALE_RANGE.first.toFloat()..APP_SCALE_RANGE.last.toFloat(),
        keyPoints = appScaleKeyPoints,
        showKeyPoints = true,
        magnetThreshold = 0.01f,
        modifier = Modifier.fillMaxWidth().testTag("settings.app-scale.slider"),
      )

      GlassTextField(
        state = input,
        inputTransformation = InputTransformation {
          if (length > 3 || asCharSequence().any { it !in '0'..'9' }) revertAllChanges()
        },
        label = stringResource(R.string.app_scale_input, APP_SCALE_RANGE.first, APP_SCALE_RANGE.last),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("settings.app-scale.input"),
      )

      Os4DialogActions(
        listOf(
          Os4DialogAction(stringResource(R.string.storage_cancel), { editing = false }),
          Os4DialogAction(
            stringResource(R.string.app_scale_reset, DEFAULT_APP_SCALE_PERCENT),
            { applyScale(DEFAULT_APP_SCALE_PERCENT) },
          ),
          Os4DialogAction(
            stringResource(R.string.storage_save),
            { entered?.let(::applyScale) },
            enabled = entered != null,
            role = GlassDialogButtonRole.Primary,
            tag = "settings.app-scale.save",
          ),
        ),
      )
    }
  }
}
