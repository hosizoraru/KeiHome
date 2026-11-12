package os.kei.hyper.home.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One disclosure action and one trailing affordance, independent of a card's navigation actions. */
@Composable
internal fun Os4DisclosureHeader(
  title: String,
  summary: String?,
  expanded: Boolean,
  actionLabel: String,
  stateLabel: String,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  titleContent: (@Composable () -> Unit)? = null,
  bottomAction: (@Composable () -> Unit)? = null,
) {
  val pressSource = rememberOs4PressSource()
  val rotation by animateFloatAsState(
    targetValue = if (expanded) -90f else 90f,
    animationSpec = spring(dampingRatio = 1f, stiffness = 500f),
    label = "disclosure-chevron",
  )
  val indicator: @Composable RowScope.() -> Unit = {
    Icon(MiuixIcons.Glass.ChevronForward, null,
      modifier = Modifier.size(24.dp).testTag("disclosure.indicator").graphicsLayer { rotationZ = rotation },
      tint = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
  if (titleContent != null) {
    BasicComponent(
      modifier = modifier.glassPress(pressSource).semantics { stateDescription = stateLabel },
      onClick = onToggle, onClickLabel = actionLabel, role = Role.Button,
      interactionSource = pressSource,
      insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      bottomAction = bottomAction, endActions = indicator,
    ) { titleContent() }
  } else BasicComponent(
    title = title, summary = summary,
    modifier = modifier.glassPress(pressSource).semantics { stateDescription = stateLabel },
    onClick = onToggle, onClickLabel = actionLabel, role = Role.Button,
    interactionSource = pressSource,
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    bottomAction = bottomAction,
    endActions = indicator,
  )
}

@Composable
internal fun Os4DisclosureContent(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
  AnimatedVisibility(
    visible = expanded,
    enter = expandVertically(
      spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top,
    ) + fadeIn(),
    exit = shrinkVertically(
      spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top,
    ) + fadeOut(),
  ) { Column(Modifier.fillMaxWidth(), content = content) }
}
