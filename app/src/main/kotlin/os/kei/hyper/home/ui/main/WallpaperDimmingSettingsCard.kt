package os.kei.hyper.home.ui.main

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import os.kei.hyper.home.R
import os.kei.hyper.home.data.WallpaperDimStrengths
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4ViewTabs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun WallpaperDimmingSettingsCard(model: HomeViewModel) {
  val settings by model.wallpaperDimming.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val inScope = framework.wallpaperDimmingInScope()
  var show by rememberSaveable { mutableStateOf(false) }
  SplashAppearanceSettingsCard(settings, inScope, model.wallpaperDimming::save,
    scopeChecking = framework.desktopScopeChecking(), scopeReadFailed = framework.readFailed,
    title = R.string.wallpaper_dimming_title, summary = R.string.wallpaper_dimming_summary,
    tag = "wallpaper-dimming", feature = null, scopeMessage = R.string.wallpaper_dimming_scope) {
    BasicComponent(title = stringResource(R.string.wallpaper_dimming_strength),
      summary = stringResource(R.string.wallpaper_dimming_strength_entry),
      enabled = inScope, onClick = { show = true },
      endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) },
      modifier = Modifier.testTag("wallpaper-dimming.strength"))
  }
  WallpaperDimmingStrengthSheet(show && inScope, { show = false }, model.wallpaperDimmingStrength,
    model.wallpaperPreviews)
}

@Composable
private fun WallpaperDimmingStrengthSheet(
  show: Boolean,
  onDismiss: () -> Unit,
  settings: WallpaperDimmingStrengthSettings,
  cache: WallpaperPreviewCache,
) {
  val state by settings.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val previewState by cache.state.collectAsStateWithLifecycle()
  var selected by rememberSaveable { mutableIntStateOf(0) }
  var attempt by remember { mutableIntStateOf(0) }
  val separate = previewState.separate
  val selectedTab = if (separate == false) 0 else selected
  val screen = if (selectedTab == 0) WallpaperManager.FLAG_SYSTEM else WallpaperManager.FLAG_LOCK
  val currentScreen by rememberUpdatedState(screen)
  val manualRefresh = remember(show) { Channel<Unit>(Channel.CONFLATED) }
  val refresh: () -> Unit = { manualRefresh.trySend(Unit) }
  LaunchedEffect(show, attempt, state.strengths == null) { if (show) settings.read() }
  LaunchedEffect(context, lifecycle, show, cache) {
    if (!show) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      val changes = Channel<Unit>(Channel.CONFLATED)
      var pendingChange: Job? = null
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          pendingChange?.cancel()
          pendingChange = launch {
            delay(220)
            changes.trySend(Unit)
          }
        }
      }
      context.registerReceiver(receiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
        Context.RECEIVER_NOT_EXPORTED)
      try {
        cache.refresh(currentScreen)
        while (true) {
          select<Unit> {
            manualRefresh.onReceive { cache.refresh(currentScreen, force = true) }
            changes.onReceive { cache.refresh(currentScreen) }
          }
        }
      } finally {
        pendingChange?.cancel()
        context.unregisterReceiver(receiver)
      }
    }
  }
  Os4DetailsSheet(show, onDismiss, title = stringResource(R.string.wallpaper_dimming_strength),
    endAction = {
      val label = stringResource(R.string.wallpaper_dimming_preview_refresh)
      KeiHomeIconButton(onClick = refresh, enabled = !previewState.refreshing,
        modifier = Modifier.testTag("wallpaper-dimming.refresh")) {
        Icon(MiuixIcons.Glass.Refresh, label)
      }
    }) {
    if (separate != false) {
      Os4ViewTabs(
        listOf(stringResource(R.string.wallpaper_dimming_home), stringResource(R.string.wallpaper_dimming_lock)),
        selectedTab, { selected = it },
      )
    }
    val strengths = state.strengths
    val percent = strengths?.forScreen(selectedTab == 1) ?: 0
    WallpaperPreviewComparison(
      Modifier.align(Alignment.CenterHorizontally).widthIn(max = 380.dp).fillMaxWidth(),
      previewState, screen, strengths ?: WallpaperDimStrengths.Off, show, onRetry = refresh,
    )
    Text(stringResource(if (separate == false) R.string.wallpaper_dimming_shared else R.string.wallpaper_dimming_live),
      style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
    SliderPreference(value = percent.toFloat(), valueRange = 0f..100f,
      title = stringResource(R.string.wallpaper_dimming_strength),
      valueText = stringResource(R.string.wallpaper_dimming_percent, percent),
      enabled = strengths != null && separate != null,
      onValueChange = { settings.set(selectedTab == 1, it.roundToInt().coerceIn(0, 100), separate == true) },
      modifier = Modifier.testTag("wallpaper-dimming.percent"))
    if (state.failed) {
      Text(stringResource(if (strengths == null) R.string.theme_effects_read_failed
        else R.string.theme_effects_save_failed), color = MiuixTheme.colorScheme.error)
      BasicComponent(title = stringResource(R.string.launcher_labels_retry), onClick = {
        settings.retry()
        attempt++
      })
    }
  }
}

