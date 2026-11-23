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
import android.system.Os
import android.system.StructStat
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
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
      val force = intent.getBooleanExtra(Protocol.FORCE, false)
      val known = intent.getStringExtra(Protocol.REVISION)
      val result = runCatching { readWallpaperThumbnail(context, requested, known, force) }.getOrNull()
        ?: Bundle().apply { putBoolean(Protocol.READ_FAILED, true) }
      if (!active()) return
      result.putBoolean(Protocol.SEPARATE_DIMMING, separateDimming)
      setResult(1, null, result)
    }
  }
  registrations.own { context.unregisterReceiver(receiver) }
  context.registerReceiver(receiver, IntentFilter(Protocol.ACTION), Protocol.PERMISSION,
    handler, Context.RECEIVER_EXPORTED)
}

@SuppressLint("MissingPermission")
private fun readWallpaperThumbnail(context: Context, requested: Int, known: String?, force: Boolean): Bundle? {
  val manager = WallpaperManager.getInstance(context)
  val requestedId = manager.getWallpaperId(requested)
  val screen = if (requested == WallpaperManager.FLAG_LOCK && requestedId < 0)
    WallpaperManager.FLAG_SYSTEM else requested
  val wallpaperId = if (screen == requested) requestedId else manager.getWallpaperId(screen)
  val info = WallpaperManager::class.java.getMethod("getWallpaperInfo", Int::class.javaPrimitiveType)
    .invoke(manager, screen) as? WallpaperInfo
  // These HyperOS engines render the static crop held by WallpaperManagerService.
  val source = "$screen:$wallpaperId:${info?.component?.flattenToString().orEmpty()}"
  if (info != null && info.serviceName != "com.miui.miwallpaper.wallpaperservice.ImageWallpaper" &&
    info.serviceName != "com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper") {
    return Bundle().apply {
      putString(Protocol.REVISION, source)
      putBoolean(Protocol.NOT_MODIFIED, !force && known == source)
    }
  }
  val file = manager.getWallpaperFile(screen) ?: return if (wallpaperId < 0 || info == null) Bundle() else null
  return file.use {
    val stat = Os.fstat(file.fileDescriptor)
    val fileRevision = stat.previewRevision()
    val revision = "$source:$fileRevision"
    val reply = Bundle().apply { putString(Protocol.REVISION, revision) }
    if (!force && known == revision) {
      reply.putBoolean(Protocol.NOT_MODIFIED, true)
      return@use reply
    }
    val image = encodeWallpaperThumbnail(file.fileDescriptor) ?: return@use null
    if (fileRevision != Os.fstat(file.fileDescriptor).previewRevision() ||
      manager.getWallpaperId(screen) != wallpaperId || manager.getWallpaperId(requested) != requestedId) return@use null
    reply.putByteArray(Protocol.IMAGE, image)
    reply
  }
}

private fun StructStat.previewRevision(): String =
  "$st_dev:$st_ino:$st_size:${st_mtim.tv_sec}.${st_mtim.tv_nsec}:${st_ctim.tv_sec}.${st_ctim.tv_nsec}"

private fun encodeWallpaperThumbnail(file: FileDescriptor): ByteArray? {
  val options = BitmapFactory.Options().apply {
    inJustDecodeBounds = true
    inSampleSize = 1
  }
  BitmapFactory.decodeFileDescriptor(file, null, options)
  while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 640) options.inSampleSize *= 2
  options.inJustDecodeBounds = false
  val bitmap = BitmapFactory.decodeFileDescriptor(file, null, options) ?: return null
  try {
    val output = ByteArrayOutputStream()
    for (quality in intArrayOf(83, 65)) {
      output.reset()
      if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= Protocol.MAX_BYTES) {
        return output.toByteArray()
      }
    }
    return null
  } finally {
    bitmap.recycle()
  }
}
