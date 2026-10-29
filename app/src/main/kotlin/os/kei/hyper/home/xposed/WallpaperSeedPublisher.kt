package os.kei.hyper.home.xposed

import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.Settings
import os.kei.hyper.home.data.MonetReadState
import os.kei.hyper.home.data.MonetSchemes
import os.kei.hyper.home.data.WallpaperColorSnapshot
import os.kei.hyper.home.data.WallpaperSeedStorage

/** Runs on the existing Monet worker in system_server, where the wallpaper file is accessible. */
internal class WallpaperSeedPublisher {
  private var cacheKey: List<Any?>? = null
  private var cached = WallpaperColorSnapshot(MonetReadState.NOT_PROVIDED, emptyList())

  @SuppressLint("MissingPermission")
  fun readAndPublish(context: Context, userId: Int): WallpaperColorSnapshot {
    val manager = WallpaperManager.getInstance(context)
    val homeId = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    val lockId = manager.getWallpaperId(WallpaperManager.FLAG_LOCK)
    val homeReported = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM).seeds()
    val lockReported = manager.getWallpaperColors(WallpaperManager.FLAG_LOCK).seeds()
    val key = listOf(userId, homeId, lockId, homeReported, lockReported)
    if (key != cacheKey) {
      // Public primary/secondary/tertiary colors can omit useful wallpaper seeds.
      // Score the static image when accessible, and use reported colors for other wallpapers.
      val homeImage = extract(manager, WallpaperManager.FLAG_SYSTEM)
      val home = homeImage.ifEmpty { homeReported }
      val lock = if (lockId < 0) home else
        extract(manager, WallpaperManager.FLAG_LOCK).ifEmpty { lockReported }
      cached = WallpaperColorSnapshot(
        if (home.isEmpty() && lock.isEmpty()) MonetReadState.NOT_PROVIDED else MonetReadState.AVAILABLE,
        home, lock, homeImage.isNotEmpty(),
      )
      cacheKey = key.takeIf { home.isNotEmpty() || lock.isNotEmpty() }
    }
    val serialized = WallpaperSeedStorage.encode(cached)
    if (Settings.Secure.getString(context.contentResolver, WallpaperSeedStorage.KEY) != serialized) {
      Settings.Secure.putString(context.contentResolver, WallpaperSeedStorage.KEY, serialized)
    }
    return cached
  }

  @SuppressLint("MissingPermission")
  private fun extract(manager: WallpaperManager, which: Int): List<Int> = try {
    // Unlike getDrawable(), this cannot silently substitute the default wallpaper.
    manager.getWallpaperFile(which)?.use { file ->
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options)
      options.inJustDecodeBounds = false
      options.inSampleSize = 1
      while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 192) {
        options.inSampleSize *= 2
      }
      BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options)?.let { bitmap ->
        try {
          val pixels = IntArray(bitmap.width * bitmap.height)
          bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
          MonetSchemes.wallpaperSeeds(pixels)
        } finally { bitmap.recycle() }
      }
    }.orEmpty()
  } catch (_: Exception) { emptyList() }

  private fun WallpaperColors?.seeds(): List<Int> = this?.let {
    listOfNotNull(primaryColor.toArgb(), secondaryColor?.toArgb(), tertiaryColor?.toArgb()).distinct()
  }.orEmpty()
}
