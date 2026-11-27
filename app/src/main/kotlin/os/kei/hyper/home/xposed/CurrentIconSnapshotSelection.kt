package os.kei.hyper.home.xposed

import java.security.MessageDigest
import os.kei.hyper.home.iconpack.validNativeIconName

/** Selects source snapshot entries only; the publisher still validates paths and bounds copied bytes. */
internal class CurrentIconSnapshotSelection(names: Set<String>) {
  private val names = names.toSet()

  /** Unknown layouts, global metadata, and non-package shape helpers are retained conservatively. */
  fun includes(path: String): Boolean {
    val pieces = path.removePrefix("nightmode/").split('/')
    val name = when {
      pieces.size >= 3 &&
        (pieces[0] == "fancy_icons" || pieces[0] == "animating_icons" ||
          pieces[0] == "layer_animating_icons") -> pieces[1]
      pieces.size >= 3 && pieces[0] == "res" &&
        (pieces[1] == "drawable" || pieces[1].startsWith("drawable-")) -> when {
          pieces.size == 3 && pieces[2].endsWith(".png") -> pieces[2].removeSuffix(".png")
          pieces.size >= 4 -> pieces[2]
          else -> null
        }
      pieces.size == 1 && pieces[0].endsWith(".png") -> pieces[0].removeSuffix(".png")
      else -> null
    }
    // Native stems also accept bare helpers; unrecognized layouts remain conservative.
    return name == null || name in names || '.' !in name || !validNativeIconName(name)
  }

  /** SHA-256 of sorted, UTF-8 names with byte-length framing; independent of archive revisions. */
  fun cacheIdentity(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (name in names.sorted()) {
      val bytes = name.toByteArray(Charsets.UTF_8)
      for (shift in 24 downTo 0 step 8) digest.update((bytes.size ushr shift).toByte())
      digest.update(bytes)
    }
    return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
  }
}
