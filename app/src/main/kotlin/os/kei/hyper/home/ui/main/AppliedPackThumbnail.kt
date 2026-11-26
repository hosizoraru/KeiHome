package os.kei.hyper.home.ui.main

import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.status.SystemIconThemeReport
import os.kei.hyper.home.ui.apple.appleCurrentThemeSourceReady
import os.kei.hyper.home.ui.components.Os4ShimmerPlaceholder

/** Applied artwork must never inherit pending mask or preservation settings. */
@Composable
internal fun AppliedPackThumbnail(
  source: IconBrowserSource,
  entry: IconPackEntry,
  repository: CurrentIconPreviewRepository,
  theme: SystemIconThemeReport?,
  previewGeneration: Int,
  modifier: Modifier = Modifier,
) {
  val configuration = Configuration(LocalConfiguration.current)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val ready = appleCurrentThemeSourceReady(theme, source)
  val preview by key(source, entry, repository, ready, theme?.previewKey,
    previewGeneration, configuration, lifecycle) {
    produceState<AppIconPreviews?>(null) {
      if (!ready) {
        value = AppIconPreviews()
        return@produceState
      }
      lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        value = null
        value = try {
          repository.load(source, entry)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          AppIconPreviews()
        }
        awaitCancellation()
      }
    }
  }
  val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
  val artwork = if (dark) preview?.dark else preview?.light
  val image = remember(artwork) { artwork?.asImageBitmap() }
  Crossfade(
    targetState = image to (ready && preview == null),
    modifier = modifier.size(54.dp),
    animationSpec = tween(180),
    label = "applied-pack-thumbnail",
  ) { (img, loading) ->
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      if (img != null) Image(img, contentDescription = null, modifier = Modifier.size(50.dp))
      else if (loading) Os4ShimmerPlaceholder(Modifier.size(46.dp), cornerRadius = 12.dp)
    }
  }
}
