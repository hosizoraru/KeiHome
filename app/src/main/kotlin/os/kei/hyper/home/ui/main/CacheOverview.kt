package os.kei.hyper.home.ui.main

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.data.CacheBucket
import os.kei.hyper.home.data.DiskCacheUsage
import os.kei.hyper.home.data.measureDiskCache
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Date

private data class CacheSample(val disk: DiskCacheUsage, val memory: Long, val capturedAt: Long,
  val appleBytes: Long)

@Composable
internal fun CacheOverview(model: HomeViewModel, refreshKey: Int) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var refresh by remember { mutableIntStateOf(0) }
  var sample by remember { mutableStateOf<CacheSample?>(null) }
  var reading by remember { mutableStateOf(true) }
  LaunchedEffect(model, lifecycle, refreshKey, refresh) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      reading = true
      sample = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        CacheSample(measureDiskCache(context.cacheDir) { job.ensureActive() }, model.previewCacheBytes(),
          System.currentTimeMillis(), model.appleDownloadCacheBytes())
      }
      reading = false
    }
  }
  val current = sample
  fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
  SmallTitle(stringResource(R.string.cache_usage_title))
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(title = stringResource(R.string.cache_disk_total),
      summary = when {
        reading -> stringResource(R.string.cache_reading)
        current == null || !current.disk.complete -> stringResource(R.string.cache_incomplete)
        else -> stringResource(R.string.cache_measured_at, DateFormat.getTimeFormat(context).format(Date(current.capturedAt)))
      }, onClick = { refresh++ },
      endActions = { Text(current?.let { size(it.disk.total + it.appleBytes) } ?: "—", color = MiuixTheme.colorScheme.primary) })
    if (current != null) {
      listOf(
        CacheBucket.CURRENT_ICONS to R.string.cache_current_icons,
        CacheBucket.BUILTIN_PACKS to R.string.cache_builtin_packs,
        CacheBucket.HANDOFF to R.string.cache_handoff,
        CacheBucket.OTHER to R.string.cache_other,
      ).forEach { (bucket, label) ->
        BasicComponent(title = stringResource(label),
          endActions = { Text(size(current.disk.bytes.getValue(bucket)), color = MiuixTheme.colorScheme.onSurfaceSecondary) })
      }
      BasicComponent(title = stringResource(R.string.apple_download_cache),
        summary = stringResource(R.string.apple_cache_manage_hint),
        endActions = { Text(size(current.appleBytes), color = MiuixTheme.colorScheme.onSurfaceSecondary) })
      BasicComponent(title = stringResource(R.string.cache_memory), summary = stringResource(R.string.cache_memory_summary),
        endActions = { Text(size(current.memory), color = MiuixTheme.colorScheme.onSurfaceSecondary) })
    }
    Text(stringResource(R.string.cache_explanation), modifier = Modifier.fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
      style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}
