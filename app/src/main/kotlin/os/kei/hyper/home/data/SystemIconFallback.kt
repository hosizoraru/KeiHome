package os.kei.hyper.home.data

internal data class SystemIconFallback private constructor(val name: String) {
  val perfectStyle: String? get() = name.takeIf { it.startsWith(PERFECT_PREFIX) }?.removePrefix(PERFECT_PREFIX)
  val isPerfect: Boolean get() = perfectStyle != null

  fun sourceId(id: String): String = "${baseSourceId(id)}$SOURCE_SUFFIX$name"

  companion object {
    val DEFAULT = SystemIconFallback("DEFAULT")
    val CLASSIC = SystemIconFallback("CLASSIC")
    val BOUNDLESS = SystemIconFallback("BOUNDLESS")
    val entries = listOf(DEFAULT, CLASSIC, BOUNDLESS)
    const val DEFAULT_PERFECT_STYLE = "HyperOS4"
    private const val PERFECT_PREFIX = "PERFECT:"
    private const val SOURCE_SUFFIX = ":system-fallback-v4:"
    fun baseSourceId(id: String): String =
      id.substringBefore(":system-fallback-v1:").substringBefore(":system-fallback-v2:")
        .substringBefore(":system-fallback-v3:")
        .substringBefore(SOURCE_SUFFIX)
    fun perfect(style: String): SystemIconFallback {
      require(style.length in 1..128 && style.isNotBlank() && style !in setOf(".", "..") &&
        style.none { it.code < 32 || it in "/\\\u007f" }) { "Invalid Perfect Icons fallback style" }
      return SystemIconFallback("$PERFECT_PREFIX$style")
    }
    fun valueOf(name: String): SystemIconFallback =
      entries.firstOrNull { it.name == name } ?: if (name.startsWith(PERFECT_PREFIX))
        perfect(name.removePrefix(PERFECT_PREFIX)) else throw IllegalArgumentException("Unknown system fallback")
  }
}
