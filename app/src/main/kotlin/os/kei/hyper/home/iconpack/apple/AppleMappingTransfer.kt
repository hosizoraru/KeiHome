package os.kei.hyper.home.iconpack.apple

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class AppleTransferredMapping(
  val packageName: String,
  val platform: ApplePlatform,
  val country: String,
  val app: AppleAppMatch?,
)

internal data class AppleMappingImportPlan(
  val count: Int,
  val writes: Map<ApplePlatform, Map<String, String>>,
)

/** A deliberately small, strict JSON format containing manual choices only. */
internal object AppleMappingTransfer {
  const val MAX_BYTES = 2 * 1024 * 1024
  const val MAX_MAPPINGS = 10_000
  private const val FORMAT = "keihome.apple-mappings"
  private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

  fun read(input: InputStream): ByteArray {
    val bytes = input.readNBytes(MAX_BYTES + 1)
    require(bytes.size <= MAX_BYTES) { "Mapping document is too large" }
    return bytes
  }

  fun encode(choices: List<AppleTransferredMapping>): ByteArray {
    validate(choices)
    return json(linkedMapOf("format" to FORMAT, "schema" to 1, "mappings" to choices
      .sortedWith(compareBy({ it.platform.ordinal }, { it.packageName })).map {
        linkedMapOf("packageName" to it.packageName, "platform" to it.platform.name,
          "country" to it.country, "app" to it.app?.let(::appFields))
      })).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
  }

  fun decode(bytes: ByteArray): List<AppleTransferredMapping> {
    val root = parse(bytes).fields(setOf("format", "schema", "mappings"))
    require(root["format"] == FORMAT && root["schema"] == 1) { "Unsupported mapping document" }
    val rows = root["mappings"] as? List<*> ?: error("Missing mappings")
    require(rows.size <= MAX_MAPPINGS)
    return rows.map {
      val row = it.fields(setOf("packageName", "platform", "country", "app"))
      val country = row.string("country")
      AppleTransferredMapping(row.string("packageName"), ApplePlatform.valueOf(row.string("platform")),
        country, row["app"]?.let { app -> match(app.fields(setOf("id", "name", "publisher", "bundleId")), country) })
    }.also(::validate)
  }

  fun merge(existing: List<AppleTransferredMapping>, incoming: List<AppleTransferredMapping>): List<AppleTransferredMapping> {
    validate(existing)
    validate(incoming)
    return (existing.associateBy { it.platform to it.packageName } +
      incoming.associateBy { it.platform to it.packageName }).values.toList().also(::validate)
  }

  /** Enumerate preferences rather than installed apps, retaining exclusions and uninstalled apps. */
  fun savedChoices(keys: Array<out String>, read: (String) -> String?, country: String): List<AppleTransferredMapping> {
    val legacy = keys.filter { it.startsWith("mapping.") }.map { key ->
      val parts = key.split('.', limit = 3)
      require(parts.size == 3)
      savedChoice(parts[2], ApplePlatform.valueOf(parts[1]), requireNotNull(read(key)), country)
    }
    val snapshots = ApplePlatform.entries.flatMap { platform ->
      val key = namespaceKey(keys, platform) ?: return@flatMap emptyList()
      namespace(requireNotNull(read(key))).map { (pkg, value) -> savedChoice(pkg, platform, value, country) }
    }
    return merge(legacy, snapshots)
  }

  /** Snapshot layouts need one value write per platform; legacy layouts use one editor per platform. */
  fun importWrites(bytes: ByteArray, keys: Array<out String>, read: (String) -> String?, country: String):
    AppleMappingImportPlan {
    val incoming = decode(bytes)
    val merged = merge(savedChoices(keys, read, country), incoming)
    encode(merged)
    val writes = incoming.groupBy { it.platform }.mapValues { (platform, choices) ->
      val key = namespaceKey(keys, platform)
      if (key == null) choices.associate { "mapping.${platform.name}.${it.packageName}" to savedValue(it) }
      else {
        val previous = namespace(requireNotNull(read(key)))
        val stringValues = previous.values.any { it is String && it != "-" }
        val snapshot = merged.filter { it.platform == platform }.associate { row ->
          row.packageName to if (stringValues) savedValue(row) else
            (row.app?.let { appFields(it) + ("country" to row.country) } ?: "-")
        }
        val value = json(snapshot)
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        buildMap {
          put(key, value)
          choices.forEach { row ->
            val legacyKey = "mapping.${platform.name}.${row.packageName}"
            if (legacyKey in keys) put(legacyKey, savedValue(row))
          }
        }
      }
    }
    return AppleMappingImportPlan(incoming.size, writes)
  }

  private fun namespaceKey(keys: Array<out String>, platform: ApplePlatform): String? {
    val found = keys.filter { it == "matches_${platform.name}" ||
      it == "matches_${platform.name.lowercase(java.util.Locale.ROOT)}" }
    require(found.size <= 1)
    return found.singleOrNull()
  }

  private fun namespace(raw: String): Map<String, Any?> {
    val parsed = parse(raw.toByteArray(Charsets.UTF_8))
    require(parsed is Map<*, *>)
    @Suppress("UNCHECKED_CAST")
    return parsed as Map<String, Any?>
  }

