package os.kei.hyper.home.iconpack.perfect

internal object PerfectIconStyles {
  val initialOptions = PerfectIconOptions(style = "HyperOS4")
  private val priority = listOf("HyperOS4", "HyperOSClassic", "HyperOS3", "Hyper", "default")

  fun ordered(styles: Iterable<String>, downloaded: Iterable<String> = emptyList()): List<String> {
    val ready = downloaded.map { it.ifEmpty { "default" } }.toHashSet()
    return styles.map { it.ifEmpty { "default" } }.distinct()
      .sortedWith(compareBy<String> { it !in ready }
        .thenBy { priority.indexOf(it).takeIf { rank -> rank >= 0 } ?: priority.size }
        .thenBy { it })
  }
}
