package os.kei.hyper.home.ui.apple

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.apple.AppleArtworkPreview
import os.kei.hyper.home.iconpack.apple.AppleDynamicArtwork
import os.kei.hyper.home.ui.components.IconPreviewInspectionSheet
import os.kei.hyper.home.ui.components.Os4DialogButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppleMappingInspection(
  row: AppleMappingRow, state: ApplePackUiState, controller: ApplePackController,
  maskPolicy: IconMaskPolicy?, show: Boolean, enabled: Boolean,
  onDismiss: () -> Unit, onDismissFinished: () -> Unit, onEdit: () -> Unit,
) {
  val preview by produceState<Pair<Boolean, AppleArtworkPreview?>>(false to null, controller, row.app,
    state.options, state.previewRevision) {
    value = true to try { controller.cachedPreview(row) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
  }
  IconPreviewInspectionSheet(show = show, onDismissRequest = onDismiss,
    onDismissFinished = onDismissFinished, title = row.target.label,
    modifier = Modifier.testTag("apple.mapping.inspection"),
    details = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.target.packageName, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        row.app?.let { app ->
          Text(app.name + " · " + appleRegionName(app.country ?: state.options.country))
        }
        if (row.app?.let { AppleDynamicArtwork.kind(it, state.options) } != null)
          Text(stringResource(R.string.apple_dynamic_preview_note),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Os4DialogButton(stringResource(R.string.apple_glass_change_mapping),
          onClick = onEdit, enabled = enabled, modifier = Modifier.fillMaxWidth()
            .testTag("apple.mapping.inspection.edit"))
      }
    }) { mode ->
    Crossfade(targetState = preview, animationSpec = tween(180), label = "apple-inspection-artwork") { (done, artwork) ->
      Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
        when {
          artwork != null -> AppleArtworkPair(artwork, iconSize = 112.dp, maskPolicy = maskPolicy, mode = mode)
          !done -> CircularProgressIndicator(size = 24.dp, strokeWidth = 2.dp)
          else -> Text(stringResource(R.string.apple_glass_preview_unavailable),
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
  }
}
