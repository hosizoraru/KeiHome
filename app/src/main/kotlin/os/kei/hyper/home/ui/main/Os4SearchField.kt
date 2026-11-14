package os.kei.hyper.home.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.os4Clickable
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Local search mode keeps the real results and their list state in place.
 * Only editing focus is transient; the page retains its query and filters.
 */
@Composable
internal fun Os4SearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  inputModifier: Modifier = Modifier,
  label: String = "",
  onSearch: (String) -> Unit = {},
  enabled: Boolean = true,
) {
  var expanded by remember { mutableStateOf(false) }
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  fun dismiss() {
    expanded = false
    focus.clearFocus()
    keyboard?.hide()
  }
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = expanded,
    onBackCompleted = ::dismiss,
  )
  // Keep the editable node's identity and existing test tag through the animation.
  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    LocalizedInputField(
      query = query,
      onQueryChange = onQueryChange,
      expanded = expanded,
      onExpandedChange = { expanded = it },
      onSearch = { value -> dismiss(); onSearch(value) },
      modifier = inputModifier.weight(1f).onPreviewKeyEvent {
        if (expanded && it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
          dismiss()
          true
        } else false
      },
      label = label,
      enabled = enabled,
    )
    AnimatedVisibility(
      visible = expanded,
      enter = expandHorizontally(animationSpec = spring(dampingRatio = 0.98f, stiffness = 500f)) + fadeIn(),
      exit = shrinkHorizontally(animationSpec = spring(dampingRatio = 0.98f, stiffness = 500f)) + fadeOut(),
    ) {
      val cancel = stringResource(R.string.search_os4_cancel)
      Text(cancel, color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.testTag("os4.search.cancel")
          .os4Clickable(enabled = enabled, role = Role.Button, onClickLabel = cancel,
            cornerRadius = 50.dp, onClick = ::dismiss)
          .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp))
    }
  }
}
