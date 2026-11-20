package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import top.yukonga.miuix.kmp.utils.overScrollVertical
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import os.kei.hyper.home.ui.theme.LocalKeiHomeScrollEndHaptic
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import os.kei.hyper.home.data.AppTopBarBlur

private val LocalGlassToolbar = staticCompositionLocalOf { false }

@Composable
internal fun keiHomeIconButtonSize(): Dp = if (LocalGlassToolbar.current) 40.dp else 48.dp

@Composable
internal fun Modifier.keiHomeTopBarScroll(scrollBehavior: ScrollBehavior, overScrollEnabled: Boolean = true): Modifier {
  val state = scrollBehavior.state
  val onOffsetChanged: (Float) -> Unit = remember(state) { { state.overscrollOffset = it } }
  // Keep actual scroll inside the spring so rebound never accumulates in contentOffset.
  // MIUIX overscroll can drive its inherited pull state directly, bypassing a parent's
  // disabled nested-scroll connection. Disable that spring too while a pull is unavailable.
  val modifier = if (overScrollEnabled && LocalKeiHomeScrollEndHaptic.current) scrollEndHaptic() else this
  return modifier.overScrollVertical(onOffsetChanged = onOffsetChanged, isEnabled = { overScrollEnabled })
    .nestedScroll(scrollBehavior.nestedScrollConnection)
}

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
  Box(modifier.fillMaxSize().background(containerColor), contentAlignment = Alignment.TopCenter) {
    CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop) {
      top.yukonga.miuix.kmp.basic.Scaffold(
        modifier = Modifier.widthIn(max = 1040.dp).fillMaxSize(), topBar = topBar, bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        floatingToolbar = floatingToolbar, floatingToolbarPosition = floatingToolbarPosition,
        containerColor = containerColor, contentWindowInsets = contentWindowInsets, content = content,
      )
    }
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
  bottomContent: @Composable () -> Unit = {},
  expandable: Boolean = true,
) {
  val backdrop = LocalKeiHomeBackdrop.current
  val progressive = LocalKeiHomeTopBarBlur.current == AppTopBarBlur.PROGRESSIVE
  GlassTopAppBar(title = title, modifier = modifier, backdrop = backdrop,
    expandable = expandable,
    progressiveBlur = progressive && backdrop != null,
    style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
    navigationIcon = { CompositionLocalProvider(LocalGlassToolbar provides true, content = navigationIcon) },
    actions = { KeiHomeTopBarActions(actions) },
    scrollBehavior = scrollBehavior,
    bottomContent = bottomContent,
    bandBrush = if (progressive) GlassTopAppBarDefaults.bandBrush(color)
      else SolidColor(Color.Transparent))
}

@Composable
internal fun KeiHomeTopBarActions(content: @Composable RowScope.() -> Unit) {
  CompositionLocalProvider(LocalGlassToolbar provides true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically, content = content)
  }
}

@Composable
internal fun KeiHomeIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  holdDownState: Boolean = false,
  backgroundColor: Color = Color.Unspecified,
  minHeight: Dp = keiHomeIconButtonSize(),
  minWidth: Dp = keiHomeIconButtonSize(),
  content: @Composable () -> Unit,
) {
  val backdrop = LocalKeiHomeBackdrop.current
  // Explicit legacy 48dp requests must not enlarge toolbar paint or popup anchors.
  val height = if (LocalGlassToolbar.current) 40.dp else minHeight
  val width = if (LocalGlassToolbar.current) 40.dp else minWidth
  if (LocalGlassToolbar.current && enabled && !holdDownState &&
    backgroundColor == Color.Unspecified) {
    GlassIconButton(onClick = onClick, modifier = modifier.semantics { role = Role.Button }, backdrop = backdrop,
      style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
      size = maxOf(height, width), content = content)
  } else {
    top.yukonga.miuix.kmp.basic.IconButton(onClick = onClick,
      modifier = modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        if (!enabled) disabled()
      },
      enabled = enabled, holdDownState = holdDownState, minHeight = height, minWidth = width,
      backgroundColor = if (backgroundColor == Color.Unspecified) Color.Transparent else backgroundColor,
      content = content)
  }
}
