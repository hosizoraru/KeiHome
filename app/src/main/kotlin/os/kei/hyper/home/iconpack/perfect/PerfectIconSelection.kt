package os.kei.hyper.home.iconpack.perfect

/** Resolve native resource dependencies from paths before acquiring their image bodies. */
internal object PerfectIconSelection {
  fun paths(paths: Collection<String>, styles: Set<String>): Set<String> {
    require(styles.isNotEmpty())
    styles.forEach(PerfectIconFormat::style)
    fun branch(prefix: String) = paths.filter { it.startsWith("$prefix/") }.map {
      PerfectIconBlob(it.removePrefix("$prefix/"), "", 0, it)
    }
    val base = branch("icons")
    val common = branch("themes/common/layer_animating_icons")
    return buildSet {
      for (style in styles.sorted()) {
        val plan = PerfectIconFormat.plan(base, branch("themes/$style/icons"), common,
          branch("themes/$style/layer_animating_icons"),
          PerfectIconOptions(style = style, installedOnly = false, dynamic = true), emptySet())
        addAll(plan.files.values.map { it.source })
        val description = "themes/$style/description.xml"
        if (description in paths) add(description)
      }
    }
  }
}
