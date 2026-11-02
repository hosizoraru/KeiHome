package os.kei.hyper.home.data

internal data class IconBrowserOptions(
  val scope: Int = 0, val origin: Int = 0, val coverage: Int = 0, val support: Int = 0,
  val sort: Int = 0, val showOverlays: Boolean = false, val reverse: Boolean = false,
) {
  fun stored(): Map<String, Any> = mapOf("scope" to scope, "origin" to origin, "coverage" to coverage,
    "support" to support, "sort" to sort, "overlays" to showOverlays, "reverse" to reverse)

  companion object {
    fun restore(values: Map<String, *>): IconBrowserOptions {
      fun index(key: String, max: Int) = (values[key] as? Int)?.takeIf { it in 0..max } ?: 0
      return IconBrowserOptions(index("scope", 2), index("origin", 2), index("coverage", 2),
        index("support", 4), index("sort", 3), values["overlays"] as? Boolean ?: false,
        values["reverse"] as? Boolean ?: false)
    }
  }
}
