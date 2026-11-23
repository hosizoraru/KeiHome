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
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram

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
      night.keys != group.nightPaths ||
      originals.values.sumOf { it.size.toLong() } > MAX_BYTES ||
      night.values.sumOf { it.size.toLong() } > MAX_BYTES) return null
    for ((path, bytes) in originals.entries + night.entries) {
      PerfectIconFormat.safePath(path)
      if (bytes.isEmpty() || bytes.size > PerfectIconFormat.MAX_BLOB ||
        path.split('/').any { it.equals(NAMESPACE, true) }) return null
    }
    // Recheck the bytes being rewritten, rather than trusting a stale or manually constructed Group.
    val files = originals.mapValues { (path, bytes) ->
      PerfectIconBlob(path, PerfectIconFormat.sha(bytes), bytes.size.toLong())
    }
    val admitted = PerfectDynamicNight.group(files, { originals.getValue(it.path) }) ?: return null
    if (admitted.background != group.background || admitted.foreground != group.foreground ||
      admitted.bgAssets != group.bgAssets || admitted.compositeQuiet != group.compositeQuiet ||
      admitted.compositeQuietLayers != group.compositeQuietLayers ||
      admitted.compositeQuietProofLayers != group.compositeQuietProofLayers) return null
    val availableLayers = NativeLayerConfig.availableLayerDirectories("", originals.keys)
    val layers = NativeLayerConfig.parse(
      originals.getValue("config.xml"),
      IconPackLimits(),
      availableLayers = availableLayers,
    )
    val backgroundDirectory = layers.layers.first()
    for (path in group.nightPaths) {
      PerfectIconFormat.raster(originals.getValue(path))
      PerfectIconFormat.png(night.getValue(path), square = false)
      val (imageWidth, imageHeight) = dimensions(night.getValue(path))
      if (imageWidth.toLong() * imageHeight > 4 * 1024 * 1024) return null
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
    val output = originals.toMutableMap()
    fun add(path: String, bytes: ByteArray) {
      require(path !in output) { "Dynamic selector path collision" }
      output[path] = bytes
    }
    for ((slot, directory) in layers.layers.withIndex()) {
      val fancy = "$directory/fancy"
      val manifestPath = "$fancy/manifest.xml"
      val fancyImages = group.nightPaths.filterTo(linkedSetOf()) { it.startsWith("$fancy/") }
      val manifest = originals[manifestPath]
      if (manifest == null) {
        val quiet = "$directory/quiet/quietImage.png"
        if (directory !in layers.quietOnly || quiet !in group.nightPaths) return null
        add("$fancy/day.png", originals.getValue(quiet))
        add("$fancy/night.png", night.getValue(quiet))
        add(manifestPath, quietSelector(night.getValue(quiet)))
        setFlag("layer_only_quiet$slot", "false")
        setFlag("layer_only_fancy$slot", "true")
        continue
      }
      val weather = NativeWeatherLayerProgram.darkVariant(manifest, "$fancy/", originals.keys)
      if (weather != null) {
        if (NativeWeatherLayerProgram.resources(manifest, "$fancy/", originals.keys) != fancyImages) return null
        val namespacePaths = fancyImages.mapTo(linkedSetOf()) {
          "$fancy/$NAMESPACE/${it.removePrefix("$fancy/")}"
        }
        for (path in fancyImages)
          add("$fancy/$NAMESPACE/${path.removePrefix("$fancy/")}", night.getValue(path))
        if (NativeWeatherLayerProgram.resources(weather, "$fancy/", output.keys) !=
          fancyImages + namespacePaths) return null
        output[manifestPath] = weather
        continue
      }
      val document = parse(manifest)
      val root = document.documentElement
      if (root.tagName != "Icon") return null
      // Explicitly retain the implicit minute updater and every authored time updater.
      val updater = root.getAttribute("useVariableUpdater").ifBlank { "DateTime.Minute" }
      val updaters = updater.split(',').map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals("none", true) }
      if (updaters.any { it.equals("DarkMode", true) }) return null
      root.setAttribute("useVariableUpdater", (updaters + "DarkMode").joinToString(","))
      val texts = document.getElementsByTagName("Text")
      for (index in 0 until texts.length) {
        val text = texts.item(index) as Element
        // Keep the native Text, including its name, metrics variables and command targets.
        val color = text.getAttribute("color")
        PerfectDynamicIconValidator.nightTextColor(color)?.let {
          // Folme Text reads its property wrapper instead of ColorParser on each draw.
          if (text.getAttribute("folmeMode").equals("true", true)) return null
          text.setAttribute("color", it)
        }
        if (text.hasAttribute("color") &&
          !PerfectDynamicIconValidator.validLiveTextColor(text.getAttribute("color"), true)) return null
      }
      val images = document.getElementsByTagName("Image")
      if (images.length == 0 && document.getElementsByTagName("Text").length == 0) return null
      val referenced = mutableSetOf<String>()
      for (index in 0 until images.length) {
        val image = images.item(index) as Element
        if (listOf("srcExp", "srcidExp").any(image::hasAttribute)) return null
        val source = image.getAttribute("src")
        if (source.isEmpty() || !source.matches(Regex("[A-Za-z0-9_./-]+"))) return null
        PerfectIconFormat.safePath(source)
        if (image.hasAttribute("srcid")) {
          // MAML appends _<srcid> before the extension after evaluating srcExp.
          // Only the complete calendar day series has a bounded, proven closure.
          if (image.getAttribute("srcid") != "#date" || !source.endsWith(".png")) return null
          val frames = (1..31).map { "$fancy/${source.removeSuffix(".png")}_$it.png" }
          if (!fancyImages.containsAll(frames)) return null
          referenced += frames
        } else {
          val path = "$fancy/$source"
          if (path !in fancyImages) return null
          referenced += path
        }
        image.setAttribute("srcExp", "ifelse(#__darkmode,'$NAMESPACE/$source','$source')")
      }
      if (referenced != fancyImages) return null
      val commands = document.getElementsByTagName("*")
      for (index in 0 until commands.length) {
        val element = commands.item(index) as Element
        if (element.tagName != "Image" &&
          listOf("src", "srcExp", "srcid", "srcidExp").any(element::hasAttribute)) return null
        if ((element.tagName.endsWith("Command") || element.tagName in setOf("FolmeState", "Var", "Variable")) &&
          listOf("property", "target", "name", "propertyName", "attribute").any {
            element.getAttribute(it).split('.').last() in setOf("src", "srcExp", "srcid", "srcidExp")
          }) return null
      }
      for (path in fancyImages)
        add("$fancy/$NAMESPACE/${path.removePrefix("$fancy/")}", night.getValue(path))
      output[manifestPath] = serialize(document)
      if (directory == backgroundDirectory) {
        // Activate the original single-image background program, now with its selector.
        setFlag("layer_only_quiet0", "false")
        setFlag("layer_only_fancy0", "true")
      }
    }
    val configBytes = serialize(config)
    val updatedLayers = NativeLayerConfig.parse(
      configBytes,
      IconPackLimits(),
      availableLayers = availableLayers,
    )
    if (backgroundDirectory !in updatedLayers.fancyOnly ||
      backgroundDirectory in updatedLayers.quietOnly) return null
    output["config.xml"] = configBytes
    if (output.size > 256 || output.values.sumOf { it.size.toLong() } > MAX_BYTES * 2) return null
    return output
  }

  private fun quietSelector(night: ByteArray): ByteArray {
    val (width, height) = dimensions(night)
    val document = newDocument()
    val icon = document.createElement("Icon")
    document.appendChild(icon)
    mapOf(
      "version" to "1", "width" to "$width", "height" to "$height",
      "frameRate" to "0", "useVariableUpdater" to "DarkMode",
    ).forEach { (name, value) -> icon.setAttribute(name, value) }
    for ((source, visibility) in listOf("day.png" to "not(#__darkmode)", "night.png" to "#__darkmode")) {
      icon.appendChild(document.createElement("Image").apply {
        mapOf("x" to "0", "y" to "0", "w" to "$width", "h" to "$height",
          "src" to source, "visibility" to visibility).forEach { (name, value) -> setAttribute(name, value) }
      })
    }
    return serialize(document)
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
