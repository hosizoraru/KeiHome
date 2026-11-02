package os.kei.hyper.home.xposed

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import os.kei.hyper.home.status.CurrentIconSnapshot
import os.kei.hyper.home.status.RuntimeStatusInbox
import os.kei.hyper.home.status.SystemIconThemeReport
import os.kei.hyper.home.iconpack.IconBuildInventory
import os.kei.hyper.home.iconpack.nativeFallbackProtectedNames

/** Worker-only copy; neither a launcher hook nor the UI reads protected theme files. */
internal object CurrentIconSnapshotPublisher {
  fun publish(context: Context, report: SystemIconThemeReport, current: () -> Boolean): String? { return try {
    val path = report.archivePath ?: return null
    require(path in setOf("/data/system/theme/icons", "/system/media/theme/default/icons"))
    val key = MessageDigest.getInstance("SHA-256").digest((report.revision + ":browser-v3").toByteArray())
      .joinToString("") { "%02x".format(it) }
    if (context.contentResolver.call(RuntimeStatusInbox.AUTHORITY, "preview_exists", key, null)
        ?.getBoolean("accepted") == true) return key
    val written = hashSetOf<String>()
    val nativeArt = hashSetOf<String>()
    var bytes = 0L
    context.contentResolver.openOutputStream(CurrentIconSnapshot.uri(key), "w")!!.use { stream ->
      ZipOutputStream(stream).use { output ->
        fun write(name: String, data: ByteArray) {
          check(current())
          if (!written.add(name)) return
          require(written.size <= 16_384 && data.size <= 4 * 1024 * 1024)
          bytes += data.size; require(bytes <= 64 * 1024 * 1024)
          output.putNextEntry(ZipEntry(name)); output.write(data); output.closeEntry()
        }
        ZipFile(path).use { zip ->
          val originalNames = nativeFallbackProtectedNames(zip.entries().asSequence().map { it.name })
          zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
            if (entry.name == IconBuildInventory.NAMES_PATH) return@forEach
            require(entry.size in 0..4 * 1024 * 1024)
            if (entry.name.startsWith("fancy_icons/"))
              nativeArt += entry.name.removePrefix("fancy_icons/").substringBefore('/') + ".png"
            if (entry.name.endsWith(".png") && !entry.name.startsWith("fancy_icons/"))
              nativeArt += entry.name.substringAfterLast('/')
            write(entry.name, zip.getInputStream(entry).use { it.readNBytes(4 * 1024 * 1024 + 1) })
          }
          write(IconBuildInventory.NAMES_PATH, IconBuildInventory.encodeNames(originalNames))
        }
        val mod = File("/system/media/theme/miui_mod_icons")
        // Active artwork wins across densities; stock mod resources are only a fallback.
        mod.walkTopDown().filter { it.isFile && it.extension == "png" && it.name !in nativeArt }.forEach { file ->
          write("res/drawable-xxhdpi/${file.name}", file.inputStream().use { it.readNBytes(4 * 1024 * 1024 + 1) })
        }
      }
    }
    check(current())
    context.contentResolver.call(RuntimeStatusInbox.AUTHORITY, "preview_ready", key, null)
    key
  } catch (_: Exception) { null }
  }
}
