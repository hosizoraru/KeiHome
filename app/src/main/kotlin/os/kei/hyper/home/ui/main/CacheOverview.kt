package os.kei.hyper.home.ui.main

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import os.kei.hyper.home.data.CacheBucket
import os.kei.hyper.home.data.CacheReminderBudget
import os.kei.hyper.home.data.DiskCacheUsage
import os.kei.hyper.home.data.measureDiskCache
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class CacheSample(
  val disk: DiskCacheUsage,
  val memory: Long,
  val apple: Long,
  val perfect: Long,
  val capturedAt: Long,
) {
  val total: Long get() = disk.total + apple + perfect
  val categories: List<Long> get() = CacheBucket.entries.map { disk.bytes[it] ?: 0L } + apple + perfect
}

@Composable
internal fun CacheOverview(model: HomeViewModel, refreshKey: Int = 0, onOpenPack: (String) -> Unit = {}) {
  val context = LocalContext.current.applicationContext
  val owner = LocalLifecycleOwner.current
  val refreshing by model.refreshing.collectAsStateWithLifecycle()
  val preferences by model.preferences.state.collectAsStateWithLifecycle()
  var nonce by remember { mutableIntStateOf(0) }
  var sample by remember { mutableStateOf<CacheSample?>(null) }
  var reading by remember { mutableStateOf(true) }
  var failed by remember { mutableStateOf(false) }
  LaunchedEffect(owner, nonce, refreshKey, 6 in refreshing) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      reading = true
      failed = false
      try {
        sample = coroutineScope {
          val disk = async(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            measureDiskCache(context.cacheDir) { coroutine.ensureActive() }
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
    onRefresh = { nonce++ }, onOpenPack = onOpenPack,
    onBudgetChange = model.preferences::setCacheReminderBytes)
}

@Composable
internal fun CacheStorageContent(
  sample: CacheSample?,
  reading: Boolean,
  failed: Boolean,
  customBudget: Long,
  onRefresh: () -> Unit,
  onOpenPack: (String) -> Unit,
  onBudgetChange: (Long) -> Unit,
) {
  val context = LocalContext.current
  val scheme = MiuixTheme.colorScheme
  fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
  val budget = CacheReminderBudget.resolve(customBudget, sample?.perfect ?: 0L)
  val exceeded = sample != null && sample.total >= budget
  val partial = failed || sample?.disk?.complete == false
  Column(Modifier.fillMaxWidth().testTag("cache.storage"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Card(Modifier.fillMaxWidth().testTag("cache.storage.summary")) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(stringResource(R.string.storage_used), style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.clickable(onClick = onRefresh))
          MetadataPill(stringResource(when {
            exceeded -> R.string.storage_warning
            partial -> R.string.storage_partial
            sample == null -> R.string.storage_measuring
            else -> R.string.storage_ok
          }), if (exceeded || partial) scheme.error else scheme.primary,
            Modifier.testTag("cache.storage.status"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
          MetadataPill(stringResource(R.string.storage_used_pill,
            sample?.let { size(it.total) } ?: "\u2014"), scheme.primary,
            Modifier.testTag("cache.storage.total"))
          MetadataPill(stringResource(R.string.storage_budget_pill, size(budget)),
            scheme.onSurfaceVariantSummary, Modifier.testTag("cache.storage.limit"))
          if (sample != null) MetadataPill(
            stringResource(if (failed) R.string.storage_refresh_failed else R.string.storage_measured,
              DateFormat.getTimeFormat(context).format(Date(sample.capturedAt))),
            if (failed) scheme.error else scheme.onSurfaceVariantSummary)
        }
        if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
      }
    }
    CacheStorageChart(sample?.categories ?: List(6) { 0L }, budget, sample != null, onOpenPack)
    Card(Modifier.fillMaxWidth().testTag("cache.storage.preferences")) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CacheBudgetEditor(customBudget, budget, onBudgetChange)
        Text(stringResource(R.string.storage_budget_help), style = MiuixTheme.textStyles.footnote1,
          color = scheme.onSurfaceVariantSummary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(stringResource(R.string.storage_memory), style = MiuixTheme.textStyles.body2)
          Text(sample?.let { size(it.memory) } ?: "\u2014",
            style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
        }
        Text(stringResource(R.string.storage_clear_help), style = MiuixTheme.textStyles.footnote2,
          color = scheme.onSurfaceVariantSummary)
      }
    }
  }
}
