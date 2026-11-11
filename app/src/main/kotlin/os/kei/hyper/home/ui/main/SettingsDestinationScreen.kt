package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.CacheReminderBudget
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsDestinationScreen(model: HomeViewModel, route: String, onBack: () -> Unit,
  onNavigate: (String) -> Unit, onOpenPackSection: (String) -> Unit) {
  // Retain restored navigation state from before these preferences moved to Packs.
  if (route == SettingsSection.PACKS.route) {
    PackSettingsScreen(model, onBack)
    return
  }
  if (route == SettingsSection.HOOK.route) {
    ScopeAuthorizationScreen(model, onBack)
    return
  }
  if (route == SettingsSection.ABOUT.route || route == "about/environment") {
    AboutScreen(model, onBack = onBack, onOpenNotices = { onNavigate("notices") },
      focusEnvironment = route == "about/environment")
    return
  }
  if (route == SettingsSection.LOG.route) {
    LogSettingsScreen(onBack)
    return
  }
  val section = SettingsSection.entries.firstOrNull { it.route == route } ?: SettingsSection.APP
  val license = bundledLicenseForRoute(route)
  val component = thirdPartyComponentForRoute(route)
  val licenseTitle = when (license?.key) {
    "notices" -> stringResource(R.string.notices_full)
    "dexkit-source" -> stringResource(R.string.notices_source_offer)
    else -> license?.title
  }
  var perfectStorageBytes by remember { mutableLongStateOf(0L) }
  var cacheRefresh by rememberSaveable { mutableIntStateOf(0) }
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionBottomPadding = maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, locales, section) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      if (section == SettingsSection.HOOK) model.observeScopeStatus()
      else model.observePage(4, locales)
    }
  }
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = licenseTitle ?: component?.name ?: stringResource(if (route == "notices") R.string.settings_notices else section.title), scrollBehavior = behavior,
        color = if (backdrop != null && license == null) Color.Transparent else MiuixTheme.colorScheme.surface,
        actions = {
          if (route == SettingsSection.CACHE.route) {
            val preferences by model.preferences.state.collectAsStateWithLifecycle()
            CacheBudgetEditor(preferences.cacheReminderBytes,
              CacheReminderBudget.resolve(preferences.cacheReminderBytes, perfectStorageBytes),
              model.preferences::setCacheReminderBytes)
            IconButton(onClick = { cacheRefresh++ }, modifier = Modifier.testTag("settings.cache.refresh")) {
              Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.refresh))
            }
          }
        },
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } })
    }
  }, floatingActionButton = {
    if (section == SettingsSection.CACHE) PageActionFab(
      stringResource(R.string.action_label_clear), actionIcon(PageActionIcon.CLEAR),
      description = stringResource(R.string.polish_clear_previews),
      onHeightChanged = { actionHeight = it },
      modifier = Modifier.testTag("settings.cache.clear"), onClick = {
        model.clearPreviewCache()
        cacheRefresh++
        android.widget.Toast.makeText(context, R.string.polish_previews_cleared, android.widget.Toast.LENGTH_SHORT).show()
      })
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection).verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + if (section == SettingsSection.CACHE) actionBottomPadding else 24.dp)) {
        when {
          license != null -> LicenseDocument(license.asset)
          component != null -> ThirdPartyComponentDetails(component, onNavigate)
          route == "notices" -> ThirdPartyNotices(onNavigate)
          section == SettingsSection.CACHE -> CacheOverview(model, cacheRefresh, onOpenPackSection,
            onPerfectUsageChanged = { perfectStorageBytes = it })
          else -> SettingsSectionContent(model, section, onNavigate = onNavigate, cacheRefreshKey = cacheRefresh,
            onOpenPerfectIcons = onOpenPackSection,
            onNavigationBarStyleChange = model.preferences::setNavigationBarStyle)
        }
      }
    }
  }
}
