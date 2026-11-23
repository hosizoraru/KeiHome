package os.kei.hyper.home.xposed

import android.annotation.SuppressLint
import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import java.io.ByteArrayOutputStream
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.status.WallpaperPreviewProtocol as Protocol

/** Return a bounded thumbnail only to the requesting KeiHome app. */
internal fun registerWallpaperPreview(
  context: Context,
  handler: Handler,
  registrations: MonitorRegistrations,
  separateDimming: Boolean,
  active: () -> Boolean,
) {
  val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (!active() || !isOrderedBroadcast || sentFromPackage != BuildConfig.APPLICATION_ID) return
      val uid = runCatching { context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0) }.getOrNull()
      if (uid != sentFromUid) return
      val requested = intent.getIntExtra(Protocol.SCREEN, 0)
      if (requested != WallpaperManager.FLAG_SYSTEM && requested != WallpaperManager.FLAG_LOCK) return
      val image = runCatching { readWallpaperThumbnail(context, requested) }.getOrNull()
      if (!active()) return
      setResult(1, null, Bundle().apply {
        putBoolean(Protocol.SEPARATE_DIMMING, separateDimming)
        if (image != null) putByteArray(Protocol.IMAGE, image)
      })
    }
  }
  registrations.own { context.unregisterReceiver(receiver) }
  context.registerReceiver(receiver, IntentFilter(Protocol.ACTION), Protocol.PERMISSION,
    handler, Context.RECEIVER_EXPORTED)
}

@SuppressLint("MissingPermission")
private fun readWallpaperThumbnail(context: Context, requested: Int): ByteArray? {
  val manager = WallpaperManager.getInstance(context)
  val screen = if (requested == WallpaperManager.FLAG_LOCK && manager.getWallpaperId(requested) < 0)
    WallpaperManager.FLAG_SYSTEM else requested
  val info = WallpaperManager::class.java.getMethod("getWallpaperInfo", Int::class.javaPrimitiveType)
    .invoke(manager, screen) as? WallpaperInfo
  // These HyperOS engines render the static crop held by WallpaperManagerService.
  if (info != null && info.serviceName !in setOf(
      "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
      "com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper",
    )) return null
  return manager.getWallpaperFile(screen)?.use { file ->
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options)
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize.coerceAtLeast(1) > 640) {
      options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
    }
    options.inJustDecodeBounds = false
    val bitmap = BitmapFactory.decodeFileDescriptor(file.fileDescriptor, null, options) ?: return@use null
    try {
      val output = ByteArrayOutputStream()
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 83, output) || output.size() > Protocol.MAX_BYTES)
        null else output.toByteArray()
    } finally {
      bitmap.recycle()
    }
  }
}
