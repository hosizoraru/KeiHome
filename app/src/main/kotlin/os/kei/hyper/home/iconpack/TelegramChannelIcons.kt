package os.kei.hyper.home.iconpack

import java.io.File
import java.util.zip.ZipFile

/** Completes missing official-channel artwork without changing an explicit target selection. */
internal class TelegramChannelIcons(entries: List<IconPackEntry>, private val enabled: Boolean = true) {
  private data class Candidate(val entry: IconPackEntry, val family: String, val alias: Int)

  private val candidates = (if (enabled) entries else emptyList()).mapNotNull { entry ->
    val identity = entry.component ?: entry.nativeName?.let(::nativeComponent) ?: return@mapNotNull null
    val alias = aliasPriority(identity) ?: return@mapNotNull null
    Candidate(entry, identity.packageName, alias)
  }.groupBy { it.family }.mapValues { (_, values) ->
    values.sortedWith(compareBy<Candidate> { it.alias }.thenBy { it.entry.key }
      .thenBy { it.entry.component?.flattened.orEmpty() }.thenBy { it.entry.nativeName.orEmpty() })
  }

  /** Returns one replacement entry, or null when the target should keep its own artwork. */
  fun donor(
    target: IconPackComponent,
    dark: Boolean,
    existing: IconPackEntry?,
    supportsNight: (IconPackEntry) -> Boolean = ::declaredNight,
  ): IconPackEntry? {
    if (!enabled || aliasPriority(target) == null) return null
    if (!dark && existing?.light != null) return null
    if (dark && existing != null && supportsNight(existing)) return null
    for (family in FAMILIES) {
      if (family == target.packageName) continue
      for (candidate in candidates[family].orEmpty()) {
        val entry = candidate.entry
        if ((dark || entry.light != null) && supportsNight(entry)) return entry
      }
    }
    return null
  }

  data class NativeCopy(val names: Set<String>, val paths: Map<String, String>)
  data class NativeAdditions(val resources: Map<String, ByteArray>, val names: Set<String>)

  fun nativeAdditions(
    archive: File, targets: List<IconPackComponent>, existing: (IconPackComponent) -> IconPackEntry?,
    limits: IconPackLimits, check: () -> Unit,
  ): NativeAdditions {
    val output = linkedMapOf<String, ByteArray>()
    val written = linkedSetOf<String>()
    if (!enabled) return NativeAdditions(output, written)
    check()
    if (targets.size > 5000) throw IconPackException("Telegram native target limit exceeded")
    ZipFile(archive).use { zip ->
      val entries = linkedMapOf<String, java.util.zip.ZipEntry>()
      var expanded = 0L
      val enumeration = zip.entries()
      while (enumeration.hasMoreElements()) {
        check()
        val entry = enumeration.nextElement()
        if (entry.isDirectory) continue
        if (entries.size >= limits.maxEntries || entry.size < 0 ||
          entry.size > limits.maxEntryBytes || expanded > limits.maxExpandedBytes - entry.size)
          throw IconPackException("Telegram native resource limit exceeded")
        if (entries.put(entry.name, entry) != null) throw IconPackException("Duplicate Telegram native resource")
        expanded += entry.size
      }
      val budget = minOf(64L * 1024 * 1024, limits.maxExpandedBytes - expanded)
      val bytes = hashMapOf<String, ByteArray>()
      var added = 0L
      for (target in targets) {
        check()
        if (!isDefaultTarget(target)) continue
        val primary = nativeLauncherActivityName(target) ?: target.packageName
        for (name in listOf(primary, target.packageName).distinct()) {
          if (entries.keys.any { renameNativePath(it, name, name) != null }) written += name
        }
        if (primary in written) continue
        val copy = nativeCopy(target, existing(target), entries.keys + output.keys, check) ?: continue
        if (entries.size.toLong() + output.size + copy.paths.size > limits.maxEntries) continue
        val length = copy.paths.values.sumOf { entries.getValue(it).size }
        if (length > budget - added) continue
        val complete = linkedMapOf<String, ByteArray>()
        for ((destination, source) in copy.paths) {
          check()
          complete[destination] = bytes.getOrPut(source) {
            val entry = entries.getValue(source)
            // Budget the complete closure before allocating or publishing any of it.
            val data = ByteArray(entry.size.toInt())
            zip.getInputStream(entry).use { input ->
              var offset = 0
              while (offset < data.size) {
                check()
                val count = input.read(data, offset, minOf(8192, data.size - offset))
                if (count <= 0) throw IconPackException("Truncated Telegram native resource")
                offset += count
              }
              check()
              if (input.read() != -1) throw IconPackException("Oversized Telegram native resource")
            }
            data
          }
        }
        output.putAll(complete)
        written += copy.names
        added += length
      }
    }
    return NativeAdditions(output, written)
  }

