package os.kei.hyper.home.iconpack

import java.util.zip.ZipFile

/** Static lookup and MAML use different filenames for a launcher class outside its APK namespace. */
internal object ForeignTelegramAliases {
  fun additions(archive: ZipFile, targets: List<IconPackComponent>, nativeLayers: Boolean, check: () -> Unit,
    original: (IconPackComponent, dark: Boolean) -> ByteArray?): Map<String, ByteArray> =
    additions(archive.entries().asSequence().filterNot { it.isDirectory }.map { it.name }, targets, nativeLayers, check,
      read = { path ->
        archive.getInputStream(archive.getEntry(path)).use { it.readNBytes(CustomIconResources.MAX_BYTES + 1) }
      }, original = original)

  fun additions(
    paths: Sequence<String>, targets: List<IconPackComponent>, nativeLayers: Boolean, check: () -> Unit,
    read: (String) -> ByteArray,
    original: (IconPackComponent, dark: Boolean) -> ByteArray?,
  ): Map<String, ByteArray> {
    val candidates = targets.filter { TelegramChannelIcons.foreignLauncherClass(it) != null }.distinct()
    if (candidates.isEmpty()) return emptyMap()
    val wanted = candidates.flatMapTo(hashSetOf()) { target ->
      listOfNotNull(target.packageName, nativeLauncherActivityName(target))
    }
    val owners = linkedMapOf<String, MutableList<String>>()
    for (path in paths) {
      check()
      val owner = CustomIconResources.owner(path) ?: continue
      if (owner in wanted) owners.getOrPut(owner) { arrayListOf() }.add(path)
    }
    return buildMap {
      var bytesCopied = 0L
      fun copy(source: String, target: String, resources: List<String>) {
        for (path in resources) {
          check()
          val payload = read(path)
          bytesCopied += payload.size
          require(payload.size <= CustomIconResources.MAX_BYTES &&
            bytesCopied <= NativeDefaultAliasCompletion.MAX_COPY_BYTES) { "Telegram icon aliases exceed the copy budget" }
          val (name, bytes) = CustomIconResources.rekey(path, payload, source, target)
          put(name, bytes)
        }
      }
      val activeCounts = targets.filter { it.className != null }.distinct().groupingBy { it.packageName }.eachCount()
      for (target in candidates) {
        check()
        val pkg = target.packageName
        val exact = nativeLauncherActivityName(target) ?: continue
        val exactResources = owners[exact].orEmpty()
        val packageResources = owners[pkg].orEmpty()
        if (exactResources.isEmpty() && packageResources.isNotEmpty()) copy(pkg, exact, packageResources)

        // A foreign class can use the package MAML root. Only derive that root from an
        // activity when the package has a single active launcher component.
        val packageProgram = packageResources.any { isProgram(it, pkg) }
        val needsPackageProgram = activeCounts[pkg] == 1 && !packageProgram
        val exactProgram = exactResources.filter { isProgram(it, exact) }
        if (needsPackageProgram && exactProgram.isNotEmpty()) {
          copy(exact, pkg, exactProgram)
        }
        val needsExact = exactResources.isEmpty() && packageResources.isEmpty()
        if (!needsExact && (!needsPackageProgram || exactProgram.isNotEmpty())) continue
        val authored = if (exactResources.isNotEmpty()) exactResources else packageResources
        val day = flatPng(authored, false, read) ?: original(target, false) ?: continue
        val night = flatPng(authored, true, read)?.takeIf { NativeDayNightIcon.pngSize(it) == NativeDayNightIcon.pngSize(day) }
          ?: if (authored.isEmpty()) original(target, true) ?: day else day
        if (needsExact) putAll(NativeDayNightIcon.artworkEntries(exact, day, night))
        if (needsPackageProgram) {
          val entries = NativeDayNightIcon.entries(pkg, day, night)
          putAll(entries.filterKeys { it !in packageResources })
          if (nativeLayers) {
            val root = "layer_animating_icons/$pkg"
            put("$root/config.xml", CONFIG)
            put("$root/0/quiet/quietImage.png", day)
            put("nightmode/$root/config.xml", CONFIG)
            put("nightmode/$root/0/quiet/quietImage.png", night)
          }
        }
      }
    }
  }

  private fun isProgram(path: String, owner: String): Boolean {
    val root = path.removePrefix("nightmode/").substringBefore('/')
    return when (root) {
      "fancy_icons", "animating_icons", "layer_animating_icons" ->
        path.removePrefix("nightmode/").startsWith("$root/$owner/")
      else -> false
    }
  }

  private fun flatPng(paths: List<String>, dark: Boolean, read: (String) -> ByteArray): ByteArray? {
    val path = paths.firstOrNull {
      it.startsWith("nightmode/") == dark && it.endsWith(".png") &&
        it.removePrefix("nightmode/").count { c -> c == '/' } in 0..2 &&
        !it.removePrefix("nightmode/").startsWith("fancy_icons/")
    } ?: return null
    val bytes = read(path)
    return bytes.takeIf { runCatching { NativeDayNightIcon.pngSize(it) }.isSuccess }
  }

  private val CONFIG = """
    <Config><Variables>
      <Variable name="layer0" type="string" value="0"/>
      <Variable name="layer_only_quiet0" type="string" value="true"/>
    </Variables></Config>
  """.trimIndent().toByteArray(Charsets.UTF_8)
}
