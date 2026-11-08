package os.kei.hyper.home.iconpack.perfect

/**
 * Destination contract for the current native theme loader. Repository style names do not select
 * an OS compatibility mode. Field evidence and the independent input boundary are recorded in
 * docs/perfect-icons-provenance.md.
 */
internal object HyperOs4IconModule {
  const val REVISION = "keihome-hyperos4-native-1"

  fun cacheIdentity(stockMask: String?): String =
    "$REVISION\n${PerfectIconFormat.sha(stockMask.orEmpty().toByteArray(Charsets.UTF_8))}"

  fun transform(hasDynamicResources: Boolean, stockMask: String?): ByteArray {
    stockMask?.let {
      require(it.length in 1..4096 && it.none { c -> c.code < 32 && c !in "\t\r\n" }) {
        "Invalid native icon mask"
      }
    }
    fun attribute(value: String): String = buildString {
      value.forEach { c ->
        append(when (c) {
          '&' -> "&amp;"
          '<' -> "&lt;"
          '>' -> "&gt;"
          '"' -> "&quot;"
          '\'' -> "&apos;"
          else -> c.toString()
        })
      }
    }
    return buildString {
      append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<IconTransform>\n")
      append("  <Config name=\"SupportLayerIcon\" value=\"true\"/>\n")
      append("  <Config name=\"UseDynamicIcon\" value=\"$hasDynamicResources\"/>\n")
      // The provider reads the destination's original product theme, never the applied third-party
      // theme. If it is unavailable, leave shape selection to the native loader.
      stockMask?.let { append("  <Config name=\"ConfigIconMask\" value=\"${attribute(it)}\"/>\n") }
      append("</IconTransform>\n")
    }.toByteArray(Charsets.UTF_8)
  }
}
