// Provenance: KeiHome-authored adapter; no reference-app code copied or translated.
// Public API reference: https://github.com/compose-miuix-ui/miuix
// Revision: ca674992c9645395cdb0d5109d66504ff53d018c (0.9.4-ca674992-SNAPSHOT).
// Reviewed upstream Apache-2.0 sources under src/commonMain/kotlin/top/yukonga/miuix/kmp/:
// miuix-glass: glass/{GlassDialog,GlassDialogSurface,GlassDialogButton,GlassPanel,Glass}.kt
// miuix-material: material/{GlassMaterials,EdgeStroke}.kt
// miuix-ui: window/WindowDialog.kt and layout/DialogContentLayout.kt
package os.kei.hyper.home.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LocalGlassDialog = staticCompositionLocalOf { false }

@Composable
internal fun Os4Dialog(
  show: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  title: String? = null,
  summary: String? = null,
  onDismissFinished: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  // Only sample the page's existing recording; the overlay and scrim must stay outside it.
  val backdrop = LocalKeiHomeBackdrop.current
  val dark = LocalKeiHomeDarkTheme.current
  val dialogModifier = modifier.semantics {
    dialog()
    title?.let { paneTitle = it }
    dismiss { onDismissRequest(); true }
  }
  val dialogContent: @Composable () -> Unit = {
    CompositionLocalProvider(LocalGlassDialog provides (backdrop != null),
      LocalKeiHomeDarkTheme provides dark, content = content)
  }
  // A null backdrop uses the component's opaque fallback and keeps the same return dispatcher.
  GlassDialog(show = show, backdrop = backdrop, modifier = dialogModifier, title = title,
    material = GlassMaterials.dialogGlass(dark),
    stroke = GlassEdgeStrokes.forTheme(dark, GlassEdgeStrokes.SmallLight, GlassEdgeStrokes.SmallDark),
    summary = summary, onDismissRequest = onDismissRequest,
    onDismissFinished = onDismissFinished, content = dialogContent)
}

@Composable
internal fun Os4DialogButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  role: GlassDialogButtonRole = GlassDialogButtonRole.Normal,
) {
  if (LocalGlassDialog.current) {
    GlassDialogButton(onClick = onClick, modifier = modifier, enabled = enabled, role = role) {
      Text(text, style = MiuixTheme.textStyles.button, color = LocalContentColor.current)
    }
  } else {
    val colors = when (role) {
      GlassDialogButtonRole.Normal -> ButtonDefaults.textButtonColors()
      GlassDialogButtonRole.Primary -> ButtonDefaults.textButtonColorsPrimary()
      GlassDialogButtonRole.Destructive -> ButtonDefaults.textButtonColors(
        textColor = MiuixTheme.colorScheme.error,
        disabledTextColor = MiuixTheme.colorScheme.error.copy(alpha = 0.3f))
    }
    TextButton(text, onClick = onClick, modifier = modifier, enabled = enabled, colors = colors)
  }
}
