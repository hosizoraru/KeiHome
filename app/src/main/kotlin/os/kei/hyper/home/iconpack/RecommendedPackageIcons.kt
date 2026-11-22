package os.kei.hyper.home.iconpack

import os.kei.hyper.home.data.RecommendedIconMappings

/** One-way artwork sharing for packages with no dedicated entry. */
internal class RecommendedPackageIcons(entries: List<IconPackEntry>, mappings: RecommendedIconMappings) {
  val authoredTargets: Set<String> = TARGETS.filterTo(hashSetOf()) { pkg ->
    entries.any { belongsTo(it, pkg) }
  }
  private val sources = buildMap {
    if (mappings.phoneCompanions) selectDonor(entries, PHONE)?.let { donor ->
      PHONE_TARGETS.forEach { put(it, donor) }
    }
    if (mappings.weiboLite) selectDonor(entries, WEIBO)?.let { put(WEIBO_LITE, it) }
  }

  fun donor(target: IconPackComponent, existing: IconPackEntry?): IconPackEntry? =
    sources[target.packageName]?.takeIf { target.packageName !in authoredTargets && existing == null }

  fun completionTargets(): List<IconPackComponent> =
    sources.keys.filterNot { it in authoredTargets }.map(::IconPackComponent)

  companion object {
    const val PHONE = "com.android.incallui"
    val PHONE_TARGETS = listOf("com.android.server.telecom", "com.android.phone")
    const val WEIBO = "com.sina.weibo"
    const val WEIBO_LITE = "com.weico.international"
    val TARGETS = PHONE_TARGETS + WEIBO_LITE

    private fun selectDonor(entries: List<IconPackEntry>, pkg: String): IconPackEntry? {
      val donors = entries.filter { belongsTo(it, pkg) && (it.light != null || it.dark != null) }
      val selected = donors.filter { it.component == IconPackComponent(pkg) || it.nativeName == pkg }
        .minByOrNull { it.key }
        ?: donors.takeIf { it.map { entry -> entry.light to entry.dark }.distinct().size == 1 }
          ?.minByOrNull { it.key }
      return selected?.copy(light = selected.light ?: selected.dark, dark = selected.dark ?: selected.light)
    }

    private fun belongsTo(entry: IconPackEntry, pkg: String): Boolean =
      entry.component?.let { it.packageName == pkg } ?: (entry.nativeName?.let {
        it == pkg || it.startsWith("$pkg#") || (pkg in TARGETS && it.startsWith("$pkg.")) ||
          (pkg == WEIBO && it == "$WEIBO.SplashActivity")
      } == true)
  }
}
