package os.kei.hyper.home.iconpack

/** Exact weather display schemas; existing provider and refresh capabilities stay fixed. */
internal object NativeWeatherLayerProgram {
  private val limits = IconPackLimits(maxXmlBytes = 16 * 1024, maxMappings = 32)
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
  private val numericProgram = program.copy(
    children = program.children.filterNot { it.tag == "Image" },
  )

  private data class Boundary(val tag: String, val attributes: Map<String, String>?, val depth: Int)

  private fun boundaries(root: Element) = buildList<Boundary> {
    fun append(node: Element, depth: Int) {
      add(Boundary(node.tag, node.attributes, depth))
      node.children.forEach { append(it, depth + 1) }
      add(Boundary(node.tag, null, depth))
    }
    append(root, 1)
  }
  private val expected = boundaries(program)
  private val numericExpected = boundaries(numericProgram)

  private val weatherGlobalProgram = element(
    "Icon",
    "version" to "1", "frameRate" to "0", "width" to "351", "height" to "351",
    "screenWidth" to "1080", "useVariableUpdater" to "DateTime.Minute",
    children = listOf(
      element("VariableBinders", children = listOf(
        weatherGlobalBinder(
          "weather_provider", "content://weather/actualWeatherData/1",
        ),
        weatherGlobalBinder("weather_provider2", "content://weather/weather"),
      )),
      element(
        "Var", "name" to "weather_temperature",
        "expression" to "ifelse(#weather_publish_time1,#weather_temperature1,#weather_temperature2)",
      ),
      element(
        "Var", "name" to "weather_publish_time",
        "expression" to "max(#weather_publish_time1,#weather_publish_time2)",
      ),
      element("ExternalCommands", children = listOf(
        element("Trigger", "action" to "init,resume", children = listOf(
          element("BinderCommand", "name" to "weather_provider", "command" to "refresh"),
          element("BinderCommand", "name" to "weather_provider2", "command" to "refresh"),
        )),
      )),
      element(
        "Group", "visibility" to "#weather_publish_time!=0",
        children = listOf(element(
          "ImageChars",
          "x" to "351/2-84", "y" to "351/2-102",
          "src" to "num.png", "string" to "#weather_temperature+'°'",
        )),
      ),
    ),
  )

  private fun weatherGlobalBinder(name: String, uri: String) = element(
    "ContentProviderBinder",
    "name" to name,
    "uri" to uri,
    "columns" to "city_name,weather_type,temperature,city_id,aqilevel,publish_time",
    "countName" to "hasweather",
    children = listOf(
      element("Variable", "name" to "${if (name.endsWith("2")) "weather_location2" else "weather_location"}",
        "type" to "string", "column" to "city_name"),
      element("Variable", "name" to "${if (name.endsWith("2")) "weather_id2" else "weather_id"}",
        "type" to "int", "column" to "weather_type"),
      element("Variable", "name" to "${if (name.endsWith("2")) "weather_temperature2" else "weather_temperature1"}",
        "type" to "int", "column" to "temperature"),
      element("Variable", "name" to "${if (name.endsWith("2")) "weather_temperature_str2" else "weather_temperature_str"}",
        "type" to "string", "column" to "temperature"),
      element("Variable", "name" to "${if (name.endsWith("2")) "temperature_unit2" else "temperature_unit"}",
        "type" to "int", "column" to "temperature_unit"),
      element("Variable", "name" to "${if (name.endsWith("2")) "city_id2" else "city_id"}",
        "type" to "string", "column" to "city_id"),
      element("Variable", "name" to "${if (name.endsWith("2")) "aqi2" else "aqi"}",
        "type" to "int", "column" to "aqilevel"),
      element("Variable", "name" to "${if (name.endsWith("2")) "weather_publish_time2" else "weather_publish_time1"}",
        "type" to "int", "column" to "publish_time"),
    ),
  )

