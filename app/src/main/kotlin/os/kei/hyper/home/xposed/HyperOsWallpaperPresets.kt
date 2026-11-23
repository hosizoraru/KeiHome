package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.system.Os
import dalvik.system.PathClassLoader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject
import os.kei.hyper.home.data.WallpaperPresetIdentity
import os.kei.hyper.home.status.WallpaperPresetProtocol

/** The provider used by HyperOS System Personalization. Only built-in OEM providers are accepted. */
internal class HyperOsWallpaperPresets(private val context: Context) {
  data class Entry(
    val key: String,
    val id: Long,
    val config: String,
    val hideFlag: Int,
    val lockPreview: String?,
    val homePreview: String?,
  )

  data class Catalog(val current: Entry?, val history: List<Entry>)

  private val resolver = context.contentResolver
  private val digests = android.util.LruCache<String, String>(64)
  private var dto: Dto? = null

  fun read(): Catalog {
    val type = adapter()
    val result = checkNotNull(resolver.call(uri, "callGetHistoryTemplates", null, null)) {
      "Wallpaper preset provider did not respond"
    }
    result.classLoader = type.model.classLoader
    val history = checkNotNull(result.getParcelableArrayList("historyTemplates", android.os.Parcelable::class.java)) {
      "Wallpaper preset catalog missing"
    }
      .mapNotNull { item ->
        if (item.javaClass != type.model) return@mapNotNull null
        val path = type.config.invoke(item) as String
        if (!historyConfig.matches(path)) return@mapNotNull null
        val id = type.id.invoke(item) as Long
        if (id <= 0) return@mapNotNull null
        val config = readConfig(path) ?: return@mapNotNull null
        val key = key(config, path.substringBeforeLast('/')) ?: return@mapNotNull null
        Entry(key, id, path, type.hideFlag.invoke(item) as Int,
          type.lockPreview.invoke(item) as? String, type.homePreview.invoke(item) as? String)
      }
    val current = Settings.Secure.getString(resolver, WallpaperPresetProtocol.SYSTEM_CONFIG)
      ?.takeIf(String::isNotBlank)?.let(::parseConfig)?.let { key(it, "$directory/current") }
      ?.let { Entry(it, 0, "", 0, "$directory/current/small_screenshot", "$directory/current/small_wallpaper_screenshot") }
    return Catalog(current, history)
  }

  fun apply(entry: Entry): Boolean {
    check(entry.id > 0 && historyConfig.matches(entry.config))
    return resolver.call(uri, "callApplyHistoryTemplate", null, Bundle().apply {
      putString("configUri", entry.config)
      putLong("entityId", entry.id)
      putInt("hideFlag", entry.hideFlag)
      putBoolean("shouldApplyToAll", true)
    })?.getBoolean("resultApplyHistoryTemplate") == true
  }

  fun preview(entry: Entry): Bundle = Bundle().apply {
    thumbnail(entry.lockPreview)?.let { putByteArray("lock", it) }
    thumbnail(entry.homePreview)?.let { putByteArray("home", it) }
  }

  private fun key(config: JSONObject, folder: String): String? {
    fun wallpaper(section: String, imageName: String, videoName: String): String? {
      val info = config.optJSONObject(section)?.optJSONObject("wallpaperInfo") ?: return null
      val source = info.optString("source")
      if (!templateAsset.matches(source)) return null
      val digest = digest("$folder/$imageName") ?: return null
      if (!info.optBoolean("isDepthVideo")) return digest
      // After application the desktop's depthVideo field can still name the deleted history slot.
      val video = digest("$folder/$videoName") ?: return null
      return "$digest:$video"
    }
    val lock = wallpaper("lockscreenInfo", "wallpaper", "videoWallpaper") ?: return null
    val home = wallpaper("homeInfo", "wallpaper_home", "videoWallpaperHome") ?: return null
    return WallpaperPresetIdentity.key(config, lock, home)
  }

  private fun readConfig(path: String): JSONObject? =
    withFile(path) { stream ->
      val bytes = stream.readNBytes(MAX_CONFIG_BYTES + 1)
      if (bytes.size <= MAX_CONFIG_BYTES) parseConfig(bytes.toString(Charsets.UTF_8)) else null
    }

