package os.kei.hyper.home.ui.main

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.components.Os4Motion
import os.kei.hyper.home.ui.components.Os4TrailingChevron
import os.kei.hyper.home.ui.components.os4Clickable
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import os.kei.hyper.home.data.CacheBucket
import os.kei.hyper.home.data.CacheReminderBudget
import os.kei.hyper.home.data.DiskCacheUsage
import os.kei.hyper.home.data.measureDiskCache
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.FolderOutline
import top.yukonga.miuix.kmp.icon.glass.Iphone
import top.yukonga.miuix.kmp.icon.glass.Layers
import top.yukonga.miuix.kmp.icon.glass.Theme
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class CacheSample(
  val disk: DiskCacheUsage,
  val memory: Long,
  val apple: Long,
  val perfect: Long,
  val capturedAt: Long,
) {
  val total: Long get() = disk.total + apple + perfect
  val categories: List<Long> get() =
    CacheBucket.entries.filter { it != CacheBucket.LOGS && it != CacheBucket.IMPORTED_PACKS }
      .map { disk.bytes[it] ?: 0L } +
      listOf(apple, perfect, disk.bytes[CacheBucket.LOGS] ?: 0L, disk.bytes[CacheBucket.IMPORTED_PACKS] ?: 0L)
}

@Composable
internal fun CacheOverview(model: HomeViewModel, refreshKey: Int = 0, onOpenPack: (String) -> Unit = {},
  onOpenLogs: () -> Unit = {}, onClearMemory: (() -> Unit)? = null,
  onReadingChange: (Boolean) -> Unit = {}) {
  val context = LocalContext.current.applicationContext
  val owner = LocalLifecycleOwner.current
  val refreshing by model.refreshing.collectAsStateWithLifecycle()
  val importedRevision by model.importedPackRevision.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  var nonce by remember { mutableIntStateOf(0) }
  var sample by remember { mutableStateOf<CacheSample?>(null) }
  var reading by remember { mutableStateOf(true) }
  var failed by remember { mutableStateOf(false) }
  LaunchedEffect(reading) { onReadingChange(reading) }
  LaunchedEffect(owner, nonce, refreshKey, 6 in refreshing, importedRevision) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      reading = true
      failed = false
      try {
        sample = coroutineScope {
          val disk = async(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            measureDiskCache(context.cacheDir, java.io.File(context.filesDir, "app-logs"),
              java.io.File(context.noBackupFilesDir, "icon-packs")) { coroutine.ensureActive() }
          }
          val apple = async { model.appleDownloadCacheBytes() }
          val perfect = async { model.perfectStorageUsage() }
          val perfectUsage = perfect.await()
          val cacheUsage = disk.await()
          CacheSample(cacheUsage.copy(complete = cacheUsage.complete && perfectUsage.complete),
            model.previewCacheBytes(), apple.await(), perfectUsage.total, System.currentTimeMillis())
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: IOException) {
        failed = true
      } finally {
        reading = false
      }
      awaitCancellation()
    }
  }
  CacheStorageContent(sample, reading, failed, preferences.cacheReminderBytes,
    onRefresh = { nonce++ }, onOpenPack = onOpenPack, onOpenLogs = onOpenLogs,
    onClearMemory = onClearMemory)
}

