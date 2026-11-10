package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import os.kei.hyper.home.data.AppTopBarBlur

private val LocalGlassToolbar = staticCompositionLocalOf { false }

@Composable
internal fun KeiHomeScaffold(
  modifier: Modifier = Modifier,
  backdrop: Backdrop? = null,
  topBar: @Composable () -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  floatingActionButton: @Composable () -> Unit = {},
  floatingActionButtonPosition: FabPosition = FabPosition.End,
  floatingToolbar: @Composable () -> Unit = {},
  floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
  containerColor: Color = MiuixTheme.colorScheme.surface,
  contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
  content: @Composable (PaddingValues) -> Unit,
) {
  // Pages record their content below the overlay host, never the glass or its own scrim.
  CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop) {
    top.yukonga.miuix.kmp.basic.Scaffold(
      modifier = modifier, topBar = topBar, bottomBar = bottomBar,
      floatingActionButton = floatingActionButton,
      floatingActionButtonPosition = floatingActionButtonPosition,
      floatingToolbar = floatingToolbar, floatingToolbarPosition = floatingToolbarPosition,
      containerColor = containerColor, contentWindowInsets = contentWindowInsets, content = content,
    )
  }
}

@Composable
internal fun KeiHomeTopAppBar(
  title: String,
  modifier: Modifier = Modifier,
  color: Color = MiuixTheme.colorScheme.surface,
  navigationIcon: @Composable () -> Unit = {},
  actions: @Composable RowScope.() -> Unit = {},
  scrollBehavior: ScrollBehavior? = null,
) {
  val backdrop = LocalKeiHomeBackdrop.current
  if (backdrop == null) {
    top.yukonga.miuix.kmp.basic.TopAppBar(
      title = title, modifier = modifier, color = color,
      navigationIcon = navigationIcon, actions = actions, scrollBehavior = scrollBehavior,
    )
  } else {
    val progressive = LocalKeiHomeTopBarBlur.current == AppTopBarBlur.PROGRESSIVE
    GlassTopAppBar(title = title, modifier = modifier, backdrop = backdrop,
      progressiveBlur = progressive,
      style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
      navigationIcon = { CompositionLocalProvider(LocalGlassToolbar provides true, content = navigationIcon) },
      actions = { CompositionLocalProvider(LocalGlassToolbar provides true) { actions() } },
      scrollBehavior = scrollBehavior,
      bandBrush = if (progressive) GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface)
        else SolidColor(Color.Transparent))
  }
}

@Composable
internal fun KeiHomeIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  holdDownState: Boolean = false,
  backgroundColor: Color = Color.Unspecified,
  minHeight: Dp = 40.dp,
  minWidth: Dp = 40.dp,
  content: @Composable () -> Unit,
) {
  val backdrop = LocalKeiHomeBackdrop.current
  if (LocalGlassToolbar.current && backdrop != null && enabled && !holdDownState &&
    backgroundColor == Color.Unspecified) {
    GlassIconButton(onClick = onClick, modifier = modifier, backdrop = backdrop,
      style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
      size = maxOf(minHeight, minWidth), content = content)
  } else {
    top.yukonga.miuix.kmp.basic.IconButton(onClick = onClick,
      modifier = modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        if (!enabled) disabled()
      },
      enabled = enabled, holdDownState = holdDownState, minHeight = minHeight, minWidth = minWidth,
      backgroundColor = if (backgroundColor == Color.Unspecified) Color.Transparent else backgroundColor,
      content = content)
  }
}