@Composable
private fun WallpaperPreviewComparison(
  modifier: Modifier,
  previewState: WallpaperPreviewCache.Snapshot,
  screen: Int,
  strengths: WallpaperDimStrengths,
  show: Boolean,
  onRetry: () -> Unit,
) {
  val previews = previewState.previews
  val home = previews[WallpaperManager.FLAG_SYSTEM]
  val lock = previews[WallpaperManager.FLAG_LOCK]
  val failed = screen in previewState.failed
  val requestPending = previewState.refreshing || (screen !in previews && !failed)
  val selected = previews[screen]
  var showLoading by remember(screen, requestPending, show) { mutableStateOf(false) }
  LaunchedEffect(screen, requestPending, show) {
    if (show && requestPending) {
      delay(150)
      showLoading = true
    }
  }
  var firstScreen by remember { mutableStateOf<Int?>(null) }
  var visibleScreen by remember { mutableIntStateOf(screen) }
  var showedHome by remember { mutableStateOf(false) }
  LaunchedEffect(screen, selected, previews.isEmpty(), show) {
    if (previews.isEmpty()) {
      firstScreen = null
      showedHome = false
    }
    if (show && selected != null) {
      if (firstScreen == null) firstScreen = screen
      visibleScreen = screen
      if (screen == WallpaperManager.FLAG_SYSTEM) showedHome = true
    }
  }
  // The lock preview fades over an opaque home preview. A single alpha also
  // lets a second tap reverse the blend from its current frame.
  val lockAlpha = key(firstScreen) {
    animateFloatAsState(if (visibleScreen == WallpaperManager.FLAG_LOCK) 1f else 0f,
      tween(220), label = "lock wallpaper blend")
  }
  val baseIsHome = home != null && showedHome
  val base = if (baseIsHome) home else lock.takeIf { firstScreen != null }
  val baseImage = remember(base?.image) { base?.image?.asImageBitmap() }
  val lockImage = remember(lock?.image) { lock?.image?.asImageBitmap() }
  val visible = if (visibleScreen == WallpaperManager.FLAG_LOCK) lock else home
  val reveal = animateFloatAsState(if (base == null) 0f else 1f, tween(180), label = "wallpaper preview reveal")
  Box(modifier) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      WallpaperPreviewTile(Modifier.weight(1f), R.string.wallpaper_dimming_before,
        base != null, baseImage, 0, baseIsHome && lock != null, lockImage, 0, reveal, lockAlpha)
      WallpaperPreviewTile(Modifier.weight(1f), R.string.wallpaper_dimming_after,
        base != null, baseImage, if (baseIsHome) strengths.home else strengths.lock,
        baseIsHome && lock != null, lockImage, strengths.lock, reveal, lockAlpha)
    }
    val status = when {
      failed && !previewState.refreshing -> R.string.wallpaper_dimming_preview_failed
      showLoading -> R.string.wallpaper_dimming_preview_loading
      visible != null && visible.image == null -> R.string.wallpaper_dimming_preview_sample
      else -> null
    }
    // The status is measured inside the preview bounds and never moves the slider.
    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
      AnimatedContent(status, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
        label = "wallpaper preview status") { message ->
        if (message != null) WallpaperPreviewStatus(message, onRetry)
      }
    }
  }
}

@Composable
private fun WallpaperPreviewStatus(message: Int, onRetry: () -> Unit) {
  Column(Modifier.widthIn(max = 250.dp).padding(12.dp).clip(RoundedCornerShape(14.dp))
    .background(MiuixTheme.colorScheme.surfaceContainer)
    .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (message == R.string.wallpaper_dimming_preview_loading) {
      CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
    }
    Text(stringResource(message), style = MiuixTheme.textStyles.body2)
    if (message == R.string.wallpaper_dimming_preview_failed) {
      TextButton(stringResource(R.string.launcher_labels_retry), onClick = onRetry)
    }
  }
}

@Composable
private fun WallpaperPreviewTile(
  modifier: Modifier,
  label: Int,
  hasBase: Boolean,
  baseImage: ImageBitmap?,
  basePercent: Int,
  hasLock: Boolean,
  lockImage: ImageBitmap?,
  lockPercent: Int,
  reveal: State<Float>,
  lockAlpha: State<Float>,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(14.dp))
      .background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
      if (hasBase) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }) {
          WallpaperPreviewArtwork(Modifier.matchParentSize(), baseImage, basePercent)
          if (hasLock) WallpaperPreviewArtwork(Modifier.matchParentSize().graphicsLayer { alpha = lockAlpha.value },
            lockImage, lockPercent)
        }
      }
    }
    Text(stringResource(label), Modifier.align(Alignment.CenterHorizontally),
      style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}

@Composable
private fun WallpaperPreviewArtwork(modifier: Modifier, image: ImageBitmap?, percent: Int) {
  Box(modifier) {
    AnimatedContent(image, Modifier.matchParentSize(), transitionSpec = {
      (fadeIn(tween(220)) togetherWith ExitTransition.KeepUntilTransitionsFinished).using(null)
    }, label = "wallpaper image refresh") { artwork ->
      if (artwork != null) {
        Image(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      } else {
        val sample = remember { Brush.verticalGradient(listOf(Color(0xff9cbbcf), Color(0xffe5d2bd), Color(0xff4f6874))) }
        Box(Modifier.fillMaxSize().background(sample))
      }
    }
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = percent / 100f)))
  }
}