  private fun savedChoice(pkg: String, platform: ApplePlatform, saved: Any?, country: String): AppleTransferredMapping {
    val value = if (saved is String && saved != "-") parse(saved.toByteArray(Charsets.UTF_8)) else saved
    val app = if (value == null || value == "-") null else {
      val fields = value.fields(setOf("id", "name", "publisher", "bundleId"), setOf("country"))
      val savedCountry = fields["country"]
      require(savedCountry == null || savedCountry is String)
      match(fields, savedCountry?.takeIf { it.isNotEmpty() && it != "null" } ?: country)
    }
    return AppleTransferredMapping(pkg, platform, app?.country ?: country, app)
  }

  fun savedValue(choice: AppleTransferredMapping): String =
    choice.app?.let { json(appFields(it) + ("country" to choice.country)) } ?: "-"

  private fun match(fields: Map<String, Any?>, country: String) =
    AppleAppMatch(fields.string("id"), fields.string("name"), fields.string("publisher"),
      fields.string("bundleId"), country)

  private fun appFields(app: AppleAppMatch) = linkedMapOf(
    "id" to app.id, "name" to app.name, "publisher" to app.publisher, "bundleId" to app.bundleId)

  private fun validate(choices: List<AppleTransferredMapping>) {
    require(choices.size <= MAX_MAPPINGS)
    require(choices.map { it.platform to it.packageName }.toSet().size == choices.size) { "Duplicate mapping" }
    choices.forEach { row ->
      require(row.packageName.length in 1..255 && row.packageName.matches(packagePattern))
      require(row.country.matches(Regex("[a-z]{2}")))
      row.app?.let { app ->
        require(app.country == null || app.country == row.country)
        require(app.name.isNotBlank() && app.name.length <= 512 && app.publisher.length <= 512 && app.bundleId.length <= 255)
        require(listOf(app.name, app.publisher, app.bundleId).none { text -> text.any { it.code < 32 } })
      }
    }
  }

  private fun Any?.fields(required: Set<String>, optional: Set<String> = emptySet()): Map<String, Any?> {
    require(this is Map<*, *> && keys.containsAll(required) && keys.all { it in required || it in optional })
    @Suppress("UNCHECKED_CAST")
    return this as Map<String, Any?>
  }

  private fun Map<String, Any?>.string(key: String) = this[key] as? String ?: error("Invalid $key")

  private fun parse(bytes: ByteArray): Any? {
    require(bytes.size in 1..MAX_BYTES)
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    return Reader(decoder.decode(ByteBuffer.wrap(bytes)).toString()).document()
  }

  private fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
      append('"')
      value.forEach { c ->
        when (c) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          in '\u0000'..'\u001f' -> append("\\u%04x".format(c.code))
          else -> append(c)
        }
      }
      append('"')
    }
    is Int -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key) + ":" + json(it.value) }
    is List<*> -> value.joinToString(",", "[", "]") { json(it) }
    else -> error("Unsupported JSON value")
  }

  /** No lenient JSON extensions, duplicate keys, trailing input or unbounded nesting. */
  private class Reader(private val text: String) {
    private var offset = 0
    fun document(): Any? = value(0).also { space(); require(offset == text.length) }
    private fun space() { while (offset < text.length && text[offset] in " \r\n\t") offset++ }
    private fun take(c: Char): Boolean {
      space()
      return (offset < text.length && text[offset] == c).also { if (it) offset++ }
    }
    private fun expect(c: Char) { require(take(c)) { "Expected $c" } }
    private fun value(depth: Int): Any? {
      require(depth <= 5)
      space()
      require(offset < text.length)
      return when (text[offset]) {
        '{' -> {
          offset++
          val map = linkedMapOf<String, Any?>()
          if (!take('}')) {
            do {
              space()
              val key = string()
              require(!map.containsKey(key))
              expect(':')
              map[key] = value(depth + 1)
              require(map.size <= if (depth == 0) MAX_MAPPINGS else 16)
            } while (take(','))
            expect('}')
          }
          map
        }
        '[' -> {
          offset++
          val list = mutableListOf<Any?>()
          if (!take(']')) {
            do {
              require(list.size < MAX_MAPPINGS)
              list += value(depth + 1)
            } while (take(','))
            expect(']')
          }
          list
        }
        '"' -> string()
        'n' -> { require(text.startsWith("null", offset)); offset += 4; null }
        '1' -> { offset++; 1 } // The only numeric field in this format is schema: 1.
        else -> error("Invalid JSON value")
      }
    }
    private fun string(): String {
      require(offset < text.length && text[offset++] == '"')
      return buildString {
        while (true) {
          require(offset < text.length)
          val c = text[offset++]
          if (c == '"') break
          require(c.code >= 32)
          if (c != '\\') append(c) else {
            require(offset < text.length)
            append(when (val escape = text[offset++]) {
              '"', '\\', '/' -> escape
              'b' -> '\b'
              'f' -> '\u000c'
              'n' -> '\n'
              'r' -> '\r'
              't' -> '\t'
              'u' -> {
                require(offset + 4 <= text.length)
                val hex = text.substring(offset, offset + 4)
                require(hex.all { it in "0123456789abcdefABCDEF" })
                offset += 4
                hex.toInt(16).toChar()
              }
              else -> error("Invalid JSON escape")
            })
          }
          require(length <= 1024)
        }
        var index = 0
        while (index < length) {
          val c = get(index++)
          if (c.isHighSurrogate()) require(index < length && get(index++).isLowSurrogate())
          else require(!c.isLowSurrogate())
        }
      }
    }
  }
}
