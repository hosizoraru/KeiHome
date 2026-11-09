package os.kei.hyper.home.iconpack.perfect

import java.nio.charset.StandardCharsets
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.IconPackLimits

/** Checks local resource closure, independently of the native renderer's MAML execution policy. */
internal object PerfectDynamicIconValidator {
  /** True means at least one declared layer uses fancy resources; quiet-only layers are static. */
  fun validate(resources: Map<String, PerfectIconBlob>, read: (PerfectIconBlob) -> ByteArray): Boolean {
    fun bytes(path: String) = read(requireNotNull(resources[path]) { "Missing dynamic resource: $path" })
    val configBytes = bytes("config.xml")
    val config = PerfectIconFormat.xml(configBytes)
    require(config.root == "Config") { "Invalid dynamic Config root" }
    require(config.elements.all { element ->
      when (element.name) {
        "Config" -> element.depth == 1 && element.attributes.isEmpty()
        "Variables" -> element.depth == 2 && element.attributes.isEmpty()
        "Variable" -> element.depth in 2..3 && element.attributes.keys.all { it in setOf("name", "value", "type") } &&
          element.attributes["type"] in setOf(null, "string")
        else -> false
      }
    } && config.elements.count { it.name == "Variables" } <= 1) { "Invalid dynamic Config structure" }
    // Existing repository fixtures also use direct Variable children. Normalize only the parser
    // input for that envelope; all published bytes remain the original source blobs.
    val parserBytes = if (config.elements.none { it.name == "Variables" }) {
      fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
      ("<Config><Variables>" + config.variables.entries.joinToString("") { (name, value) ->
        "<Variable name=\"${escape(name)}\" value=\"${escape(value)}\" type=\"string\"/>"
      } + "</Variables></Config>").toByteArray()
    } else configBytes
    val mode = try {
      NativeLayerConfig.parse(parserBytes, IconPackLimits(maxXmlBytes = PerfectIconFormat.MAX_XML))
    } catch (failure: Exception) {
      if (failure is CancellationException || failure is InterruptedException) throw failure
      throw IOException("config.xml (source ${resources.getValue("config.xml").source}): " +
        "${failure.message}; variables=${config.variables.toString().take(1024)}", failure)
    }
    val undeclaredResources = resources.keys.filter { '/' in it && !it.startsWith("quiet/") &&
      it.substringBefore('/') !in mode.layers }
    require(undeclaredResources.isEmpty()) {
      "Resource belongs to an undeclared layer: $undeclaredResources"
    }
    resources.keys.forEach(PerfectIconFormat::safePath)
    val rootQuiet = resources["quiet/quietImage.png"]
    var rootQuietChecked = false
    var animated = false
    mode.layers.forEachIndexed { index, layer ->
      // Legacy use_* fields are preserved, without inferring an only-mode from their names.
      val quietOnly = layer in mode.quietOnly
      val fancyOnly = layer in mode.fancyOnly
      require(!quietOnly || !fancyOnly) { "Conflicting layer flags: $index" }
      val quietPath = "$layer/quiet/quietImage.png"
      val quiet = rootQuiet != null || quietPath in resources
      val manifestPath = "$layer/fancy/manifest.xml"
      val fancy = manifestPath in resources
      require((!quietOnly || quiet) && (!fancyOnly || fancy) && (quiet || fancy)) {
        "Missing quiet/fancy resource for declared layer: $layer"
      }
      if (!fancyOnly && quiet) {
        if (rootQuiet != null) {
          if (!rootQuietChecked) resources.filterKeys { it.startsWith("quiet/") && it.endsWith("/quietImage.png") }
            .forEach { (path, blob) -> image(path, blob, read) }
          rootQuietChecked = true
        } else image(quietPath, resources.getValue(quietPath), read)
      }
      // fancy_work is retained as source metadata; it does not replace per-layer mode flags.
      if (!quietOnly && fancy) {
        try {
          manifest(bytes(manifestPath), "$layer/fancy/", resources, read)
        } catch (failure: Exception) {
          if (failure is CancellationException || failure is InterruptedException) throw failure
          throw IOException("$manifestPath (source ${resources.getValue(manifestPath).source}): ${failure.message}", failure)
        }
      }
      if (!quietOnly && fancy) animated = true
    }
    return animated
  }

