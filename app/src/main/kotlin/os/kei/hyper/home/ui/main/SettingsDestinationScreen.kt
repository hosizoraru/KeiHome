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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsDestinationScreen(model: HomeViewModel, route: String, onBack: () -> Unit,
  onNavigate: (String) -> Unit) {
  if (route == SettingsSection.ABOUT.route || route == "about/environment") {
    AboutScreen(model, onBack = onBack, onOpenNotices = { onNavigate("notices") },
      focusEnvironment = route == "about/environment")
    return
  }
  val section = SettingsSection.entries.firstOrNull { it.route == route } ?: SettingsSection.APP
  val license = bundledLicenseForRoute(route)
  var cacheRefresh by rememberSaveable { mutableIntStateOf(0) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, locales) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observePage(4, locales) }
  }
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  Scaffold(topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = license?.title ?: stringResource(if (route == "notices") R.string.settings_notices else section.title), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        actions = {
          if (route == SettingsSection.CACHE.route) IconButton(onClick = { cacheRefresh++ },
            modifier = Modifier.testTag("settings.cache.refresh")) {
            Icon(MiuixIcons.Refresh, stringResource(R.string.refresh))
          }
        },
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Back, stringResource(R.string.pack_back))
        } })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection).verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 4.dp,
          bottom = insets.calculateBottomPadding() + 24.dp)) {
        when {
          license != null -> LicenseDocument(license.asset)
          route == "notices" -> ThirdPartyNotices(onNavigate)
          else -> SettingsSectionContent(model, section, onNavigate = onNavigate, cacheRefreshKey = cacheRefresh)
        }
      }
    }
  }
}
