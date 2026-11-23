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

internal object WallpaperPresetProtocol {
  const val ACTION = BuildConfig.APPLICATION_ID + ".WALLPAPER_PRESETS"
  const val PERMISSION = WallpaperPreviewProtocol.PERMISSION
  const val SYSTEM_CONFIG = "constant_template_editor_info"
  const val FORCE = "force"
  const val MAX_IMAGE_BYTES = 72 * 1024
  const val KEY = "key"
  const val KEYS = "keys"
  const val CURRENT = "current"
  const val REVISION = "revision"
  const val IMAGES = "images"
  const val APPLY_FAILED = "applyFailed"

  data class Catalog(val keys: List<String>, val current: String?, val revision: Long, val failedKey: String?)
  data class Preview(val lock: Bitmap?, val home: Bitmap?)

  suspend fun catalog(context: Context, force: Boolean): Catalog? = request(context, force = force)?.let { reply ->
    val keys = reply.getStringArrayList(KEYS) ?: return null
    Catalog(keys, reply.getString(CURRENT), reply.getLong(REVISION), reply.getString(APPLY_FAILED))
  }

  suspend fun preview(context: Context, key: String): Preview? {
    val images = request(context, key)?.getBundle(IMAGES) ?: return null
    return withContext(Dispatchers.Default) {
      fun image(name: String): Bitmap? = images.getByteArray(name)?.takeIf { it.size <= MAX_IMAGE_BYTES }
        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
      Preview(image("lock"), image("home"))
    }
  }

  private suspend fun request(context: Context, key: String? = null, force: Boolean = false): Bundle? = withTimeoutOrNull(5_000L) {
    suspendCancellableCoroutine { continuation ->
      val result = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (continuation.isActive) continuation.resume(if (resultCode == 1) getResultExtras(false) else null)
        }
      }
      try {
        val intent = Intent(ACTION).setPackage("android").putExtra(KEY, key).putExtra(FORCE, force)
        context.sendOrderedBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
          result, Handler(Looper.getMainLooper()), 0, null, null)
      } catch (_: Exception) {
        if (continuation.isActive) continuation.resume(null)
      }
    }
  }
}
