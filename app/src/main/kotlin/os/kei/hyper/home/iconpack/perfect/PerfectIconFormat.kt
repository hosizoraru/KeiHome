package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal data class PerfectIconBlob(val path: String, val sha: String, val size: Long, val source: String = path)
internal data class PerfectIconPlan(
  val files: Map<String, PerfectIconBlob>,
  val staticIds: Set<String>,
  val dynamicIds: Set<String>,
)

/**
 * Independent implementation of the public resource layout, not PerfectIconsPacker code.
 * https://github.com/YifePlayte/PerfectIconsPacker/blob/main/docs/THEME_FORMAT.md
 * https://github.com/YifePlayte/PerfectIconsPacker/blob/main/docs/CUSTOMIZATION.md
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
  ): PerfectIconPlan {
    fun selected(name: String) = !options.installedOnly || name in installed
    val pairs = staticGroups(base).toMutableMap().apply { putAll(staticGroups(themed)) }
    val files = sortedMapOf<String, PerfectIconBlob>()
    val staticIds = sortedSetOf<String>()
    pairs.filterKeys(::selected).forEach { (name, layers) ->
      staticIds += name
      layers.forEach { files["res/drawable-xxhdpi/$name/${it.path.substringAfter('/')}"] = it }
    }
    // Single-image style resources are independent of the static-layer chain.
    themed.filter { '/' !in it.path && it.path.endsWith(".png") }.forEach {
      val name = it.path.removeSuffix(".png")
      if (id(name) && selected(name)) {
        files["res/drawable-xxhdpi/$name.png"] = it
        staticIds += name
      }
    }
    val dynamicIds = sortedSetOf<String>()
    if (options.dynamic) {
      fun groups(blobs: List<PerfectIconBlob>) = blobs.filter {
        '/' in it.path && id(it.path.substringBefore('/'))
      }.groupBy { it.path.substringBefore('/') }
      val dynamic = groups(commonDynamic).toMutableMap().apply { putAll(groups(themedDynamic)) }
      dynamic.filterKeys(::selected).forEach { (name, resources) ->
        dynamicIds += name
        resources.forEach { files["layer_animating_icons/${it.path}"] = it }
      }
    }
    require(files.size <= MAX_FILES) { "Perfect Icons file limit exceeded" }
    require(files.values.sumOf { it.size } <= MAX_PACK) { "Perfect Icons pack exceeds 512 MiB" }
    return PerfectIconPlan(files, staticIds, dynamicIds)
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
        require(length == 0 && input.remaining() == 0 && data)
        end = true
        break
      }
    }
    require(header && data && end) { "Incomplete PNG" }
  }

  internal data class Xml(val root: String, val variables: Map<String, String>)

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
    val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
    val handler = object : DefaultHandler() {
      override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
        throw SAXException("External XML resources are forbidden")
      override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        require(++nodes <= 100_000 && ++depth <= 64 && attributes.length <= 128) { "XML limit exceeded" }
        val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
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
    return Xml(root, variables)
  }

  fun validate(plan: PerfectIconPlan, read: (PerfectIconBlob) -> ByteArray) {
    plan.files.forEach { (path, blob) ->
      safePath(path)
      if (path.endsWith(".png", true)) png(read(blob), square = path.startsWith("res/"))
    }
    plan.dynamicIds.forEach { name ->
      val prefix = "layer_animating_icons/$name/"
      val resources = plan.files.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
      fun required(path: String) = resources[path] ?: throw IOException("Incomplete dynamic icon $name: missing $path")
      val config = xml(read(required("config.xml")))
      require(config.root == "Config") { "Invalid dynamic config for $name" }
      val layers = resources.keys.map { it.substringBefore('/') }.filter { it.matches(Regex("[0-9]+")) }
        .toSet().map { it.toInt() }.sorted()
      require(layers.isNotEmpty() && layers == layers.indices.toList()) { "Non-contiguous dynamic layers for $name" }
      var fancy = false
      layers.forEach { layer ->
        required("$layer/quiet/quietImage.png")
        require(config.variables["layer$layer"] == layer.toString()) { "Undeclared dynamic layer for $name" }
        if (resources.keys.any { it.startsWith("$layer/fancy/") }) {
          require(xml(read(required("$layer/fancy/manifest.xml"))).root == "Icon")
          fancy = true
        } else require(config.variables["layer_only_quiet$layer"] == "true")
      }
      require(fancy) { "Dynamic icon has no fancy layer: $name" }
    }
  }

  // Field names/root follow THEME_FORMAT.md. Mask data comes from the local stock icons
  // research fixture, not packer code: .artifacts/research/system-icon-fallback/stock-icons.zip.
  fun transform(dynamic: Boolean): ByteArray = """<?xml version="1.0" encoding="UTF-8"?>
<IconTransform>
  <Config name="SupportLayerIcon" value="true"/>
  <Config name="UseDynamicIcon" value="$dynamic"/>
  <Config name="ConfigIconMask" value="M 0 42.7 C 0 27.7 0 20.3 2.9 14.6 C 5.5 9.5 9.5 5.5 14.6 2.9 C 20.3 0 27.7 0 42.7 0 L 57.3 0 C 72.3 0 79.7 0 85.4 2.9 C 90.5 5.5 94.5 9.5 97.1 14.6 C 100 20.3 100 27.7 100 42.7 V 57.3 C 100 72.3 100 79.7 97.1 85.4 C 94.5 90.5 90.5 94.5 85.4 97.1 C 79.7 100 72.3 100 57.3 100 H 42.7 C 27.7 100 20.3 100 14.6 97.1 C 9.5 94.5 5.5 90.5 2.9 85.4 C 0 79.7 0 72.3 0 57.3 L 0 42.7 Z"/>
</IconTransform>
""".toByteArray(Charsets.UTF_8)
}
