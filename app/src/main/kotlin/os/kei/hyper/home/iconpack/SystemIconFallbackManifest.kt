package os.kei.hyper.home.iconpack

import os.kei.hyper.home.data.SystemIconFallback

/** Provenance only; this file never grants rendering or DRM permissions. */
internal data class SystemIconFallbackManifest(val style: SystemIconFallback, val names: Set<String>,
  val nameMatching: Boolean = false, val colorOsFallback: Boolean = false) {
  fun encode(): ByteArray = (listOf("KeiHomeSystemFallback${1 + (if (nameMatching) 1 else 0) + (if (colorOsFallback) 2 else 0)}", style.name) + names.sorted())
    .joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

  companion object {
    const val PATH = "keihome-system-fallback.txt"
    const val MAX_BYTES = 512 * 1024
    fun decode(bytes: ByteArray): SystemIconFallbackManifest {
      require(bytes.size in 1..MAX_BYTES)
      val lines = bytes.toString(Charsets.UTF_8).split('\n')
      require(lines.size in 3..5003 && lines.first() in (1..4).map { "KeiHomeSystemFallback$it" } && lines.last().isEmpty())
      val style = SystemIconFallback.valueOf(lines[1])
      val names = lines.drop(2).dropLast(1)
      require(names.all { validDottedName(it) && it.length <= 240 } && names.toSet().size == names.size)
      val options = lines.first().removePrefix("KeiHomeSystemFallback").toInt() - 1
      return SystemIconFallbackManifest(style, names.toSet(), options and 1 != 0, options and 2 != 0).also {
        require(it.encode().contentEquals(bytes)) { "Noncanonical fallback provenance" }
      }
    }
  }
}
