package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppPreferencesState
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackSettingsScreen(model: HomeViewModel, onBack: () -> Unit) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(modifier = Modifier.testTag("icon-packs.settings"), backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.pack_action_settings), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)
        .verticalScroll(rememberScrollState()).padding(PaddingValues(
          start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + 24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PackSettingsContent(preferences, model.preferences::setAutoApplyIconPack,
          model.preferences::setInstalledIconsOnly, model::setRememberMenuSelections,
          model.preferences::setCompactPackHints, model.preferences::setShowIconPreviews,
          onCompleteSystemIconConfig = model.preferences::setCompleteSystemIconConfig)
      }
    }
  }
}

@Composable
internal fun PackSettingsContent(
  preferences: AppPreferencesState,
  onAutoApply: (Boolean) -> Unit,
  onInstalledOnly: (Boolean) -> Unit,
  onRememberMenus: (Boolean) -> Unit,
  onCompactHints: (Boolean) -> Unit,
  onPreviews: (Boolean) -> Unit,
  showAutoApply: Boolean = true,
  showInstalledOnly: Boolean = true,
  showRememberMenus: Boolean = true,
  showCompactHints: Boolean = true,
  showPreviews: Boolean = true,
  onCompleteSystemIconConfig: ((Boolean) -> Unit)? = null,
) {
  if (showAutoApply || showInstalledOnly || onCompleteSystemIconConfig != null) {
    SmallTitle(stringResource(R.string.pack_settings_generation))
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      if (showAutoApply) SwitchPreference(title = stringResource(R.string.pack_auto_apply),
        summary = stringResource(R.string.pack_auto_apply_summary), checked = preferences.autoApplyIconPack,
        onCheckedChange = onAutoApply)
      if (showInstalledOnly) SwitchPreference(title = stringResource(R.string.pack_installed_icons_only),
        summary = stringResource(R.string.pack_installed_icons_only_summary), checked = preferences.installedIconsOnly,
        onCheckedChange = onInstalledOnly)
      if (onCompleteSystemIconConfig != null) SwitchPreference(
        title = stringResource(R.string.complete_system_icon_config_title),
        summary = stringResource(R.string.complete_system_icon_config_summary),
        checked = preferences.completeSystemIconConfig,
        onCheckedChange = onCompleteSystemIconConfig,
        modifier = Modifier.testTag("icon-packs.complete-system-icon-config"))
    }
  }
  if (showRememberMenus || showCompactHints || showPreviews) {
    SmallTitle(stringResource(R.string.pack_settings_experience))
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      if (showRememberMenus) SwitchPreference(title = stringResource(R.string.pack_remember_menus),
        summary = stringResource(R.string.pack_remember_menus_summary), checked = preferences.rememberMenuSelections,
        onCheckedChange = onRememberMenus)
      if (showCompactHints) SwitchPreference(title = stringResource(R.string.pack_compact_hints_title),
        summary = stringResource(R.string.pack_compact_hints_summary), checked = preferences.compactPackHints,
        onCheckedChange = onCompactHints, modifier = Modifier.testTag("settings.compact-pack-hints"))
      if (showPreviews) SwitchPreference(title = stringResource(R.string.polish_previews_title),
        summary = stringResource(R.string.polish_previews_summary), checked = preferences.showIconPreviews,
        onCheckedChange = onPreviews, modifier = Modifier.testTag("settings.icon-previews"))
    }
  }
}
