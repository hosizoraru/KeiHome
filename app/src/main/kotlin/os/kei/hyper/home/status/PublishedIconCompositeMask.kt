package os.kei.hyper.home.status

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.IconPackException
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.NativeMamlPair
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram
import os.kei.hyper.home.iconpack.RawIconArchive
import os.kei.hyper.home.iconpack.validNativeIconName

/** Masks complete published canvases, never the imported ZIP or individual animation layers. */
internal object PublishedIconCompositeMask {
  private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
  private const val MAX_DIMENSION = 1024
  private const val MAX_XML_BYTES = NativeIconShapeConfig.MAX_BYTES
  private val staticImageTypes = setOf("image/png", "image/jpeg", "image/webp")
  private val flat = Regex("(?:nightmode/)?res/drawable(?:-[A-Za-z0-9]+)*/([^/]+)\\.png")
  private val fancy = Regex("(?:nightmode/)?fancy_icons/([^/]+)/")
  private val layered = Regex("(?:nightmode/)?layer_animating_icons/([^/]+)/")
  private val groupPatterns = listOf(fancy, layered)

  private class Template(val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()
    override fun hashCode() = hash
    override fun equals(other: Any?) = other is Template && bytes.contentEquals(other.bytes)
  }

  /** False leaves output absent; callers must explicitly opt into the completed shape policy. */
  fun write(
    source: File,
    output: File,
    enabled: Boolean,
    check: () -> Unit = {},
    limits: IconPackLimits = IconPackLimits(),
    onProgress: (Int, Int) -> Unit = { _, _ -> },
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
        val normalizedNames = hashSetOf<String>()
        var expanded = 0L
        zip.entries().asSequence().forEach { entry ->
          check()
          val parts = entry.name.removeSuffix("/").split('/')
          require(entry.name.length in 1..512 && parts.size <= 32 && parts.all {
            it.isNotEmpty() && it != "." && it != ".." &&
              it.none { c -> c == '\\' || c == ':' || c.isISOControl() }
          }) { "Unsafe published icon path" }
          require(entries.put(entry.name, entry) == null) { "Duplicate published icon path" }
          require(normalizedNames.add(entry.name.removeSuffix("/"))) {
            "Ambiguous published icon path"
          }
          require(entry.size in 0..limits.maxEntryBytes)
          expanded += entry.size
          require(expanded <= limits.maxExpandedBytes)
        }
        val files = entries.values.asSequence().filterNot { it.isDirectory }
          .map { it.name }.toHashSet()
        for (name in entries.keys) {
          check()
          var slash = name.indexOf('/')
          while (slash >= 0) {
            require(name.substring(0, slash) !in files) {
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
          }
        }
        val groups = linkedMapOf<String, MutableSet<String>>()
        for (name in entries.keys) {
          check()
          flat.matchEntire(name)?.groupValues?.get(1)?.takeIf(::validNativeIconName)?.let { add(name) }
          for (pattern in groupPatterns) {
            val match = pattern.find(name)?.takeIf { it.range.first == 0 } ?: continue
            if (validNativeIconName(match.groupValues[1]))
              groups.getOrPut(match.value) { linkedSetOf() } += name.removePrefix(match.value)
          }
        }
        // Large native packs commonly repeat one day/night XML template for thousands of apps.
        val templates = linkedMapOf<Template, NativeMamlPair?>()
        for ((root, paths) in groups) {
          check()
          val base = if ("${root}manifest.xml" in entries) root else "${root}fancy/"
          val pair = try {
            read("${base}manifest.xml", MAX_XML_BYTES)?.let { bytes ->
              val key = Template(bytes)
              if (templates.containsKey(key)) templates[key]
              else NativeMamlPair.parse(bytes, check).also {
                if (templates.size == 32) templates.remove(templates.keys.first())
                templates[key] = it
              }
            }
          } catch (_: IconPackException) { null }
          if (pair != null) {
            add(base + pair.light)
            add(base + pair.dark)
            add("${root}quiet/quietImage.png")
          }
          if ("${root}quiet/quietImage.png" !in entries || "/layer_animating_icons/" !in "/$root") continue
          val configBytes = read("${root}config.xml", MAX_XML_BYTES) ?: continue
          val config = try {
            val availableLayers = NativeLayerConfig.availableLayerDirectories("", paths)
            NativeLayerConfig.parse(
              configBytes,
              IconPackLimits(maxXmlBytes = MAX_XML_BYTES),
              check,
              availableLayers,
            )
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
        val scanner = MaskScanner(mask)
        val total = entries.values.count { !it.isDirectory }
        var visited = 0
        onProgress(0, total)
        RawIconArchive.write(source, output, check = check, limits = limits,
          maxOutputBytes = limits.maxInputBytes, transform = transform@{ _, entry ->
            onProgress(++visited, total)
            if (entry.name !in candidates || entry.size > MAX_IMAGE_BYTES) return@transform null
            val image = requireNotNull(read(entry.name, MAX_IMAGE_BYTES)) {
              "Published image size limit exceeded"
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, options)
            if (options.outMimeType !in staticImageTypes || options.outWidth !in 1..MAX_DIMENSION ||
              options.outHeight != options.outWidth) return@transform null
            check()
            val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return@transform null
            var clipped: Bitmap? = null
            try {
              if (!scanner.needsMask(bitmap, check)) return@transform null
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
              encoded.toByteArray()
            } finally {
              if (clipped !== bitmap) clipped?.recycle()
              bitmap.recycle()
            }
          })
        owned = true
        onProgress(total, total)
        check()
      }
      return true
    } catch (failure: Throwable) {
      if (owned) output.delete()
      throw failure
    }
  }

  private class MaskScanner(private val mask: NativeAdaptiveIconMask) {
    private var size = 0
    private var coverage = IntArray(0)
    private var pixels = IntArray(0)

    fun needsMask(bitmap: Bitmap, check: () -> Unit): Boolean {
      if (size != bitmap.width) {
        size = bitmap.width
        coverage = IntArray(size * size)
        pixels = IntArray(size * size)
        val stencil = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
          Canvas(stencil).drawPath(mask.pathForBounds(Rect(0, 0, size, size)),
            Paint(Paint.ANTI_ALIAS_FLAG))
          stencil.getPixels(coverage, 0, size, 0, 0, size, size)
        } finally { stencil.recycle() }
      }
      bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
      for (y in 0 until size) {
        check()
        for (x in 0 until size) {
          val index = y * size + x
          if ((pixels[index] ushr 24) > (coverage[index] ushr 24)) return true
        }
      }
      return false
    }
  }
}
