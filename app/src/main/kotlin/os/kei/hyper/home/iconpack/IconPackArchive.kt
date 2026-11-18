package os.kei.hyper.home.iconpack

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class IconPackArchiveIndex(
  val format: IconPackFormat,
  val entries: List<IconPackEntry>,
  val description: IconPackDescription = IconPackDescription(),
  val previews: List<IconArchiveVariant> = emptyList(),
  val notices: Set<IconPackNotice> = emptySet(),
  val directives: List<IconPackDirective> = emptyList(),
  val nativeIconsSha256: String? = null,
)

/** ZIP entries are inspected in place, never extracted using their names. Only MTZ's exact
 * root `icons` entry is copied to the caller's private staging file, with one nesting level. */
internal class IconPackArchive(private val limits: IconPackLimits = IconPackLimits()) {
  /** A lossless bridge handoff for native imports, including every night/animation resource. */
  fun copyNativeModule(
    record: StoredIconPack, destination: File, scratch: File, check: () -> Unit = {},
    onBytes: ((Long, Long) -> Unit)? = null,
  ): IconPackArchiveIndex {
    val source = when (record.format) {
      IconPackFormat.MIUI_MTZ -> record.iconsFile
      IconPackFormat.MIUI_ICONS -> record.originalFile
      else -> throw IconPackException("This import is not a native icons module")
    } ?: throw IconPackException("Missing native icons module")
    if (setOf(source.canonicalPath, destination.canonicalPath, scratch.canonicalPath).size != 3) {
      throw IconPackException("Native export requires separate staging files")
    }
    if (sha256(source, check) != record.nativeIconsSha256) throw IconPackException("Native icons checksum mismatch")
    val size = source.length()
    source.inputStream().use { input ->
      destination.outputStream().use { output ->
        copyBounded(input, output, limits.maxInputBytes, check, onBytes?.let { publish ->
          { done -> publish(done, size) }
        })
      }
    }
    val index = inspect(destination, scratch, checkCancelled = check)
    if (index.format != IconPackFormat.MIUI_ICONS || index.nativeIconsSha256 != record.nativeIconsSha256) {
      throw IconPackException("Native icons module validation failed")
    }
    return index
  }

  fun inspect(
    file: File, nestedIconsFile: File, sdk: Int = 37, checkCancelled: () -> Unit = {},
  ): IconPackArchiveIndex = inspect(file, nestedIconsFile, sdk, checkCancelled, null)

  internal fun inspectPreviouslyValidated(
    proof: IconPackValidationCache.VerifiedContents, nestedIconsFile: File,
    sdk: Int, checkCancelled: () -> Unit,
  ): IconPackArchiveIndex = inspect(proof.original, nestedIconsFile, sdk, checkCancelled, proof)

