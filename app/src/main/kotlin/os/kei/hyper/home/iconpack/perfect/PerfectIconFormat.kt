package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.concurrent.CancellationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal data class PerfectIconBlob(val path: String, val sha: String, val size: Long, val source: String = path)

internal data class StaticLayerPair(
  val background: PerfectIconBlob,
  val foreground: PerfectIconBlob,
) {
  val isCustom: Boolean
    get() = background.source.startsWith(PerfectCustomStatic.SOURCE_PREFIX) ||
      foreground.source.startsWith(PerfectCustomStatic.SOURCE_PREFIX)

  fun toList(): List<PerfectIconBlob> = listOf(background, foreground)
}

internal data class StaticIconEntry(
  val id: String,
  val flat: PerfectIconBlob? = null,
  val layers: StaticLayerPair? = null,
) {
  init { require(flat != null || layers != null) }

  fun dayBlobs(): List<PerfectIconBlob> = flat?.let(::listOf) ?: requireNotNull(layers).toList()

  fun nightInputs(): List<PerfectIconBlob>? {
    if (layers?.isCustom == true) return null
    return when {
      flat != null -> {
        val sourcePrefix = flat.source.removeSuffix(".png")
        val sameTheme = layers != null &&
          layers.background.source == "$sourcePrefix/0.png" &&
          layers.foreground.source == "$sourcePrefix/1.png"
        if (sameTheme) listOf(flat, layers.background, layers.foreground) else listOf(flat)
      }
      layers != null -> layers.toList()
      else -> null
    }
  }

  fun allBlobs(): List<PerfectIconBlob> = buildList(3) {
    flat?.let(::add)
    layers?.let { add(it.background); add(it.foreground) }
  }
}

internal data class DynamicIconGroup(
  val id: String,
  val resources: Map<String, PerfectIconBlob>,
  val quietOnly: Boolean = false,
)

internal data class PerfectIconPlan(
  val staticIcons: Map<String, StaticIconEntry>,
  val dynamicGroups: Map<String, DynamicIconGroup>,
  val staticIds: Set<String>,
  val dynamicIds: Set<String>,
  val rejectedDynamic: Map<String, String> = emptyMap(),
) {
  val hasLayerDirectory: Boolean get() = dynamicGroups.isNotEmpty()
  val isEmpty: Boolean get() = staticIcons.isEmpty() && dynamicGroups.isEmpty()

  fun allBlobs(): Sequence<PerfectIconBlob> = sequence {
    staticIcons.values.forEach { yieldAll(it.allBlobs()) }
    dynamicGroups.values.forEach { yieldAll(it.resources.values) }
  }

  /** Materialized archive-path view for stable publication hashing and test inspection. */
  val files: Map<String, PerfectIconBlob> by lazy(LazyThreadSafetyMode.NONE) {
    sortedMapOf<String, PerfectIconBlob>().apply {
      staticIcons.forEach { (id, entry) ->
        entry.flat?.let { put("res/drawable-xxhdpi/$id.png", it) }
        entry.layers?.let {
          put("res/drawable-xxhdpi/$id/0.png", it.background)
          put("res/drawable-xxhdpi/$id/1.png", it.foreground)
        }
      }
      dynamicGroups.forEach { (id, group) ->
        group.resources.forEach { (relative, blob) ->
          put("layer_animating_icons/$id/$relative", blob)
        }
      }
    }
  }
}

/** Source-data paths and bounded validation for the pzcn repository snapshot.
 * Static layers and resource selection are specified from retained source inputs in
 * docs/perfect-icons-provenance.md; native destination configuration lives separately.
 */
internal object PerfectIconFormat {
  const val MAX_BLOB = 32 * 1024 * 1024
  const val MAX_XML = 1024 * 1024
  const val MAX_FILES = 100_000
  const val MAX_PACK = 512L * 1024 * 1024

  fun safePath(path: String) {
    require(path.length in 1..1024 && !path.startsWith('/') && '\\' !in path)
    require(path.none { it.code < 32 || it.code == 127 })
    val parts = path.split('/')
    require(parts.size <= 32 && parts.all { it.isNotEmpty() && it != "." && it != ".." })
  }

  fun id(value: String): Boolean =
    value.length in 1..255 && !value.startsWith('.') && ".." !in value &&
      value.all { it.isLetterOrDigit() || it in "._-$#" }

