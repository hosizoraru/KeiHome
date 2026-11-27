package os.kei.hyper.home.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ExplainedSwitchInsideMargin = PaddingValues(horizontal = 16.dp, vertical = 13.dp)

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
  leadSummary: String? = summary,
  presentation: Os4InfoPresentation = Os4InfoPresentation.Auto,
) {
  var infoOpen by remember { mutableStateOf(false) }
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
  BasicComponent(
    modifier = Modifier.testTag(tag).semantics { if (!enabled) disabled() },
    insideMargin = ExplainedSwitchInsideMargin,
    enabled = enabled,
    holdDownState = infoOpen,
    role = Role.Switch,
    onClick = { onCheckedChange(!checked) },
    endActions = {
      Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    },
  ) {
    // Keep the info trigger active even while the switch itself is disabled so users can read
    // requirements or behavior without mutating the preference.
    Os4ExplainedTitleRow(
      title = title,
      explanation = explanation,
      leadSummary = leadSummary,
      titleColor = animatedTitleColor,
      titleFontWeight = FontWeight.Medium,
      presentation = presentation,
      helpTag = helpTag,
      onOpenChange = { infoOpen = it },
    )
    AnimatedContent(
      targetState = summary,
      transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
      label = "explained-switch-summary-text",
    ) { text ->
      Text(text, style = MiuixTheme.textStyles.body2, color = animatedSummaryColor)
    }
  }
}
