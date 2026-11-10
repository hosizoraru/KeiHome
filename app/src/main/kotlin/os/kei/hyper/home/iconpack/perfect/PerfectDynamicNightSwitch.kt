package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeLayerConfig

/** Bounded live selectors that keep the native foreground animation owners and events. */
internal object PerfectDynamicNightSwitch {
  private const val NAMESPACE = "keihome_night"
  private const val MAX_BYTES = 32L * 1024 * 1024

  fun systemFiles(
    group: PerfectDynamicNight.Group,
    originals: Map<String, ByteArray>,
    night: Map<String, ByteArray>,
  ): Map<String, ByteArray>? = try {
    convert(group, originals, night)
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    null
  }

  private fun convert(
    group: PerfectDynamicNight.Group,
    originals: Map<String, ByteArray>,
    night: Map<String, ByteArray>,
  ): Map<String, ByteArray>? {
    if (originals.keys != group.files.keys || originals.size > 256 ||
      night.keys != group.foreground + group.background ||
      originals.values.sumOf { it.size.toLong() } > MAX_BYTES ||
      night.values.sumOf { it.size.toLong() } > MAX_BYTES) return null
    for ((path, bytes) in originals.entries + night.entries) {
      PerfectIconFormat.safePath(path)
      if (bytes.isEmpty() || bytes.size > PerfectIconFormat.MAX_BLOB ||
        path.split('/').any { it.equals(NAMESPACE, true) }) return null
    }
    // Recheck the bytes being rewritten, rather than trusting a stale or manually constructed Group.
    val files = originals.mapValues { (path, bytes) ->
      PerfectIconBlob(path, path, bytes.size.toLong())
    }
    val admitted = PerfectDynamicNight.group(files, { originals.getValue(it.path) }) ?: return null
    if (admitted.background != group.background || admitted.foreground != group.foreground) return null
    val layers = NativeLayerConfig.parse(originals.getValue("config.xml"), IconPackLimits())
    val backgroundDirectory = layers.layers.first()
    val fancy = "${layers.layers.last()}/fancy"
    val manifestPath = "$fancy/manifest.xml"
    if (originals.keys.filter { it.endsWith("/manifest.xml") } != listOf(manifestPath)) return null
    val fancyImages = group.foreground.filter { it.startsWith("$fancy/") }.toSet()
    if (fancyImages.isEmpty() || group.foreground.any {
        !it.startsWith("$fancy/") && it != "${layers.layers.last()}/quiet/quietImage.png"
      }) return null
    for (path in group.foreground + group.background) {
      PerfectIconFormat.raster(originals.getValue(path))
      PerfectIconFormat.png(night.getValue(path), square = false)
      val (imageWidth, imageHeight) = dimensions(night.getValue(path))
      if (imageWidth.toLong() * imageHeight > 4 * 1024 * 1024) return null
    }
    val (width, height) = dimensions(night.getValue(group.background))

    val foregroundDocument = parse(originals.getValue(manifestPath))
    val root = foregroundDocument.documentElement
    if (root.tagName != "Icon") return null
    // An omitted list invokes engine defaults; preserve it rather than guessing those defaults.
    // The documented implicit updater is DateTime.Minute. Preserve it when
    // making DarkMode explicit; "none" is the author's explicit opt-out.
    val updater = root.getAttribute("useVariableUpdater").ifBlank { "DateTime.Minute" }
    val updaters = updater.split(',').map { it.trim() }
      .filter { it.isNotEmpty() && !it.equals("none", true) }
    if (updaters.any { it.equals("DarkMode", true) }) return null
    root.setAttribute("useVariableUpdater", (updaters + "DarkMode").joinToString(","))
    val images = foregroundDocument.getElementsByTagName("Image")
    if (images.length == 0) return null
    val referenced = mutableSetOf<String>()
    for (index in 0 until images.length) {
      val image = images.item(index) as Element
      if (listOf("srcExp", "srcid", "srcidExp").any(image::hasAttribute)) return null
      val source = image.getAttribute("src")
      if (source.isEmpty() || !source.matches(Regex("[A-Za-z0-9_./-]+"))) return null
      PerfectIconFormat.safePath(source)
      val path = "$fancy/$source"
      if (path !in fancyImages) return null
      referenced += path
      image.setAttribute("srcExp", "ifelse(#__darkmode,'$NAMESPACE/$source','$source')")
    }
    // Unreferenced pictures could be used by a command that mutates src, or belong to another role.
    if (referenced != fancyImages) return null
    val commands = foregroundDocument.getElementsByTagName("*")
    for (index in 0 until commands.length) {
      val element = commands.item(index) as Element
      if (element.tagName != "Image" &&
        listOf("src", "srcExp", "srcid", "srcidExp").any(element::hasAttribute)) return null
      if (element.tagName in setOf("VariableCommand", "FolmeState", "FolmeCommand") &&
        listOf("property", "target", "name").any {
          element.getAttribute(it).split('.').last() in setOf("src", "srcExp", "srcid", "srcidExp")
        }) return null
    }

    val config = parse(originals.getValue("config.xml"))
    val variables = config.getElementsByTagName("Variables")
    if (variables.length != 1) return null
    val container = variables.item(0) as Element
    fun setFlag(name: String, value: String) {
      val entries = container.getElementsByTagName("Variable")
      val existing = (0 until entries.length).map { entries.item(it) as Element }
        .singleOrNull { it.getAttribute("name") == name }
      val variable = existing ?: config.createElement("Variable").also {
        it.setAttribute("name", name)
        it.setAttribute("type", "string")
        container.appendChild(it)
      }
      variable.setAttribute("value", value)
    }
    // Flag suffixes are config slots, not directory names.
    setFlag("layer_only_quiet0", "false")
    setFlag("layer_only_fancy0", "true")
    val configBytes = serialize(config)
    val updatedLayers = NativeLayerConfig.parse(configBytes, IconPackLimits())
    if (backgroundDirectory !in updatedLayers.fancyOnly ||
      backgroundDirectory in updatedLayers.quietOnly) return null

    val output = originals.toMutableMap()
    fun add(path: String, bytes: ByteArray) {
      require(path !in output) { "Dynamic selector path collision" }
      output[path] = bytes
    }
    for (path in fancyImages) {
      add("$fancy/$NAMESPACE/${path.removePrefix("$fancy/")}", night.getValue(path))
    }
    val backgroundFancy = "$backgroundDirectory/fancy"
    add("$backgroundFancy/day.png", originals.getValue(group.background))
    add("$backgroundFancy/night.png", night.getValue(group.background))
    val background = newDocument()
    val icon = background.createElement("Icon")
    background.appendChild(icon)
    mapOf(
      "version" to "1", "width" to "$width", "height" to "$height",
      "frameRate" to "0", "useVariableUpdater" to "DarkMode",
    ).forEach { (name, value) -> icon.setAttribute(name, value) }
    for ((source, visibility) in listOf("day.png" to "not(#__darkmode)", "night.png" to "#__darkmode")) {
      icon.appendChild(background.createElement("Image").apply {
        mapOf("x" to "0", "y" to "0", "w" to "$width", "h" to "$height",
          "src" to source, "visibility" to visibility).forEach { (name, value) -> setAttribute(name, value) }
      })
    }
    add("$backgroundFancy/manifest.xml", serialize(background))
    output[manifestPath] = serialize(foregroundDocument)
    output["config.xml"] = configBytes
    if (output.size > 256 || output.values.sumOf { it.size.toLong() } > MAX_BYTES * 2) return null
    return output
  }

