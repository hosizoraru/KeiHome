package os.kei.hyper.home.ui.main

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.status.openNativeThemeDetail
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4Motion

@Composable
internal fun PackActionFab(model: HomeViewModel, page: Int, section: String,
  onHeightChanged: (Int) -> Unit = {}) {
  if (page != 2 && page != 3) return
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val state by model.iconPacks.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val monet by model.monet.collectAsStateWithLifecycle()
  val inspiration = preferences.monetInspiration ?:
    (monet.snapshot?.customization?.style == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION ||
      runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == true)
  val update = page == 3 && when (section) {
    "home", "fallback", "fallback-settings", "extra-icons" -> true
    else -> false
  }
  val generate = page == 3 && section == "coloros"
  val busy = state.nativeImporting != null
  // Read the observed report before deciding visibility so a later source discovery animates the FAB in.
  val activeSource = runtime.report?.iconTheme?.localId
  val pending = if (update) pendingNativeApplication(state, activeSource, preferences.systemIconFallback)
    ?.takeIf { section != "extra-icons" || state.extraIconPreparation == (it.token to preferences.extraIconPackages) }
    else null
  val context = LocalContext.current
  val visible = (update || generate || (page == 2 && inspiration)) &&
    (!update || (activeSource != null &&
      model.currentFallbackSource(runtime.report?.iconTheme, state, preferences.colorOsComponents) != null))
  LaunchedEffect(visible) {
    if (!visible) onHeightChanged(0)
  }
  Os4AnimatedVisibility(
    visible = visible,
    enter = scaleIn(Os4Motion.stateSpring(), initialScale = 0.86f) + fadeIn(tween(180)),
    exit = scaleOut(Os4Motion.stateSpring(), targetScale = 0.86f) + fadeOut(tween(140)),
    label = "pack-action-fab",
  ) {
    val ready = framework.connected && framework.snapshot?.themesInScope == true &&
      runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong() && !runtime.readFailed && !runtime.timedOut
    val description = stringResource(if (busy && state.preparing) R.string.pack_action_preparing
      else if (busy) R.string.icon_pack_importing else if (pending != null) R.string.pack_apply_native else if (update)
      R.string.pack_action_update else if (generate) R.string.pack_action_install else R.string.pack_action_inspiration)
    val label = stringResource(when {
      busy && state.preparing -> R.string.action_label_preparing
      busy -> R.string.action_label_processing
      pending != null -> R.string.action_label_apply
      update -> R.string.action_label_update
      else -> R.string.action_label_apply
    })
    PageActionFab(label, actionIcon(when {
      pending != null -> PageActionIcon.APPLY
      update -> PageActionIcon.UPDATE
      else -> PageActionIcon.APPLY
    }), description = description,
      onHeightChanged = onHeightChanged,
      modifier = Modifier.testTag("icon-packs.$section.action"), enabled = ready, busy = busy, onClick = {
        if (pending != null) {
          openNativeThemeDetail(context, requireNotNull(pending.localId))
        } else if (update) model.updateCurrentSystemFallback() else model.applyColorOs(page == 2)
      })
  }
}
