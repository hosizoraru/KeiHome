package os.kei.hyper.home.ui.main

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OnboardingScreen(model: HomeViewModel, page: Int, replay: Boolean,
  onBack: () -> Unit, onExit: () -> Unit, onNext: () -> Unit,
  onOpenPack: (String) -> Unit, onOpenDesktop: () -> Unit, onOpenSettings: (String) -> Unit,
  onOpenDesktopSection: (String) -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(model, lifecycle, page) {
    if (page == 1 || page == 4) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      model.observeScopeStatus()
    }
  }
  val backdrop = rememberKeiHomeBlurBackdrop()
  val stepLabel = stringResource(R.string.setup_step, page + 1, OnboardingPreferences.PAGE_COUNT)
  OnboardingPageLayout(backdrop = backdrop, modifier = Modifier.testTag("setup.page.$page"), header = {
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
      contentAlignment = Alignment.TopCenter) {
    Row(Modifier.widthIn(max = 640.dp).fillMaxWidth()
      .testTag("setup.header")
      .padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 48.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Text(stringResource(R.string.app_name), modifier = Modifier.weight(1f, fill = false),
        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.subtitle)
      Row(Modifier.semantics { contentDescription = stepLabel },
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(OnboardingPreferences.PAGE_COUNT) { index ->
          val width by animateDpAsState(if (index == page) 24.dp else 6.dp, tween(200), label = "setup-step")
          Box(Modifier.width(width).height(6.dp).background(
            if (index <= page) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer,
            CircleShape))
        }
      }
      TextButton(stringResource(if (replay) R.string.setup_os4_exit else R.string.setup_os4_later),
        onClick = onExit, modifier = Modifier.heightIn(min = 48.dp).testTag("setup.exit"))
    }
    }
  }, footer = {
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
      contentAlignment = Alignment.BottomCenter) {
    Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
      .testTag("setup.toolbar"),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      if (page > 0 || replay) {
        val previous = stringResource(if (page == 0) R.string.pack_back else R.string.setup_previous)
        GlassIconButton(onClick = onBack, backdrop = backdrop, inheritTopBarMaterial = false,
          style = GlassDefaults.style(LocalKeiHomeDarkTheme.current), size = 56.dp,
          modifier = Modifier.testTag("setup.previous").semantics { role = Role.Button; contentDescription = previous }) {
          Icon(MiuixIcons.Glass.Back, null, Modifier.size(24.dp))
        }
      } else Spacer(Modifier.size(56.dp))
      PageActionFab(stringResource(when (page) {
        0 -> R.string.setup_os4_start
        4 -> R.string.setup_os4_done
        else -> R.string.setup_os4_next
      }), if (page == 4) MiuixIcons.Glass.Ok else MiuixIcons.Glass.ChevronForward,
        onClick = onNext, trailingIcon = true, modifier = Modifier.widthIn(max = 260.dp).testTag("setup.next"))
    }
    }
  }) { insets ->
      key(page) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()
          .testTag("setup.content").verticalScroll(rememberScrollState())
          .padding(insets).padding(horizontal = 24.dp),
          verticalArrangement = Arrangement.spacedBy(24.dp)) {
          OnboardingHero(page)
          OnboardingStepContent(model, page, onOpenPack, onOpenDesktop, onOpenSettings, onOpenDesktopSection)
          Spacer(Modifier.height(8.dp).testTag("setup.end"))
        }
      }
  }
}

@Composable
internal fun OnboardingDesktopScreen(model: HomeViewModel, onBack: () -> Unit, onOpenSection: (String) -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(model, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeDesktopStatus(DesktopSection.OVERVIEW) }
  }
  val backdrop = rememberKeiHomeBlurBackdrop()
  val behavior = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior()
  Scaffold(backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.desktop_home_title),
        modifier = Modifier.testTag("setup.desktop.topbar"),
        scrollBehavior = behavior,
        color = if (backdrop != null) androidx.compose.ui.graphics.Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack) {
          Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
        } })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      Column(Modifier.fillMaxSize().testTag("setup.desktop.content")
        .nestedScroll(behavior.nestedScrollConnection)
        .verticalScroll(rememberScrollState()).padding(insets)
        .padding(16.dp)) { DesktopPage(model, onOpenSection) }
    }
  }
}
