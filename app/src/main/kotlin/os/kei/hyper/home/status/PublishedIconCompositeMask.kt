package os.kei.hyper.home.status

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.IconPackException
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.ImageArchiveZip
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.NativeMamlPair
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram
import os.kei.hyper.home.iconpack.validNativeIconName

/** Masks complete published canvases, never the imported ZIP or individual animation layers. */
internal object PublishedIconCompositeMask {
  private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
  private const val MAX_DIMENSION = 1024
  private const val MAX_XML_BYTES = NativeIconShapeConfig.MAX_BYTES
  private const val MAX_COMPOSITES = 8192
  private val staticImageTypes = setOf("image/png", "image/jpeg", "image/webp")
  private val flat = Regex("(?:nightmode/)?res/drawable(?:-[A-Za-z0-9]+)*/([^/]+)\\.png")
  private val fancy = Regex("(?:nightmode/)?fancy_icons/([^/]+)/")
  private val layered = Regex("(?:nightmode/)?layer_animating_icons/([^/]+)/")

  /** False leaves output absent; callers must explicitly opt into the completed shape policy. */
  fun write(
    source: File,
    output: File,
    enabled: Boolean,
    check: () -> Unit = {},
    limits: IconPackLimits = IconPackLimits(),
  ): Boolean {
    require(source.canonicalFile != output.canonicalFile)
    require(!Files.isSymbolicLink(output.toPath()) && !output.exists())
    check()
    if (!enabled) return false
    require(source.isFile && source.length() in 1..limits.maxInputBytes)
    var owned = false
    try {
      ZipFile(source).use { zip ->
        require(zip.size() <= limits.maxEntries)
        val entries = linkedMapOf<String, ZipEntry>()
        val foldedNames = hashSetOf<String>()
        var expanded = 0L
        zip.entries().asSequence().forEach { entry ->
          check()
          val parts = entry.name.removeSuffix("/").split('/')
          require(entry.name.length in 1..512 && parts.size <= 32 && parts.all {
            it.isNotEmpty() && it != "." && it != ".." &&
              it.none { c -> c == '\\' || c == ':' || c.isISOControl() }
          }) { "Unsafe published icon path" }
          require(entries.put(entry.name, entry) == null) { "Duplicate published icon path" }
          require(foldedNames.add(entry.name.removeSuffix("/").lowercase(Locale.ROOT))) {
            "Ambiguous published icon path"
          }
          require(entry.size in 0..limits.maxEntryBytes)
          expanded += entry.size
          require(expanded <= limits.maxExpandedBytes)
        }
        val files = entries.values.asSequence().filterNot { it.isDirectory }
          .map { it.name.lowercase(Locale.ROOT) }.toHashSet()
        for (name in entries.keys) {
          check()
          var slash = name.indexOf('/')
          while (slash >= 0) {
            require(name.substring(0, slash).lowercase(Locale.ROOT) !in files) {
              "File used as a published icon directory"
            }
            slash = name.indexOf('/', slash + 1)
          }
        }
        if (NativeIconShapeConfig.hasLegacyStyle(entries.keys.asSequence())) return false
        fun read(name: String, max: Int): ByteArray? = entries[name]?.let { entry ->
          if (entry.isDirectory || entry.size > max) return@let null
          zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
              check()
              val count = input.read(buffer)
              if (count < 0) break
              require(out.size().toLong() + count <= max)
              out.write(buffer, 0, count)
            }
            require(out.size().toLong() == entry.size)
            out.toByteArray()
          }
        }
        val mask = IconMaskPolicy.fromConfig(read(NativeIconShapeConfig.PATH, MAX_XML_BYTES))
          ?: return false
        val candidates = linkedSetOf<String>()
        fun add(name: String) {
          if (entries[name]?.isDirectory == false) {
            candidates += name
            require(candidates.size <= MAX_COMPOSITES) { "Published composite count limit exceeded" }
          }
        }
        val groups = linkedMapOf<String, MutableSet<String>>()
        for (name in entries.keys) {
          check()
          flat.matchEntire(name)?.groupValues?.get(1)?.takeIf(::validNativeIconName)?.let { add(name) }
          for (pattern in listOf(fancy, layered)) {
            val match = pattern.find(name)?.takeIf { it.range.first == 0 } ?: continue
            if (validNativeIconName(match.groupValues[1]))
              groups.getOrPut(match.value) { linkedSetOf() } += name.removePrefix(match.value)
          }
        }
        for ((root, paths) in groups) {
          check()
          val base = if ("${root}manifest.xml" in entries) root else "${root}fancy/"
          val pair = try {
            read("${base}manifest.xml", MAX_XML_BYTES)?.let { NativeMamlPair.parse(it, check) }
          } catch (_: IconPackException) { null }
          if (pair != null) {
            add(base + pair.light)
            add(base + pair.dark)
            add("${root}quiet/quietImage.png")
          }
          if ("${root}quiet/quietImage.png" !in entries || "/layer_animating_icons/" !in "/$root") continue
          val configBytes = read("${root}config.xml", MAX_XML_BYTES) ?: continue
          val config = try {
            NativeLayerConfig.parse(configBytes, IconPackLimits(maxXmlBytes = MAX_XML_BYTES), check)
          } catch (_: IconPackException) { continue }
          if (config.layers.size != 2) continue
          // Only the exact weather display/provider schema identifies this root quiet as a
          // complete fallback. Unknown group quiets and all per-layer quiets remain untouched.
          val weather = config.layers.last()
          if (weather !in config.fancyOnly) continue
          val program = read("$root$weather/fancy/manifest.xml", MAX_XML_BYTES) ?: continue
          check()
          if (NativeWeatherLayerProgram.resources(program, "$weather/fancy/", paths) != null)
            add("${root}quiet/quietImage.png")
        }
        if (candidates.isEmpty()) return false
        Files.createFile(output.toPath())
        owned = true
        var publishedBytes = 0L
        val bounded = object : FilterOutputStream(output.outputStream().buffered()) {
          override fun write(value: Int) {
            require(++publishedBytes <= limits.maxInputBytes)
            out.write(value)
          }
          override fun write(bytes: ByteArray, offset: Int, count: Int) {
            publishedBytes += count
            require(publishedBytes <= limits.maxInputBytes)
            out.write(bytes, offset, count)
          }
        }
        ZipOutputStream(bounded).use { out ->
          for ((name, entry) in entries) {
            check()
            if (name !in candidates || entry.size > MAX_IMAGE_BYTES) {
              val copy = ZipEntry(name).apply {
                time = entry.time
                if (entry.method == ZipEntry.STORED) {
                  method = ZipEntry.STORED
                  size = entry.size
                  compressedSize = entry.size
                  crc = entry.crc
                }
              }
              out.putNextEntry(copy)
              zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(32 * 1024)
                var copied = 0L
                while (true) {
                  check()
                  val count = input.read(buffer)
                  if (count < 0) break
                  copied += count
                  require(copied <= entry.size) { "Published entry exceeded its declared size" }
                  out.write(buffer, 0, count)
                }
                require(copied == entry.size)
              }
              out.closeEntry()
              continue
            }
            val image = requireNotNull(read(name, MAX_IMAGE_BYTES)) { "Published image size limit exceeded" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, options)
            if (options.outMimeType !in staticImageTypes || options.outWidth !in 1..MAX_DIMENSION ||
              options.outHeight != options.outWidth) {
              ImageArchiveZip.writeBytes(out, name, image, entry.time, check)
              continue
            }
            check()
            val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size)
            if (bitmap == null) {
              ImageArchiveZip.writeBytes(out, name, image, entry.time, check)
              continue
            }
            var clipped: Bitmap? = null
            try {
              check()
              if (!needsMask(bitmap, mask, check)) {
                ImageArchiveZip.writeBytes(out, name, image, entry.time, check)
                continue
              }
              val result = IconMaskPolicy.clip(bitmap, mask)
              clipped = result
              val encoded = ByteArrayOutputStream()
              val pngOutput = object : OutputStream() {
                override fun write(value: Int) {
                  require(encoded.size() < MAX_IMAGE_BYTES)
                  encoded.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                  check()
                  require(encoded.size().toLong() + count <= MAX_IMAGE_BYTES)
                  encoded.write(bytes, offset, count)
                }
              }
              require(result.compress(Bitmap.CompressFormat.PNG, 100, pngOutput))
              check()
              ImageArchiveZip.writeBytes(out, name, encoded.toByteArray(), entry.time, check)
            } finally {
              if (clipped !== bitmap) clipped?.recycle()
              bitmap.recycle()
            }
          }
        }
        check()
      }
      return true
    } catch (failure: Throwable) {
      if (owned) output.delete()
      throw failure
    }
  }

  private fun needsMask(bitmap: Bitmap, mask: NativeAdaptiveIconMask, check: () -> Unit): Boolean {
    val stencil = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    try {
      Canvas(stencil).drawPath(mask.pathForBounds(Rect(0, 0, bitmap.width, bitmap.height)),
        Paint(Paint.ANTI_ALIAS_FLAG))
      val pixels = IntArray(bitmap.width)
      val alpha = IntArray(bitmap.width)
      for (y in 0 until bitmap.height) {
        check()
        bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
        stencil.getPixels(alpha, 0, bitmap.width, 0, y, bitmap.width, 1)
        if (pixels.indices.any { (pixels[it] ushr 24) > (alpha[it] ushr 24) }) return true
      }
      return false
    } finally { stencil.recycle() }
  }
}