  private fun dimensions(png: ByteArray): Pair<Int, Int> =
    ByteBuffer.wrap(png, 16, 8).let { it.int to it.int }

  private fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    isValidating = false
    isExpandEntityReferences = false
    // Android's Harmony factory rejects these flags. parse() forbids DTDs in the exact
    // decoded character stream before DOM sees it; supported flags add defense on the JVM.
    for ((feature, enabled) in listOf(
      XMLConstants.FEATURE_SECURE_PROCESSING to true,
      "http://apache.org/xml/features/disallow-doctype-decl" to true,
      "http://xml.org/sax/features/external-general-entities" to false,
      "http://xml.org/sax/features/external-parameter-entities" to false,
      "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )) {
      try {
        setFeature(feature, enabled)
      } catch (_: ParserConfigurationException) {
        // Security does not depend on optional factory features.
      }
    }
  }

  private fun parse(bytes: ByteArray): Document {
    require(bytes.size <= PerfectIconFormat.MAX_XML)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
      .removePrefix("\ufeff")
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text))
    val builder = factory().newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw SAXException("External entities are disabled") }
    builder.setErrorHandler(object : DefaultHandler() {
      override fun error(error: SAXParseException) { throw error }
      override fun fatalError(error: SAXParseException) { throw error }
    })
    return builder.parse(InputSource(StringReader(text))).also { document ->
      require(document.doctype == null)
      val elements = document.getElementsByTagName("*")
      for (index in 0 until elements.length) {
        val element = elements.item(index) as Element
        require(element.namespaceURI == null && element.prefix == null)
        require((0 until element.attributes.length).none {
          element.attributes.item(it).nodeName.startsWith("xmlns")
        })
      }
    }
  }

  private fun newDocument(): Document = factory().newDocumentBuilder().newDocument()

  private fun serialize(document: Document): ByteArray {
    val transformer = TransformerFactory.newInstance().apply {
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }.newTransformer().apply {
      setOutputProperty(OutputKeys.ENCODING, "UTF-8")
      setOutputProperty(OutputKeys.INDENT, "no")
    }
    return ByteArrayOutputStream().also {
      transformer.transform(DOMSource(document), StreamResult(it))
      require(it.size() <= PerfectIconFormat.MAX_XML)
    }.toByteArray()
  }
}