  private fun matches(bytes: ByteArray, expected: List<Boundary>): Boolean {
    var cursor = 0
    return try {
      BoundedPackXml.read(bytes, limits, start = { tag, attributes, depth ->
        require(expected.getOrNull(cursor++) == Boundary(tag, attributes, depth))
      }, text = { value, _ ->
        require(value.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' })
      }, end = { tag, depth ->
        require(expected.getOrNull(cursor++) == Boundary(tag, null, depth))
      })
      cursor == expected.size
    } catch (_: IconPackException) {
      false
    }
  }

  private fun visibility(node: Element, dark: Boolean) = node.copy(
    attributes = node.attributes + ("visibility" to if (dark) "#__darkmode" else "!#__darkmode"))

  private val darkProgram = program.copy(
    attributes = program.attributes + ("useVariableUpdater" to "DateTime.Minute,DarkMode"),
    children = program.children.flatMap { node ->
      when (node.tag) {
        "Image" -> listOf(visibility(node, false),
          visibility(node.copy(attributes = node.attributes + ("src" to "keihome_night/bg.png")), true))
        "Group" -> listOf(node.copy(children = listOf(
          visibility(element("Group", children = node.children), false),
          visibility(element("Group", children = node.children.map {
            it.copy(attributes = it.attributes + ("src" to "keihome_night/num.png"))
          }), true),
        )))
        else -> listOf(node)
      }
    },
  )
  private val darkNumericProgram = numericProgram.copy(
    attributes = numericProgram.attributes + ("useVariableUpdater" to "DateTime.Minute,DarkMode"),
    children = numericProgram.children.flatMap { node ->
      if (node.tag != "Group") return@flatMap listOf(node)
      listOf(node.copy(children = listOf(
        visibility(element("Group", children = node.children), false),
        visibility(element("Group", children = node.children.map {
          it.copy(attributes = it.attributes + ("src" to "keihome_night/num.png"))
        }), true),
      )))
    },
  )
  private val darkNumericExpected = boundaries(darkNumericProgram)
  private val darkExpected = boundaries(darkProgram)
  private val globalExpected = boundaries(weatherGlobalProgram)
  private val darkGlobalProgram = weatherGlobalProgram.copy(
    attributes = weatherGlobalProgram.attributes + ("useVariableUpdater" to "DateTime.Minute,DarkMode"),
    children = weatherGlobalProgram.children.map { node ->
      if (node.tag != "Group") node else node.copy(children = listOf(
        visibility(element("Group", children = node.children), false),
        visibility(element("Group", children = node.children.map {
          it.copy(attributes = it.attributes + ("src" to "keihome_night/num.png"))
        }), true),
      ))
    },
  )
  private val darkGlobalExpected = boundaries(darkGlobalProgram)

  /**
   * Returns the complete image closure for this exact program, or null on any mismatch.
   * [base] is an archive-relative directory ending in '/'; callers still validate image bytes.
   */
  fun resources(bytes: ByteArray, base: String, paths: Set<String>): Set<String>? {
    if (base.length > 1024 || !localBase.matches(base) || ".." in base ||
      bytes.isEmpty() || bytes.size > limits.maxXmlBytes) return null
    val required = images.mapTo(linkedSetOf()) { base + it }
    val numericRequired = required - (base + "bg.png")
    val sourceResources: Set<String>
    val sourceSchemas: List<List<Boundary>>
    val darkSchemas: List<List<Boundary>>
    if (paths.containsAll(required)) {
      sourceResources = required
      sourceSchemas = listOf(expected)
      darkSchemas = listOf(darkExpected)
    } else if (paths.containsAll(numericRequired)) {
      sourceResources = numericRequired
      sourceSchemas = listOf(numericExpected, globalExpected)
      darkSchemas = listOf(darkNumericExpected, darkGlobalExpected)
    } else return null

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

    val isSource = sourceSchemas.any { matches(bytes, it) }
    val isDark = darkSchemas.any { matches(bytes, it) }
    if (!isSource && !isDark) return null
    val darkResources = sourceResources.mapTo(linkedSetOf()) {
      base + "keihome_night/" + it.removePrefix(base)
    }
    return if (isDark && paths.containsAll(darkResources)) {
      sourceResources + darkResources
    } else if (isDark) null else sourceResources
  }

  /** Rewrites only the exact original display tree; provider queries and event timing are unchanged. */
  fun darkVariant(bytes: ByteArray, base: String, paths: Set<String>): ByteArray? {
    val required = resources(bytes, base, paths) ?: return null
    if (required.any { it.startsWith(base + "keihome_night/") }) return null
    val numeric = base + "bg.png" !in required
    val global = numeric && matches(bytes, globalExpected)
    fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
      .replace("<", "&lt;").replace(">", "&gt;")
    val xml = buildString {
      append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
      fun appendElement(node: Element) {
        append('<').append(node.tag)
        node.attributes.forEach { (key, value) ->
          append(' ').append(key).append("=\"").append(escape(value)).append('"')
        }
        if (node.children.isEmpty()) append("/>") else {
          append('>')
          node.children.forEach(::appendElement)
          append("</").append(node.tag).append('>')
        }
      }
      appendElement(if (global) darkGlobalProgram else if (numeric) darkNumericProgram else darkProgram)
    }
    return xml.toByteArray(Charsets.UTF_8)
  }
}
