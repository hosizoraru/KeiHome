package os.kei.hyper.home.iconpack

/** A closed day/night crossfade whose only additional input is the day of month. */
internal data class NativeMamlCalendarPair(val light: String, val dark: String) {
  val images: List<String>
    get() = listOf(light, dark).flatMap { src ->
      (1..31).map { day -> "${src.removeSuffix(".png")}_$day.png" }
    }

  companion object {
    fun parse(bytes: ByteArray, check: () -> Unit = {}): NativeMamlCalendarPair? {
      val normalized = StringBuilder()
      val sources = mutableListOf<String>()
      var valid = true
      var elements = 0
      fun escaped(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
      fun safe(src: String): Boolean = src.length <= 256 && src.endsWith(".png") &&
        src.split('/').let { parts -> parts.size in 1..4 &&
          parts.all { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}")) } }
      BoundedPackXml.read(bytes, IconPackLimits(maxXmlBytes = 64 * 1024), check,
        start = { tag, attrs, depth ->
          if (++elements > 40) valid = false
          val values = attrs.toMutableMap()
          if (depth == 1) {
            if (tag != "Icon" || attrs["useVariableUpdater"] != "DateTime.Day") valid = false
            values["useVariableUpdater"] = "none"
          }
          if (tag == "Image") {
            val src = attrs["src"]
            if (depth != 2 || attrs["srcid"] != "#date" || src == null || !safe(src) ||
              sources.size >= 2) valid = false
            else sources += src
            values.remove("srcid")
            values["src"] = if (sources.size == 1) "day.png" else "night.png"
          }
          if (valid) {
            normalized.append('<').append(tag)
            values.forEach { (name, value) ->
              normalized.append(' ').append(name).append("=\"").append(escaped(value)).append('"')
            }
            normalized.append('>')
          }
        },
        text = { value, _ -> if (value.isNotBlank()) valid = false },
        end = { tag, _ -> if (valid) normalized.append("</").append(tag).append('>') })
      if (!valid || sources.size != 2 || sources[0] == sources[1]) return null
      val proof = NativeMamlPair.parse(normalized.toString().toByteArray(Charsets.UTF_8), check)
      if (proof != NativeMamlPair("day.png", "night.png")) return null
      return NativeMamlCalendarPair(sources[0], sources[1])
    }
  }
}
