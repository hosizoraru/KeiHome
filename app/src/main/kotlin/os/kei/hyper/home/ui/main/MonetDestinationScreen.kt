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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MonetDestinationScreen(model: HomeViewModel, onBack: () -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(2, locales) }
  }
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(backdrop = backdrop,
    modifier = Modifier.testTag("icon-packs.monet"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = stringResource(R.string.navigation_monet),
          scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          navigationIcon = { IconButton(onClick = onBack) {
            Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
          } },
          actions = { RefreshAction(model, 2, Modifier.testTag("monet.page.refresh")) },
        )
      }
    },
    floatingActionButton = { PackActionFab(model, 2, "monet") },
  ) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(
        modifier = Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)
          .verticalScroll(rememberScrollState()).padding(
            start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
            bottom = insets.calculateBottomPadding() + 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        MonetPage(model)
        RefreshFeedback(model, 2)
      }
    }
  }
}

@Composable
private fun MonetPage(model: HomeViewModel) {
  val monet by model.monet.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  val packs by model.iconPacks.collectAsStateWithLifecycle()
  val browser by model.browserSource.collectAsStateWithLifecycle()
  val ordinary = runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == false
  val paletteNames = browser.takeIf { it?.revision == runtime.report?.iconTheme?.previewKey }?.paletteNames
  val locked = colorOsPaletteLocked(ordinary, paletteNames)
  val ready = framework.connected && framework.snapshot?.themesInScope == true &&
    runtime.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong()
  top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    top.yukonga.miuix.kmp.preference.SwitchPreference(
      title = stringResource(R.string.pack_monet_toggle),
      summary = stringResource(if (locked) R.string.pack_monet_ordinary_locked
        else if (ordinary) R.string.pack_monet_fallback_summary else R.string.pack_monet_toggle_summary),
      checked = monet.snapshot?.monochrome?.enabled == true,
      enabled = !locked && framework.connected && monet.snapshot != null &&
        framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong() &&
        monet.action != os.kei.hyper.home.data.MonetActionStatus.APPLYING,
      onCheckedChange = model::setMonetEnabled)
  }
  val inspiration = preferences.monetInspiration ?:
    (monet.snapshot?.customization?.style == os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION ||
      runtime.report?.iconTheme?.localId?.let { preferences.colorOsComponents[it] } == true)
  top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    os.kei.hyper.home.ui.components.OverlaySpinnerPreference(
      title = stringResource(R.string.pack_monet_design),
      items = listOf("Pixel / AOSP", stringResource(R.string.pack_style_inspiration)).map { DropdownItem(text = it) },
      selectedIndex = if (inspiration) 1 else 0,
      onSelectedIndexChange = { model.preferences.setMonetInspiration(it == 1) })
    if (inspiration) IconPackAppearanceControl(preferences.iconPackAppearance,
      model.preferences::setIconPackAppearance, packs.nativeImporting == null, compact = true)
  }
  if (inspiration) ColorOsSettings(model, ready, inspiration = true)
  MonetContent(snapshot = monet.snapshot, readFailed = monet.readFailed,
    preferredStyle = if (inspiration) os.kei.hyper.home.data.MonetSchemeStyle.COLOROS_INSPIRATION else null,
    preferredAppearance = preferences.iconPackAppearance,
    actionAvailable = !locked && framework.connected && !framework.readFailed &&
      framework.snapshot?.target?.loadedVersionCode == BuildConfig.VERSION_CODE.toLong(),
    actionStatus = monet.action, onApply = model::applyMonet)
}
