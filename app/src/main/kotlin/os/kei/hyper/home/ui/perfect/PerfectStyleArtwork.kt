package os.kei.hyper.home.ui.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val artworkCache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
  override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

/** Bounded logo decoding; no recursive resource discovery or file access on the UI thread. */
@Composable
internal fun PerfectStyleArtwork(file: File, identity: String, size: Dp = 40.dp) {
  val bitmap = produceState<Bitmap?>(null, file, identity) {
    value = withContext(Dispatchers.IO) {
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
  }.value
  if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
}
