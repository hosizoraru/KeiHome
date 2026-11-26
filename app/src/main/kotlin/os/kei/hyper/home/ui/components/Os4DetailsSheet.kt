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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalOs4GlassSurface = staticCompositionLocalOf { false }

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
  startAction: (@Composable () -> Unit)? = null,
  endAction: (@Composable () -> Unit)? = null,
  headerBottomContent: @Composable () -> Unit = {},
  scrollOffset: (() -> Float)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
  val backdrop = LocalKeiHomeBackdrop.current
  val dark = LocalKeiHomeDarkTheme.current
  val locals = currentCompositionLocalContext
  val bodyScroll = rememberScrollState()
  val effectiveScrollOffset = scrollOffset ?: if (scrollContent) ({ bodyScroll.value.toFloat() }) else null
  val headerScrolled by remember(effectiveScrollOffset) {
    derivedStateOf { effectiveScrollOffset?.let { it() > 1f } }
  }
  fun wrapHeaderAction(action: (@Composable () -> Unit)?): (@Composable () -> Unit)? = action?.let {
    {
      CompositionLocalProvider(LocalGlassToolbar provides true, LocalKeiHomeDarkTheme provides dark) {
        it()
      }
    }
  }
  // Keep the overlay header for both wrapper-owned scrolling and nested content lists.
  GlassBottomSheet(show = show, onDismissRequest = onDismissRequest, backdrop = backdrop,
    progressiveBlur = true,
    title = title, insideMargin = insideMargin, modifier = modifier.semantics {
      dialog()
      title?.let { paneTitle = it }
      dismiss { onDismissRequest(); true }
    },
    onDismissFinished = onDismissFinished,
    startAction = wrapHeaderAction(startAction),
    endAction = wrapHeaderAction(endAction),
    headerBottomContent = {
      CompositionLocalProvider(
        LocalKeiHomeDarkTheme provides dark,
        LocalKeiHomeBackdrop provides backdrop,
        LocalGlassTopBarScrolled provides headerScrolled,
        content = headerBottomContent,
      )
    },
    scrollOffset = effectiveScrollOffset) { headerPadding ->
    CompositionLocalProvider(locals) {
      CompositionLocalProvider(LocalOs4GlassSurface provides true) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
          .then(if (scrollContent) Modifier.verticalScroll(bodyScroll) else Modifier)
          .padding(headerPadding)
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
}
