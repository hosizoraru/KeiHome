package os.kei.hyper.home.iconpack

/** Resources for one icon only. Global masks, metadata and unrelated apps never cross packs. */
internal object CustomIconResources {
  const val MAX_BYTES = 32 * 1024 * 1024
  private val programs = setOf("fancy_icons", "animating_icons", "layer_animating_icons")

  fun owner(path: String): String? {
    val parts = path.removePrefix("nightmode/").split('/')
    return when {
      parts.size >= 3 && parts[0] == "res" && parts[1].substringBefore('-') == "drawable" ->
        parts[2].removeSuffix(".png")
      parts.size >= 3 && parts[0] in programs -> parts[1]
      parts.size == 1 && validNativeIconName(parts[0].removeSuffix(".png")) -> parts[0].removeSuffix(".png")
      else -> null
    }
  }

  fun select(entries: Map<String, ByteArray>, candidates: List<String>): Map<String, ByteArray> {
    val name = candidates.firstOrNull { candidate -> entries.keys.any { owner(it) == candidate } }
      ?: throw IconPackException("The selected icon has no exportable resources")
    val result = entries.filterKeys { owner(it) == name }
    require(result.size <= 4096 && result.values.sumOf { it.size.toLong() } <= MAX_BYTES)
    return result.mapKeys { (path, _) -> rename(path, name, "custom.icon") }
      .mapValues { (path, bytes) -> rewriteReferences(path, bytes, name, "custom.icon") }
  }

  fun retarget(entries: Map<String, ByteArray>, names: Set<String>): Map<String, ByteArray> {
    require(names.isNotEmpty() && names.all(::validNativeIconName))
    return buildMap {
      names.forEach { name -> entries.forEach { (path, bytes) ->
        require(owner(path) == "custom.icon")
        put(rename(path, "custom.icon", name), rewriteReferences(path, bytes, "custom.icon", name))
      } }
    }
  }

  private fun rename(path: String, source: String, target: String): String {
    require(path.isNotEmpty() && !path.startsWith('/') &&
      path.split('/').none { it.isEmpty() || it == "." || it == ".." || '\\' in it })
    val parts = path.split('/').toMutableList()
    val start = if (parts.first() == "nightmode") 1 else 0
    val index = when {
      parts[start] == "res" -> start + 2
      parts[start] in programs -> start + 1
      else -> start
    }
    require(parts[index] == source || parts[index] == "$source.png")
    parts[index] = if (parts[index].endsWith(".png")) "$target.png" else target
    return parts.joinToString("/")
  }

  private fun rewriteReferences(path: String, bytes: ByteArray, source: String, target: String): ByteArray {
    if (!path.endsWith(".xml")) return bytes
    var text = bytes.toString(Charsets.UTF_8)
    val escaped = Regex.escape(source)
    programs.forEach { root ->
      text = text.replace(Regex("$root/$escaped(?=/|[\"'\\s<]|$)")) { "$root/$target" }
    }
    text = text.replace(Regex("res/(drawable(?:-[A-Za-z0-9-]+)?)/$escaped(?=\\.png|/)")) {
      "res/${it.groupValues[1]}/$target"
    }
    return text.toByteArray(Charsets.UTF_8)
  }
}
