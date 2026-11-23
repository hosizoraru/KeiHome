package os.kei.hyper.home.ui.main

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.kei.hyper.home.R
import os.kei.hyper.home.data.WallpaperPresetChoices
import os.kei.hyper.home.data.WallpaperPresetPreferences
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.WallpaperPresetProtocol as Protocol
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.Os4DetailsSheet
import os.kei.hyper.home.ui.components.Os4ViewTabs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WallpaperPresetSettingsCard(model: HomeViewModel) {
  val framework by model.framework.collectAsStateWithLifecycle()
  val inScope = framework.wallpaperDimmingInScope()
  var show by rememberSaveable { mutableStateOf(false) }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(title = stringResource(R.string.wallpaper_presets_title),
      summary = stringResource(if (inScope) R.string.wallpaper_presets_summary else R.string.wallpaper_presets_scope),
      enabled = inScope, onClick = { show = true },
      endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) },
      modifier = Modifier.testTag("wallpaper-presets.open"))
  }
  WallpaperPresetSheet(show && inScope) { show = false }
}

@Composable
private fun WallpaperPresetSheet(show: Boolean, onDismiss: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val service by FrameworkConnection.service.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val refresh = remember { Channel<Unit>(Channel.CONFLATED) }
  val thumbnails = remember { LruCache<String, PresetThumbnail>(16) }
  var mode by rememberSaveable { mutableIntStateOf(0) }
  var catalog by remember { mutableStateOf<Protocol.Catalog?>(null) }
  var choices by remember(service) { mutableStateOf<WallpaperPresetChoices?>(null) }
  var loading by remember { mutableStateOf(false) }
  var loadFailed by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  var saveFailed by remember { mutableStateOf(false) }
  val activeService = service
  val selected = choices?.forMode(mode == 1)
  val entries = catalog?.keys.orEmpty()

  LaunchedEffect(show, context, lifecycle, activeService) {
    if (!show) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh.trySend(Unit) }
      }
      val resolver = context.contentResolver
      resolver.registerContentObserver(Settings.Secure.getUriFor(Protocol.SYSTEM_CONFIG), false, observer)
      try {
        refresh.trySend(Unit)
        for (signal in refresh) {
          loading = true
          try {
            choices = if (activeService == null) null else WallpaperPresetPreferences.read(activeService)
            val result = Protocol.catalog(context, force = true) ?: run {
              delay(350)
              Protocol.catalog(context, force = true)
            }
            if (result != null) catalog = result
            loadFailed = result == null || choices == null
          } catch (failure: CancellationException) {
            throw failure
          } catch (_: Exception) {
            loadFailed = true
          } finally {
            loading = false
          }
        }
      } finally {
        resolver.unregisterContentObserver(observer)
      }
    }
  }

  fun select(key: String?) {
    if (activeService == null || choices == null || saving || selected == key || (key != null && loadFailed)) return
    val dark = mode == 1
    saving = true
    saveFailed = false
    scope.launch {
      try {
        WallpaperPresetPreferences.save(activeService, dark, key)
        choices = WallpaperPresetPreferences.read(activeService)
        // Allow the current mode's native apply to start before reading it back.
        delay(350)
        refresh.trySend(Unit)
      } catch (failure: CancellationException) {
        throw failure
      } catch (_: Exception) {
        saveFailed = true
      } finally {
        saving = false
      }
    }
  }

  Os4DetailsSheet(show, onDismiss, title = stringResource(R.string.wallpaper_presets_title), scrollContent = false,
    endAction = {
      KeiHomeIconButton(onClick = { refresh.trySend(Unit) }, enabled = !loading && !saving,
        modifier = Modifier.testTag("wallpaper-presets.refresh")) {
        Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.wallpaper_presets_refresh))
      }
    }) {
    Os4ViewTabs(listOf(stringResource(R.string.wallpaper_presets_light), stringResource(R.string.wallpaper_presets_dark)),
      mode, { mode = it })
    val status = when {
      loadFailed -> R.string.wallpaper_presets_unavailable
      saveFailed -> R.string.wallpaper_presets_save_failed
      selected != null && catalog?.failedKey == selected -> R.string.wallpaper_presets_apply_failed
      loading && catalog == null -> R.string.wallpaper_presets_loading
      catalog != null && selected != null && selected !in entries -> R.string.wallpaper_presets_missing
      catalog != null && entries.isEmpty() -> R.string.wallpaper_presets_empty
      else -> null
    }
    status?.let { Text(stringResource(it), style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary) }
    val enabled = choices != null && !saving && !loadFailed
    LazyColumn(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item {
        PresetOption(stringResource(R.string.wallpaper_presets_keep), choices != null && selected == null,
          choices != null && !saving, onClick = { select(null) })
      }
      itemsIndexed(entries, key = { _, key -> key }) { index, key ->
        val current = key == catalog?.current
        PresetOption(if (current) stringResource(R.string.wallpaper_presets_current)
          else stringResource(R.string.wallpaper_presets_number, index + if (catalog?.current == null) 1 else 0),
          key == selected, enabled, onClick = { select(key) }) {
          val revision = catalog?.revision
          var thumbnail by remember(key) { mutableStateOf(thumbnails[key]) }
          LaunchedEffect(show, key, revision) {
            if (!show || revision == null || thumbnail?.revision == revision) return@LaunchedEffect
            val image = Protocol.preview(context, key) ?: return@LaunchedEffect
            thumbnail = PresetThumbnail(revision, image).also { thumbnails.put(key, it) }
          }
          PresetPreview(thumbnail?.image)
        }
      }
    }
  }
}

private data class PresetThumbnail(val revision: Long, val image: Protocol.Preview)

@Composable
private fun PresetOption(
  title: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  preview: (@Composable RowScope.() -> Unit)? = null,
) {
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(title = title, enabled = enabled, onClick = onClick,
      summary = if (selected) stringResource(R.string.wallpaper_presets_selected) else null,
      endActions = preview, modifier = Modifier.semantics {
        this.selected = selected
        role = Role.RadioButton
      })
  }
}

@Composable
private fun PresetPreview(preview: Protocol.Preview?) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    listOf(preview?.lock to R.string.wallpaper_dimming_lock, preview?.home to R.string.wallpaper_dimming_home)
      .forEach { (bitmap, label) ->
        val modifier = Modifier.width(33.dp).aspectRatio(0.51f).clip(RoundedCornerShape(6.dp))
        if (bitmap == null) Box(modifier.background(MiuixTheme.colorScheme.surfaceContainer))
        else Image(remember(bitmap) { bitmap.asImageBitmap() }, stringResource(label), modifier, contentScale = ContentScale.Crop)
      }
  }
}
