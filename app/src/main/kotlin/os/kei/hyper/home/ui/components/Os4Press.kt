package os.kei.hyper.home.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun rememberOs4PressSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/** Custom surfaces share the click's press/cancel stream; native controls retain their own feedback. */
@Composable
internal fun Modifier.os4Clickable(
  enabled: Boolean = true,
  onClickLabel: String? = null,
  role: Role? = null,
  cornerRadius: Dp? = null,
  indication: Indication? = LocalIndication.current,
  onClick: () -> Unit,
): Modifier {
  val source = rememberOs4PressSource()
  val pressed = glassPress(source, enabled)
  val surface = if (cornerRadius != null) pressed.squircleClip(cornerRadius) else pressed
  return surface.clickable(
    interactionSource = source,
    indication = indication,
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick,
  )
}

@Composable
internal fun Modifier.os4Toggleable(
  value: Boolean,
  enabled: Boolean = true,
  role: Role? = null,
  onValueChange: (Boolean) -> Unit,
): Modifier {
  val source = rememberOs4PressSource()
  return glassPress(source, enabled).toggleable(
    value = value,
    interactionSource = source,
    indication = LocalIndication.current,
    enabled = enabled,
    role = role,
    onValueChange = onValueChange,
  )
}
