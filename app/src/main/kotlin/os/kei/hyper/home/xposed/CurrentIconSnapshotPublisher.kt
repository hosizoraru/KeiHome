package os.kei.hyper.home.xposed

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import os.kei.hyper.home.status.CurrentIconSnapshot
import os.kei.hyper.home.status.RuntimeStatusInbox
import os.kei.hyper.home.status.SystemIconThemeReport
import os.kei.hyper.home.iconpack.IconBuildInventory
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.nativeFallbackProtectedNames

/** Worker-only copy; neither a launcher hook nor the UI reads protected theme files. */
internal object CurrentIconSnapshotPublisher {
  fun publish(context: Context, report: SystemIconThemeReport, current: () -> Boolean): String? { return try {
    val path = report.archivePath ?: return null
    require(path in setOf("/data/system/theme/icons", "/system/media/theme/default/icons"))
    val limits = IconPackLimits()
    require(File(path).isFile && File(path).length() in 1..limits.maxInputBytes)
    val checkCurrent = { check(current()) }
    checkCurrent()
    val key = MessageDigest.getInstance("SHA-256").digest((report.revision + ":browser-v4").toByteArray())
      .joinToString("") { "%02x".format(it) }
    if (context.contentResolver.call(RuntimeStatusInbox.AUTHORITY, "preview_exists", key, null)
        ?.getBoolean("accepted") == true) {
      checkCurrent()
      return key
    }
    val written = hashSetOf<String>()
    val nativeArt = hashSetOf<String>()
    val budget = CurrentIconSnapshotBudget(limits, checkCurrent)
    context.contentResolver.openOutputStream(CurrentIconSnapshot.uri(key), "w")!!.use { stream ->
      ZipOutputStream(budget.boundedOutput(stream)).use { output ->
        fun write(name: String, size: Long, open: () -> InputStream) {
          checkCurrent()
          IconPackArchive.validatePath(name)
          if (!written.add(name)) return
          output.putNextEntry(ZipEntry(name))
          open().use { budget.copy(it, output, size) }
          output.closeEntry()
        }
        ZipFile(path).use { zip ->
          require(zip.size() <= limits.maxEntries)
          val originalNames = nativeFallbackProtectedNames(zip.entries().asSequence().map {
            checkCurrent()
            IconPackArchive.validatePath(it.name)
            it.name
          })
          zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
            checkCurrent()
            if (entry.name == IconBuildInventory.NAMES_PATH) return@forEach
            require(entry.size in 0..limits.maxEntryBytes && entry.compressedSize >= 0 &&
              entry.size <= maxOf(1L, entry.compressedSize) * limits.maxCompressionRatio)
            if (entry.name.startsWith("fancy_icons/"))
              nativeArt += entry.name.removePrefix("fancy_icons/").substringBefore('/') + ".png"
            if (entry.name.endsWith(".png") && !entry.name.startsWith("fancy_icons/"))
              nativeArt += entry.name.substringAfterLast('/')
            val crc = CRC32()
            write(entry.name, entry.size) { CheckedInputStream(zip.getInputStream(entry), crc) }
            require(crc.value == entry.crc) { "Snapshot source CRC mismatch" }
          }
          val names = IconBuildInventory.encodeNames(originalNames)
          write(IconBuildInventory.NAMES_PATH, names.size.toLong()) { names.inputStream() }
        }
        val mod = File("/system/media/theme/miui_mod_icons")
        val root = mod.canonicalFile
        // Active artwork wins across densities; stock mod resources are only a fallback.
        mod.walkTopDown().onEnter {
          checkCurrent()
          it.canonicalFile == root || it.canonicalPath.startsWith(root.path + File.separator)
        }.filter {
          checkCurrent()
          it.isFile && it.extension == "png" && it.name !in nativeArt
        }.forEach { file ->
          require(file.canonicalPath.startsWith(root.path + File.separator))
          write("res/drawable-xxhdpi/${file.name}", file.length()) { file.inputStream() }
        }
      }
    }
    checkCurrent()
    val publishedReady = context.contentResolver.call(RuntimeStatusInbox.AUTHORITY, "preview_ready", key, null)
      ?.getBoolean("accepted") == true
    if (publishedReady) key else null
  } catch (_: Exception) { null }
  }
}
