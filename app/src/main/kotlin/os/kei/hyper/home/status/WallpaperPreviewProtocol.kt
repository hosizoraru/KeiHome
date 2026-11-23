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
import java.security.MessageDigest
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
  const val REVISION = "revision"
  const val FORCE = "force"
  const val NOT_MODIFIED = "notModified"
  const val READ_FAILED = "readFailed"
  const val SEPARATE_DIMMING = "separateDimming"
  const val MAX_BYTES = 192 * 1024

  data class Preview(
    val image: Bitmap?,
    val revision: String? = null,
    val imageKey: String? = null,
  )

  data class ReadResult(val preview: Preview?, val separateDimming: Boolean)

  suspend fun read(context: Context, which: Int, cached: Preview?, force: Boolean): ReadResult? {
    val reply = withTimeoutOrNull(2_500L) {
      suspendCancellableCoroutine<Bundle?> { continuation ->
        val result = object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            if (continuation.isActive) continuation.resume(if (resultCode == 1) getResultExtras(false) else null)
          }
        }
        try {
          val request = Intent(ACTION).setPackage("android").putExtra(SCREEN, which)
            .putExtra(REVISION, cached?.revision.takeUnless { force }).putExtra(FORCE, force)
          context.sendOrderedBroadcast(request,
            null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
            result, Handler(Looper.getMainLooper()), 0, null, null)
        } catch (_: Exception) {
          if (continuation.isActive) continuation.resume(null)
        }
      }
    } ?: return null
    return withContext(Dispatchers.Default) {
      val separate = reply.getBoolean(SEPARATE_DIMMING)
      val revision = reply.getString(REVISION)
      val preview = when {
        reply.getBoolean(READ_FAILED) -> null
        reply.getBoolean(NOT_MODIFIED) -> cached?.takeIf { revision != null && it.revision == revision }
        else -> {
          val bytes = reply.getByteArray(IMAGE)
          if (bytes == null) Preview(null, revision)
          else decodePreview(bytes, cached, revision)
        }
      }
      ReadResult(preview, separate)
    }
  }

  private fun decodePreview(bytes: ByteArray, cached: Preview?, revision: String?): Preview? {
    if (bytes.size > MAX_BYTES) return null
    val imageKey = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    val image = cached?.takeIf { it.imageKey == imageKey }?.image
      ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      ?: return null
    return Preview(image, revision, imageKey)
  }
}