  private fun inspect(
    file: File, nestedIconsFile: File, sdk: Int, checkCancelled: () -> Unit,
    proof: IconPackValidationCache.VerifiedContents?,
  ): IconPackArchiveIndex {
    val budget = Budget()
    val paths = validate(file, budget, allowIconsModule = true, checkCancelled, proof == null)
    if ("AndroidManifest.xml" in paths && "resources.arsc" in paths) {
      // Compiled names may be shortened/obfuscated in the ZIP (e.g. Lawnicons 2.19.0).
      // Android resource-table lookup, not ZIP filenames, must supply this index.
      return IconPackArchiveIndex(IconPackFormat.APK, emptyList())
    }
    if ("icons" in paths) {
      val icons = proof?.icons ?: nestedIconsFile
      if (icons.canonicalFile == file.canonicalFile) throw IconPackException("Invalid staging file")
      if (proof?.icons == null) {
        ZipFile(file).use { zip ->
          zip.getInputStream(zip.getEntry("icons")).use { input ->
            icons.outputStream().use { output -> copyBounded(input, output, limits.maxInputBytes, checkCancelled) }
          }
        }
      }
      val innerPaths = validate(icons, budget, allowIconsModule = false, checkCancelled, proof == null)
      val inner = nativeIndex(icons, innerPaths, IconArchivePart.ICONS, checkCancelled)
      if (inner.entries.isEmpty()) throw IconPackException("MTZ contains no recognized native icons")
      val description = if ("description.xml" in paths) description(file, checkCancelled) else IconPackDescription()
      val previews = paths.filter { it.startsWith("preview/") && it.count { c -> c == '/' } == 1 }
        .filter { it.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp") }
        .sorted().take(12).map { IconArchiveVariant(it, IconResourceQualifiers("drawable-nodpi", densityDpi = 0)) }
      return inner.copy(format = IconPackFormat.MIUI_MTZ, description = description, previews = previews,
        nativeIconsSha256 = proof?.nativeSha256 ?: sha256(icons, checkCancelled))
    }
    if (paths.any { filterVariant(it) != null }) return classicIndex(file, paths, sdk, checkCancelled)
    val native = nativeIndex(file, paths, IconArchivePart.ORIGINAL, checkCancelled)
    if (native.entries.isEmpty()) throw IconPackException("No supported appfilter, APK resources, or MIUI icons module")
    return native.copy(nativeIconsSha256 = proof?.nativeSha256 ?: sha256(file, checkCancelled))
  }

  // Final resources share one budget across both levels. Only the exact outer icons
  // container is excluded; its bytes still count against that level's expansion cap.
  // Validation thus reads at most maxExpandedBytes + min(maxInputBytes, maxExpandedBytes).
  private class Budget(var entries: Int = 0, var resourceBytes: Long = 0)

  private fun validate(
    file: File, budget: Budget, allowIconsModule: Boolean, checkCancelled: () -> Unit,
    verifyPayloads: Boolean = true,
  ): Set<String> {
    if (!file.isFile || file.length() !in 1..limits.maxInputBytes) throw IconPackException("Archive size limit exceeded")
    ZipFile(file).use { zip ->
      if (zip.size() > limits.maxEntries - budget.entries) throw IconPackException("Archive entry limit exceeded")
      val names = linkedSetOf<String>()
      val files = linkedSetOf<String>()
      val directories = linkedSetOf<String>()
      val entries = zip.entries()
      val buffer = ByteArray(32 * 1024)
      var expandedBytes = 0L
      // inspect() gives compiled APKs precedence, including APKs with an icons entry.
      val nestedModule = allowIconsModule &&
        !(zip.getEntry("AndroidManifest.xml")?.isDirectory == false && zip.getEntry("resources.arsc")?.isDirectory == false)
      while (entries.hasMoreElements()) {
        checkCancelled()
        val entry = entries.nextElement()
        budget.entries++
        validatePath(entry.name)
        val name = entry.name.removeSuffix("/")
        if (!names.add(name)) throw IconPackException("Duplicate archive path: $name")
        if (entry.isDirectory) directories += name else files += name
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
          throw IconPackException("Unsupported ZIP compression")
        }
        val entryLimit = if (allowIconsModule && entry.name == "icons") limits.maxInputBytes else limits.maxEntryBytes
        val intermediate = nestedModule && entry.name == "icons" && !entry.isDirectory
        if (entry.size < 0 || entry.size > entryLimit || entry.compressedSize < 0 ||
          entry.size > maxOf(1, entry.compressedSize) * limits.maxCompressionRatio ||
          entry.size > limits.maxExpandedBytes - expandedBytes ||
          (!intermediate && entry.size > limits.maxExpandedBytes - budget.resourceBytes)
        ) throw IconPackException("Archive expansion limit exceeded")
        if (!verifyPayloads) {
          // Fresh compressed hashes plus a receipt prove these exact payloads already passed
          // inflation/CRC checks. Metadata, path and shared expansion budgets still apply.
          if (entry.isDirectory && entry.size != 0L) throw IconPackException("Corrupt ZIP directory")
          expandedBytes += entry.size
          if (!intermediate) budget.resourceBytes += entry.size
          continue
        }
        var actual = 0L
        val crc = CRC32()
        zip.getInputStream(entry).use { input ->
          while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            actual += count
            expandedBytes += count
            if (!intermediate) budget.resourceBytes += count
            if (actual > entryLimit || actual > entry.size || expandedBytes > limits.maxExpandedBytes ||
              budget.resourceBytes > limits.maxExpandedBytes
            ) {
              throw IconPackException("Archive expansion limit exceeded")
            }
            crc.update(buffer, 0, count)
          }
        }
        if (actual != entry.size || crc.value != entry.crc || (entry.isDirectory && actual != 0L)) {
          throw IconPackException("Corrupt ZIP entry: ${entry.name}")
        }
      }
      // A path cannot be both a file and a parent, even though nothing is extracted by name.
      for (name in names) {
        var slash = name.indexOf('/')
        while (slash >= 0) {
          if (name.substring(0, slash) in files) throw IconPackException("Archive file/directory collision")
          slash = name.indexOf('/', slash + 1)
        }
      }
      if (files.any { it in directories }) throw IconPackException("Archive file/directory collision")
      return files
    }
  }

  private fun classicIndex(file: File, paths: Set<String>, sdk: Int, check: () -> Unit): IconPackArchiveIndex =
    classicIndex(paths, sdk, check) { path, max -> readEntry(file, path, max, check) }

  /** Same parser for validated archive paths and the signed, content-addressed builtin catalog. */
  fun classicIndex(paths: Set<String>, sdk: Int, check: () -> Unit,
    read: (String, Int) -> ByteArray): IconPackArchiveIndex {
    val filterFiles = paths.mapNotNull(::filterVariant)
    fun filter(dark: Boolean): IconAppFilter? {
      val path = selectArchiveVariant(filterFiles, dark, 160, sdk)?.path ?: return null
      return AppFilterParser(limits).parse(read(path, limits.maxXmlBytes),
        if (dark) IconNightMode.DARK else IconNightMode.LIGHT, check)
    }
    val light = filter(false)
    val dark = filter(true)
    val resources = paths.mapNotNull { path ->
      resourceVariant(path)?.let { (name, variant) -> name to variant }
    }.groupBy({ it.first }, { it.second })
    val notices = linkedSetOf<IconPackNotice>().apply {
      addAll(light?.notices.orEmpty()); addAll(dark?.notices.orEmpty())
    }
    fun artwork(name: IconDrawableName?): IconPackArtwork? {
      if (name == null) return null
      val variants = resources[name].orEmpty()
      if (variants.isEmpty()) { notices += IconPackNotice.MISSING_DRAWABLES; return null }
      if (variants.any { !it.isRaster }) notices += IconPackNotice.SOURCE_XML_PREVIEW_UNSUPPORTED
      if (variants.any { it.qualifiers.unsupported.isNotEmpty() }) notices += IconPackNotice.UNSUPPORTED_RESOURCE_QUALIFIERS
      return IconPackArtwork.Archive(variants)
    }
    val components = light?.mappings.orEmpty().keys + dark?.mappings.orEmpty().keys
    val entries = components.map { component ->
      IconPackEntry(component.flattened, component = component,
        light = artwork(light?.mappings?.get(component)), dark = artwork(dark?.mappings?.get(component)))
    }
    if (entries.isEmpty()) throw IconPackException("Appfilter contains no supported component mappings")
    val metadata = if ("description.xml" in paths) description(read("description.xml", 64 * 1024), check) else IconPackDescription()
    return IconPackArchiveIndex(IconPackFormat.CLASSIC_ZIP, entries, description = metadata, notices = notices,
      directives = light?.directives.orEmpty() + dark?.directives.orEmpty())
  }

  private fun filterVariant(path: String): IconArchiveVariant? {
    // Prefer compiled-style resource locations over the compatibility asset when both exist.
    if (path in setOf("assets/appfilter.xml", "appfilter.xml")) {
      return IconArchiveVariant(path, IconResourceQualifiers("xml", minSdk = 0))
    }
    val parts = path.split('/')
    if (parts.size != 3 || parts[0] != "res" || parts[2] != "appfilter.xml" ||
      parts[1].substringBefore('-') !in setOf("xml", "raw")
    ) return null
    return IconResourceQualifiers.parse(parts[1])?.let { IconArchiveVariant(path, it) }
  }

  private fun resourceVariant(path: String): Pair<IconDrawableName, IconArchiveVariant>? {
    val parts = path.split('/')
    if (parts.size != 3 || parts[0] != "res" || parts[1].substringBefore('-') !in setOf("drawable", "mipmap")) return null
    if (parts[2].substringAfterLast('.').lowercase() !in setOf("png", "webp", "jpg", "jpeg", "xml")) return null
    val name = IconDrawableName.parse("${parts[1].substringBefore('-')}/${parts[2].substringBeforeLast('.')}") ?: return null
    val qualifiers = IconResourceQualifiers.parse(parts[1]) ?: return null
    return name to IconArchiveVariant(path, qualifiers)
  }

  private fun nativeIndex(file: File, paths: Set<String>, part: IconArchivePart, check: () -> Unit): IconPackArchiveIndex {
    val variants = linkedMapOf<String, MutableList<IconArchiveVariant>>()
    val staticLayers = linkedMapOf<Pair<String, Boolean>, MutableList<List<IconArchiveVariant>>>()
    val snapshots = linkedMapOf<Pair<String, Boolean>, IconPackArtwork.Archive>()
    val pairedDarkFallbacks = linkedMapOf<String, IconPackArtwork.Archive>()
    val animated = linkedSetOf<String>()
    val notices = linkedSetOf<IconPackNotice>()
    ZipFile(file).use { zip ->
      data class Manifest(val valid: Boolean, val pair: NativeMamlPair?)
      val manifests = linkedMapOf<String, Manifest>()
      fun manifest(path: String): Manifest {
        check()
        val bytes = zip.getInputStream(zip.getEntry(path)).use { input ->
          input.readNBytes(64 * 1024 + 1).also {
            if (it.size > 64 * 1024) throw IconPackException("Native manifest limit exceeded")
          }
        }
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        manifests[key]?.let { return it }
        var root = ""
        var children = 0
        BoundedPackXml.read(bytes, limits, check,
          start = { name, _, depth -> if (depth == 1) root = name else children++ })
        val pair = if (root == "Icon" && children <= 40) NativeMamlPair.parse(bytes, check) else null
        return Manifest(root == "Icon" && children > 0, pair).also {
          if (manifests.size >= 32) manifests.remove(manifests.keys.first())
          manifests[key] = it
        }
      }
      fun variant(path: String, qualifiers: IconResourceQualifiers): IconArchiveVariant {
        val entry = zip.getEntry(path)
        try {
          zip.getInputStream(entry).use { IconRasterHeader.read(it, entry.size, check) }
        } catch (failure: IconPackException) {
          throw IconPackException("Invalid native raster header: $path (${failure.message})", failure)
        }
        if (qualifiers.unsupported.isNotEmpty()) notices += IconPackNotice.UNSUPPORTED_RESOURCE_QUALIFIERS
        return IconArchiveVariant(path, qualifiers)
      }
      fun descriptor(path: String): Boolean {
        return manifest(path).valid
      }
      for (path in paths) {
        check()
        val night = path.startsWith("nightmode/")
        val pieces = path.removePrefix("nightmode/").split('/')
        if (pieces.size in 3..4 && pieces[0] == "res" && pieces[1].substringBefore('-') == "drawable") {
          val parsed = IconResourceQualifiers.parse(pieces[1]) ?: continue
          val qualifiers = if (night) parsed.copy(night = IconNightMode.DARK) else parsed
          if (pieces.size == 3 && path.endsWith(".png")) {
            val name = pieces[2].removeSuffix(".png")
            if (nativeName(name)) variants.getOrPut(name) { mutableListOf() } += variant(path, qualifiers)
          } else if (pieces.size == 4 && pieces[3] == "0.png" && nativeName(pieces[2])) {
            val foreground = path.removeSuffix("0.png") + "1.png"
            // A partial source is never a flat icon and never borrows an inferred background.
            if (foreground !in paths) continue
            val key = pieces[2] to (qualifiers.night == IconNightMode.DARK)
            val groups = staticLayers.getOrPut(key) { mutableListOf(mutableListOf(), mutableListOf()) }
            groups[0] = groups[0] + variant(path, qualifiers)
            groups[1] = groups[1] + variant(foreground, qualifiers)
          }
        }
        if (pieces.size == 3 && pieces[0] == "layer_animating_icons" &&
          pieces[2] == "config.xml" && nativeName(pieces[1])) {
          val config = try {
            NativeLayerConfig.parse(readEntry(file, path, 64 * 1024, check), limits, check)
          } catch (_: IconPackException) { continue }
          val base = path.removeSuffix("config.xml")
          val qualifiers = IconResourceQualifiers("drawable-nodpi",
            night = if (night) IconNightMode.DARK else IconNightMode.ANY, densityDpi = 0)
          val rootQuiet = base + "quiet/quietImage.png"
          val quietPaths = config.layers.map { base + it + "/quiet/quietImage.png" }
          val manifests = config.layers.map { base + it + "/fancy/manifest.xml" }
          val groups = when {
            rootQuiet in paths -> listOf(listOf(variant(rootQuiet, qualifiers)))
            quietPaths.all { it in paths } -> quietPaths.map { listOf(variant(it, qualifiers)) }
            else -> emptyList()
          }
          val known = groups.isNotEmpty() || manifests.any { it in paths && descriptor(it) }
          if (known || groups.isNotEmpty()) animated += pieces[1]
          if (groups.isNotEmpty()) snapshots[pieces[1] to night] = IconPackArtwork.Archive(
            groups.flatten(), part, layers = if (groups.size > 1) groups else emptyList(), snapshot = true)
        }
        val dynamicName = when {
          pieces.size == 3 && pieces[0] == "fancy_icons" && pieces[2] == "manifest.xml" -> pieces[1]
          pieces.size == 4 && pieces[0] == "animating_icons" && pieces[2] == "fancy" && pieces[3] == "manifest.xml" -> pieces[1]
          else -> null
        }
        if (dynamicName != null && nativeName(dynamicName)) {
          val descriptor = manifest(path)
          val pair = descriptor.pair
          val base = path.removeSuffix("manifest.xml")
          if (pair != null && base + pair.light in paths && base + pair.dark in paths) {
            for ((dark, image) in if (night) listOf(true to pair.dark)
              else listOf(false to pair.light, true to pair.dark)) {
              val qualifiers = IconResourceQualifiers("drawable-nodpi", densityDpi = 0,
                night = if (dark) IconNightMode.DARK else IconNightMode.LIGHT)
              val artwork = IconPackArtwork.Archive(
                listOf(variant(base + image, qualifiers)), part, palettePair = true)
              // Explicit night resources win over the day program's dark fallback,
              // regardless of ZIP entry order. A night program never supplies light.
              if (dark && !night) pairedDarkFallbacks[dynamicName] = artwork
              else snapshots[dynamicName to dark] = artwork
            }
          } else if (descriptor.valid) animated += dynamicName
        }
      }
    }
    if (animated.isNotEmpty()) notices += IconPackNotice.NATIVE_ANIMATION_PREVIEW_UNSUPPORTED
    fun artwork(name: String, night: Boolean): IconPackArtwork.Archive? {
      snapshots[name to night]?.let { return it }
      staticLayers[name to night]?.let {
        return IconPackArtwork.Archive(it.flatten(), part, layers = it, nativeLayers = true)
      }
      val candidates = variants[name]?.filter { night || it.qualifiers.night != IconNightMode.DARK }.orEmpty()
      if (night && candidates.none { it.qualifiers.night == IconNightMode.DARK }) return pairedDarkFallbacks[name]
      return candidates.takeIf { it.isNotEmpty() }?.let { IconPackArtwork.Archive(it, part) }
    }
    val names = variants.keys + staticLayers.keys.map { it.first } + snapshots.keys.map { it.first } + animated
    val entries = names.map { name ->
      val light = artwork(name, false)
      IconPackEntry(name, nativeName = name, light = light, dark = artwork(name, true) ?: light)
    }
    return IconPackArchiveIndex(IconPackFormat.MIUI_ICONS, entries, notices = notices)
  }

  private fun nativeName(name: String) = validNativeIconName(name) && ('.' in name || name == "android")

  private fun description(file: File, check: () -> Unit): IconPackDescription {
    return description(readEntry(file, "description.xml", 64 * 1024, check), check)
  }

  private fun description(bytes: ByteArray, check: () -> Unit): IconPackDescription {
    val values = linkedMapOf<String, String>()
    var tag: String? = null
    var value = StringBuilder()
    BoundedPackXml.read(bytes, limits, check,
      start = { name, _, depth ->
        if (depth == 1 && name != "MIUI-Theme" && name != "theme")
          throw IconPackException("Invalid MIUI description root")
        if (depth == 2) { tag = name; value = StringBuilder() }
      }, text = { text, depth -> if (depth == 2 && value.length < 4096) value.append(text.take(4096 - value.length)) },
      end = { _, depth -> if (depth == 2) tag?.let { values.putIfAbsent(it, value.toString().trim()) } })
    fun field(key: String) = values[key]?.takeIf { it.isNotBlank() }
    return IconPackDescription(field("title"), field("author"), field("designer"), field("version"), field("uiVersion"), field("description"))
  }

  companion object {
    fun validatePath(path: String) {
      if (path.isEmpty() || path.length > 1024 || path.startsWith('/') || '\\' in path || ':' in path ||
        path.any { it.isISOControl() }
      ) throw IconPackException("Unsafe archive path")
      val parts = path.removeSuffix("/").split('/')
      if (parts.size > 12 || parts.any { it.isEmpty() || it == "." || it == ".." }) throw IconPackException("Unsafe archive path")
    }

    fun readEntry(file: File, path: String, maxBytes: Int, checkCancelled: () -> Unit = {}): ByteArray {
      validatePath(path)
      return ZipFile(file).use { zip ->
        val entry = zip.getEntry(path) ?: throw IconPackException("Missing artwork entry")
        if (entry.isDirectory || entry.size !in 0..maxBytes.toLong()) throw IconPackException("Artwork size limit exceeded")
        zip.getInputStream(entry).use { input ->
          val output = java.io.ByteArrayOutputStream(minOf(entry.size.toInt(), 64 * 1024))
          copyBounded(input, output, maxBytes.toLong(), checkCancelled)
          output.toByteArray()
        }
      }
    }
  }
}

internal fun copyBounded(
  input: InputStream, output: OutputStream, limit: Long, check: () -> Unit = {},
  onBytes: ((Long) -> Unit)? = null,
): Long {
  var total = 0L
  val buffer = ByteArray(32 * 1024)
  while (true) {
    check()
    val count = input.read(buffer)
    if (count < 0) return total
    total += count
    if (total > limit) throw IconPackException("Input size limit exceeded")
    output.write(buffer, 0, count)
    onBytes?.invoke(total)
  }
}

internal fun sha256(file: File, check: () -> Unit = {}): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(32 * 1024)
    while (true) {
      check()
      val count = input.read(buffer)
      if (count < 0) break
      digest.update(buffer, 0, count)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}
