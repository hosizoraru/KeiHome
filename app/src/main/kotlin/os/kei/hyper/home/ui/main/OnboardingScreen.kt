package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop

@Composable
internal fun OnboardingScreen(model: HomeViewModel, page: Int, replay: Boolean,
  onBack: () -> Unit, onExit: () -> Unit, onNext: () -> Unit,
  onOpenPack: (String) -> Unit, onOpenDesktop: () -> Unit, onOpenLogs: () -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val locales = LocalConfiguration.current.locales.toLanguageTags()
  LaunchedEffect(model, lifecycle, page, locales) {
    if (page == 1 || page == 4) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      model.observePage(4, locales)
    }
  }
  val titles = listOf(R.string.setup_welcome_title, R.string.setup_framework_title,
    R.string.setup_icons_title, R.string.setup_desktop_title, R.string.setup_finish_title)
  val summaries = listOf(R.string.setup_welcome_body, R.string.setup_framework_body,
    R.string.setup_icons_body, R.string.setup_desktop_body, R.string.setup_finish_body)
  val stepLabel = stringResource(R.string.setup_step, page + 1, OnboardingPreferences.PAGE_COUNT)
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  var toolbarHeight by remember { mutableIntStateOf(0) }
  val toolbarClearance = with(LocalDensity.current) { toolbarHeight.toDp() } + 24.dp
  Scaffold(
    modifier = Modifier.testTag("setup.page.$page"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(title = stringResource(R.string.setup_title), scrollBehavior = behavior,
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          actions = { TextButton(stringResource(if (replay) R.string.setup_exit else R.string.setup_later),
            onClick = onExit, modifier = Modifier.testTag("setup.exit")) })
      }
    },
    floatingToolbar = {
      FloatingToolbar(modifier = Modifier.fillMaxWidth()
        .onSizeChanged { toolbarHeight = it.height }.testTag("setup.toolbar"), cornerRadius = 28.dp,
        outSidePadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        showDivider = true) {
        Row(Modifier.fillMaxWidth().padding(10.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          if (page > 0 || replay) TextButton(stringResource(if (page == 0) R.string.pack_back
            else R.string.setup_previous), onClick = onBack,
            modifier = Modifier.weight(1f).testTag("setup.previous"))
          TextButton(stringResource(if (page == 4) R.string.setup_finish else R.string.setup_next),
            onClick = onNext, modifier = Modifier.weight(1f).testTag("setup.next"),
            colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.primary,
              textColor = MiuixTheme.colorScheme.onPrimary))
        }
      }
    },
  ) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      key(page) {
        // Reserve toolbar space inside the scroll content, keeping the viewport behind it visible.
        Column(Modifier.fillMaxSize().testTag("setup.content")
          .nestedScroll(behavior.nestedScrollConnection).verticalScroll(rememberScrollState())
          .padding(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + toolbarClearance),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            .semantics { contentDescription = stepLabel },
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(OnboardingPreferences.PAGE_COUNT) { index ->
              Box(Modifier.weight(1f).height(4.dp)
                .background(if (index <= page) MiuixTheme.colorScheme.primary
                  else MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(2.dp)))
            }
          }
          Text(stepLabel, modifier = Modifier.padding(start = 4.dp),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Icon(when (page) {
                1 -> MiuixIcons.Info
                2 -> MiuixIcons.Layers
                3 -> MiuixIcons.Settings
                else -> MiuixIcons.Home
              }, null, Modifier.size(40.dp), tint = MiuixTheme.colorScheme.primary)
              Text(stringResource(titles[page]), style = MiuixTheme.textStyles.title2,
                modifier = Modifier.semantics { heading() })
              Text(stringResource(summaries[page]), style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          }
          when (page) {
            0 -> Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.setup_feature_icons), summary = stringResource(R.string.setup_feature_icons_body))
              BasicComponent(title = stringResource(R.string.setup_feature_desktop), summary = stringResource(R.string.setup_feature_desktop_body))
              BasicComponent(title = stringResource(R.string.setup_feature_control), summary = stringResource(R.string.setup_feature_control_body))
            }
            1 -> {
              val framework by model.framework.collectAsStateWithLifecycle()
              SetupNote(stringResource(if (framework.connected) R.string.setup_service_connected
                else R.string.setup_service_disconnected), stringResource(R.string.setup_activation_note))
              StaticScopeCard(model)
            }
            2 -> {
              SetupNote(stringResource(R.string.setup_native_icons_title),
                stringResource(R.string.pack_system_appearance_note))
              Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = stringResource(R.string.icon_pack_library), summary = stringResource(R.string.setup_library_body),
                  onClick = { onOpenPack("library") })
                BasicComponent(title = stringResource(R.string.navigation_monet), summary = stringResource(R.string.setup_monet_body),
                  onClick = { onOpenPack("monet") })
                BasicComponent(title = stringResource(R.string.system_fallback_title), summary = stringResource(R.string.setup_fallback_body),
                  onClick = { onOpenPack("fallback") })
              }
              SetupNote(stringResource(R.string.setup_icon_tip_title), stringResource(R.string.setup_icon_tip_body))
            }
            3 -> Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
              BasicComponent(title = stringResource(R.string.setup_open_desktop), summary = stringResource(R.string.setup_open_desktop_body),
                onClick = onOpenDesktop)
            }
            4 -> {
              val framework by model.framework.collectAsStateWithLifecycle()
              StaticScopeCard(model, attentionOnly = true)
              SetupNote(stringResource(if (framework.connected) R.string.setup_service_connected
                else R.string.setup_service_disconnected), stringResource(R.string.setup_finish_note_body))
              Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = stringResource(R.string.settings_log_section), summary = stringResource(R.string.setup_logs_body),
                  onClick = onOpenLogs)
              }
            }
          }
          Spacer(Modifier.height(4.dp).testTag("setup.end"))
        }
      }
    }
  }
}

@Composable
private fun SetupNote(title: String, body: String) {
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MiuixTheme.textStyles.body1)
      Text(body, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
internal fun OnboardingDesktopScreen(model: HomeViewModel, onBack: () -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(model, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeDesktopStatus() }
  }
  Scaffold(topBar = { TopAppBar(title = stringResource(R.string.navigation_desktop),
    navigationIcon = { IconButton(onClick = onBack) {
      Icon(MiuixIcons.Back, stringResource(R.string.pack_back))
    } }) }) { insets ->
    Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
      .padding(16.dp)) { DesktopPage(model) }
  }
}
