package os.kei.hyper.home.xposed

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.BoundedPackXml
import os.kei.hyper.home.iconpack.IconBuildInventory
import os.kei.hyper.home.iconpack.IconGenerationManifest
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackException
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.IconRasterHeader
import os.kei.hyper.home.iconpack.NativeMamlPair
import os.kei.hyper.home.iconpack.NativeMamlCalendarPair
import os.kei.hyper.home.iconpack.SystemIconFallbackManifest

/** Admission for local, image-only icon modules. Unknown MAML programs remain ineligible. */
internal object LocalNativeIconValidator {
  private const val IMAGE_LIMIT = 8L * 1024 * 1024
  private const val PAIR_XML_LIMIT = 64 * 1024
  private val limits = IconPackLimits(maxInputBytes = ConvertedIconArchive.MAX_BYTES)
  private val visualTags = setOf(
    "Icon", "Group", "Image", "Var", "DateTime", "VariableBinders", "BroadcastBinder",
    "VariableAnimation", "AniFrame", "ExternalCommands", "Trigger", "VariableCommand",
    "FrameRateCommand", "AnimationCommand", "FolmeCommand", "FolmeState", "FolmeConfig",
  )
  private val transformTags = setOf("IconTransform", "ScaleX", "ScaleY", "PointsMapping", "Point", "Config")
  private val clockActions = setOf(
    "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED",
    "android.intent.action.DATE_CHANGED", "android.intent.action.LOCALE_CHANGED",
  )
  internal fun validClockBinding(attrs: Map<String, String>): Boolean =
    attrs.keys == setOf("action") && attrs["action"] in clockActions
  private val localPath = Regex("[A-Za-z0-9_./-]*")
  private val quoted = Regex("'([^']*)'|\"([^\"]*)\"")
  private val pureFunctions = setOf("ifelse", "eq", "ne", "gt", "ge", "lt", "le",
    "and", "or", "not", "min", "max", "abs", "int", "round", "floor", "ceil")

