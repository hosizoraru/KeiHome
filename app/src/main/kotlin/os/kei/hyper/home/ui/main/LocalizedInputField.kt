package os.kei.hyper.home.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MIUIX-styled editing with localized icons and page-owned query lifetime.
 * Collapsing changes focus only; the public InputField also clears its query.
 */
@Composable
internal fun LocalizedInputField(
  query: String,
  onQueryChange: (String) -> Unit,
  onSearch: (String) -> Unit,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  label: String = "",
  enabled: Boolean = true,
  textStyle: TextStyle? = null,
  color: Color = MiuixTheme.colorScheme.surfaceContainerHigh,
  leadingIcon: @Composable (() -> Unit)? = null,
  trailingIcon: @Composable (() -> Unit)? = null,
  interactionSource: MutableInteractionSource? = null,
) {
  val source = interactionSource ?: remember { MutableInteractionSource() }
  val focused by source.collectIsFocusedAsState()
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  LaunchedEffect(expanded, enabled) {
    if (expanded && enabled) focusRequester.requestFocus()
    else if (focused) focusManager.clearFocus()
  }
  BasicTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = modifier.focusRequester(focusRequester)
      .onFocusChanged { onExpandedChange(it.isFocused) }
      .semantics { contentDescription = label },
    enabled = enabled,
    singleLine = true,
    textStyle = MiuixTheme.textStyles.main.copy(fontWeight = FontWeight.Medium)
      .merge(textStyle).copy(color = MiuixTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
    interactionSource = source,
    decorationBox = { input ->
      Row(Modifier.fillMaxWidth().background(color, CircleShape)
        .heightIn(min = SearchBarDefaults.InputFieldMinHeight),
        verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) leadingIcon() else Icon(
          modifier = Modifier.padding(
            start = SearchBarDefaults.LeadingIconStartPadding,
            end = SearchBarDefaults.LeadingIconEndPadding,
          ),
          imageVector = MiuixIcons.Glass.Search,
          tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
          contentDescription = null,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
          if (query.isEmpty() && !expanded) Text(label,
            style = MiuixTheme.textStyles.main.merge(textStyle),
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh)
          input()
        }
        if (trailingIcon != null) trailingIcon() else AnimatedVisibility(
          visible = query.isNotEmpty(), enter = fadeIn(), exit = fadeOut(),
        ) {
          val clear = stringResource(R.string.search_clear_text)
          Icon(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp).clip(CircleShape)
              .os4Clickable(enabled = enabled, role = Role.Button, onClickLabel = clear) {
                onQueryChange("")
              }.padding(12.dp),
            imageVector = MiuixIcons.Glass.Close,
            tint = MiuixTheme.colorScheme.onSurfaceContainerHighest,
            contentDescription = clear,
          )
        }
      }
    },
  )
}
