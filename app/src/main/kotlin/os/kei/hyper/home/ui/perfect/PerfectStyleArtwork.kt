package os.kei.hyper.home.ui.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.ui.components.Os4ShimmerPlaceholder

private val artworkCache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
  override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

/** Bounded logo decoding; reserves layout bounds with a shimmer placeholder to prevent text shift. */
@Composable
internal fun PerfectStyleArtwork(file: File, identity: String, size: Dp = 40.dp) {
  val loaded by produceState<Pair<Boolean, Bitmap?>>(false to null, file, identity) {
    value = true to withContext(Dispatchers.IO) {
      val key = "$identity:${file.path}:${file.length()}:${file.lastModified()}"
      artworkCache.get(key) ?: runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
          inSampleSize = 1
          while (bounds.outWidth / inSampleSize > 192 || bounds.outHeight / inSampleSize > 192) inSampleSize *= 2
        }
        BitmapFactory.decodeFile(file.path, options)?.also { artworkCache.put(key, it) }
      }.getOrNull()
    }
  }
  val image = remember(loaded.second) { loaded.second?.asImageBitmap() }
  Crossfade(
    targetState = image to !loaded.first,
    modifier = Modifier.size(size),
    animationSpec = tween(180),
    label = "perfect-style-artwork",
  ) { (img, loading) ->
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      if (img != null) Image(img, contentDescription = null, modifier = Modifier.fillMaxSize())
      else if (loading) Os4ShimmerPlaceholder(Modifier.fillMaxSize(), cornerRadius = 10.dp)
    }
  }
}
