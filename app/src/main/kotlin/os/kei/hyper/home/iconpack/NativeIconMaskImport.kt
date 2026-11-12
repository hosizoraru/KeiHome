package os.kei.hyper.home.iconpack

import java.math.BigDecimal

/** Extracts a mask only. Other authored fields are accepted but never executed or retained. */
internal object NativeIconMaskImport {
  const val MAX_XML_BYTES = 16 * 1024
  private const val MASK = "ConfigIconMask"
  private val tokens = Regex("[MmZzLlHhVvCcSsQqTtAa]|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
  private val arities = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1,
    'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7, 'Z' to 0)

  fun pathData(bytes: ByteArray): String {
    var mask: String? = null
    var insideMask = false
    BoundedPackXml.read(bytes, IconPackLimits(maxXmlBytes = MAX_XML_BYTES, maxMappings = 128),
      start = { name, attrs, depth ->
        require(!insideMask) { "Nested mask content" }
        require(attrs.keys.none { it == "xmlns" || it.startsWith("xmlns:") }) { "Namespaced mask XML" }
        if (depth == 1) require(name == "IconTransform") { "Expected IconTransform root" }
        if (attrs["name"] == MASK) {
          require(name == "Config" && depth == 2 && mask == null) { "Ambiguous or nested icon mask" }
          mask = canonicalPath(requireNotNull(attrs["value"]) { "Missing icon mask value" })
          insideMask = true
        }
      },
      text = { value, _ -> if (insideMask) require(value.isBlank()) { "Unexpected mask text" } },
      end = { name, depth -> if (name == "Config" && depth == 2) insideMask = false })
    return requireNotNull(mask) { "Missing icon mask" }
  }

  /** Canonical lexical form, preserving relative/absolute commands and the authored geometry. */
  internal fun canonicalPath(value: String): String {
    require(value.length in 1..4096 && value.isNotBlank()) { "Invalid mask path size" }
    val result = ArrayList<String>()
    var offset = 0
    var previousNumber = false
    var command: Char? = null
    val numbers = ArrayList<BigDecimal>()
    fun finishCommand() {
      val arity = arities.getValue(requireNotNull(command))
      require(if (arity == 0) numbers.isEmpty() else numbers.isNotEmpty() && numbers.size % arity == 0) {
        "Incomplete mask path command"
      }
      if (command == 'A') numbers.chunked(7).forEach {
        require(it[0].signum() >= 0 && it[1].signum() >= 0 &&
          it[3] in listOf(BigDecimal.ZERO, BigDecimal.ONE) &&
          it[4] in listOf(BigDecimal.ZERO, BigDecimal.ONE)) { "Invalid mask arc" }
      }
      numbers.clear()
    }
    for (match in tokens.findAll(value)) {
      val token = match.value
      val isNumber = token.first() !in "MmZzLlHhVvCcSsQqTtAa"
      val gap = value.substring(offset, match.range.first)
      require(gap.all { it in " \t\r\n," } && gap.count { it == ',' } <= 1 &&
        (',' !in gap || previousNumber && isNumber)) { "Invalid mask path token" }
      if (!isNumber) {
        if (command != null) finishCommand() else require(token == "M" || token == "m") {
          "Mask path must start with moveto"
        }
        command = token.single().uppercaseChar()
        result += token
      } else {
        require(command != null && command != 'Z') { "Unexpected mask coordinates" }
        val number = token.toBigDecimal().stripTrailingZeros()
        require(number.scale() in -308..308 && number.toDouble().isFinite()) { "Non-finite mask coordinate" }
        numbers += number
        result += number.toPlainString()
      }
      previousNumber = isNumber
      offset = match.range.last + 1
    }
    require(value.substring(offset).all { it in " \t\r\n" } && command != null) { "Invalid mask path tail" }
    finishCommand()
    return result.joinToString(" ").also { require(it.length <= 4096) { "Canonical mask path too large" } }
  }
}
