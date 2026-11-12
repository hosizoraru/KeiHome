package os.kei.hyper.home.iconpack

import os.kei.hyper.home.data.SystemIconFallback

/** Provenance only; this file never grants rendering or DRM permissions. */
internal data class SystemIconFallbackManifest(val style: SystemIconFallback, val names: Set<String>,
  val nameMatching: Boolean = false, val colorOsFallback: Boolean = false,
  val perfectSource: String? = null) {
  init {
    require(perfectSource == null || (style.isPerfect && perfectSource.matches(Regex("[0-9a-f]{64}")))) {
      "Perfect source must be a canonical SHA256 for a Perfect fallback"
    }
  }

  fun encode(): ByteArray = (listOf("KeiHomeSystemFallback${1 + (if (nameMatching) 1 else 0) + (if (colorOsFallback) 2 else 0) + (if (perfectSource != null) 4 else 0)}", style.name) +
    listOfNotNull(perfectSource) + names.sorted())
    .joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

  companion object {
    const val PATH = "keihome-system-fallback.txt"
    const val MAX_BYTES = 512 * 1024
    fun decode(bytes: ByteArray): SystemIconFallbackManifest {
      require(bytes.size in 1..MAX_BYTES)
      val lines = bytes.toString(Charsets.UTF_8).split('\n')
      require(lines.size >= 3 && lines.first() in (1..8).map { "KeiHomeSystemFallback$it" } && lines.last().isEmpty())
      val options = lines.first().removePrefix("KeiHomeSystemFallback").toInt() - 1
      val hasPerfectSource = options and 4 != 0
      require(lines.size in (if (hasPerfectSource) 4..5004 else 3..5003))
      val style = SystemIconFallback.valueOf(lines[1])
      val perfectSource = if (hasPerfectSource) lines[2] else null
      val names = lines.drop(if (hasPerfectSource) 3 else 2).dropLast(1)
      require(names.all { validNativeIconName(it) && it.length <= 240 } && names.toSet().size == names.size)
      return SystemIconFallbackManifest(style, names.toSet(), options and 1 != 0, options and 2 != 0,
        perfectSource).also {
        require(it.encode().contentEquals(bytes)) { "Noncanonical fallback provenance" }
      }
    }
  }
}