@Composable
internal fun CacheStorageContent(
  sample: CacheSample?,
  reading: Boolean,
  failed: Boolean,
  customBudget: Long,
  onRefresh: () -> Unit,
  onOpenPack: (String) -> Unit,
  onOpenLogs: () -> Unit = {},
  onClearMemory: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val scheme = MiuixTheme.colorScheme
  fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
  val budget = CacheReminderBudget.resolve(customBudget)
  val exceeded = sample != null && sample.total >= budget
  val partial = failed || sample?.disk?.complete == false
  Column(Modifier.fillMaxWidth().testTag("cache.storage"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(Modifier.fillMaxWidth().os4ContentSize().testTag("cache.storage.summary"), insideMargin = PaddingValues(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f).os4Clickable(onClick = onRefresh), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.storage_used), style = MiuixTheme.textStyles.footnote1,
              color = scheme.onSurfaceVariantSummary)
            val totalText = sample?.let { size(it.total) } ?: "\u2014"
            AnimatedContent(
              targetState = totalText,
              transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
              label = "cache-total-text",
            ) { value ->
              Text(value,
                style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface, modifier = Modifier.testTag("cache.storage.total"))
            }
          }
          MetadataPill(stringResource(when {
            exceeded -> R.string.storage_warning
            partial -> R.string.storage_partial
            sample == null -> R.string.storage_measuring
            else -> R.string.storage_ok
          }), if (exceeded || partial) scheme.error else scheme.primary,
            Modifier.testTag("cache.storage.status"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(stringResource(R.string.storage_budget_pill, size(budget)),
            style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
            modifier = Modifier.testTag("cache.storage.limit"))
          if (sample != null) Text(
            stringResource(if (failed) R.string.storage_refresh_failed else R.string.storage_measured,
              DateFormat.getTimeFormat(context).format(Date(sample.capturedAt))),
            style = MiuixTheme.textStyles.footnote1,
            color = if (failed) scheme.error else scheme.onSurfaceVariantSummary)
        }
        Os4AnimatedVisibility(visible = reading, label = "cache-reading-progress") {
          LinearProgressIndicator(Modifier.fillMaxWidth())
        }
      }
    }
    CacheStorageChart(sample?.categories ?: List(8) { 0L }, budget, sample != null, onOpenPack, onOpenLogs)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {
      SmallTitle(stringResource(R.string.storage_manage_title),
        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        modifier = Modifier.weight(1f).semantics { heading() })
      Os4InfoPopup(
        title = stringResource(R.string.storage_manage_title),
        summary = stringResource(R.string.storage_generated_cleanup_help),
        contentDescription = stringResource(R.string.storage_category_info, stringResource(R.string.storage_manage_title)),
      )
    }
    Card(Modifier.fillMaxWidth().testTag("cache.storage.manage"), insideMargin = PaddingValues(0.dp)) {
      StorageManageRow(
        title = stringResource(R.string.storage_manage_imported),
        summary = stringResource(R.string.storage_manage_imported_summary),
        icon = MiuixIcons.Glass.FolderOutline,
        accent = cacheStorageColors[7],
        sizeText = sample?.let { size(it.disk.bytes[CacheBucket.IMPORTED_PACKS] ?: 0L) } ?: "\u2014",
        tag = "cache.storage.manage.imported",
        onClick = { onOpenPack("imported") },
      )
      HorizontalDivider(Modifier.padding(start = 66.dp, end = 16.dp))
      StorageManageRow(
        title = stringResource(R.string.storage_manage_perfect),
        summary = stringResource(R.string.storage_manage_perfect_summary),
        icon = MiuixIcons.Glass.Theme,
        accent = cacheStorageColors[5],
        sizeText = sample?.let { size(it.perfect) } ?: "\u2014",
        tag = "cache.storage.manage.perfect",
        onClick = { onOpenPack("perfect") },
      )
      HorizontalDivider(Modifier.padding(start = 66.dp, end = 16.dp))
      StorageManageRow(
        title = stringResource(R.string.storage_manage_apple),
        summary = stringResource(R.string.storage_manage_apple_summary),
        icon = MiuixIcons.Glass.Iphone,
        accent = cacheStorageColors[4],
        sizeText = sample?.let { size(it.apple) } ?: "\u2014",
        tag = "cache.storage.manage.apple",
        onClick = { onOpenPack("apple") },
      )
    }
    Card(Modifier.fillMaxWidth().os4ContentSize().testTag("cache.storage.preferences"),
      insideMargin = PaddingValues(16.dp)) {
      val hasMemory = (sample?.memory ?: 0L) > 0L
      val badgeAccent by animateColorAsState(
        if (hasMemory) scheme.primary else scheme.onSurfaceSecondary,
        Os4Motion.colorSpring(),
        label = "cache-memory-accent",
      )
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Box(Modifier.size(36.dp).squircleBackground(badgeAccent.copy(alpha = 0.14f), 11.dp),
            contentAlignment = Alignment.Center) {
            Icon(MiuixIcons.Glass.Layers, contentDescription = null,
              modifier = Modifier.size(20.dp), tint = badgeAccent)
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.storage_memory), style = MiuixTheme.textStyles.body1,
              fontWeight = FontWeight.Medium)
            val memoryText = sample?.let { size(it.memory) } ?: "\u2014"
            AnimatedContent(
              targetState = memoryText,
              transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
              label = "cache-memory-text",
            ) { value ->
              Text(value, style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = if (hasMemory) scheme.primary else scheme.onSurfaceVariantSummary)
            }
          }
          if (onClearMemory != null) {
            val clearDescription = stringResource(R.string.polish_clear_previews)
            TextButton(
              text = stringResource(R.string.action_label_clear),
              onClick = onClearMemory,
              minHeight = 36.dp,
              cornerRadius = 18.dp,
              insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
              colors = ButtonDefaults.textButtonColors(
                color = scheme.primary.copy(alpha = if (hasMemory) 0.14f else 0.08f),
                textColor = if (hasMemory) scheme.primary else scheme.onSurfaceSecondary,
              ),
              textStyle = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
              modifier = Modifier.testTag("settings.cache.clear").semantics {
                contentDescription = clearDescription
              },
            )
          }
        }
        Text(stringResource(R.string.storage_clear_help), style = MiuixTheme.textStyles.footnote2,
          color = scheme.onSurfaceVariantSummary)
      }
    }
  }
}

@Composable
private fun StorageManageRow(
  title: String,
  summary: String,
  icon: ImageVector,
  accent: Color,
  sizeText: String,
  tag: String,
  onClick: () -> Unit,
) {
  val pressSource = rememberOs4PressSource()
  BasicComponent(
    title = title,
    summary = summary,
    onClick = onClick,
    interactionSource = pressSource,
    startAction = {
      Box(Modifier.size(36.dp).squircleBackground(accent.copy(alpha = 0.15f), 11.dp),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = accent)
      }
    },
    endActions = {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
          targetState = sizeText,
          transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
          label = "storage-manage-size",
        ) { value ->
          Text(value, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Os4TrailingChevron()
      }
    },
    modifier = Modifier.testTag(tag).glassPress(pressSource),
  )
}
