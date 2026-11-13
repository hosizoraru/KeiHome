package os.kei.hyper.home.iconpack

/** One-way, missing-only artwork sharing for the two platform telephony services. */
internal class PhoneCompanionIcons(entries: List<IconPackEntry>, private val enabled: Boolean = true) {
  val authoredTargets: Set<String> = TARGETS.filterTo(hashSetOf()) { pkg ->
    entries.any { belongsTo(it, pkg) }
  }
  private val source = entries.filter { belongsTo(it, SOURCE) && (it.light != null || it.dark != null) }
    .let { donors ->
      donors.filter { it.component == IconPackComponent(SOURCE) || it.nativeName == SOURCE }
        .minByOrNull { it.key }
        ?: donors.takeIf { it.map { entry -> entry.light to entry.dark }.distinct().size == 1 }
          ?.minByOrNull { it.key }
    }?.let { it.copy(light = it.light ?: it.dark, dark = it.dark ?: it.light) }

  fun donor(target: IconPackComponent, existing: IconPackEntry?): IconPackEntry? =
    source?.takeIf { enabled && target.packageName in TARGETS &&
      target.packageName !in authoredTargets && existing == null }

  fun completionTargets(): List<IconPackComponent> =
    if (!enabled || source == null) emptyList()
    else TARGETS.filterNot { it in authoredTargets }.map(::IconPackComponent)

  companion object {
    const val SOURCE = "com.android.incallui"
    val TARGETS = listOf("com.android.server.telecom", "com.android.phone")

    private fun belongsTo(entry: IconPackEntry, pkg: String): Boolean =
      entry.component?.let { it.packageName == pkg } ?: (entry.nativeName?.let {
        it == pkg || it.startsWith("$pkg#") || (pkg in TARGETS && it.startsWith("$pkg."))
      } == true)
  }
}
