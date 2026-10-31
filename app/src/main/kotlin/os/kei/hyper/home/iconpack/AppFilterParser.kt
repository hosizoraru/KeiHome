package os.kei.hyper.home.iconpack

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal data class IconAppFilter(
  val mappings: Map<IconPackComponent, IconDrawableName>,
  val directives: List<IconPackDirective>,
  val notices: Set<IconPackNotice>,
)

/** Classic ADW/Nova/Candybar appfilter format, including the resource-form used by Lawnicons. */
internal class AppFilterParser(private val limits: IconPackLimits = IconPackLimits()) {
  fun parse(
    bytes: ByteArray,
    mode: IconNightMode = IconNightMode.ANY,
    checkCancelled: () -> Unit = {},
  ): IconAppFilter {
    val collector = Collector(limits, mode)
    BoundedPackXml.read(bytes, limits, checkCancelled, start = { name, attributes, depth ->
      if (depth == 1 && name != "resources") throw IconPackException("Expected appfilter resources root")
      if (depth == 2) collector.tag(name, attributes)
    })
    return collector.finish()
  }

  /** Shared with XmlResourceParser so compiled APK XML follows exactly the same component rules. */
  class Collector(private val limits: IconPackLimits, private val mode: IconNightMode) {
    private val mappings = linkedMapOf<IconPackComponent, IconDrawableName>()
    private val directives = mutableListOf<IconPackDirective>()
    private val notices = linkedSetOf<IconPackNotice>()
    private var items = 0

    fun tag(name: String, attributes: Map<String, String>) {
      if (++items > limits.maxMappings) throw IconPackException("Appfilter item limit exceeded")
      if (name == "item") {
        val raw = attributes["component"].orEmpty()
        if (raw.startsWith(':')) {
          notices += IconPackNotice.LAUNCHER_SHORTCUTS
          directives += IconPackDirective(name, attributes.toMap(), mode)
          return
        }
        val component = IconPackComponent.parse(raw)
        val drawable = attributes["drawable"]?.let(IconDrawableName::parse)
        if (component == null || drawable == null) {
          notices += IconPackNotice.INVALID_FILTER_ITEMS
          return
        }
        val previous = mappings.put(component, drawable)
        if (previous != null && previous != drawable) notices += IconPackNotice.CONFLICTING_MAPPINGS
      } else if (name in setOf("calendar", "dynamic-clock", "iconback", "iconmask", "iconupon", "scale")) {
        directives += IconPackDirective(name, attributes.toMap(), mode)
        notices += if (name == "calendar" || name == "dynamic-clock") IconPackNotice.DYNAMIC_FILTER_DIRECTIVES
        else IconPackNotice.FALLBACK_MASK_DIRECTIVES
      }
    }

    fun finish() = IconAppFilter(mappings.toMap(), directives.toList(), notices.toSet())
  }
}

/** Decode before rejecting DTDs (also covers UTF-16), with no external entity or file access. */
internal object BoundedPackXml {
  fun read(
    bytes: ByteArray,
    limits: IconPackLimits,
    checkCancelled: () -> Unit = {},
    start: (String, Map<String, String>, Int) -> Unit = { _, _, _ -> },
    text: (String, Int) -> Unit = { _, _ -> },
    end: (String, Int) -> Unit = { _, _ -> },
  ) {
    if (bytes.isEmpty() || bytes.size > limits.maxXmlBytes) throw IconPackException("XML size limit exceeded")
    try {
      val encoding = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
      }
      val decoded = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
      if ("<!DOCTYPE" in decoded || "<!ENTITY" in decoded || '\u0000' in decoded) {
        throw IconPackException("DTD/entity declarations are not supported")
      }
      val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
      val handler = object : DefaultHandler() {
        var depth = 0
        var elements = 0
        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
          checkCancelled()
          if (++depth > 16 || ++elements > limits.maxMappings * 3L || attrs.length > 32 || qName.length > 128) {
            throw IconPackException("XML structure limit exceeded")
          }
          val values = linkedMapOf<String, String>()
          for (i in 0 until attrs.length) {
            if (attrs.getQName(i).length > 128 || attrs.getValue(i).length > 4096) {
              throw IconPackException("XML attribute limit exceeded")
            }
            values[attrs.getQName(i)] = attrs.getValue(i)
          }
          start(qName, values, depth)
        }
        override fun characters(ch: CharArray, offset: Int, length: Int) { text(String(ch, offset, length), depth) }
        override fun endElement(uri: String?, localName: String?, qName: String) { end(qName, depth); depth-- }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entity")
        override fun error(e: org.xml.sax.SAXParseException) { throw e }
        override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
      }
      reader.contentHandler = handler
      reader.entityResolver = handler
      reader.errorHandler = handler
      reader.parse(InputSource(StringReader(decoded)))
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: IconPackException) { throw failure
    } catch (failure: Exception) { throw IconPackException("Invalid icon-pack XML", failure) }
  }
}
