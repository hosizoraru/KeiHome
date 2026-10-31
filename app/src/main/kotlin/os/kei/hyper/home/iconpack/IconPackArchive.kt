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
    source.inputStream().use { input -> destination.outputStream().use { copyBounded(input, it, limits.maxInputBytes, check) } }
    val index = inspect(destination, scratch, checkCancelled = check)
    if (index.format != IconPackFormat.MIUI_ICONS || index.nativeIconsSha256 != record.nativeIconsSha256) {
      throw IconPackException("Native icons module validation failed")
    }
    return index
  }

  fun inspect(
    file: File, nestedIconsFile: File, sdk: Int = 37, checkCancelled: () -> Unit = {},
  ): IconPackArchiveIndex {
    val budget = Budget()
    val paths = validate(file, budget, allowIconsModule = true, checkCancelled)
    if ("AndroidManifest.xml" in paths && "resources.arsc" in paths) {
      // Compiled names may be shortened/obfuscated in the ZIP (e.g. Lawnicons 2.19.0).
      // Android resource-table lookup, not ZIP filenames, must supply this index.
      return IconPackArchiveIndex(IconPackFormat.APK, emptyList())
    }
    if ("icons" in paths) {
      if (nestedIconsFile.canonicalFile == file.canonicalFile) throw IconPackException("Invalid staging file")
      ZipFile(file).use { zip ->
        zip.getInputStream(zip.getEntry("icons")).use { input ->
          nestedIconsFile.outputStream().use { output -> copyBounded(input, output, limits.maxInputBytes, checkCancelled) }
        }
      }
      val innerPaths = validate(nestedIconsFile, budget, allowIconsModule = false, checkCancelled)
      val inner = nativeIndex(nestedIconsFile, innerPaths, IconArchivePart.ICONS, checkCancelled)
      if (inner.entries.isEmpty()) throw IconPackException("MTZ contains no recognized native icons")
      val description = if ("description.xml" in paths) description(file, checkCancelled) else IconPackDescription()
      val previews = paths.filter { it.startsWith("preview/") && it.count { c -> c == '/' } == 1 }
        .filter { it.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp") }
        .sorted().take(12).map { IconArchiveVariant(it, IconResourceQualifiers("drawable-nodpi", densityDpi = 0)) }
      return inner.copy(format = IconPackFormat.MIUI_MTZ, description = description, previews = previews,
        nativeIconsSha256 = sha256(nestedIconsFile, checkCancelled))
    }
    if (paths.any { filterVariant(it) != null }) return classicIndex(file, paths, sdk, checkCancelled)
    val native = nativeIndex(file, paths, IconArchivePart.ORIGINAL, checkCancelled)
    if (native.entries.isEmpty()) throw IconPackException("No supported appfilter, APK resources, or MIUI icons module")
    return native.copy(nativeIconsSha256 = sha256(file, checkCancelled))
  }

  private class Budget(var entries: Int = 0, var bytes: Long = 0)

  private fun validate(
    file: File, budget: Budget, allowIconsModule: Boolean, checkCancelled: () -> Unit,
  ): Set<String> {
    if (!file.isFile || file.length() !in 1..limits.maxInputBytes) throw IconPackException("Archive size limit exceeded")
    ZipFile(file).use { zip ->
      if (zip.size() > limits.maxEntries - budget.entries) throw IconPackException("Archive entry limit exceeded")
      val names = linkedSetOf<String>()
      val files = linkedSetOf<String>()
      val directories = linkedSetOf<String>()
      val entries = zip.entries()
      val buffer = ByteArray(32 * 1024)
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
        if (entry.size < 0 || entry.size > entryLimit || entry.compressedSize < 0 ||
          entry.size > maxOf(1, entry.compressedSize) * limits.maxCompressionRatio ||
          entry.size > limits.maxExpandedBytes - budget.bytes
        ) throw IconPackException("Archive expansion limit exceeded")
        var actual = 0L
        val crc = CRC32()
        zip.getInputStream(entry).use { input ->
          while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            actual += count
            budget.bytes += count
            if (actual > entryLimit || actual > entry.size || budget.bytes > limits.maxExpandedBytes) {
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

  private fun classicIndex(file: File, paths: Set<String>, sdk: Int, check: () -> Unit): IconPackArchiveIndex {
    val filterFiles = paths.mapNotNull(::filterVariant)
    fun filter(dark: Boolean): IconAppFilter? {
      val path = selectArchiveVariant(filterFiles, dark, 160, sdk)?.path ?: return null
      return AppFilterParser(limits).parse(readEntry(file, path, limits.maxXmlBytes, check),
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
    return IconPackArchiveIndex(IconPackFormat.CLASSIC_ZIP, entries, notices = notices,
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
    val animated = linkedSetOf<String>()
    val notices = linkedSetOf<IconPackNotice>()
    ZipFile(file).use { zip ->
      for (path in paths) {
        check()
        val night = path.startsWith("nightmode/")
        val pieces = path.removePrefix("nightmode/").split('/')
        if (pieces.size == 3 && pieces[0] == "res" && pieces[1].substringBefore('-') == "drawable" && path.endsWith(".png")) {
          val name = pieces[2].removeSuffix(".png")
          if (!nativeName(name)) continue
          val header = zip.getInputStream(zip.getEntry(path)).use { it.readNBytes(24) }
          if (!pngHeader(header)) throw IconPackException("Invalid native PNG header: $path")
          val parsed = IconResourceQualifiers.parse(pieces[1]) ?: continue
          val qualifiers = if (night) parsed.copy(night = IconNightMode.DARK) else parsed
          variants.getOrPut(name) { mutableListOf() } += IconArchiveVariant(path, qualifiers)
          if (qualifiers.unsupported.isNotEmpty()) notices += IconPackNotice.UNSUPPORTED_RESOURCE_QUALIFIERS
        }
        val dynamicName = when {
          pieces.size == 3 && pieces[0] == "fancy_icons" && pieces[2] == "manifest.xml" -> pieces[1]
          pieces.size == 4 && pieces[0] == "animating_icons" && pieces[2] == "fancy" && pieces[3] == "manifest.xml" -> pieces[1]
          pieces.size == 5 && pieces[0] == "layer_animating_icons" && pieces[3] == "fancy" && pieces[4] == "manifest.xml" -> {
            val config = (if (night) "nightmode/" else "") + "layer_animating_icons/${pieces[1]}/config.xml"
            if (config in paths && declaresLayer(file, config, pieces[2], check)) pieces[1] else null
          }
          else -> null
        }
        if (dynamicName != null && nativeName(dynamicName)) {
          var root = ""
          var children = 0
          BoundedPackXml.read(readEntry(file, path, 64 * 1024, check), limits, check,
            start = { name, _, depth -> if (depth == 1) root = name else children++ })
          if (root == "Icon" && children > 0) animated += dynamicName
        }
      }
    }
    if (animated.isNotEmpty()) notices += IconPackNotice.NATIVE_ANIMATION_PREVIEW_UNSUPPORTED
    val entries = (variants.keys + animated).map { name ->
      val artwork = variants[name]?.let { IconPackArtwork.Archive(it.toList(), part) }
      IconPackEntry(name, nativeName = name, light = artwork, dark = artwork)
    }
    return IconPackArchiveIndex(IconPackFormat.MIUI_ICONS, entries, notices = notices)
  }

  private fun nativeName(name: String) = validDottedName(name) && ('.' in name || name == "android")

  private fun pngHeader(bytes: ByteArray): Boolean {
    if (bytes.size != 24 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) ||
      !bytes.copyOfRange(12, 16).contentEquals(byteArrayOf(73, 72, 68, 82))
    ) return false
    val buffer = java.nio.ByteBuffer.wrap(bytes)
    val width = buffer.getInt(16)
    val height = buffer.getInt(20)
    return width in 1..8192 && height in 1..8192 && width.toLong() * height <= 32L * 1024 * 1024
  }

  private fun declaresLayer(file: File, path: String, layer: String, check: () -> Unit): Boolean {
    var validRoot = false
    var declared = false
    BoundedPackXml.read(readEntry(file, path, 64 * 1024, check), limits, check, start = { name, attrs, depth ->
      if (depth == 1) validRoot = name == "Config"
      if (name == "Variable" && attrs["name"].orEmpty().matches(Regex("layer[0-9]+")) && attrs["value"] == layer) declared = true
    })
    return validRoot && declared
  }

  private fun description(file: File, check: () -> Unit): IconPackDescription {
    val values = linkedMapOf<String, String>()
    var tag: String? = null
    var value = StringBuilder()
    BoundedPackXml.read(readEntry(file, "description.xml", 64 * 1024, check), limits, check,
      start = { name, _, depth ->
        if (depth == 1 && name != "MIUI-Theme") throw IconPackException("Invalid MIUI description root")
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

internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long, check: () -> Unit = {}): Long {
  var total = 0L
  val buffer = ByteArray(32 * 1024)
  while (true) {
    check()
    val count = input.read(buffer)
    if (count < 0) return total
    total += count
    if (total > limit) throw IconPackException("Input size limit exceeded")
    output.write(buffer, 0, count)
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
