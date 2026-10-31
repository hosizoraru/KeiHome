package os.kei.hyper.home.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.NativeDayNightIcon

/** A user-requested icons-only MTZ, offered to the existing system module through a private URI. */
internal data class IconPackHandoff(
  val token: String, val file: File, val sourceId: String, val title: String,
  val convertedOrigin: String? = null,
)

internal object IconPackHandoffs {
  private val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  fun file(context: Context, token: String): File {
    require(tokenPattern.matches(token)) { "Invalid icon handoff" }
    return File(File(context.cacheDir, "native-icon-imports"), "$token.mtz")
  }

  suspend fun prepare(context: Context, repository: IconPackRepository, previews: IconPackPreviewRepository,
    pack: IconPackDescriptor, targets: List<IconPackComponent>): IconPackHandoff {
    val light = repository.exportNativeIcons(pack, targets, darkMode = false)
    var darkFile: File? = null
    var merged: File? = null
    val token = UUID.randomUUID().toString()
    val target = file(context, token)
    try {
      val icons = if (light.preservesNativeResources) light.archive else {
        val dark = repository.exportNativeIcons(pack, targets, darkMode = true)
        darkFile = dark.archive
        withContext(Dispatchers.IO) {
          val current = currentCoroutineContext()
          File.createTempFile("paired-icons-", ".zip", context.cacheDir).also { paired ->
            merged = paired
            ZipOutputStream(paired.outputStream()).use { output ->
              listOf(light.archive to false, dark.archive to true).forEach { (source, night) ->
                ZipFile(source).use { zip ->
                  zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    current.ensureActive()
                    val name = if (night) "nightmode/${entry.name}" else entry.name
                    output.putNextEntry(ZipEntry(name).apply { time = 0 })
                    zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                  }
                }
              }
              // The current Flutter icon chain does not select nightmode PNGs. The native
              // MAML loader has its own configuration observer and can select this fixed pair.
              // Keep both ordinary trees for native readers that do not allow fancy icons.
              ZipFile(light.archive).use { dayZip ->
                ZipFile(dark.archive).use { nightZip ->
                  dayZip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name.startsWith("res/drawable-xxhdpi/") && it.name.endsWith(".png") }
                    .forEach { dayEntry ->
                    current.ensureActive()
                    val nightEntry = nightZip.getEntry(dayEntry.name) ?: return@forEach
                    val dayBytes = dayZip.getInputStream(dayEntry).use { it.readBytes() }
                    val nightBytes = nightZip.getInputStream(nightEntry).use { it.readBytes() }
                    if (dayBytes.contentEquals(nightBytes)) return@forEach
                    val name = dayEntry.name.removePrefix("res/drawable-xxhdpi/").removeSuffix(".png")
                    for ((path, bytes) in NativeDayNightIcon.entries(name, dayBytes, nightBytes)) {
                      if (!path.startsWith("fancy_icons/")) continue
                      output.putNextEntry(ZipEntry(path).apply { time = 0 })
                      output.write(bytes)
                      output.closeEntry()
                    }
                  }
                }
              }
            }
          }
        }
      }
      val first = iconPackPreviewEntry(pack, targets)
      val artwork = first?.let { previews.load(pack, it, 144) }
      return withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        ZipOutputStream(target.outputStream()).use { output ->
          output.putNextEntry(ZipEntry("description.xml").apply { time = 0 })
          val title = xml(pack.displayName)
          val author = xml(pack.description.author ?: "Original icon pack authors")
          output.write(("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<MIUI-Theme><title>$title</title><author>$author</author><designer>$author</designer>" +
            "<version>1.0</version><uiVersion>13</uiVersion>" +
            "<description>Local icon import by KeiHome. Artwork: $title.</description></MIUI-Theme>").toByteArray())
          output.closeEntry()
          output.putNextEntry(ZipEntry("icons").apply { time = 0 })
          icons.inputStream().use { it.copyTo(output) }
          output.closeEntry()
          // Themes crops previews to a portrait phone frame, including icons-only components.
          val cover = Bitmap.createBitmap(400, 860, Bitmap.Config.ARGB_8888)
          try {
            val canvas = Canvas(cover)
            canvas.drawColor(Color.rgb(242, 243, 246))
            canvas.drawRect(200f, 0f, 400f, 860f, Paint().apply { color = Color.rgb(35, 36, 40) })
            artwork?.light?.let { canvas.drawBitmap(it, 28f, 358f, null) }
            artwork?.dark?.let { canvas.drawBitmap(it, 228f, 358f, null) }
            output.putNextEntry(ZipEntry("preview/preview_icons_0.png").apply { time = 0 })
            check(cover.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.closeEntry()
          } finally { cover.recycle() }
        }
        IconPackHandoff(token, target, pack.id, pack.displayName, when (pack.format) {
          os.kei.hyper.home.iconpack.IconPackFormat.INSTALLED_APK,
          os.kei.hyper.home.iconpack.IconPackFormat.APK -> "CONVERTED_APK"
          os.kei.hyper.home.iconpack.IconPackFormat.CLASSIC_ZIP -> "CONVERTED_CLASSIC_ZIP"
          else -> null
        })
      }
    } catch (failure: Throwable) {
      target.delete()
      throw failure
    } finally {
      light.archive.delete()
      darkFile?.delete()
      merged?.delete()
    }
  }

  private fun iconPackPreviewEntry(pack: IconPackDescriptor, targets: List<IconPackComponent>): os.kei.hyper.home.iconpack.IconPackEntry? {
    val components = targets.toHashSet()
    val packages = targets.mapTo(HashSet()) { it.packageName }
    return pack.entries.firstOrNull { it.component in components || it.nativeName in packages }
      ?: pack.entries.firstOrNull { it.light != null }
  }

  private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
