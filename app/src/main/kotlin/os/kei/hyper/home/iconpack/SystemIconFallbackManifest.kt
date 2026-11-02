package os.kei.hyper.home.iconpack

import os.kei.hyper.home.data.SystemIconFallback

/** Provenance only; this file never grants rendering or DRM permissions. */
internal data class SystemIconFallbackManifest(val style: SystemIconFallback, val names: Set<String>) {
  fun encode(): ByteArray = (listOf("KeiHomeSystemFallback1", style.name) + names.sorted())
    .joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

  companion object {
    const val PATH = "keihome-system-fallback.txt"
    const val MAX_BYTES = 512 * 1024
    fun decode(bytes: ByteArray): SystemIconFallbackManifest {
      require(bytes.size in 1..MAX_BYTES)
      val lines = bytes.toString(Charsets.UTF_8).split('\n')
      require(lines.size in 3..5003 && lines.first() == "KeiHomeSystemFallback1" && lines.last().isEmpty())
      val style = SystemIconFallback.valueOf(lines[1])
      val names = lines.drop(2).dropLast(1)
      require(names.all { validDottedName(it) && it.length <= 240 } && names.toSet().size == names.size)
      return SystemIconFallbackManifest(style, names.toSet()).also {
        require(it.encode().contentEquals(bytes)) { "Noncanonical fallback provenance" }
      }
    }
  }
}
