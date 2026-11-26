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
import os.kei.hyper.home.ui.components.Os4ExplainedSwitchPreference
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
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
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackSettingsScreen(model: HomeViewModel, onBack: () -> Unit, onOpenMask: () -> Unit = {}) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(modifier = Modifier.testTag("icon-packs.settings"), backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.pack_action_settings), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
        } })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
        .verticalScroll(rememberScrollState(), overscrollEffect = null).padding(PaddingValues(
          start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + 24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PackSettingsContent(preferences, model.preferences::setAutoApplyIconPack,
          model.preferences::setInstalledIconsOnly, model::setRememberMenuSelections,
          model.preferences::setShowIconPreviews,
          onManualThemeFallback = model.preferences::setManualThemeFallback,
          onOpenMask = onOpenMask,
          onOpenCurrentIconPack = model.preferences::setOpenCurrentIconPack,
          onKeepLatestGeneratedPacks = model.preferences::setKeepLatestGeneratedPacks)
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
  onPreviews: (Boolean) -> Unit,
  showAutoApply: Boolean = true,
  showInstalledOnly: Boolean = true,
  showRememberMenus: Boolean = true,
  showPreviews: Boolean = true,
  onCompleteSystemIconConfig: ((Boolean) -> Unit)? = null,
  onOpenMask: (() -> Unit)? = null,
  onOpenCurrentIconPack: ((Boolean) -> Unit)? = null,
  onKeepLatestGeneratedPacks: ((Boolean) -> Unit)? = null,
  onManualThemeFallback: ((Boolean) -> Unit)? = null,
) {
  if (showAutoApply || onManualThemeFallback != null || showInstalledOnly ||
    onCompleteSystemIconConfig != null || onOpenMask != null) {
    SmallTitle(stringResource(R.string.pack_settings_generation))
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      if (showAutoApply) SwitchPreference(title = stringResource(R.string.pack_auto_apply),
        summary = stringResource(R.string.pack_auto_apply_summary), checked = preferences.autoApplyIconPack,
        onCheckedChange = onAutoApply)
      if (onManualThemeFallback != null) Os4ExplainedSwitchPreference(
        title = stringResource(R.string.pack_manual_theme_fallback),
        summary = stringResource(R.string.pack_manual_theme_fallback_summary),
        explanation = stringResource(R.string.pack_manual_theme_fallback_help),
        checked = preferences.manualThemeFallback, onCheckedChange = onManualThemeFallback,
        tag = "icon-packs.manual-theme-fallback")
      if (showInstalledOnly) Os4ExplainedSwitchPreference(title = stringResource(R.string.pack_installed_icons_only),
        summary = stringResource(R.string.pack_installed_icons_only_summary),
        explanation = stringResource(R.string.pack_installed_icons_only_help), checked = preferences.installedIconsOnly,
        onCheckedChange = onInstalledOnly, tag = "icon-packs.installed-only")
      if (onCompleteSystemIconConfig != null) SwitchPreference(
        title = stringResource(R.string.complete_system_icon_config_title),
        summary = stringResource(R.string.complete_system_icon_config_summary),
        checked = preferences.completeSystemIconConfig,
        onCheckedChange = onCompleteSystemIconConfig,
        modifier = Modifier.testTag("icon-packs.complete-system-icon-config"))
      if (onOpenMask != null) {
        val maskPress = rememberOs4PressSource()
        BasicComponent(
          title = stringResource(R.string.icon_mask_entry_title),
          summary = stringResource(R.string.icon_mask_entry_summary),
          onClick = onOpenMask,
          interactionSource = maskPress,
          endActions = { Os4TrailingChevron() },
          modifier = Modifier.glassPress(maskPress).testTag("icon-packs.open-mask"))
      }
    }
  }
  if (showRememberMenus || showPreviews || onOpenCurrentIconPack != null || onKeepLatestGeneratedPacks != null) {
    SmallTitle(stringResource(R.string.pack_settings_experience))
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      if (onOpenCurrentIconPack != null) SwitchPreference(
        title = stringResource(R.string.pack_open_current),
        summary = stringResource(R.string.pack_open_current_summary),
        checked = preferences.openCurrentIconPack, onCheckedChange = onOpenCurrentIconPack,
        modifier = Modifier.testTag("icon-packs.open-current"))
      if (showRememberMenus) SwitchPreference(title = stringResource(R.string.pack_remember_menus),
        summary = stringResource(R.string.pack_remember_menus_summary), checked = preferences.rememberMenuSelections,
        onCheckedChange = onRememberMenus, modifier = Modifier.testTag("settings.remember-pack-menus"))
      if (onKeepLatestGeneratedPacks != null) Os4ExplainedSwitchPreference(
        title = stringResource(R.string.generated_pack_retention_title),
        summary = stringResource(R.string.generated_pack_retention_summary),
        explanation = stringResource(R.string.generated_pack_retention_help),
        checked = preferences.keepLatestGeneratedPacks, onCheckedChange = onKeepLatestGeneratedPacks,
        tag = "settings.keep-latest-generated-packs")
      if (showPreviews) SwitchPreference(title = stringResource(R.string.polish_previews_title),
        summary = stringResource(R.string.polish_previews_summary), checked = preferences.showIconPreviews,
        onCheckedChange = onPreviews, modifier = Modifier.testTag("settings.icon-previews"))
    }
  }
}
