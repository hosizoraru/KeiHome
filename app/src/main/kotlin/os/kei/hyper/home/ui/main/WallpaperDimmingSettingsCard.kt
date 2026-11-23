package os.kei.hyper.home.ui.main

import android.app.WallpaperManager
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import os.kei.hyper.home.R
import os.kei.hyper.home.status.WallpaperPreviewProtocol
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4ViewTabs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
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
  WallpaperDimmingStrengthSheet(show && inScope, { show = false }, model.wallpaperDimmingStrength)
}

@Composable
private fun WallpaperDimmingStrengthSheet(
  show: Boolean,
  onDismiss: () -> Unit,
  settings: WallpaperDimmingStrengthSettings,
) {
  val state by settings.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var selected by rememberSaveable { mutableIntStateOf(0) }
  var attempt by remember { mutableIntStateOf(0) }
  var previews by remember { mutableStateOf<Map<Int, WallpaperPreviewProtocol.Preview>>(emptyMap()) }
  var separate by remember { mutableStateOf<Boolean?>(null) }
  var previewFailed by remember { mutableStateOf(false) }
  val screen = if (selected == 0) WallpaperManager.FLAG_SYSTEM else WallpaperManager.FLAG_LOCK
  LaunchedEffect(show, attempt, state.strengths == null) { if (show) settings.read() }
  DisposableEffect(context, show) {
    if (!show) {
      onDispose {}
    } else {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          previews = emptyMap()
          attempt++
        }
      }
      context.registerReceiver(receiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), Context.RECEIVER_NOT_EXPORTED)
      onDispose { context.unregisterReceiver(receiver) }
    }
  }
  LaunchedEffect(show, screen, attempt) {
    if (show && screen !in previews) {
      previewFailed = false
      val preview = WallpaperPreviewProtocol.read(context, screen)
      if (preview == null) {
        previewFailed = true
      } else {
        previews = previews + (screen to preview)
        separate = preview.separateDimming
        if (!preview.separateDimming) selected = 0
      }
    }
  }
  Os4DetailsSheet(show, onDismiss, title = stringResource(R.string.wallpaper_dimming_strength),
    onDismissFinished = {
      if (!show) {
        previews = emptyMap()
        separate = null
        previewFailed = false
      }
    }) {
    if (separate != false) {
      Os4ViewTabs(
        listOf(stringResource(R.string.wallpaper_dimming_home), stringResource(R.string.wallpaper_dimming_lock)),
        selected, { selected = it },
      )
    }
    val preview = previews[screen]
    val strengths = state.strengths
    val percent = strengths?.forScreen(selected == 1) ?: 0
    val image = remember(preview?.image) { preview?.image?.asImageBitmap() }
    if (preview != null) {
      Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 380.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WallpaperPreviewTile(Modifier.weight(1f), R.string.wallpaper_dimming_before, image, 0)
        WallpaperPreviewTile(Modifier.weight(1f), R.string.wallpaper_dimming_after, image, percent)
      }
    }
    val hint = when {
      previewFailed -> R.string.wallpaper_dimming_preview_failed
      preview == null -> R.string.wallpaper_dimming_preview_loading
      separate == false -> R.string.wallpaper_dimming_shared
      image == null -> R.string.wallpaper_dimming_preview_sample
      else -> R.string.wallpaper_dimming_live
    }
    Text(stringResource(hint), style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
    if (strengths != null && separate != null) {
      SliderPreference(value = percent.toFloat(), valueRange = 0f..100f,
        title = stringResource(R.string.wallpaper_dimming_strength),
        valueText = stringResource(R.string.wallpaper_dimming_percent, percent),
        onValueChange = { settings.set(selected == 1, it.roundToInt().coerceIn(0, 100), separate == true) },
        modifier = Modifier.testTag("wallpaper-dimming.percent"))
    }
    if (state.failed || previewFailed) {
      if (state.failed) Text(stringResource(if (strengths == null) R.string.theme_effects_read_failed
        else R.string.theme_effects_save_failed), color = MiuixTheme.colorScheme.error)
      BasicComponent(title = stringResource(R.string.launcher_labels_retry), onClick = {
        if (state.failed) settings.retry()
        attempt++
      })
    }
  }
}

@Composable
private fun WallpaperPreviewTile(modifier: Modifier, label: Int, image: ImageBitmap?, percent: Int) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(14.dp))) {
      if (image != null) {
        Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
      } else {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
          listOf(Color(0xff9cbbcf), Color(0xffe5d2bd), Color(0xff4f6874)))))
      }
      Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = percent / 100f)))
    }
    Text(stringResource(label), Modifier.align(Alignment.CenterHorizontally),
      style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}
