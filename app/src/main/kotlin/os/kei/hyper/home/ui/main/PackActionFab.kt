package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackActionFab(model: HomeViewModel, page: Int, section: String) {
  if (page != 2 && page != 3) return
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val inspiration = preferences.monetInspiration ?:
    (model.monet.value.snapshot?.customization?.style == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION ||
      runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == true)
  val update = page == 3 && section == "home"
  val generate = page == 3 && section == "coloros"
  val busy = state.nativeImporting != null
  if ((!update && !generate && !(page == 2 && inspiration)) ||
    (update && model.currentFallbackSource() == null)) return
  val ready = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong()
  val label = stringResource(if (busy && state.preparing) R.string.pack_action_preparing
    else if (busy) R.string.icon_pack_importing else if (update)
    R.string.pack_action_update else if (generate) R.string.pack_action_install else R.string.pack_action_inspiration)
  FloatingActionButton(modifier = Modifier.widthIn(min = 164.dp)
    .semantics { if (!ready || busy) disabled() }, onClick = {
    if (ready && !busy) {
      if (update) model.updateCurrentSystemFallback() else model.applyColorOs(page == 2)
    }
  }, shape = RoundedCornerShape(28.dp),
    containerColor = if (ready || busy) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Crossfade(busy, animationSpec = tween(160), label = "pack-action-progress") { loading ->
          if (loading) CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
              foregroundColor = MiuixTheme.colorScheme.onPrimary,
              backgroundColor = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.2f)))
          else Icon(if (update) MiuixIcons.Refresh else MiuixIcons.Theme, null,
            tint = if (ready) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
      Text(label, style = MiuixTheme.textStyles.button,
        color = if (ready || busy) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}
