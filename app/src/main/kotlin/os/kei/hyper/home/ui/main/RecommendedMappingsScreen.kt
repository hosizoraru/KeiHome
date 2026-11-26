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
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RecommendedMappingsScreen(model: HomeViewModel, onBack: () -> Unit) {
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  RecommendedMappingsPage(
    systemAppNameMatching = preferences.systemAppNameMatching,
    telegramChannelMapping = preferences.telegramChannelMapping,
    phoneCompanionMapping = preferences.phoneCompanionMapping,
    weiboLiteMapping = preferences.weiboLiteMapping,
    colorOsThemeStoreMapping = preferences.colorOsThemeStoreMapping,
    nativeImporting = packs.nativeImporting != null,
    onSystemAppNameMatchingChange = model.preferences::setSystemAppNameMatching,
    onTelegramChannelMappingChange = model.preferences::setTelegramChannelMapping,
    onPhoneCompanionMappingChange = model.preferences::setPhoneCompanionMapping,
    onWeiboLiteMappingChange = model.preferences::setWeiboLiteMapping,
    onColorOsThemeStoreMappingChange = model.preferences::setColorOsThemeStoreMapping,
    onBack = onBack,
  )
}

@Composable
internal fun RecommendedMappingsPage(
  systemAppNameMatching: Boolean,
  telegramChannelMapping: Boolean,
  phoneCompanionMapping: Boolean,
  weiboLiteMapping: Boolean,
  colorOsThemeStoreMapping: Boolean,
  nativeImporting: Boolean,
  onSystemAppNameMatchingChange: (Boolean) -> Unit,
  onTelegramChannelMappingChange: (Boolean) -> Unit,
  onPhoneCompanionMappingChange: (Boolean) -> Unit,
  onWeiboLiteMappingChange: (Boolean) -> Unit,
  onColorOsThemeStoreMappingChange: (Boolean) -> Unit,
  onBack: () -> Unit,
) {
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(backdrop = backdrop, modifier = Modifier.testTag("recommended-mappings.page"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.recommended_mappings_title), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("recommended-mappings.back")) {
            Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
          }
        })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().keiHomeTopBarScroll(behavior)
        .verticalScroll(rememberScrollState(), overscrollEffect = null).testTag("recommended-mappings.list")
        .padding(PaddingValues(start = 16.dp, end = 16.dp,
          top = insets.calculateTopPadding() + 4.dp, bottom = insets.calculateBottomPadding() + 24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val enabledCount = listOf(
          systemAppNameMatching,
          telegramChannelMapping,
          phoneCompanionMapping,
          weiboLiteMapping,
          colorOsThemeStoreMapping,
        ).count { it }
        Card(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(16.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
              MetadataPill(stringResource(R.string.recommended_mappings_preview),
                MiuixTheme.colorScheme.primary)
              MetadataPill("$enabledCount/5", os.kei.hyper.home.ui.components.Os4BadgePalette.Teal)
            }
            Text(stringResource(R.string.recommended_mappings_update_note),
              modifier = Modifier.testTag("recommended-mappings.update-note"),
              style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Os4AnimatedVisibility(nativeImporting) {
              Text(stringResource(R.string.recommended_mappings_importing),
                modifier = Modifier.testTag("recommended-mappings.importing"),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          }
        }
        top.yukonga.miuix.kmp.basic.SmallTitle(stringResource(R.string.recommended_section_general))
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          Os4ExplainedSwitchPreference(title = stringResource(R.string.system_name_matching_title),
            summary = stringResource(R.string.system_name_matching_summary),
            explanation = stringResource(R.string.system_name_matching_help),
            checked = systemAppNameMatching, enabled = !nativeImporting,
            onCheckedChange = { if (!nativeImporting) onSystemAppNameMatchingChange(it) },
            tag = "icon-packs.system-name-matching")
        }
        top.yukonga.miuix.kmp.basic.SmallTitle(stringResource(R.string.recommended_section_apps))
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          Os4ExplainedSwitchPreference(title = stringResource(R.string.recommended_mappings_telegram_title),
            summary = stringResource(R.string.recommended_mappings_telegram_summary),
            explanation = stringResource(R.string.recommended_mappings_telegram_help),
            checked = telegramChannelMapping, enabled = !nativeImporting,
            onCheckedChange = { if (!nativeImporting) onTelegramChannelMappingChange(it) },
            tag = "recommended-mappings.telegram")
          os.kei.hyper.home.ui.components.Os4InsetDivider(start = 16.dp)
          Os4ExplainedSwitchPreference(title = stringResource(R.string.recommended_mappings_phone_companions_title),
            summary = stringResource(R.string.recommended_mappings_phone_companions_summary),
            explanation = stringResource(R.string.recommended_mappings_phone_companions_help),
            checked = phoneCompanionMapping, enabled = !nativeImporting,
            onCheckedChange = { if (!nativeImporting) onPhoneCompanionMappingChange(it) },
            tag = "recommended-mappings.phone-companions")
          os.kei.hyper.home.ui.components.Os4InsetDivider(start = 16.dp)
          Os4ExplainedSwitchPreference(title = stringResource(R.string.recommended_mappings_weibo_lite_title),
            summary = stringResource(R.string.recommended_mappings_weibo_lite_summary),
            explanation = stringResource(R.string.recommended_mappings_weibo_lite_help),
            checked = weiboLiteMapping, enabled = !nativeImporting,
            onCheckedChange = { if (!nativeImporting) onWeiboLiteMappingChange(it) },
            tag = "recommended-mappings.weibo-lite")
          os.kei.hyper.home.ui.components.Os4InsetDivider(start = 16.dp)
          Os4ExplainedSwitchPreference(title = stringResource(R.string.recommended_mappings_coloros_title),
            summary = stringResource(R.string.recommended_mappings_coloros_summary),
            explanation = stringResource(R.string.recommended_mappings_coloros_help),
            checked = colorOsThemeStoreMapping, enabled = !nativeImporting,
            onCheckedChange = { if (!nativeImporting) onColorOsThemeStoreMappingChange(it) },
            tag = "recommended-mappings.coloros-theme-store")
        }
      }
    }
  }
}