  private fun parseConfig(text: String): JSONObject? = try {
    JSONObject(text)
  } catch (_: JSONException) {
    null
  }

  private fun digest(path: String): String? = withFile(path) { stream ->
    val stat = Os.fstat(stream.fd)
    val revision = "$path:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}:${stat.st_ctime}"
    digests[revision] ?: run {
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
      digest.digest().toHexString().also { digests.put(revision, it) }
    }
  }

  private fun thumbnail(path: String?): ByteArray? {
    if (path == null || !previewFile.matches(path)) return null
    val bitmap = withFile(path) { stream ->
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFileDescriptor(stream.fd, null, bounds)
      val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
      if (longestEdge <= 0) return@withFile null
      val options = BitmapFactory.Options().apply {
        inSampleSize = Integer.highestOneBit((longestEdge / 400).coerceAtLeast(1))
      }
      BitmapFactory.decodeFileDescriptor(stream.fd, null, options)
    } ?: return null
    try {
      val scale = maxOf(bitmap.width, bitmap.height) / 400f
      val rendered = if (scale > 1f) Bitmap.createScaledBitmap(bitmap,
        (bitmap.width / scale).toInt().coerceAtLeast(1), (bitmap.height / scale).toInt().coerceAtLeast(1), true) else bitmap
      try {
        val output = ByteArrayOutputStream()
        for (quality in intArrayOf(82, 60)) {
          output.reset()
          if (rendered.compress(Bitmap.CompressFormat.JPEG, quality, output) &&
            output.size() <= WallpaperPresetProtocol.MAX_IMAGE_BYTES) return output.toByteArray()
        }
        return null
      } finally {
        if (rendered !== bitmap) rendered.recycle()
      }
    } finally {
      bitmap.recycle()
    }
  }

  private fun <T> withFile(path: String, read: (ParcelFileDescriptor.AutoCloseInputStream) -> T): T? {
    val bundle = resolver.call(uri, "callGetFileDescriptor", path, null) ?: return null
    val file = bundle.getParcelable("parcelFileDescriptor", ParcelFileDescriptor::class.java) ?: return null
    return try {
      ParcelFileDescriptor.AutoCloseInputStream(file).use(read)
    } catch (_: IOException) {
      null
    }
  }

  private fun adapter(): Dto {
    val pm = context.packageManager
    val provider = checkNotNull(pm.resolveContentProvider(AUTHORITY, 0)) { "Wallpaper preset provider unavailable" }
    val app = provider.applicationInfo
    check(provider.packageName == AOD && provider.name == PROVIDER &&
      app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
      pm.checkSignatures("android", AOD) == PackageManager.SIGNATURE_MATCH) { "Unrecognized wallpaper preset provider" }
    val path = (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).joinToString(java.io.File.pathSeparator)
    return dto?.takeIf { it.path == path } ?: Dto(path).also { dto = it }
  }

  private class Dto(val path: String) {
    val model = Class.forName("com.miui.keyguard.editor.data.bean.TemplateHistoryConfig", false,
      PathClassLoader(path, android.os.Parcelable::class.java.classLoader))
    val config = model.getMethod("getConfigUri")
    val id = model.getMethod("getId")
    val hideFlag = model.getMethod("getHideFlag")
    val lockPreview = model.getMethod("getSmallScreenshotUri")
    val homePreview = model.getMethod("getSmallWallpaperScreenshotUri")
  }

  private companion object {
    const val AOD = "com.miui.aod"
    const val AUTHORITY = "miui.keyguard.editor.templatefileprovider"
    const val PROVIDER = "com.miui.keyguard.editor.data.template.TemplateFileProvider"
    const val directory = "/data/user_de/0/$AOD/files/templates"
    const val MAX_CONFIG_BYTES = 512 * 1024
    val uri: Uri = Uri.parse("content://$AUTHORITY")
    val historyConfig = Regex("${Regex.escape(directory)}/history/[0-9]+/config")
    val templateAsset = Regex("${Regex.escape(directory)}/(?:current|history/[0-9]+)/[a-zA-Z0-9_]+")
    val previewFile = Regex("${Regex.escape(directory)}/(?:current|history/[0-9]+)/small_(?:wallpaper_)?screenshot")
  }
}
