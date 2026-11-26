package os.kei.hyper.home.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ContributorsScreen(onBack: () -> Unit) {
  val uriHandler = LocalUriHandler.current
  val backdrop = rememberKeiHomeBlurBackdrop()
  val behavior = MiuixScrollBehavior()
  Scaffold(backdrop = backdrop, modifier = Modifier.testTag("contributors.page"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.about_contributors_title), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("contributors.back")) {
            Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
          }
        })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
      contentAlignment = Alignment.TopCenter) {
      Column(Modifier.widthIn(max = 840.dp).fillMaxSize().keiHomeTopBarScroll(behavior)
        .verticalScroll(rememberScrollState(), overscrollEffect = null)
        .padding(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 8.dp,
          bottom = insets.calculateBottomPadding() + 24.dp)) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          BasicComponent(title = "@jizizr", summary = stringResource(R.string.about_contributors_jizizr_thanks),
            modifier = Modifier.testTag("contributors.jizizr"),
            onClickLabel = stringResource(R.string.about_contributors_open_profile),
            onClick = { uriHandler.openUri("https://github.com/jizizr") },
            startAction = {
              Image(painterResource(R.drawable.contributor_jizizr), contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape))
            },
            endActions = {
              Icon(actionIcon(PageActionIcon.OPEN_EXTERNAL), null, tint = MiuixTheme.colorScheme.onSurfaceSecondary)
            })
        }
      }
    }
  }
}
