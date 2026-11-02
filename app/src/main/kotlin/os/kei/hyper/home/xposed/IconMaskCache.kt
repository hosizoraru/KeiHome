package os.kei.hyper.home.xposed

/** Worker-owned verdicts: retain other packages when just one installed source changes. */
internal class IconMaskCache<C, S>(
  private val maxConfigurations: Int = 4,
  private val maxPackages: Int = 2048,
) {
  private data class Entry<S>(val source: S, val status: LauncherMaskStatus, val retryAt: Long)
  private val configurations = LinkedHashMap<C, LinkedHashMap<String, Entry<S>>>(4, 0.75f, true)

  init { require(maxConfigurations > 0 && maxPackages > 0) }

  fun reuse(configuration: C, sources: Map<String, S>, now: Long): Map<String, LauncherMaskStatus> {
    val entries = configurations[configuration] ?: return emptyMap()
    entries.keys.retainAll(sources.keys)
    return sources.mapNotNull { (name, source) ->
      entries[name]?.takeIf { it.source == source && now < it.retryAt }?.let { name to it.status }
    }.toMap()
  }

  fun record(configuration: C, sources: Map<String, S>, results: Map<String, LauncherMaskStatus>, now: Long) {
    val entries = configurations.getOrPut(configuration) { linkedMapOf() }
    sources.forEach { (name, source) ->
      val status = results[name] ?: LauncherMaskStatus.UNKNOWN
      entries.remove(name)
      entries[name] = Entry(source, status, if (status == LauncherMaskStatus.UNKNOWN) now + 60_000 else Long.MAX_VALUE)
    }
    while (entries.size > maxPackages) entries.remove(entries.keys.first())
    while (configurations.size > maxConfigurations) configurations.remove(configurations.keys.first())
  }

  fun invalidate(packageName: String? = null) {
    if (packageName == null) configurations.clear()
    else configurations.values.forEach { it.remove(packageName) }
  }
}