  fun validate(file: File) {
    // Reuse ZIP bounds, CRC, traversal/duplicate checks, and bounded XML parsing.
    val unused = File.createTempFile("keihome-local-icons-", ".tmp", file.parentFile)
    val index = try { IconPackArchive(limits).inspect(file, unused) } finally { unused.delete() }
    require(index.format == IconPackFormat.MIUI_ICONS && index.entries.isNotEmpty()) {
      "Expected a standalone local icons module"
    }
    ZipFile(file).use { zip ->
      val paths = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
      fun read(path: String, limit: Long): ByteArray {
        val entry = requireNotNull(zip.getEntry(path))
        require(entry.size in 1..limit) { "Local icon entry size limit: $path" }
        val bytes = zip.getInputStream(entry).use { it.readNBytes((limit + 1).toInt()) }
        require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) {
          "Local icon integrity mismatch: $path"
        }
        return bytes
      }
      val layered = LocalLayerIconValidator.validate(paths, ::read, ::image)
      val templates = PairTemplates()
      val validatedImages = mutableSetOf<String>()
      fun bitmap(path: String) {
        if (path !in validatedImages) {
          image(read(path, IMAGE_LIMIT))
          validatedImages += path
        }
      }
      for (path in paths) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Local icon inspection interrupted")
        when {
          path in layered -> Unit
          path in validatedImages -> Unit
          path == IconBuildInventory.SOURCE_PATH -> IconBuildInventory.decodeSource(read(path, 4096))
          path == IconGenerationManifest.PATH -> IconGenerationManifest.decode(read(path, 1024 * 1024))
          path == os.kei.hyper.home.iconpack.IconThemeMetadata.PATH ->
            requireNotNull(os.kei.hyper.home.iconpack.IconThemeMetadata.decode(read(path, 4096)))
          path == SystemIconFallbackManifest.PATH -> SystemIconFallbackManifest.decode(read(path, 1024 * 1024))
          path == "transform_config.xml" -> xml(read(path, limits.maxXmlBytes.toLong()), transform = true)
          path.startsWith("fancy_icons/") && path.endsWith("/manifest.xml") -> {
            val parts = path.split('/')
            require(parts.size == 3) { "Unexpected local manifest location" }
            val bytes = read(path, limits.maxXmlBytes.toLong())
            val pair = templates.parse(bytes)
            val calendar = if (pair == null) templates.calendar(bytes) else null
            val images = pair?.let { setOf(it.light, it.dark) } ?: calendar?.images
            if (images != null) {
              val base = path.removeSuffix("manifest.xml")
              for (name in images) {
                val artwork = base + name
                require(artwork in paths) { "Missing closed native image: $artwork" }
                bitmap(artwork)
              }
              continue
            }
            try {
              xml(bytes, transform = false)
            } catch (failure: Exception) {
              if (failure !is IconPackException && failure !is IllegalArgumentException) throw failure
              // Our own appended palette selectors may use Settings binders, but
              // only their exact generated template and complete resources qualify.
              try {
                GeneratedNativeIconValidator.validate(parts[1], paths, ::read)
              } catch (verification: Exception) {
                if (verification is InterruptedException) throw verification
                failure.addSuppressed(verification)
                throw IllegalArgumentException("Ineligible local icon manifest: $path", failure)
              }
            }
          }
          (path.startsWith("res/") || path.startsWith("nightmode/res/") || path.startsWith("fancy_icons/")) &&
            (path.endsWith(".png") || path.endsWith(".webp")) -> bitmap(path)
          else -> error("Unsupported local icon resource: $path")
        }
      }
      require(paths.any { it.endsWith(".png") || it.endsWith(".webp") }) { "No local icon artwork" }
    }
  }

  /** Per-validation cache of exact bytes, including non-matches; never caches artwork admission. */
  internal class PairTemplates(
    private val parser: (ByteArray) -> NativeMamlPair? = { bytes ->
      NativeMamlPair.parse(bytes) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Local icon inspection interrupted")
      }
    },
  ) {
    private class Key(val bytes: ByteArray) {
      override fun equals(other: Any?) = other is Key && bytes.contentEquals(other.bytes)
      override fun hashCode() = bytes.contentHashCode()
    }
    private data class Proof(val pair: NativeMamlPair?, val calendar: NativeMamlCalendarPair?)
    private val entries = linkedMapOf<Key, Proof>()

    fun parse(bytes: ByteArray): NativeMamlPair? = proof(bytes)?.pair
    fun calendar(bytes: ByteArray): NativeMamlCalendarPair? = proof(bytes)?.calendar

    private fun proof(bytes: ByteArray): Proof? {
      if (bytes.size > PAIR_XML_LIMIT) return null
      val key = Key(bytes)
      entries[key]?.let { return it }
      val pair = parser(bytes)
      val calendar = if (pair == null) NativeMamlCalendarPair.parse(bytes) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Local icon inspection interrupted")
      } else null
      val proof = Proof(pair, calendar)
      if (entries.size == 32) entries.remove(entries.keys.first())
      entries[Key(bytes.copyOf())] = proof
      return proof
    }
  }

  private fun xml(bytes: ByteArray, transform: Boolean) {
    var root: String? = null
    val stringVariables = mutableSetOf<String>()
    val imageVariables = mutableSetOf<String>()
    BoundedPackXml.read(bytes, limits, start = { tag, attrs, depth ->
      if (depth == 1) root = tag
      require(tag in if (transform) transformTags else visualTags) { "Unsupported local icon element: $tag" }
      require(attrs.values.none {
        it.contains("://") || it.contains("content:", true) || it.contains("file:", true) ||
          it.contains('\\') || it.contains("..") || it.trimStart().startsWith('/')
      }) { "External local icon reference" }
      if (tag == "Config") {
        require(transform && depth == 2 && attrs.keys == setOf("name", "value")) {
          "Unsupported local transform configuration"
        }
        when (attrs["name"]) {
          "SupportLayerIcon", "UseDynamicIcon" ->
            require(attrs["value"] in setOf("true", "false"))
          "ConfigIconMask" -> maskPath(requireNotNull(attrs["value"]))
          else -> error("Unsupported local transform setting")
        }
      }
      if (tag == "Icon") require(attrs["useVariableUpdater"].let { it == null || it == "DateTime" }) {
        "Unsupported local icon updater"
      }
      if (tag == "BroadcastBinder") require(validClockBinding(attrs)) {
        "Unsupported local icon broadcast binding"
      }
      if (tag == "Var" || tag == "VariableCommand") {
        attrs["expression"]?.let(::expression)
        if (tag == "Var" && attrs["type"] == "string") {
          require(attrs.keys.all { it in setOf("name", "type", "expression") }) {
            "Unsupported local image variable attributes"
          }
          require(!attrs["expression"].isNullOrBlank()) { "Missing local image variable expression" }
          stringVariables += requireNotNull(attrs["name"])
        }
        if (tag == "VariableCommand") {
          require(attrs.keys.all { it in setOf("name", "expression", "delay") } &&
            !attrs["expression"].isNullOrBlank()) { "Unsupported local variable command" }
          attrs["delay"]?.let(::expression)
        }
      }
      if (tag == "Image" || tag == "DateTime") {
        attrs["src"]?.let { require(localPath.matches(it) && !it.startsWith('/')) { "Nonlocal image source" } }
        attrs["srcExp"]?.let {
          if (it.matches(Regex("@[A-Za-z_][A-Za-z0-9_]*"))) imageVariables += it.drop(1)
          else expression(it)
        }
      }
    })
    require(root == if (transform) "IconTransform" else "Icon") { "Unexpected local icon root" }
    require(stringVariables.containsAll(imageVariables)) { "Unverified computed image source" }
  }

  /** Bounded SVG geometry, not a general expression, URI, or executable configuration. */
  private fun maskPath(value: String) {
    require(value.length in 1..4096)
    val token = Regex("[MLHVCSQTZmlhvcsqtz]|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
    val tokens = token.findAll(value).map { it.value }.toList()
    require(tokens.joinToString("") == value.filterNot { it.isWhitespace() || it == ',' })
    require(tokens.firstOrNull() in setOf("M", "m") && tokens.lastOrNull() in setOf("Z", "z"))
    val arity = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6,
      'S' to 4, 'Q' to 4, 'T' to 2, 'Z' to 0)
    var index = 0
    var segments = 0
    while (index < tokens.size) {
      val command = tokens[index++].singleOrNull()?.uppercaseChar()
      val count = arity[command] ?: error("Invalid icon mask command")
      if (count == 0) continue
      var groups = 0
      while (index < tokens.size && tokens[index].first() !in "MLHVCSQTZmlhvcsqtz") {
        require(index + count <= tokens.size && ++segments <= 256)
        repeat(count) {
          val coordinate = tokens[index++].toDoubleOrNull()
          require(coordinate != null && coordinate.isFinite() && coordinate in -1000.0..1000.0)
        }
        groups++
      }
      require(groups > 0)
    }
  }

  private fun expression(value: String) {
    val arithmetic = quoted.replace(value) { match ->
      val path = match.groups[1]?.value ?: requireNotNull(match.groups[2]).value
      require(localPath.matches(path) && !path.startsWith('/') && !path.contains("..")) {
        "Nonlocal image expression literal"
      }
      "0"
    }
    require(arithmetic.matches(Regex("[\\sA-Za-z0-9_#.()+*/%<>=!&|?:,\\-]*"))) {
      "Unsupported local image expression"
    }
    require(Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").findAll(arithmetic)
      .all { it.groupValues[1] in pureFunctions }) { "Unsupported local expression function" }
  }

  private fun image(bytes: ByteArray) {
    require(bytes.size.toLong() in 1..IMAGE_LIMIT) { "Local icon image size limit" }
    fun u(i: Int) = bytes[i].toInt() and 255
    fun le(i: Int, count: Int): Long = (0 until count).fold(0L) { v, n -> v or (u(i + n).toLong() shl (8 * n)) }
    fun be(i: Int): Long = (0..3).fold(0L) { v, n -> (v shl 8) or u(i + n).toLong() }
    fun dimensions(w: Long, h: Long) = require(w in 1..2048 && h in 1..2048) { "Local icon dimension limit" }
    if (bytes.size >= 2 && u(0) == 0xff && u(1) == 0xd8) {
      val size = bytes.inputStream().use {
        IconRasterHeader.read(it, bytes.size.toLong()) {
          if (Thread.currentThread().isInterrupted) throw InterruptedException("Local JPEG inspection interrupted")
        }
      }
      dimensions(size.width.toLong(), size.height.toLong())
      jpeg(bytes)
      return
    }
    if (bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) {
      var offset = 8
      var header = false
      var pixels = false
      while (offset <= bytes.size - 12) {
        val size = be(offset)
        require(size <= bytes.size - offset - 12L) { "Invalid local PNG chunk" }
        val end = offset + 8 + size.toInt()
        val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
        require(CRC32().apply { update(bytes, offset + 4, size.toInt() + 4) }.value == be(end)) { "Local PNG CRC mismatch" }
        require(header || type == "IHDR") { "Missing local PNG header" }
        when (type) {
          "IHDR" -> { require(!header && size == 13L); dimensions(be(offset + 8), be(offset + 12)); header = true }
          "IDAT" -> pixels = true
          // Decoders stop at IEND; opaque trailing bytes stay inside the existing entry-size budget.
          "IEND" -> { require(size == 0L && pixels); return }
          "acTL", "fcTL", "fdAT" -> error("Animated PNG is not an admitted local bitmap")
        }
        offset = end + 4
      }
      error("Missing local PNG end")
    }
    require(bytes.size >= 30 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
      String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" && le(4, 4) == bytes.size - 8L) { "Unsupported local image encoding" }
    var offset = 12
    var canvas = false
    var pixels = false
    while (offset <= bytes.size - 8) {
      val type = String(bytes, offset, 4, Charsets.US_ASCII)
      val size = le(offset + 4, 4)
      require(size <= bytes.size - offset - 8L) { "Invalid local WebP chunk" }
      val data = offset + 8
      when (type) {
        "VP8X" -> { require(size == 10L && !canvas && u(data) and 2 == 0); dimensions(le(data + 4, 3) + 1, le(data + 7, 3) + 1); canvas = true }
        "VP8 " -> {
          require(size >= 10 && u(data + 3) == 0x9d && u(data + 4) == 1 && u(data + 5) == 0x2a)
          dimensions(le(data + 6, 2) and 0x3fff, le(data + 8, 2) and 0x3fff); pixels = true
        }
        "VP8L" -> { require(size >= 5 && u(data) == 0x2f); val bits = le(data + 1, 4); dimensions((bits and 0x3fff) + 1, ((bits shr 14) and 0x3fff) + 1); pixels = true }
        "ANIM", "ANMF" -> error("Animated WebP is not an admitted local bitmap")
        "ALPH", "ICCP", "EXIF", "XMP " -> Unit
        else -> error("Unsupported local WebP chunk")
      }
      offset = data + size.toInt() + (size.toInt() and 1)
    }
    require(offset == bytes.size && pixels) { "Incomplete local WebP" }
  }

  /** Validate the complete scan envelope, not JPEG entropy decoding or a header-only dimension probe. */
  private fun jpeg(bytes: ByteArray) {
    fun u(index: Int) = bytes[index].toInt() and 255
    fun be16(index: Int) = (u(index) shl 8) or u(index + 1)
    var offset = 2
    var markers = 0
    var frame = 0
    var components = emptySet<Int>()
    val scanned = mutableSetOf<Int>()
    var scans = 0
    var restartInterval = 0
    while (offset < bytes.size) {
      if (Thread.currentThread().isInterrupted) throw InterruptedException("Local JPEG inspection interrupted")
      require(++markers <= 4096 && u(offset++) == 0xff) { "Invalid local JPEG marker" }
      while (offset < bytes.size && u(offset) == 0xff) offset++
      require(offset < bytes.size) { "Truncated local JPEG marker" }
      val marker = u(offset++)
      if (marker == 0xd9) {
        require(offset == bytes.size && scans > 0 && scanned.containsAll(components)) { "Incomplete local JPEG" }
        return
      }
      require(offset <= bytes.size - 2) { "Truncated local JPEG segment" }
      val length = be16(offset)
      require(length >= 2 && length <= bytes.size - offset) { "Invalid local JPEG segment length" }
      val data = offset + 2
      val end = offset + length
      when (marker) {
        0xc0, 0xc1, 0xc2 -> {
          require(frame == 0 && length >= 8) { "Invalid local JPEG frame" }
          require(u(data) == 8 || (marker != 0xc0 && u(data) == 12)) { "Invalid local JPEG sample precision" }
          val count = u(data + 5)
          require(count > 0 && length == 8 + 3 * count) { "Invalid local JPEG frame components" }
          components = (0 until count).map { u(data + 6 + 3 * it) }.toSet()
          require(components.size == count)
          frame = marker
        }
        0xda -> {
          require(frame != 0 && length >= 6 && ++scans <= 4096) { "Invalid local JPEG scan" }
          val count = u(data)
          require(count in 1..components.size && length == 6 + 2 * count) { "Invalid local JPEG scan length" }
          val ids = mutableSetOf<Int>()
          repeat(count) {
            val id = u(data + 1 + 2 * it)
            val tables = u(data + 2 + 2 * it)
            require(id in components && ids.add(id) && tables shr 4 <= 3 && tables and 15 <= 3) {
              "Invalid local JPEG scan component"
            }
          }
          val first = u(end - 3)
          val last = u(end - 2)
          val refinement = u(end - 1)
          val high = refinement shr 4
          val low = refinement and 15
          if (frame == 0xc2) {
            require(first in 0..63 && last in first..63 && (first != 0 || last == 0) &&
              (first == 0 || count == 1) && high <= 13 && low <= 13 && (high == 0 || high == low + 1)) {
              "Invalid local progressive JPEG scan"
            }
          } else {
            require(first == 0 && last == 63 && refinement == 0) { "Invalid local sequential JPEG scan" }
          }
          if (first == 0 && high == 0) scanned += ids
          offset = end
          var entropy = false
          var nextRestart = 0
          while (offset < bytes.size) {
            if (offset and 0x3fff == 0 && Thread.currentThread().isInterrupted)
              throw InterruptedException("Local JPEG inspection interrupted")
            if (u(offset++) != 0xff) {
              entropy = true
              continue
            }
            val start = offset - 1
            while (offset < bytes.size && u(offset) == 0xff) offset++
            require(offset < bytes.size) { "Truncated local JPEG entropy marker" }
            when (val next = u(offset++)) {
              0 -> entropy = true // Stuffed FF data, not a segment delimiter.
              in 0xd0..0xd7 -> {
                require(restartInterval > 0 && next == 0xd0 + nextRestart) { "Invalid local JPEG restart" }
                nextRestart = (nextRestart + 1) and 7
              }
              else -> {
                offset = start
                break
              }
            }
          }
          require(entropy && offset < bytes.size) { "Missing local JPEG scan data or end" }
          continue
        }
        0xdd -> {
          require(length == 4) { "Invalid local JPEG restart interval" }
          restartInterval = be16(data)
        }
        0xc4, 0xdb -> require(length > 2) { "Empty local JPEG table" }
        in 0xe0..0xef, 0xfe -> Unit
        else -> error("Unsupported local JPEG marker")
      }
      offset = end
    }
    error("Missing local JPEG end")
  }
}
