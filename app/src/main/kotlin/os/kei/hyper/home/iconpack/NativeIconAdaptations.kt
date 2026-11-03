package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Worker-only fingerprints. Unchanged official artwork does not demand a new static theme. */
internal class NativeIconAdaptations(private val context: Context) {
  private val cache = LruCache<String, String>(2048)
  fun read(targets: Set<String>, check: () -> Unit): Map<String, String> {
    val installed = context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
      .associateBy { it.packageName }
    val renderer = ColorOsFallbackRenderer(context)
    return targets.associateWith { name ->
      check()
      val target = requireNotNull(IconPackComponent.parse(name))
      val app = installed[target.packageName]
      if (app == null) "none" else {
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val key = name + ":" + paths.joinToString { "$it:${File(it).lastModified()}:${File(it).length()}" } +
          context.resources.configuration.locales.toLanguageTags()
        cache.get(key) ?: run {
          val digest = MessageDigest.getInstance("SHA-256")
          var adapted = false
          fun append(bitmap: Bitmap?) {
            if (bitmap == null) { digest.update(0.toByte()); return }
            try {
              adapted = true; digest.update(1.toByte())
              val pixels = IntArray(bitmap.width * bitmap.height)
              bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
              val buffer = ByteBuffer.allocate(pixels.size * 4)
              pixels.forEach { buffer.putInt(it) }; digest.update(buffer.array())
            } finally { bitmap.recycle() }
          }
          if (renderer.hasNativeDayNight(target, check)) {
            append(renderer.render(target, false, 32, check = check)); append(renderer.render(target, true, 32, check = check))
          } else { append(null); append(null) }
          append(renderer.monochrome(target, false, 32, check = check))
          (if (adapted) digest.digest().joinToString("") { "%02x".format(it) } else "none").also { cache.put(key, it) }
        }
      }
    }
  }
}
