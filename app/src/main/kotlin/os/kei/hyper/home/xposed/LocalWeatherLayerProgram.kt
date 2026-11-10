package os.kei.hyper.home.xposed

import os.kei.hyper.home.iconpack.BoundedPackXml
import os.kei.hyper.home.iconpack.IconPackException
import os.kei.hyper.home.iconpack.IconPackLimits

/** Recognizes one source weather program without querying its provider or rewriting its XML. */
internal object LocalWeatherLayerProgram {
  private val limits = IconPackLimits(maxXmlBytes = 16 * 1024, maxMappings = 16)
  private val localBase = Regex("(?:[A-Za-z0-9_][A-Za-z0-9_.-]*/)+")
  private val comments = Regex("<!--[\\s\\S]*?-->")
  private val declaration = Regex("\\A<\\?xml[ \\t\\r\\n][\\s\\S]*?\\?>")
  private val images = listOf("bg.png") + (0..9).map { "num_$it.png" } +
    listOf("num_-.png", "num_\u00b0.png")

  private data class Element(
    val tag: String,
    val attributes: Map<String, String>,
    val children: List<Element>,
  )

  private fun element(
    tag: String,
    vararg attributes: Pair<String, String>,
    children: List<Element> = emptyList(),
  ) = Element(tag, attributes.toMap(), children)

  // These authored canvas values are part of the source schema, not host display geometry.
  private val program = element(
    "Icon", "version" to "1", "width" to "351", "height" to "351", "frameRate" to "0",
    "useVariableUpdater" to "DateTime.Minute", "screenWidth" to "1080",
    children = listOf(
      element("VariableBinders", children = listOf(
        element(
          "ContentProviderBinder", "name" to "weather_provider",
          "uri" to "content://weather/actualWeatherData/4", "columns" to "temperature",
          "countName" to "hasweather",
          children = listOf(
            element("Variable", "name" to "temperature", "type" to "int", "column" to "temperature"),
            element("Trigger", children = listOf(
              element("VariableCommand", "name" to "success_time", "type" to "number",
                "expression" to "#time_sys"),
            )),
          ),
        ),
      )),
      element("ExternalCommands", children = listOf(
        element("Trigger", "action" to "init,resume", children = listOf(
          element("VariableCommand", "name" to "update_weather_time", "type" to "number",
            "expression" to "#time_sys"),
          element("MultiCommand", "condition" to "(#time_sys - #success_time) } 600000",
            children = listOf(
              element("BinderCommand", "name" to "weather_provider", "command" to "refresh",
                "delay" to "550", "delayCondition" to "(#time_sys - #update_weather_time) } 500"),
            )),
        )),
      )),
      element("Image", "x" to "351 / 2", "y" to "351 / 2", "align" to "center",
        "alignV" to "center", "src" to "bg.png"),
      element("Group", "visibility" to "#hasweather", children = listOf(
        element("ImageChars", "x" to "351 / 2 - 84", "y" to "351 / 2 - 102",
          "src" to "num.png", "string" to "#temperature + '\u00b0'"),
      )),
    ),
  )

  private data class Boundary(val tag: String, val attributes: Map<String, String>?, val depth: Int)

  private val expected = buildList<Boundary> {
    fun append(node: Element, depth: Int) {
      add(Boundary(node.tag, node.attributes, depth))
      node.children.forEach { append(it, depth + 1) }
      add(Boundary(node.tag, null, depth))
    }
    append(program, 1)
  }

  /**
   * Returns the complete image closure for this exact program, or null on any mismatch.
   * [base] is an archive-relative directory ending in '/'; callers still validate image bytes.
   */
  fun resources(bytes: ByteArray, base: String, paths: Set<String>): Set<String>? {
    if (base.length > 1024 || !localBase.matches(base) || ".." in base ||
      bytes.isEmpty() || bytes.size > limits.maxXmlBytes) return null
    val required = images.mapTo(linkedSetOf()) { base + it }
    if (!paths.containsAll(required)) return null

    // BoundedPackXml ignores processing instructions. Only the XML declaration is permitted here.
    // Its strict decoder and XML parser subsequently validate encoding and declaration syntax.
    val encoding = when {
      bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
      bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
      else -> Charsets.UTF_8
    }
    val markup = bytes.toString(encoding).removePrefix("\uFEFF").replace(comments, "")
      .replace(declaration, "")
    if ("<?" in markup) return null

    var cursor = 0
    return try {
      BoundedPackXml.read(bytes, limits, start = { tag, attributes, depth ->
        require(expected.getOrNull(cursor++) == Boundary(tag, attributes, depth))
      }, text = { value, _ ->
        require(value.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' })
      }, end = { tag, depth ->
        require(expected.getOrNull(cursor++) == Boundary(tag, null, depth))
      })
      if (cursor == expected.size) required else null
    } catch (_: IconPackException) {
      null
    }
  }
}
