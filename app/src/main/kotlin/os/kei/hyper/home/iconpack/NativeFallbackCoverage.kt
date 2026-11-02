package os.kei.hyper.home.iconpack

/** Unrecognized qualifiers or dynamic programs must still protect a pack's own artwork. */
internal fun nativeFallbackProtectedNames(paths: Sequence<String>): Set<String> = buildSet {
  for (path in paths) {
    val pieces = path.removePrefix("nightmode/").split('/')
    val name = when {
      pieces.size == 1 && path.endsWith(".png") -> pieces.single().removeSuffix(".png")
      pieces.size >= 2 && pieces.first() in setOf("fancy_icons", "animating_icons", "layer_animating_icons") -> pieces[1]
      pieces.size == 3 && pieces[0] == "res" && pieces[2].endsWith(".png") -> pieces[2].removeSuffix(".png")
      else -> null
    }
    if (name != null && '.' in name && validDottedName(name)) add(name)
  }
}
