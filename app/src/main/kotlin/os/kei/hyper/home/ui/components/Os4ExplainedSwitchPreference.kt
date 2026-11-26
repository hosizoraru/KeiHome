package os.kei.hyper.home.ui.components

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
  BasicComponent(
    modifier = Modifier.testTag(tag).semantics { if (!enabled) disabled() },
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    enabled = enabled,
    role = Role.Switch,
    onClick = { onCheckedChange(!checked) },
    endActions = {
      Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, modifier = Modifier.weight(1f, fill = false),
        fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium,
        color = if (enabled) titleColor.color else titleColor.disabledColor)
      val helpDescription = stringResource(R.string.preference_help_for, title)
      IconButton(onClick = { showHelp = true }, minWidth = 48.dp, minHeight = 28.dp,
        modifier = Modifier.testTag("$helpTag.open").semantics { contentDescription = helpDescription }) {
        Icon(MiuixIcons.Glass.Info, contentDescription = null,
          modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
    Text(summary, style = MiuixTheme.textStyles.body2,
      color = if (enabled) summaryColor.color else summaryColor.disabledColor)
  }
  Os4DetailsSheet(show = showHelp, onDismissRequest = { showHelp = false },
    title = title, modifier = Modifier.testTag(helpTag)) {
    Text(explanation, style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}