  private fun image(path: String, blob: PerfectIconBlob, read: (PerfectIconBlob) -> ByteArray) {
    try {
      PerfectIconFormat.raster(read(blob))
    } catch (failure: Exception) {
      if (failure is CancellationException || failure is InterruptedException) throw failure
      throw IOException("Invalid image $path (source ${blob.source}): ${failure.message}", failure)
    }
  }

  private fun manifest(
    bytes: ByteArray,
    base: String,
    resources: Map<String, PerfectIconBlob>,
    read: (PerfectIconBlob) -> ByteArray,
  ) {
    val xml = PerfectIconFormat.xml(bytes)
    require(xml.root in setOf("Icon", "bg", "fg")) { "Invalid fancy root: ${xml.root}" }
    val root = xml.elements.first().attributes
    val densities = root["extraResourcesDensity"].orEmpty().split(',').filter { it.isNotBlank() }.map {
      require(it.matches(Regex("[0-9]{1,5}"))) { "Unsupported resource density" }
      "den$it"
    }.toMutableSet()
    root["extraResources"]?.split(',')?.forEach {
      val match = Regex("sw[0-9]+-den[0-9]+:(den[0-9]+):[0-9.]+").matchEntire(it)
      require(match != null) { "Unsupported extra resource directory: $it" }
      densities += match.groupValues[1]
    }
    val presentDensities = densities.filter { density -> resources.keys.any { it.startsWith("$base$density/") } }
    fun resolve(path: String): List<String> {
      PerfectIconFormat.safePath(path)
      val direct = base + path
      if (direct in resources) return listOf(direct)
      require(presentDensities.isNotEmpty()) { "Missing fancy dependency: $direct" }
      return presentDensities.map { density ->
        "$base$density/$path".also { require(it in resources) { "Missing fancy dependency: $it" } }
      }
    }
    fun image(path: String) {
      resolve(path).forEach { image(it, resources.getValue(it), read) }
    }
    xml.elements.forEach { element ->
      val attrs = element.attributes
      val computed = attrs.keys.filter { it in setOf("srcExp", "srcNameExp", "srcFormat", "srcParas") }
      if (computed.isNotEmpty()) {
        require(element.name == "Image" && computed == listOf("srcExp") && "src" !in attrs && "srcid" !in attrs) {
          "Unsupported computed resource reference"
        }
        expressionFrames(attrs.getValue("srcExp"), xml.elements).forEach(::image)
      }
      val source = attrs["src"]
      require(source != null || "srcid" !in attrs) { "Indexed image has no source" }
      if (source != null) {
        when (element.name) {
          "Image", "Mask", "Source" -> {
            when (val selector = attrs["srcid"]) {
              null -> image(source)
              "#date" -> (1..31).forEach { image(suffixed(source, it.toString())) }
              "#day_of_week" -> (1..7).forEach { image(suffixed(source, it.toString())) }
              else -> error("Unresolved indexed image selector: $selector")
            }
          }
          "ImageChars" -> {
            val expression = attrs["string"].orEmpty()
            require(expression.matches(Regex("#(?:weather_temperature|temperature)\\s*\\+\\s*'°'"))) {
              "Unresolved image character alphabet"
            }
            val mapping = attrs["charNameMap"]?.split(',')?.associate {
              require(it.length == 2) { "Invalid image character mapping" }
              it[0] to it.substring(1)
            }.orEmpty()
            ("0123456789-°").forEach { image(suffixed(source, mapping[it] ?: it.toString())) }
          }
          "Time" -> {
            require(attrs["format"] in setOf("dd", "HH", "hh", "mm", "ss", "HH:mm", "hh:mm")) {
              "Unresolved Time image format"
            }
            (0..9).forEach { image(suffixed(source, it.toString())) }
            if (':' in attrs.getValue("format")) image(suffixed(source, "dot"))
          }
          "Lottie" -> resolve(source).forEach { jsonPath ->
            lottie(read(resources.getValue(jsonPath)), jsonPath.substringBeforeLast('/') + "/", resources, read)
          }
          else -> error("Unsupported resource-bearing element: ${element.name}")
        }
      }
      attrs["fontPath"]?.let { resolve(it) }
    }
  }

  private fun suffixed(source: String, suffix: String): String {
    PerfectIconFormat.safePath(source)
    val dot = source.lastIndexOf('.')
    require(dot > source.lastIndexOf('/')) { "Indexed image has no extension" }
    return source.substring(0, dot) + "_" + suffix + source.substring(dot)
  }

