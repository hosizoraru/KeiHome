package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.SearchGlassStyle
import os.kei.hyper.home.status.LauncherRuntimeCapabilities
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

@Composable
internal fun SearchGlassSettingsCard(model: HomeViewModel) {
  val state by model.launcherGlass.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.homeScreenRuntime.collectAsStateWithLifecycle()
  val inScope = framework.launcherGlassInScope()
  val choices = listOf(R.string.search_glass_follow_dock,
    R.string.launcher_glass_system, R.string.launcher_glass_light, R.string.launcher_glass_dark)
  val now = android.os.SystemClock.elapsedRealtime()
  val version = BuildConfig.VERSION_CODE.toLong()
  val supported = desktopCapability(runtime, now, version, LauncherRuntimeCapabilities.GLASS_PAIR)
  val refreshes by model.refreshResults.collectAsStateWithLifecycle()
  val choice = state.saved?.let { dock -> state.savedSearch?.let { search ->
    os.kei.hyper.home.data.LauncherGlassChoice(dock, search)
  } }
  val status = rememberDesktopOperationStatus(
    desktopSearchGlassStatus(state, runtime, inScope, now, version), choice, refreshes[5])
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    OverlaySpinnerPreference(title = stringResource(R.string.search_glass_title),
      summary = stringResource(R.string.search_glass_summary),
      items = choices.map { DropdownItem(text = stringResource(it)) },
      selectedIndex = (state.savedSearch ?: SearchGlassStyle.FOLLOW_DOCK).ordinal,
      enabled = desktopControlEnabled(inScope, state.saving, state.unavailable, state.savedSearch != null, supported),
      onSelectedIndexChange = { index -> SearchGlassStyle.entries.getOrNull(index)?.let(model.launcherGlass::saveSearch) },
      modifier = Modifier.testTag("search-glass.style"))
    DesktopSettingProgress(status, Modifier.testTag("desktop.feature.search-material.progress"))
    if (state.failed && inScope) BasicComponent(title = stringResource(R.string.launcher_labels_retry),
      enabled = !state.saving, onClick = {
        when {
          state.pendingSearch != null -> model.launcherGlass.saveSearch(state.pendingSearch!!)
          state.pending != null -> model.launcherGlass.save(state.pending!!)
          else -> model.readLauncherGlassSettings()
        }
      })
  }
}
