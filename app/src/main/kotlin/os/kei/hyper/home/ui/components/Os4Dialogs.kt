// Provenance: KeiHome-authored adapter; no reference-app code copied or translated.
// Public API reference: https://github.com/compose-miuix-ui/miuix
// Revision: 9d4b9c85c18707c7da12cf8c497ea57955fcf81e (0.9.4-9d4b9c85-SNAPSHOT).
// Reviewed upstream Apache-2.0 sources under src/commonMain/kotlin/top/yukonga/miuix/kmp/:
// miuix-glass: glass/{GlassDialog,GlassDialogSurface,GlassDialogButton,GlassPanel,Glass}.kt
// miuix-material: material/{GlassMaterials,EdgeStroke}.kt
// miuix-ui: window/WindowDialog.kt and layout/DialogContentLayout.kt
package os.kei.hyper.home.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun Os4Dialog(
  show: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  title: String? = null,
  summary: String? = null,
  onDismissFinished: (() -> Unit)? = null,
  centered: Boolean? = null,
  dismissible: Boolean = true,
  sampleBackdrop: Boolean = true,
  content: @Composable () -> Unit,
) {
  // Only sample the page's existing recording; the overlay and scrim must stay outside it.
  val backdrop = LocalKeiHomeBackdrop.current.takeIf { sampleBackdrop }
  val dark = LocalKeiHomeDarkTheme.current
  val dialogModifier = modifier.semantics {
    dialog()
    title?.let { paneTitle = it }
    if (dismissible) dismiss { onDismissRequest(); true }
  }
  val dialogContent: @Composable () -> Unit = {
    CompositionLocalProvider(LocalKeiHomeDarkTheme provides dark, content = content)
  }
  // A null backdrop uses the component's opaque fallback and keeps the same return dispatcher.
  GlassDialog(show = show, backdrop = backdrop, modifier = dialogModifier, title = title,
    largeScreen = centered, summary = summary, onDismissRequest = { if (dismissible) onDismissRequest() },
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
  // The native action has no backdrop dependency, including on opaque dialog surfaces.
  GlassDialogButton(onClick = onClick, modifier = modifier, enabled = enabled, role = role) {
    Text(text, style = MiuixTheme.textStyles.button, color = LocalContentColor.current)
  }
}
