package os.kei.hyper.home.xposed

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Admission only: a bounded, self-contained vector subset, with no expressions or asset loading.
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
  private val version = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){1,3}")
  private val topKeys = setOf("v", "fr", "ip", "op", "w", "h", "nm", "ddd", "assets", "layers", "markers")
  private val layerKeys = setOf("ddd", "ind", "ty", "nm", "sr", "ks", "ao", "shapes", "ip", "op",
    "st", "bm", "hd", "parent", "cl", "ln")
  // No repeaters, procedural stars, merge paths, text, effects, slots or asset references.
  private val shapeTypes = setOf("gr", "sh", "rc", "el", "fl", "st", "gf", "gs", "tr", "tm", "rd")
  private val shapeKeys = setOf("ty", "nm", "mn", "hd", "bm", "ix", "ind", "it", "np", "cix",
    "ks", "p", "s", "r", "d", "c", "o", "g", "t", "e", "h", "a", "sk", "sa", "rz",
    "rx", "ry", "lc", "lj", "ml", "w", "m")
  private val dataKeys = topKeys + layerKeys + shapeKeys + setOf(
    "k", "i", "x", "y", "z", "l", "v", "to", "ti", "n", "c", "ro", "os", "is",
    "cm", "dr", "tm", "s", "a", "h", "t",
  )
  private val forbidden = setOf("refid", "u", "url", "uri", "href", "src", "source", "path",
    "filename", "file", "script", "scripts", "expression", "expressions", "expr", "code", "js",
    "fonts", "chars", "fpath", "fontpath", "ef", "effects", "slots")
  private val labelKeys = setOf("v", "nm", "mn", "ty", "cl", "ln")

  internal fun validate(bytes: ByteArray) {
    require(bytes.size in 2..MAX_BYTES) { "Lottie JSON exceeds the 1 MiB admission limit" }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    val root = Reader(text).read() as? JSONObject ?: error("Lottie content must be a JSON object")
    keys(root, topKeys)
    require((root.opt("v") as? String)?.matches(version) == true) { "Unsupported Lottie version" }
    integer(root, "w", 1..4096)
    integer(root, "h", 1..4096)
    if (root.has("ddd")) integer(root, "ddd", 0..1)
    require(number(root, "fr") > 0 && number(root, "fr") <= 120) { "Unsupported Lottie frame rate" }
    timeline(root)
    if (root.has("assets")) require(root.get("assets") is JSONArray && root.getJSONArray("assets").length() == 0) {
      "Lottie assets are not admitted"
    }
    if (root.has("markers")) require(root.get("markers") is JSONArray && root.getJSONArray("markers").length() == 0) {
      "Lottie markers are not admitted"
    }
    val layers = root.get("layers") as? JSONArray ?: error("Missing vector layers")
    require(layers.length() in 1..128) { "Lottie layer limit exceeded" }
    val indexed = linkedMapOf<Int, JSONObject>()
    var shapes = 0
    fun shapeList(array: JSONArray, depth: Int) {
      require(depth <= 16)
      for (index in 0 until array.length()) {
        require(++shapes <= 2048) { "Lottie shape limit exceeded" }
        val shape = array.get(index) as? JSONObject ?: error("Invalid Lottie shape")
        keys(shape, shapeKeys)
        val type = shape.get("ty") as? String ?: error("Invalid Lottie shape type")
        require(type in shapeTypes) { "Unsupported vector shape: $type" }
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
      val layer = layers.get(index) as? JSONObject ?: error("Invalid Lottie layer")
      keys(layer, layerKeys)
      val type = integer(layer, "ty", 3..4)
      if (layer.has("ddd")) integer(layer, "ddd", 0..1)
      val id = integer(layer, "ind", 0..1_000_000)
      require(indexed.put(id, layer) == null) { "Duplicate Lottie layer index" }
      timeline(layer)
      require(layer.get("ks") is JSONObject) { "Missing vector transform" }
      if (layer.has("sr")) require(number(layer, "sr") in 0.001..1000.0) { "Unsafe time stretch" }
      if (type == 4) shapeList(layer.get("shapes") as? JSONArray ?: error("Missing vector shapes"), 1)
      else require(!layer.has("shapes")) { "Null layers cannot contain shapes" }
    }
    require(shapes > 0) { "Lottie has no vector shapes" }
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
    inspect(root)
  }

  private fun keys(value: JSONObject, allowed: Set<String>) {
    require(value.keys().asSequence().all { it in allowed }) { "Unsupported Lottie property" }
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
      is JSONObject -> value.keys().forEach { key ->
        require(key.lowercase(java.util.Locale.ROOT) !in forbidden && key in dataKeys) {
          "Lottie resource, script or unknown property is forbidden"
        }
        val child = value.get(key)
        require(!(key in setOf("x", "p") && child is String)) { "Lottie expressions and paths are forbidden" }
        require(child !is String || key in labelKeys) { "Strings are only admitted as inert Lottie labels" }
        if (key == "t" && child is Number) require(child.toDouble() in -MAX_FRAMES..MAX_FRAMES)
        inspect(child, label = key in labelKeys)
      }
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
