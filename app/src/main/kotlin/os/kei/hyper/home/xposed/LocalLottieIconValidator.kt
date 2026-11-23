package os.kei.hyper.home.xposed

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Admission only: bounded, self-contained vector graphics, including inline precompositions.
 * Property names follow https://lottie.github.io/lottie-spec/dev/specs/schema/.
 * A strict bounded reader runs before creating containers; JSONTokener's lenient/unbounded
 * recursive parsing must not process untrusted icon resources.
 */
internal object LocalLottieIconValidator {
  private const val MAX_BYTES = 1024 * 1024
  private const val MAX_DEPTH = 32
  private const val MAX_VALUES = 50_000
  private const val MAX_OBJECTS = 4096
  private const val MAX_ARRAYS = 8192
  private const val MAX_ARRAY_ITEMS = 4096
  private const val MAX_STRING = 4096
  private const val MAX_FRAMES = 36_000.0
  private const val MAX_RENDER_NODES = 4096
  private const val MAX_COMPOSITION_DEPTH = 8
  private const val MAX_COMPOSITION_PIXELS = 16L * 1024 * 1024
  private val version = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){1,3}")
  private val assetId = Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}")
  private val topKeys = setOf("v", "fr", "ip", "op", "w", "h", "nm", "ddd", "assets", "layers", "markers", "props")
  private val inlineAssetKeys = setOf("id", "nm", "fr", "layers")
  private val markerKeys = setOf("tm", "cm", "dr")
  private val precompositionKeys = setOf("refId", "w", "h", "tm")
  private val layerKeys = setOf("ddd", "ind", "ty", "nm", "sr", "ks", "ao", "shapes", "ip", "op",
    "st", "bm", "hd", "parent", "cl", "ln", "ct", "td", "tt", "tp")
  private val shapeTypes = setOf("gr", "sh", "rc", "el", "fl", "st", "gf", "gs", "tr", "tm", "rd")
  private val shapeKeys = setOf("ty", "nm", "mn", "hd", "bm", "ix", "ind", "it", "np", "cix",
    "ks", "p", "s", "r", "d", "c", "o", "g", "t", "e", "h", "a", "sk", "sa", "rz",
    "rx", "ry", "lc", "lj", "ml", "ml2", "w", "m")
  private val dataKeys = (topKeys - setOf("assets", "layers", "props")) + layerKeys + shapeKeys + setOf(
    "k", "i", "x", "y", "z", "l", "v", "to", "ti", "n", "c", "ro", "os", "is",
    "cm", "dr", "tm", "s", "a", "h", "t",
  )
  private val forbidden = setOf("refid", "u", "url", "uri", "href", "src", "source", "path",
    "filename", "file", "script", "scripts", "expression", "expressions", "expr", "code", "js",
    "fonts", "chars", "fpath", "fontpath", "ef", "effects", "slots")
  private val labelKeys = setOf("v", "nm", "mn", "ty", "cl", "ln")
  private data class CompositionReference(val id: String, val pixels: Long)
  private data class Composition(val nodes: Int, val shapes: Int, val mattes: Int, val uses: List<CompositionReference>)
  private data class RenderCost(val nodes: Int, val hasShapes: Boolean, val mattes: Int, val pixels: Long, val depth: Int)

  internal fun validate(bytes: ByteArray) {
    require(bytes.size in 2..MAX_BYTES) { "Lottie JSON exceeds the 1 MiB admission limit" }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    val root = Reader(text).read() as? JSONObject ?: error("Lottie content must be a JSON object")
    keys(root, topKeys)
    require((root.opt("v") as? String)?.matches(version) == true) { "Unsupported Lottie version" }
    val rootPixels = integer(root, "w", 1..4096).toLong() * integer(root, "h", 1..4096)
    if (root.has("ddd")) integer(root, "ddd", 0..1)
    frameRate(root)
    timeline(root)
    if (root.has("props")) require(root.get("props") is JSONObject && root.getJSONObject("props").length() == 0) {
      "Only empty Lottie props are supported"
    }
    if (root.has("markers")) {
      val markers = root.getJSONArray("markers")
      require(markers.length() <= 64) { "Lottie marker limit exceeded" }
      for (index in 0 until markers.length()) {
        val marker = markers.getJSONObject(index)
        keys(marker, markerKeys)
        require(number(marker, "tm") in -MAX_FRAMES..MAX_FRAMES && number(marker, "dr") in 0.0..MAX_FRAMES &&
          (marker.opt("cm") as? String)?.length in 0..128) { "Invalid Lottie marker" }
      }
    }
    inspectObject(root, setOf("assets", "layers", "props", "markers"))
    val assets = linkedMapOf<String, Composition>()
    if (root.has("assets")) {
      val array = root.get("assets") as? JSONArray ?: error("Invalid Lottie asset list")
      require(array.length() <= 32) { "Lottie precomposition limit exceeded" }
      for (index in 0 until array.length()) {
        val asset = array.getJSONObject(index)
        keys(asset, inlineAssetKeys)
        val id = referenceId(asset, "id")
        require(id !in assets) { "Duplicate Lottie precomposition: $id" }
        if (asset.has("fr")) frameRate(asset)
        inspectObject(asset, setOf("id", "layers"))
        assets[id] = composition(asset.getJSONArray("layers"))
      }
    }
    val scene = composition(root.getJSONArray("layers"))
    val validated = mutableMapOf<String, RenderCost>()
    val active = mutableSetOf<String>()
    fun cost(composition: Composition, depth: Int): RenderCost {
      require(depth <= MAX_COMPOSITION_DEPTH) { "Lottie precomposition depth exceeded" }
      var result = RenderCost(composition.nodes, composition.shapes > 0, composition.mattes, 0, 1)
      for (reference in composition.uses) {
        val child = validated[reference.id] ?: run {
          require(active.add(reference.id)) { "Cyclic Lottie precomposition: ${reference.id}" }
          val asset = requireNotNull(assets[reference.id]) { "Missing Lottie precomposition: ${reference.id}" }
          cost(asset, depth + 1).also {
            active.remove(reference.id)
            validated[reference.id] = it
          }
        }
        result = RenderCost(result.nodes + child.nodes, result.hasShapes || child.hasShapes,
          result.mattes + child.mattes,
          result.pixels + child.pixels + reference.pixels * (1 + assets.getValue(reference.id).mattes),
          maxOf(result.depth, child.depth + 1))
        require(result.nodes <= MAX_RENDER_NODES && result.mattes <= 32 &&
          result.pixels <= MAX_COMPOSITION_PIXELS && result.depth <= MAX_COMPOSITION_DEPTH) {
          "Lottie expanded rendering budget exceeded"
        }
      }
      return result
    }
    val render = cost(scene, 1)
    for (id in assets.keys - validated.keys) cost(assets.getValue(id), 1)
    require(render.hasShapes) { "Lottie has no vector shapes" }
    require(rootPixels * (1 + scene.mattes) + render.pixels <= MAX_COMPOSITION_PIXELS) {
      "Lottie composition pixel budget exceeded"
    }
  }

  private fun composition(layers: JSONArray): Composition {
    require(layers.length() in 1..128) { "Lottie layer limit exceeded" }
    val indexed = linkedMapOf<Int, JSONObject>()
    val mattes = mutableSetOf<Int>()
    val usedMattes = mutableSetOf<Int>()
    val uses = mutableListOf<CompositionReference>()
    var previousId: Int? = null
    var shapes = 0
    fun shapeList(array: JSONArray, depth: Int) {
      require(depth <= 16)
      for (index in 0 until array.length()) {
        require(++shapes <= 2048) { "Lottie shape limit exceeded" }
        val shape = array.get(index) as? JSONObject ?: error("Invalid Lottie shape")
        keys(shape, shapeKeys)
        val type = shape.get("ty") as? String ?: error("Invalid Lottie shape type")
        require(type in shapeTypes) { "Unsupported vector shape: $type" }
        if (shape.has("ml2")) {
          require(type == "gs") { "Only gradient strokes can declare a miter limit" }
          val limit = shape.getJSONObject("ml2")
          keys(limit, setOf("a", "k", "ix"))
          integer(limit, "a", 0..0)
          require(number(limit, "k") in 0.0..100.0) { "Invalid gradient stroke miter limit" }
        }
        when (type) {
          "gr" -> shapeList(shape.get("it") as? JSONArray ?: error("Missing vector group"), depth + 1)
          "sh" -> require(shape.get("ks") is JSONObject)
          "gf", "gs" -> {
            val gradient = shape.get("g") as? JSONObject ?: error("Invalid vector gradient")
            integer(gradient, "p", 1..32)
            require(gradient.get("k") is JSONObject)
          }
        }
      }
    }
    for (index in 0 until layers.length()) {
      val layer = layers.getJSONObject(index)
      val type = integer(layer, "ty", 0..4)
      require(type in setOf(0, 3, 4)) { "Unsupported vector layer: $type" }
      keys(layer, if (type == 0) layerKeys + precompositionKeys else layerKeys)
      inspectObject(layer, if (type == 0) setOf("refId") else emptySet())
      if (layer.has("ddd")) integer(layer, "ddd", 0..1)
      val id = integer(layer, "ind", 0..1_000_000)
      require(indexed.put(id, layer) == null) { "Duplicate Lottie layer index" }
      timeline(layer)
      require(layer.get("ks") is JSONObject) { "Missing vector transform" }
      if (layer.has("sr")) require(number(layer, "sr") in 0.001..1000.0) { "Unsafe time stretch" }
      if (layer.has("ct")) integer(layer, "ct", 0..1)
      val matte = if (layer.has("tt")) integer(layer, "tt", 0..2) else 0
      val providesMatte = layer.has("td") && integer(layer, "td", 0..1) == 1
      if (matte != 0) {
        val source = if (layer.has("tp")) integer(layer, "tp", 0..1_000_000) else previousId
        require(!providesMatte && source != null && source in mattes && usedMattes.add(source) &&
          usedMattes.size <= 32) { "Invalid or excessive Lottie track mattes" }
      } else require(!layer.has("tp")) { "Lottie matte reference has no matte mode" }
      if (providesMatte) {
        require(type != 3) { "Lottie track matte needs a drawing layer" }
        mattes += id
      }
      previousId = id
      if (type == 4) shapeList(layer.get("shapes") as? JSONArray ?: error("Missing vector shapes"), 1)
      else require(!layer.has("shapes")) { "Only shape layers can contain shapes" }
      if (type == 0) {
        val width = integer(layer, "w", 1..4096)
        val height = integer(layer, "h", 1..4096)
        uses += CompositionReference(referenceId(layer, "refId"), width.toLong() * height)
        if (layer.has("tm")) require(layer.get("tm") is JSONObject) { "Invalid Lottie time remap" }
      }
    }
    // Parent chains must terminate; even a tiny JSON can encode a cyclic renderer traversal.
    for (id in indexed.keys) {
      val visited = hashSetOf<Int>()
      var current = id
      while (true) {
        require(visited.add(current) && visited.size <= 32) { "Cyclic or deep Lottie parenting" }
        val layer = indexed[current] ?: error("Missing Lottie parent")
        if (!layer.has("parent")) break
        current = integer(layer, "parent", 0..1_000_000)
      }
    }
    return Composition(layers.length() + shapes, shapes, usedMattes.size, uses)
  }

  private fun keys(value: JSONObject, allowed: Set<String>) {
    val unknown = value.keys().asSequence().firstOrNull { it !in allowed }
    require(unknown == null) { "Unsupported Lottie property: $unknown" }
  }

  private fun frameRate(value: JSONObject) {
    val rate = number(value, "fr")
    require(rate > 0 && rate <= 120) { "Unsupported Lottie frame rate" }
  }

  private fun referenceId(value: JSONObject, key: String): String =
    (value.opt(key) as? String)?.takeIf(assetId::matches) ?: error("Invalid Lottie $key")

  private fun inspectObject(value: JSONObject, skip: Set<String>) {
    value.keys().forEach { key ->
      if (key in skip) return@forEach
      require(key.lowercase(java.util.Locale.ROOT) !in forbidden && key in dataKeys) {
        "Unsupported Lottie data property: $key"
      }
      val child = value.get(key)
      require(child !is String || key in labelKeys) { "Strings are only admitted as inert Lottie labels" }
      if (key == "t" && child is Number) require(child.toDouble() in -MAX_FRAMES..MAX_FRAMES)
      inspect(child, label = key in labelKeys)
    }
  }

  private fun number(value: JSONObject, key: String): Double {
    val number = (value.get(key) as? Number)?.toDouble() ?: error("Expected numeric Lottie $key")
    require(number.isFinite())
    return number
  }

  private fun integer(value: JSONObject, key: String, range: IntRange): Int {
    val number = number(value, key)
    require(number == number.toInt().toDouble() && number >= range.first && number <= range.last) {
      "Invalid Lottie $key"
    }
    return number.toInt()
  }

  private fun timeline(value: JSONObject) {
    val start = number(value, "ip")
    val end = number(value, "op")
    require(start in -MAX_FRAMES..MAX_FRAMES && end in -MAX_FRAMES..MAX_FRAMES &&
      end > start && end - start <= MAX_FRAMES) { "Unsafe Lottie timeline" }
  }

  private fun inspect(value: Any, label: Boolean = false) {
    when (value) {
      is JSONObject -> inspectObject(value, emptySet())
      is JSONArray -> for (index in 0 until value.length()) inspect(value.get(index))
      is Number -> require(value.toDouble().isFinite() && kotlin.math.abs(value.toDouble()) <= 1_000_000)
      is String -> require(label && value.length <= MAX_STRING)
      is Boolean -> Unit
      else -> error("Null values are outside the admitted Lottie subset")
    }
  }

  private class Reader(private val text: String) {
    private var at = 0
    private var values = 0
    private var objects = 0
    private var arrays = 0

    fun read(): Any {
      val result = value(1)
      whitespace()
      require(at == text.length) { "Trailing Lottie JSON content" }
      return result
    }

    private fun value(depth: Int): Any {
      require(depth <= MAX_DEPTH && ++values <= MAX_VALUES) { "Lottie JSON complexity limit exceeded" }
      whitespace()
      require(at < text.length) { "Incomplete Lottie JSON" }
      return when (text[at]) {
        '{' -> {
          require(++objects <= MAX_OBJECTS)
          at++
          val result = JSONObject()
          whitespace()
          if (!take('}')) {
            do {
              whitespace()
              val key = string()
              require(key.length in 1..128 && result.length() < 128 && !result.has(key)) {
                "Duplicate or excessive Lottie JSON keys"
              }
              whitespace()
              expect(':')
              result.put(key, value(depth + 1))
              whitespace()
            } while (take(','))
            expect('}')
          }
          result
        }
        '[' -> {
          require(++arrays <= MAX_ARRAYS)
          at++
          val result = JSONArray()
          whitespace()
          if (!take(']')) {
            do {
              require(result.length() < MAX_ARRAY_ITEMS)
              result.put(value(depth + 1))
              whitespace()
            } while (take(','))
            expect(']')
          }
          result
        }
        '"' -> string()
        't' -> { literal("true"); true }
        'f' -> { literal("false"); false }
        // Null is deliberately unsupported rather than disappearing through JSONObject.put.
        else -> numeric()
      }
    }

    private fun numeric(): Double {
      val start = at
      take('-')
      if (!take('0')) digits()
      if (take('.')) digits()
      if (at < text.length && text[at] in "eE") {
        at++
        if (at < text.length && text[at] in "+-") at++
        digits()
      }
      require(at > start && at - start <= 64) { "Invalid Lottie JSON number" }
      return text.substring(start, at).toDouble().also {
        require(it.isFinite() && kotlin.math.abs(it) <= 1_000_000) { "Unbounded Lottie number" }
      }
    }

    private fun digits() {
      val start = at
      while (at < text.length && text[at] in '0'..'9') at++
      require(at > start) { "Invalid Lottie JSON number" }
    }

    private fun string(): String {
      expect('"')
      val result = StringBuilder()
      while (at < text.length) {
        var char = text[at++]
        if (char == '"') {
          var index = 0
          while (index < result.length) {
            val current = result[index++]
            if (current.isHighSurrogate()) require(index < result.length && result[index++].isLowSurrogate())
            else require(!current.isLowSurrogate())
          }
          return result.toString()
        }
        require(char.code >= 32)
        if (char == '\\') {
          require(at < text.length)
          char = when (val escaped = text[at++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
              require(at + 4 <= text.length)
              val hex = text.substring(at, at + 4)
              require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
              at += 4
              hex.toInt(16).toChar()
            }
            else -> error("Invalid Lottie JSON escape")
          }
        }
        result.append(char)
        require(result.length <= MAX_STRING) { "Lottie string limit exceeded" }
      }
      error("Unterminated Lottie JSON string")
    }

    private fun literal(value: String) {
      require(text.startsWith(value, at))
      at += value.length
    }

    private fun whitespace() {
      while (at < text.length && text[at] in " \t\r\n") at++
    }

    private fun take(char: Char): Boolean {
      if (at == text.length || text[at] != char) return false
      at++
      return true
    }

    private fun expect(char: Char) {
      require(take(char)) { "Invalid Lottie JSON syntax" }
    }
  }
}
