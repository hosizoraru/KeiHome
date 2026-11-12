package os.kei.hyper.home.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Icon slots adapted from MIUIX InputField (Apache-2.0); see THIRD_PARTY_NOTICES.md.
 * Changes: localize clear semantics, hide decorative search and honor disabled input.
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
  val currentOnQueryChange by rememberUpdatedState(onQueryChange)
  InputField(
    query = query,
    onQueryChange = onQueryChange,
    onSearch = onSearch,
    expanded = expanded,
    onExpandedChange = onExpandedChange,
    modifier = modifier,
    label = label,
    enabled = enabled,
    textStyle = textStyle,
    color = color,
    leadingIcon = leadingIcon ?: {
      Icon(
        modifier = Modifier.padding(
          start = SearchBarDefaults.LeadingIconStartPadding,
          end = SearchBarDefaults.LeadingIconEndPadding,
        ),
        imageVector = MiuixIcons.Basic.Search,
        tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
        contentDescription = null,
      )
    },
    trailingIcon = trailingIcon ?: {
      AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
        Box(
          modifier = Modifier.padding(
            start = SearchBarDefaults.TrailingIconStartPadding,
            end = SearchBarDefaults.TrailingIconEndPadding,
          ),
          contentAlignment = Alignment.CenterStart,
        ) {
          Icon(
            modifier = Modifier.clip(CircleShape).clickable(enabled = enabled, role = Role.Button) { currentOnQueryChange("") },
            imageVector = MiuixIcons.Basic.SearchCleanup,
            tint = MiuixTheme.colorScheme.onSurfaceContainerHighest,
            contentDescription = stringResource(R.string.search_clear_text),
          )
        }
      }
    },
    interactionSource = interactionSource,
  )
}
