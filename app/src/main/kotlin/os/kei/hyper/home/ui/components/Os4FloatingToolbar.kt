package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.glass.GlassFloatingActionButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassFloatingToolbar
import top.yukonga.miuix.kmp.glass.GlassFloatingToolbarDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults

/** Floating surfaces consume the page recording without becoming part of it. */
@Composable
internal fun Os4FloatingToolbar(
  modifier: Modifier = Modifier,
  primary: Boolean = false,
  content: @Composable () -> Unit,
) {
  GlassFloatingToolbar(
    backdrop = LocalKeiHomeBackdrop.current,
    modifier = Modifier.widthIn(max = 640.dp).then(modifier),
    style = GlassDefaults.style(LocalKeiHomeDarkTheme.current),
    fill = if (primary) GlassFloatingActionButtonDefaults.fill()
      else GlassFloatingToolbarDefaults.fill(),
    material = if (primary) GlassFloatingActionButtonDefaults.material()
      else GlassFloatingToolbarDefaults.material(),
    // The owning scaffold already reserves navigation, keyboard and action insets.
    outSidePadding = PaddingValues(0.dp),
    content = content,
  )
}
