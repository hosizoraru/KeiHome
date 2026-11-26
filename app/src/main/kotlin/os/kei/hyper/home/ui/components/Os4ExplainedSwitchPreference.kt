package os.kei.hyper.home.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun Os4ExplainedSwitchPreference(
  title: String,
  summary: String,
  explanation: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  tag: String,
  enabled: Boolean = true,
  helpTag: String = "$tag.help",
) {
  var showHelp by rememberSaveable { mutableStateOf(false) }
  val titleColor = BasicComponentDefaults.titleColor()
  val summaryColor = BasicComponentDefaults.summaryColor()
  val animatedTitleColor by animateColorAsState(
    targetValue = if (enabled) titleColor.color else titleColor.disabledColor,
    animationSpec = Os4Motion.colorSpring(),
    label = "explained-switch-title",
  )
  val animatedSummaryColor by animateColorAsState(
    targetValue = if (enabled) summaryColor.color else summaryColor.disabledColor,
    animationSpec = Os4Motion.colorSpring(),
    label = "explained-switch-summary",
  )
  val animatedInfoTint by animateColorAsState(
    targetValue = if (showHelp) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
    animationSpec = Os4Motion.colorSpring(),
    label = "explained-switch-info",
  )
  BasicComponent(
    modifier = Modifier.testTag(tag).semantics { if (!enabled) disabled() },
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    enabled = enabled,
    holdDownState = showHelp,
    role = Role.Switch,
    onClick = { onCheckedChange(!checked) },
    endActions = {
      Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, modifier = Modifier.weight(1f, fill = false),
        fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium,
        color = animatedTitleColor)
      val helpDescription = stringResource(R.string.preference_help_for, title)
      IconButton(onClick = { showHelp = true }, minWidth = 32.dp, minHeight = 28.dp,
        holdDownState = showHelp,
        modifier = Modifier.testTag("$helpTag.open").semantics { contentDescription = helpDescription }) {
        Icon(MiuixIcons.Glass.Info, contentDescription = null,
          modifier = Modifier.size(20.dp), tint = animatedInfoTint)
      }
    }
    AnimatedContent(
      targetState = summary,
      transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
      label = "explained-switch-summary-text",
    ) { text ->
      Text(text, style = MiuixTheme.textStyles.body2, color = animatedSummaryColor)
    }
  }
  Os4DetailsSheet(show = showHelp, onDismissRequest = { showHelp = false },
    title = title, modifier = Modifier.testTag(helpTag)) {
    Text(explanation, style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}
