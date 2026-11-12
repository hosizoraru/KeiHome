package os.kei.hyper.home.iconpack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Fill absent framework contracts; authored values, including explicit false, win. */
internal object NativeIconShapeConfig {
  const val PATH = "transform_config.xml"
  const val MAX_BYTES = 16 * 1024
  private const val LAYERS = "SupportLayerIcon"
  private const val MASK = "ConfigIconMask"
  private const val DYNAMIC = "UseDynamicIcon"
  private val legacyAsset = Regex(
    "(?:nightmode/)?(?:res/drawable(?:-[A-Za-z0-9]+)*/)?icon_(?:mask|pattern|border|background)(?:\\.9)?\\.png")

  fun hasLegacyStyle(names: Sequence<String>): Boolean = names.any(legacyAsset::matches)

  fun complete(original: ByteArray?, systemMask: String, customMask: String? = null): ByteArray? {
    require(systemMask.length in 1..4096)
    customMask?.let { generated(it) }
    if (original == null) return generated(customMask ?: systemMask)
    return try {
      val names = linkedSetOf<String>()
      var authoredTransform = false
      BoundedPackXml.read(original, IconPackLimits(maxXmlBytes = MAX_BYTES, maxMappings = 256),
        start = { name, attrs, depth ->
          if (depth == 1) {
            require(name == "IconTransform")
            if (attrs.isNotEmpty()) authoredTransform = true
          }
          if (depth == 2 && name == "Config") {
            attrs["name"]?.let { require(names.add(it)) { "Ambiguous icon configuration" } }
            if (attrs.keys != setOf("name", "value") || attrs["name"] !in setOf(LAYERS, DYNAMIC, MASK)) {
              authoredTransform = true
            }
          } else if (depth > 1) {
            authoredTransform = true
          }
        })
      // Full-mask mode bypasses the legacy matrix/filter compositor, so never opt an
      // authored transform program into that path merely because its mask is absent.
      if (authoredTransform) return original
      if (LAYERS in names && MASK in names && DYNAMIC in names && customMask == null) return original
      val document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
      }.newDocumentBuilder().parse(ByteArrayInputStream(original))
      val root = document.documentElement
      require(root.nodeName == "IconTransform" && root.namespaceURI.isNullOrEmpty())
      if (customMask != null && MASK in names) {
        val configs = root.getElementsByTagName("Config")
        for (i in 0 until configs.length) {
          val element = configs.item(i) as org.w3c.dom.Element
          if (element.getAttribute("name") == MASK) element.setAttribute("value", customMask)
        }
      }
      for ((name, value) in listOf(LAYERS to "true", DYNAMIC to "true", MASK to (customMask ?: systemMask))) {
        if (name !in names) root.appendChild(document.createElement("Config").apply {
          setAttribute("name", name); setAttribute("value", value)
        })
      }
      ByteArrayOutputStream().also { out ->
        TransformerFactory.newInstance().newTransformer().apply {
          setOutputProperty(OutputKeys.ENCODING, "UTF-8")
          setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }.transform(DOMSource(document), StreamResult(out))
      }.toByteArray().also { require(it.size <= MAX_BYTES) }
    } catch (_: Exception) {
      // Unknown, oversized or malformed authored programs stay byte-for-byte intact.
      original
    }
  }

  /** Only full-mask mode can be reproduced by the shared bitmap preview compositor. */
  fun fullMask(bytes: ByteArray?): String? = try {
    if (bytes == null) null else {
      val values = linkedMapOf<String, String>()
      BoundedPackXml.read(bytes, IconPackLimits(maxXmlBytes = MAX_BYTES, maxMappings = 256),
        start = { name, attrs, depth ->
          if (depth == 1) require(name == "IconTransform")
          if (name == "Config" && attrs["name"] in setOf(LAYERS, MASK)) {
            require(depth == 2 && values.put(requireNotNull(attrs["name"]),
              requireNotNull(attrs["value"])) == null)
          }
        })
      values[MASK]?.takeIf { values[LAYERS] == "true" && it.isNotBlank() }
    }
  } catch (_: Exception) { null }

  fun generated(mask: String): ByteArray {
    require(mask.length in 1..4096 && mask.all {
      it.isDigit() || it in "MmZzLlHhVvCcSsQqTtAaEe.,+- \t\r\n"
    })
    return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
      "<IconTransform><Config name=\"$LAYERS\" value=\"true\"/>" +
      "<Config name=\"$DYNAMIC\" value=\"true\"/>" +
      "<Config name=\"$MASK\" value=\"$mask\"/></IconTransform>").toByteArray(Charsets.UTF_8)
  }

  /** Converted imports may carry only this fixed stock contract, never arbitrary XML. */
  fun validateGenerated(bytes: ByteArray) {
    val values = linkedMapOf<String, String>()
    BoundedPackXml.read(bytes, IconPackLimits(maxXmlBytes = MAX_BYTES, maxMappings = 4),
      start = { name, attrs, depth ->
        if (depth == 1) require(name == "IconTransform" && attrs.isEmpty())
        else {
          require(depth == 2 && name == "Config" && attrs.keys == setOf("name", "value"))
          require(values.put(requireNotNull(attrs["name"]), requireNotNull(attrs["value"])) == null)
        }
      })
    require(values.keys == setOf(LAYERS, DYNAMIC, MASK) && values[LAYERS] == "true" && values[DYNAMIC] == "true")
    require(generated(requireNotNull(values[MASK])).contentEquals(bytes))
  }
}