  /** Copy native topology rather than baking the quiet fallback into a single foreground plane. */
  fun nativeCopy(
    target: IconPackComponent, existing: IconPackEntry?, paths: Set<String>, check: () -> Unit = {},
  ): NativeCopy? {
    check()
    val day = donor(target, false, existing) ?: return null
    if (donor(target, true, existing) != day) return null
    val source = day.nativeName?.takeIf(::validNativeIconName) ?: return null
    val closure = linkedMapOf<String, String>()
    for (path in paths) {
      check()
      renameNativePath(path, source, source)?.let { closure[path] = it }
    }
    if (closure.isEmpty()) return null
    val names = linkedSetOf<String>()
    val copies = linkedMapOf<String, String>()
    for (name in listOfNotNull(nativeLauncherActivityName(target), target.packageName).distinct()) {
      if (!validNativeIconName(name) || name.toByteArray(Charsets.UTF_8).size > 240) continue
      // A partial authored namespace is authoritative too; never graft a donor's night onto it.
      if (paths.any { renameNativePath(it, name, name) != null }) continue
      names += name
      for (path in closure.keys) {
        check()
        copies[requireNotNull(renameNativePath(path, source, name))] = path
      }
    }
    return if (copies.isEmpty()) null else NativeCopy(names, copies)
  }

  private fun renameNativePath(path: String, source: String, target: String): String? {
    val night = if (path.startsWith("nightmode/")) "nightmode/" else ""
    val plain = path.removePrefix(night)
    if (plain == "$source.png") return "$night$target.png"
    for (root in listOf("fancy_icons", "animating_icons", "layer_animating_icons")) {
      val prefix = "$root/$source/"
      if (plain.startsWith(prefix)) return "$night$root/$target/${plain.removePrefix(prefix)}"
    }
    val parts = plain.split('/')
    if (parts.size < 3 || parts[0] != "res" ||
      (parts[1] != "drawable" && !parts[1].startsWith("drawable-"))) return null
    return when {
      parts.size == 3 && parts[2] == "$source.png" -> "$night${parts[0]}/${parts[1]}/$target.png"
      parts.size >= 4 && parts[2] == source ->
        "$night${parts[0]}/${parts[1]}/$target/" + parts.drop(3).joinToString("/")
      else -> null
    }
  }

  companion object {
    const val NATIVE_REVISION = "telegram-native-closure-1"
    private val FAMILIES = listOf(
      "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
    )
    private const val DEFAULT_ALIAS = "org.telegram.messenger.DefaultIcon"
    private const val LEGACY_ALIAS = "org.telegram.ui.LaunchActivity"

    fun offlineTargets(): List<IconPackComponent> = FAMILIES.map { IconPackComponent(it) }

    fun isFamily(packageName: String): Boolean = packageName in FAMILIES

    fun foreignLauncherClass(target: IconPackComponent): String? =
      target.className?.takeIf {
        it.startsWith("org.telegram.") && !isFamily(target.packageName) && !it.startsWith("${target.packageName}.")
      }

    /** Named alternate icons stay excluded even when they launch the same activity. */
    fun isDefaultTarget(target: IconPackComponent): Boolean = aliasPriority(target) != null

    /** A shared compiled drawable needs the caller's resource-aware proof instead. */
    fun declaredNight(entry: IconPackEntry): Boolean =
      (entry.dark != null && entry.dark != entry.light) ||
        listOfNotNull(entry.light, entry.dark).filterIsInstance<IconPackArtwork.Archive>().any { artwork ->
          (artwork.variants.asSequence() + artwork.layers.asSequence().flatten()).any {
            it.qualifiers.night == IconNightMode.DARK || it.path.startsWith("nightmode/")
          }
        }

    private fun aliasPriority(target: IconPackComponent): Int? {
      if (!isFamily(target.packageName)) return null
      val name = target.className ?: return 0
      val fullName = if (name.startsWith('.')) target.packageName + name else name
      return when (fullName) {
        DEFAULT_ALIAS, "${target.packageName}.DefaultIcon" -> 1
        LEGACY_ALIAS -> 2
        else -> null
      }
    }

    private fun nativeComponent(name: String): IconPackComponent? {
      if (isFamily(name)) return IconPackComponent(name)
      if ('#' in name) {
        if (name.count { it == '#' } != 1) return null
        return IconPackComponent(name.substringBefore('#'), name.substringAfter('#'))
          .takeIf { aliasPriority(it) != null }
      }
      // A bare class stem is owned only when it is a channel's exact DefaultIcon name.
      val family = FAMILIES.firstOrNull { name == "$it.DefaultIcon" } ?: return null
      return IconPackComponent(family, name)
    }
  }
}
