package os.kei.hyper.home.xposed

import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import os.kei.hyper.home.iconpack.IconThemeMetadata
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.nativeLauncherActivityName
import os.kei.hyper.home.iconpack.validDottedName
import os.kei.hyper.home.iconpack.validNativeIconName
import os.kei.hyper.home.status.SystemIconThemeReport

/**
 * Worker-thread resource inventory, not a monochrome-capability or renderability probe.
 * All filesystem access is in snapshot(); invalidate() only advances an atomic revision.
 *
 * ThemeIconLoader._getIconNames uses exact package/component filenames plus a native alias
 * table. This index deliberately does not guess aliases or match package-name prefixes.
 * The caller must confirm native stock-fallback/mod-source gates before enabling them.
 */
internal class ActiveIconTheme(
  private val activeIcons: File = File("/data/system/theme/icons"),
  private val stockIcons: File = File("/system/media/theme/default/icons"),
  private val modIcons: File = File("/system/media/theme/miui_mod_icons"),
  private val maxEntries: Int = os.kei.hyper.home.iconpack.IconPackLimits().maxEntries,
  private val maxArchiveBytes: Long = os.kei.hyper.home.iconpack.IconPackLimits().maxInputBytes,
  private val maxDescriptorsTotal: Long = 32L * 1024 * 1024,
) {
  data class Snapshot(
    val signature: Signature,
    val coveredPackages: Set<String>,
    val complete: Boolean,
  )

  data class Signature(
    val revision: Long,
    val sources: List<SourceStamp>,
    val stockFallback: Boolean,
    val modEnabled: Boolean,
  )

  data class SourceStamp(
    val path: String,
    val kind: Kind,
    val identity: String? = null,
    val size: Long = 0,
    val modified: String? = null,
  )

  enum class Kind { MISSING, FILE, DIRECTORY, UNKNOWN }

  private data class Inventory(val names: Set<String>, val nightNames: Set<String> = emptySet(),
    val title: String? = null, val timeNames: Map<String, os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon.Kind> = emptyMap(),
    val version: String? = null, val complete: Boolean = true)
  private data class Index(val stamp: SourceStamp, val revision: Long, val inventory: Inventory?) {
    val names get() = inventory?.names
  }
  private val revision = AtomicLong()
  private val cached = mutableMapOf<String, Index>()

  init { require(maxEntries > 0 && maxArchiveBytes > 0 && maxDescriptorsTotal > 0) }

  fun invalidate() { revision.incrementAndGet() }

  @Synchronized
  fun timeIconPackages(packages: Set<String>, components: Map<String, Set<String>>):
    Map<String, os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon.Kind> {
    val generation = revision.get()
    val active = index(activeIcons, directory = false, generation)
    val primary = if (active.stamp.kind == Kind.MISSING) index(stockIcons, directory = false, generation) else active
    if (primary.inventory?.complete != true) return emptyMap()
    val names = primary.inventory?.timeNames ?: return emptyMap()
    return packages.filter(::validDottedName).mapNotNull { pkg ->
      val kind = names[pkg] ?: components[pkg].orEmpty().mapNotNull { component ->
        exactNames(pkg, component).firstNotNullOfOrNull { names[it] }
      }.distinct().singleOrNull()
      kind?.let { pkg to it }
    }.toMap().takeIf { generation == revision.get() }.orEmpty()
  }

  /** Uses the same cached source inventory as the native-selection policy. */
  @Synchronized
  fun describe(): SystemIconThemeReport {
    val generation = revision.get()
    val active = index(activeIcons, directory = false, generation)
    val primary = if (active.stamp.kind == Kind.MISSING) index(stockIcons, directory = false, generation) else active
    val sources = listOf(primary, index(modIcons, directory = true, generation))
    val complete = generation == revision.get() && sources.all { it.inventory?.complete == true }
    val names = if (complete) sources.flatMap { it.inventory?.names.orEmpty() }.toSet() else emptySet()
    val nightNames = if (complete) sources.flatMap { it.inventory?.nightNames.orEmpty() }.toSet() else emptySet()
    return SystemIconThemeReport(
      source = when (active.stamp.kind) {
        Kind.MISSING -> SystemIconThemeReport.Source.DEFAULT
        Kind.FILE -> SystemIconThemeReport.Source.THEME
        else -> SystemIconThemeReport.Source.UNAVAILABLE
      },
      title = primary.inventory?.title,
      archivePath = primary.stamp.path.takeIf { primary.stamp.kind == Kind.FILE },
      resourceCount = if (complete) names.size else 0,
      nightResourceCount = if (complete) nightNames.size else 0,
      complete = complete,
      revision = sources.joinToString(";") { "${it.stamp.identity}:${it.stamp.size}:${it.stamp.modified}:$generation" },
      version = primary.inventory?.version,
    )
  }

  /**
   * components maps each launcher package to its ActivityInfo.name values (full or relative).
   * A matching component protects its whole package because the existing Dart veto is package-keyed.
   * Unknown/corrupt/oversized or budget-limited sources never contribute partial coverage.
   *
   * The platform mod directory is immutable between theme generations: nested file edits must
   * call invalidate(). Archive replacements are also detected by inode, size and modification time.
   */
  @Synchronized
  fun snapshot(
    packages: Set<String>,
    components: Map<String, Set<String>> = emptyMap(),
    allowStockFallback: Boolean = false,
    modEnabled: Boolean = false,
  ): Snapshot {
    val generation = revision.get()
    val active = index(activeIcons, directory = false, generation)
    val sources = mutableListOf(active)
    if (active.stamp.kind == Kind.MISSING || allowStockFallback) {
      sources += index(stockIcons, directory = false, generation)
    }
    if (modEnabled) sources += index(modIcons, directory = true, generation)
    val signature = Signature(generation, sources.map { it.stamp }, allowStockFallback, modEnabled)
    if (generation != revision.get() || sources.any { it.inventory?.complete != true }) {
      return Snapshot(signature, emptySet(), complete = false)
    }
    val names = sources.flatMap { it.names.orEmpty() }.toHashSet()
    val covered = packages.filterTo(linkedSetOf()) { packageName ->
      validDottedName(packageName) && (packageName in names || components[packageName].orEmpty().any { component ->
        exactNames(packageName, component).any { it in names }
      })
    }
    return Snapshot(signature, covered, complete = true)
  }

  private fun exactNames(pkg: String, component: String): List<String> {
    val cls = if (component.startsWith('.')) pkg + component else component
    if (!validDottedName(pkg) || !validDottedName(cls)) return emptyList()
    val canonical = nativeLauncherActivityName(IconPackComponent(pkg, cls)) ?: return emptyList()
    // The native static reader tries the bare foreign class after its qualified canonical name.
    return if (cls.startsWith(pkg)) listOf(canonical) else listOf(canonical, cls)
  }

  private fun index(file: File, directory: Boolean, generation: Long): Index {
    val sourceStamp = stamp(file)
    cached[sourceStamp.path]?.takeIf { it.stamp == sourceStamp && it.revision == generation }?.let { return it }
    val names = try {
      when (sourceStamp.kind) {
        Kind.MISSING -> Inventory(emptySet())
        Kind.DIRECTORY -> if (directory) readDirectory(file) else null
        Kind.FILE -> if (!directory && sourceStamp.size <= maxArchiveBytes) readArchive(file) else null
        Kind.UNKNOWN -> null
      }
    } catch (_: IOException) { null
    } catch (_: SecurityException) { null
    } catch (_: IllegalArgumentException) { null }
    // Cache stable budget-limited inventories too, avoiding repeated large scans.
    // Do not cache a partially replaced archive or a temporary read failure.
    val result = Index(sourceStamp, generation, names.takeIf { sourceStamp == stamp(file) })
    if (result.names != null && generation == revision.get()) cached[sourceStamp.path] = result
    else cached.remove(sourceStamp.path)
    return result
  }

  private fun stamp(file: File): SourceStamp {
    val path = file.absolutePath
    return try {
      val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
      SourceStamp(
        path,
        when {
          attrs.isRegularFile -> Kind.FILE
          attrs.isDirectory -> Kind.DIRECTORY
          else -> Kind.UNKNOWN
        },
        attrs.fileKey()?.toString(), attrs.size(), attrs.lastModifiedTime().toString(),
      )
    } catch (_: NoSuchFileException) { SourceStamp(path, Kind.MISSING)
    } catch (_: IOException) { SourceStamp(path, Kind.UNKNOWN)
    } catch (_: SecurityException) { SourceStamp(path, Kind.UNKNOWN) }
  }

  private fun readArchive(file: File): Inventory = ZipFile(file).use { zip ->
    // Read labels before any inventory budget can stop the scan. These are display data only.
    val metadata = archiveMetadata(zip)
    if (zip.size() > maxEntries) return@use Inventory(emptySet(), title = metadata.title,
      version = metadata.version, complete = false)
    val result = linkedSetOf<String>()
    val night = linkedSetOf<String>()
    val time = linkedMapOf<String, os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon.Kind>()
    val entries = zip.entries()
    var descriptorBytes = 0L
    var complete = true
    fun reserveDescriptor(size: Long): Boolean {
      if (size !in 1..MAX_DESCRIPTOR_BYTES || size > maxDescriptorsTotal - descriptorBytes) {
        complete = false
        return false
      }
      descriptorBytes += size
      return true
    }
    while (entries.hasMoreElements()) {
      val entry = entries.nextElement()
      if (entry.isDirectory || entry.size <= 0) continue
      val resource = resource(entry.name, allowFlat = false) ?: continue
      if (resource.staticCompanion != null) {
        val companion = zip.getEntry(resource.staticCompanion) ?: continue
        if (companion.isDirectory || companion.size < 24 ||
          !zip.getInputStream(companion).use { png(it.readNBytes(24)) }) continue
      }
      if (resource.descriptor) {
        if (!reserveDescriptor(entry.size)) break
      }
      if (resource.layer != null) {
        val config = zip.getEntry(resource.layerConfig) ?: continue
        if (config.isDirectory || config.size <= 0) continue
        if (!reserveDescriptor(config.size)) break
        val declared = zip.getInputStream(config).use {
          declaresLayer(it.readNBytes(MAX_DESCRIPTOR_BYTES + 1), resource.layer)
        }
        if (!declared) continue
      }
      val valid = zip.getInputStream(entry).use { input ->
        if (resource.descriptor) {
          val bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1)
          os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon.kindOfManifest(bytes)?.let { time[resource.name] = it }
          descriptor(bytes)
        }
        else png(input.readNBytes(24))
      }
      if (valid) {
        result += resource.name
        if (isNightResource(entry.name)) night += resource.name
      }
    }
    Inventory(result, night, metadata.title, time, metadata.version, complete)
  }

  private fun archiveMetadata(zip: ZipFile): IconThemeMetadata {
    val stamped = zip.getEntry(IconThemeMetadata.PATH)?.takeIf {
      !it.isDirectory && it.size in 1..IconThemeMetadata.MAX_BYTES
    }?.let { entry ->
      zip.getInputStream(entry).use { IconThemeMetadata.decode(it.readNBytes(IconThemeMetadata.MAX_BYTES + 1)) }
    }
    if (stamped?.title != null && stamped.version != null) return stamped
    val description = zip.getEntry("description.xml")?.takeIf {
      !it.isDirectory && it.size in 1..MAX_DESCRIPTOR_BYTES
    }?.let { entry ->
      zip.getInputStream(entry).use { xmlRoot(it.readNBytes(MAX_DESCRIPTOR_BYTES + 1)) }
    }
    fun label(tag: String, limit: Int) = description?.getElementsByTagName(tag)?.item(0)
      ?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.take(limit)
    return IconThemeMetadata(stamped?.title ?: label("title", 120), stamped?.version ?: label("version", 80),
      stamped?.author)
  }

  private fun readDirectory(file: File): Inventory {
    val root = file.toPath().toRealPath()
    val result = linkedSetOf<String>()
    val night = linkedSetOf<String>()
    var visited = 0
    var descriptorBytes = 0L
    var complete = true
    fun reserveDescriptor(size: Long): Boolean {
      if (size > MAX_DESCRIPTOR_BYTES || size > maxDescriptorsTotal - descriptorBytes) {
        complete = false
        return false
      }
      descriptorBytes += size
      return true
    }
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (++visited > maxEntries) {
          complete = false
          return FileVisitResult.TERMINATE
        }
        return if (root.relativize(dir).nameCount > 6) FileVisitResult.SKIP_SUBTREE
        else FileVisitResult.CONTINUE
      }

      override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (++visited > maxEntries) {
          complete = false
          return FileVisitResult.TERMINATE
        }
        if (!attrs.isRegularFile || attrs.size() <= 0) return FileVisitResult.CONTINUE
        val relativePath = root.relativize(path).joinToString("/")
        val resource = resource(relativePath, allowFlat = true)
          ?: return FileVisitResult.CONTINUE
        if (resource.staticCompanion != null) {
          val companion = root.resolve(resource.staticCompanion)
          if (!Files.isRegularFile(companion, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            !Files.newInputStream(companion).use { png(it.readNBytes(24)) }) {
            return FileVisitResult.CONTINUE
          }
        }
        if (resource.descriptor) {
          if (!reserveDescriptor(attrs.size())) return FileVisitResult.TERMINATE
        }
        if (resource.layer != null) {
          val config = root.resolve(resource.layerConfig)
          if (!Files.isRegularFile(config, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return FileVisitResult.CONTINUE
          }
          val size = Files.size(config)
          if (!reserveDescriptor(size)) return FileVisitResult.TERMINATE
          val declared = Files.newInputStream(config).use {
            declaresLayer(it.readNBytes(MAX_DESCRIPTOR_BYTES + 1), resource.layer)
          }
          if (!declared) return FileVisitResult.CONTINUE
        }
        val valid = Files.newInputStream(path).use { input ->
          if (resource.descriptor) descriptor(input.readNBytes(MAX_DESCRIPTOR_BYTES + 1))
          else png(input.readNBytes(24))
        }
        if (valid) {
          result += resource.name
          if (isNightResource(relativePath)) night += resource.name
        }
        return FileVisitResult.CONTINUE
      }
    })
    return Inventory(result, night, complete = complete)
  }

  private fun isNightResource(path: String) = path.startsWith("nightmode/") || path.split('/').any {
    it.startsWith("drawable-") && "night" in it.split('-')
  }

  private data class Resource(val name: String, val descriptor: Boolean, val layer: String? = null,
    val prefix: String = "", val staticCompanion: String? = null) {
    val layerConfig get() = "${prefix}layer_animating_icons/$name/config.xml"
  }

  private fun resource(path: String, allowFlat: Boolean): Resource? {
    if (path.length > 1024 || '\\' in path) return null
    val prefix = if (path.startsWith("nightmode/")) "nightmode/" else ""
    val parts = path.removePrefix(prefix).split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    val staticName = when {
      parts.size == 3 && parts[0] == "res" &&
        (parts[1] == "drawable" || parts[1].startsWith("drawable-")) -> parts[2]
      allowFlat && parts.size == 1 -> parts[0]
      else -> null
    }
    if (staticName?.endsWith(".png") == true) {
      return staticName.removeSuffix(".png").takeIf(::validNativeIconName)?.let {
        Resource(it, descriptor = false)
      }
    }
    if (parts.size == 4 && parts[0] == "res" &&
      (parts[1] == "drawable" || parts[1].startsWith("drawable-")) && parts[3] == "0.png") {
      // The pair is background/foreground, not day/night. A half-pair is not coverage.
      return parts[2].takeIf(::validNativeIconName)?.let {
        Resource(it, descriptor = false,
          staticCompanion = prefix + parts.take(3).joinToString("/") + "/1.png")
      }
    }
    return when {
      parts.size == 3 && parts[0] == "fancy_icons" && parts[2] == "manifest.xml" ->
        Resource(parts[1], descriptor = true)
      parts.size == 4 && parts[0] == "animating_icons" &&
        parts[2] == "fancy" && parts[3] == "manifest.xml" -> Resource(parts[1], descriptor = true)
      parts.size == 5 && parts[0] == "layer_animating_icons" &&
        parts[3] == "fancy" && parts[4] == "manifest.xml" ->
        Resource(parts[1], descriptor = true, layer = parts[2], prefix = prefix)
      else -> null
    }?.takeIf { validNativeIconName(it.name) }
  }

  private fun png(bytes: ByteArray): Boolean = bytes.size == 24 &&
    bytes.take(8) == PNG_SIGNATURE && bytes.sliceArray(12..15).contentEquals(byteArrayOf(73, 72, 68, 82)) &&
    bytes.sliceArray(16..19).any { it != 0.toByte() } && bytes.sliceArray(20..23).any { it != 0.toByte() }

  private fun descriptor(bytes: ByteArray): Boolean {
    val root = xmlRoot(bytes) ?: return false
    return root.tagName == "Icon" && (0 until root.childNodes.length).any {
      root.childNodes.item(it).nodeType == Node.ELEMENT_NODE
    }
  }

  private fun declaresLayer(bytes: ByteArray, layer: String): Boolean {
    val root = xmlRoot(bytes) ?: return false
    if (root.tagName != "Config") return false
    val variables = root.getElementsByTagName("Variable")
    return (0 until variables.length).any {
      val variable = variables.item(it) as Element
      variable.getAttribute("name").matches(LAYER_VARIABLE) && variable.getAttribute("value") == layer
    }
  }

  private fun xmlRoot(bytes: ByteArray): Element? {
    if (bytes.size > MAX_DESCRIPTOR_BYTES) return null
    val text = bytes.toString(Charsets.UTF_8)
    if ("<!DOCTYPE" in text || "<!ENTITY" in text) return null
    return try {
      val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw SAXException("External icon entity") }
        setErrorHandler(DefaultHandler())
      }
      builder.parse(InputSource(StringReader(text))).documentElement
    } catch (_: Exception) { null }
  }

  private companion object {
    const val MAX_DESCRIPTOR_BYTES = 64 * 1024
    val LAYER_VARIABLE = Regex("layer[0-9]+")
    val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10).toList()
  }
}
