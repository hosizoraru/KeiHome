package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CenteredLinkAction(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  description: String = label,
  icon: ImageVector? = null,
  colors: ButtonColors = ButtonDefaults.buttonColors(
    color = Color.Transparent,
    disabledColor = Color.Transparent,
    contentColor = MiuixTheme.colorScheme.primary,
  ),
) {
  Button(
    onClick = onClick,
    modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
      .semantics(mergeDescendants = true) { contentDescription = description },
    enabled = enabled,
    minHeight = 48.dp,
    colors = colors,
  ) {
    // Keep the full action description on the button, without repeating its short caption.
    Row(
      modifier = Modifier.widthIn(max = 480.dp).testTag("link.content").clearAndSetSemantics {},
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) Icon(icon, null, Modifier.size(20.dp))
      Text(label, modifier = Modifier.weight(1f, fill = false),
        style = MiuixTheme.textStyles.button, textAlign = TextAlign.Center)
    }
  }
}
