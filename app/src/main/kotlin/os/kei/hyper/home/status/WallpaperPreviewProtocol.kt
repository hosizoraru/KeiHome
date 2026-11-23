package os.kei.hyper.home.status

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import os.kei.hyper.home.BuildConfig

internal object WallpaperPreviewProtocol {
  const val ACTION = BuildConfig.APPLICATION_ID + ".WALLPAPER_DIM_PREVIEW"
  const val PERMISSION = BuildConfig.APPLICATION_ID + ".permission.WALLPAPER_DIM_PREVIEW"
  const val SCREEN = "screen"
  const val IMAGE = "image"
  const val SEPARATE_DIMMING = "separateDimming"
  const val MAX_BYTES = 192 * 1024

  data class Preview(val image: Bitmap?, val separateDimming: Boolean)

  suspend fun read(context: Context, which: Int): Preview? {
    val reply = withTimeoutOrNull(2_500L) {
      suspendCancellableCoroutine<Bundle?> { continuation ->
        val result = object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            if (continuation.isActive) continuation.resume(if (resultCode == 1) getResultExtras(false) else null)
          }
        }
        try {
          context.sendOrderedBroadcast(Intent(ACTION).setPackage("android").putExtra(SCREEN, which),
            null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
            result, Handler(Looper.getMainLooper()), 0, null, null)
        } catch (_: Exception) {
          if (continuation.isActive) continuation.resume(null)
        }
      }
    } ?: return null
    return withContext(Dispatchers.Default) {
      val image = reply.getByteArray(IMAGE)?.takeIf { it.size <= MAX_BYTES }?.let {
        BitmapFactory.decodeByteArray(it, 0, it.size)
      }
      Preview(image, reply.getBoolean(SEPARATE_DIMMING))
    }
  }
}