  private fun expressionFrames(expression: String, elements: List<PerfectIconFormat.XmlElement>): List<String> {
    val match = Regex("@([A-Za-z_][A-Za-z0-9_]*)\\+'_'\\+int\\(#([A-Za-z_][A-Za-z0-9_]*)\\)\\+'\\.png'")
      .matchEntire(expression)
    require(match != null) { "Unresolved computed image source" }
    fun variable(name: String): Int {
      val matches = elements.indices.filter { elements[it].name == "Var" && elements[it].attributes["name"] == name }
      require(matches.size == 1) { "Missing or ambiguous image source variable: $name" }
      return matches.single()
    }
    val prefix = elements[variable(match.groupValues[1])].attributes["expression"].orEmpty()
    val literal = Regex("'([A-Za-z0-9_/-]+)'")
    val prefixes = literal.findAll(prefix).map { it.groupValues[1] }.toSet()
    prefixes.forEach(PerfectIconFormat::safePath)
    require(prefixes.isNotEmpty() && prefixes.size <= 32 && prefix.startsWith("ifelse(") && prefix.endsWith(")") &&
      literal.replace(prefix, "0").matches(Regex("[A-Za-z0-9_#().,><={}\\s-]+"))) {
      "Unresolved image prefix alternatives"
    }
    val index = variable(match.groupValues[2])
    require("expression" !in elements[index].attributes) { "Unbounded image frame variable" }
    val frames = elements.drop(index + 1).takeWhile { it.depth > elements[index].depth }
    require(frames.isNotEmpty() && frames.all { it.name in setOf("VariableAnimation", "AniFrame") }) {
      "Unbounded image frame animation"
    }
    val values = frames.filter { it.name == "AniFrame" }.map {
      requireNotNull(it.attributes["value"]?.toDoubleOrNull()) { "Nonliteral image frame" }
    }
    require(values.isNotEmpty() && values.all { it.isFinite() && it in 0.0..1024.0 }) { "Invalid image frame range" }
    val range = floor(values.min()).toInt()..floor(values.max()).toInt()
    return prefixes.flatMap { name -> range.map { "${name}_$it.png" } }
  }

  private fun lottie(
    bytes: ByteArray,
    base: String,
    resources: Map<String, PerfectIconBlob>,
    read: (PerfectIconBlob) -> ByteArray,
  ) {
    require(bytes.size in 1..PerfectIconFormat.MAX_XML) { "Invalid Lottie resource size" }
    // Bound nesting before JSONObject constructs the tree.
    val text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    var depth = 0
    var quoted = false
    var escaped = false
    text.forEach {
      if (quoted) {
        if (escaped) escaped = false else if (it == '\\') escaped = true else if (it == '"') quoted = false
      } else when (it) {
        '"' -> quoted = true
        '{', '[' -> require(++depth <= 64) { "Lottie nesting limit exceeded" }
        '}', ']' -> require(--depth >= 0)
      }
    }
    require(!quoted && depth == 0) { "Invalid Lottie JSON" }
    val root = JSONObject(text)
    val assets = root.optJSONArray("assets") ?: JSONArray()
    val ids = mutableSetOf<String>()
    for (index in 0 until assets.length()) {
      val asset = assets.getJSONObject(index)
      if (asset.has("id")) require(ids.add(asset.getString("id"))) { "Duplicate Lottie asset ID" }
      if (asset.has("p")) {
        val path = asset.getString("p")
        if (asset.optInt("e", 0) == 1) {
          val header = "data:image/png;base64,"
          require(path.startsWith(header)) { "Unsupported embedded Lottie image" }
          PerfectIconFormat.raster(java.util.Base64.getDecoder().decode(path.removePrefix(header)))
        } else {
          val local = asset.optString("u", "") + path
          PerfectIconFormat.safePath(local)
          require(':' !in local && '?' !in local && '#' !in local) { "Nonlocal Lottie image" }
          image(base + local, requireNotNull(resources[base + local]) { "Missing Lottie image: $local" }, read)
        }
      }
    }
    fun references(value: Any?, level: Int) {
      require(level <= 64)
      when (value) {
        is JSONObject -> value.keys().forEach { key ->
          if (key == "refId") require(value.getString(key) in ids) { "Missing Lottie asset reference" }
          references(value.get(key), level + 1)
        }
        is JSONArray -> (0 until value.length()).forEach { references(value.get(it), level + 1) }
      }
    }
    references(root, 0)
  }
}