  fun style(value: String) {
    require(value.isEmpty() || (value != "common" && value.length <= 128 &&
      value != "." && value != ".." && !value.startsWith('.') &&
      value.none { it.code < 32 || it in "/\\\u007f" })) { "Invalid Perfect Icons style" }
  }

  fun sha(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
  }

  fun sha(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.US_ASCII))
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(32 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  fun staticGroups(blobs: List<PerfectIconBlob>): Map<String, List<PerfectIconBlob>> =
    blobs.filter {
      val parts = it.path.split('/')
      parts.size == 2 && id(parts[0]) && parts[1] in setOf("0.png", "1.png")
    }.groupBy { it.path.substringBefore('/') }.filterValues { group ->
      group.size == 2 && group.map { it.path.substringAfter('/') }.toSet() == setOf("0.png", "1.png")
    }

  fun plan(
    base: List<PerfectIconBlob>,
    themed: List<PerfectIconBlob>,
    commonDynamic: List<PerfectIconBlob>,
    themedDynamic: List<PerfectIconBlob>,
    options: PerfectIconOptions,
    installed: Set<String>,
    custom: List<PerfectIconBlob> = emptyList(),
  ): PerfectIconPlan {
    fun selected(name: String) = !options.installedOnly || name in installed
    val customPairs = staticGroups(custom)
    val pairs = staticGroups(base).toMutableMap().apply {
      putAll(staticGroups(themed))
      putAll(customPairs)
    }
    val layerPairs = sortedMapOf<String, StaticLayerPair>()
    val flats = sortedMapOf<String, PerfectIconBlob>()
    pairs.filterKeys(::selected).forEach { (name, layers) ->
      val bg = layers.first { it.path.endsWith("/0.png") }
      val fg = layers.first { it.path.endsWith("/1.png") }
      layerPairs[name] = StaticLayerPair(bg, fg)
    }
    // Single-image style resources are independent of the static-layer chain.
    themed.filter { '/' !in it.path && it.path.endsWith(".png") }.forEach {
      val name = it.path.removeSuffix(".png")
      if (id(name) && selected(name) && name !in customPairs) {
        flats[name] = it
      }
    }
    val staticIcons = sortedMapOf<String, StaticIconEntry>()
    (layerPairs.keys + flats.keys).forEach { name ->
      staticIcons[name] = StaticIconEntry(name, flat = flats[name], layers = layerPairs[name])
    }
    val staticIds = staticIcons.keys.toSortedSet()
    val dynamicGroups = sortedMapOf<String, DynamicIconGroup>()
    val dynamicIds = sortedSetOf<String>()
    if (options.dynamic) {
      fun groups(blobs: List<PerfectIconBlob>) = blobs.filter {
        '/' in it.path && id(it.path.substringBefore('/'))
      }.groupBy { it.path.substringBefore('/') }.filterValues { resources ->
        resources.any { it.path.substringAfter('/') == "config.xml" }
      }
      val dynamic = groups(commonDynamic).toMutableMap().apply { putAll(groups(themedDynamic)) }
      dynamic.filterKeys(::selected).forEach { (name, resources) ->
        dynamicIds += name
        val map = sortedMapOf<String, PerfectIconBlob>()
        resources.forEach { map[it.path.substringAfter('/')] = it }
        dynamicGroups[name] = DynamicIconGroup(name, map)
      }
    }
    var fileCount = 0
    var totalBytes = 0L
    staticIcons.values.forEach { entry ->
      entry.allBlobs().forEach { blob ->
        fileCount++
        totalBytes += blob.size
      }
    }
    dynamicGroups.values.forEach { group ->
      group.resources.values.forEach { blob ->
        fileCount++
        totalBytes += blob.size
      }
    }
    require(fileCount <= MAX_FILES) { "Perfect Icons file limit exceeded" }
    require(totalBytes <= MAX_PACK) { "Perfect Icons pack exceeds 512 MiB" }
    staticIds.removeAll(dynamicIds)
    return PerfectIconPlan(staticIcons, dynamicGroups, staticIds, dynamicIds)
  }

  /** Verify chunks/CRC without decoding, scaling, composing or re-encoding upstream artwork. */
  fun png(bytes: ByteArray, square: Boolean) {
    require(bytes.size in 57..MAX_BLOB) { "Invalid Perfect Icons PNG size" }
    val input = ByteBuffer.wrap(bytes)
    require(input.long == 0x89504e470d0a1a0aUL.toLong()) { "Invalid PNG signature" }
    var header = false
    var data = false
    var end = false
    while (input.remaining() >= 12) {
      val length = input.int
      require(length >= 0 && length <= input.remaining() - 8) { "Invalid PNG chunk" }
      val start = input.position()
      val type = input.int
      if (!header) {
        require(type == 0x49484452 && length == 13)
        val width = input.getInt(input.position())
        val height = input.getInt(input.position() + 4)
        require(width in 1..8192 && height in 1..8192 && (!square || width == height)) { "Invalid PNG dimensions" }
        header = true
      } else require(type != 0x49484452)
      if (type == 0x49444154) data = true
      input.position(start + 4 + length)
      val crc = CRC32().apply { update(bytes, start, length + 4) }.value
      require(input.int.toLong() and 0xffffffffL == crc) { "Invalid PNG CRC" }
      if (type == 0x49454e44) {
        require(length == 0 && data) { "Invalid PNG end chunk" }
        end = true
        break
      }
    }
    require(header && data && end) { "Incomplete PNG" }
  }

  /** Native image decoding follows content signatures, including JPEG/WebP stored as .png. */
  fun raster(bytes: ByteArray) {
    require(bytes.size in 12..MAX_BLOB) { "Invalid image resource size" }
    fun dimensions(width: Int, height: Int) {
      require(width in 1..8192 && height in 1..8192) { "Invalid image dimensions: ${width}x$height" }
    }
    fun u8(index: Int) = bytes[index].toInt() and 255
    fun be16(index: Int) = (u8(index) shl 8) or u8(index + 1)
    fun le(index: Int, count: Int): Long = (0 until count).sumOf { u8(index + it).toLong() shl (8 * it) }
    when {
      ByteBuffer.wrap(bytes).long == 0x89504e470d0a1a0aUL.toLong() -> png(bytes, square = false)
      u8(0) == 255 && u8(1) == 216 -> {
        var offset = 2
        var frame = false
        var scan = false
        while (offset + 1 < bytes.size && !scan) {
          require(u8(offset++) == 255) { "Invalid JPEG marker" }
          while (offset < bytes.size && u8(offset) == 255) offset++
          require(offset < bytes.size)
          val marker = u8(offset++)
          require(marker !in setOf(0, 216, 217) && marker !in 208..215) { "Unexpected JPEG marker" }
          require(offset + 2 <= bytes.size)
          val size = be16(offset)
          require(size >= 2 && size <= bytes.size - offset) { "Truncated JPEG segment" }
          if (marker in 192..207 && marker !in setOf(196, 200, 204)) {
            require(size >= 8) { "Invalid JPEG frame" }
            dimensions(be16(offset + 5), be16(offset + 3))
            frame = true
          }
          if (marker == 218) {
            require(frame) { "JPEG scan has no frame" }
            scan = true
          }
          offset += size
        }
        require(scan && (offset until bytes.size - 1).any { u8(it) == 255 && u8(it + 1) == 217 }) {
          "Incomplete JPEG image"
        }
      }
      String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> {
        val end = le(4, 4) + 8
        require(end in 12L..bytes.size.toLong()) { "Invalid WebP container size" }
        var offset = 12
        var image = false
        while (offset.toLong() < end) {
          require(offset + 8L <= end) { "Truncated WebP chunk" }
          val type = String(bytes, offset, 4, Charsets.US_ASCII)
          val size = le(offset + 4, 4)
          val payload = offset + 8
          require(size <= end - payload) { "Truncated WebP payload" }
          when (type) {
            "VP8X" -> {
              require(size == 10L)
              dimensions((le(payload + 4, 3) + 1).toInt(), (le(payload + 7, 3) + 1).toInt())
            }
            "VP8 " -> {
              require(size >= 10 && u8(payload + 3) == 157 && u8(payload + 4) == 1 && u8(payload + 5) == 42)
              dimensions(le(payload + 6, 2).toInt() and 16383, le(payload + 8, 2).toInt() and 16383)
              image = true
            }
            "VP8L" -> {
              require(size >= 5 && u8(payload) == 47)
              val packed = le(payload + 1, 4)
              dimensions(((packed and 16383) + 1).toInt(), (((packed shr 14) and 16383) + 1).toInt())
              image = true
            }
          }
          offset = (payload + size + (size and 1)).toInt()
        }
        require(offset.toLong() == end && image) { "Incomplete WebP image" }
      }
      else -> error("Unsupported native image signature")
    }
  }

  internal data class XmlElement(val name: String, val attributes: Map<String, String>, val depth: Int)
  internal data class Xml(
    val root: String,
    val variables: Map<String, String>,
    val elements: List<XmlElement>,
  )

  fun xml(bytes: ByteArray): Xml {
    require(bytes.size <= MAX_XML)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
      .removePrefix("\ufeff")
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "DTD is forbidden" }
    var root = ""
    var depth = 0
    var nodes = 0
    val variables = linkedMapOf<String, String>()
    val elements = ArrayList<XmlElement>()
    val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
    val handler = object : DefaultHandler() {
      override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
        throw SAXException("External XML resources are forbidden")
      override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        require(++nodes <= 100_000 && ++depth <= 64 && attributes.length <= 128) { "XML limit exceeded" }
        val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
        elements += XmlElement(name, (0 until attributes.length).associate {
          attributes.getQName(it) to attributes.getValue(it)
        }, depth)
        if (depth == 1) root = name
        if (name == "Variable") {
          val key = attributes.getValue("name")
          val value = attributes.getValue("value")
          if (key != null && value != null) require(variables.put(key, value) == null) { "Duplicate XML variable" }
        }
      }
      override fun endElement(uri: String?, localName: String?, qName: String?) { depth-- }
      override fun error(e: org.xml.sax.SAXParseException) { throw e }
      override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
    }
    parser.contentHandler = handler
    parser.entityResolver = handler
    parser.errorHandler = handler
    parser.parse(InputSource(StringReader(text)))
    require(root.isNotEmpty())
    return Xml(root, variables, elements)
  }

  /** Use the returned plan: invalid dynamic groups may fall back to their validated static ID. */
  fun validate(plan: PerfectIconPlan, read: (PerfectIconBlob) -> ByteArray): PerfectIconPlan {
    fun validateStaticPng(path: String, blob: PerfectIconBlob) {
      safePath(path)
      try {
        raster(read(blob))
      } catch (failure: Exception) {
        if (failure is CancellationException || failure is InterruptedException) throw failure
        throw IOException("Invalid static PNG $path (source ${blob.source}): ${failure.message}", failure)
      }
    }
    plan.staticIcons.forEach { (id, entry) ->
      entry.flat?.let { validateStaticPng("res/drawable-xxhdpi/$id.png", it) }
      entry.layers?.let {
        validateStaticPng("res/drawable-xxhdpi/$id/0.png", it.background)
        validateStaticPng("res/drawable-xxhdpi/$id/1.png", it.foreground)
      }
    }
    val staticIds = plan.staticIcons.keys.toSortedSet()
    val dynamicIds = sortedSetOf<String>()
    val keptGroups = sortedMapOf<String, DynamicIconGroup>()
    val rejected = plan.rejectedDynamic.toSortedMap()
    plan.dynamicGroups.forEach { (name, group) ->
      group.resources.keys.forEach { relative -> safePath("layer_animating_icons/$name/$relative") }
      try {
        val rendering = PerfectDynamicIconValidator.validate(
          group.resources, preserveNativeWeather = name == "com.miui.weather2", read = read,
        )
        when {
          rendering == PerfectDynamicIconValidator.Rendering.QUIET -> {
            keptGroups[name] = group.copy(quietOnly = true)
            staticIds += name
          }
          rendering == PerfectDynamicIconValidator.Rendering.OTHER_PROVIDER_BACKED_FANCY && name in plan.staticIcons -> {
            rejected[name] = "external content provider is launch-sensitive; kept static fallback"
          }
          else -> {
            keptGroups[name] = group.copy(quietOnly = false)
            dynamicIds += name
          }
        }
      } catch (failure: Exception) {
        if (failure is CancellationException || failure is InterruptedException) throw failure
        if (name !in plan.staticIcons) throw IOException("Invalid dynamic icon $name: ${failure.message}", failure)
        rejected[name] = failure.message ?: failure.javaClass.simpleName
      }
    }
    staticIds.removeAll(dynamicIds)
    return PerfectIconPlan(plan.staticIcons, keptGroups, staticIds, dynamicIds, rejected)
  }

}
