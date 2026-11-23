package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Keep this composed while hidden so the native drag/back exit can finish. */
@Composable
internal fun Os4DetailsSheet(
  show: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  title: String? = null,
  summary: String? = null,
  scrollContent: Boolean = true,
  onDismissFinished: (() -> Unit)? = null,
  insideMargin: DpSize = BottomSheetDefaults.insideMargin,
  endAction: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
  val backdrop = LocalKeiHomeBackdrop.current
  val locals = currentCompositionLocalContext
  GlassBottomSheet(show = show, onDismissRequest = onDismissRequest, backdrop = backdrop,
    title = title, insideMargin = insideMargin, modifier = modifier.semantics {
      dialog()
      title?.let { paneTitle = it }
      dismiss { onDismissRequest(); true }
    },
    onDismissFinished = onDismissFinished,
    endAction = endAction) {
    CompositionLocalProvider(locals) {
      Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
        .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .navigationBarsPadding()
        .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        summary?.let {
          Text(it, style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        content()
      }
    }
  }
}
