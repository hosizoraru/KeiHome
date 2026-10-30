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
  private val maxEntries: Int = 16_384,
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

  private data class Index(val stamp: SourceStamp, val revision: Long, val names: Set<String>?)
  private val revision = AtomicLong()
  private val cached = mutableMapOf<String, Index>()

  init { require(maxEntries > 0) }

  fun invalidate() { revision.incrementAndGet() }

  /**
   * components maps each launcher package to its ActivityInfo.name values (full or relative).
   * A matching component protects its whole package because the existing Dart veto is package-keyed.
   * Unknown/corrupt/oversized sources never contribute partial coverage.
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
    if (generation != revision.get() || sources.any { it.names == null }) {
      return Snapshot(signature, emptySet(), complete = false)
    }
    val names = sources.flatMap { it.names.orEmpty() }.toHashSet()
    val covered = packages.filterTo(linkedSetOf()) { packageName ->
      packageName in names || components[packageName].orEmpty().any { component ->
        val className = if (component.startsWith('.')) packageName + component else component
        className in names
      }
    }
    return Snapshot(signature, covered, complete = true)
  }

  private fun index(file: File, directory: Boolean, generation: Long): Index {
    val sourceStamp = stamp(file)
    cached[sourceStamp.path]?.takeIf { it.stamp == sourceStamp && it.revision == generation }?.let { return it }
    val names = try {
      when (sourceStamp.kind) {
        Kind.MISSING -> emptySet()
        Kind.DIRECTORY -> if (directory) readDirectory(file) else null
        Kind.FILE -> if (!directory && sourceStamp.size <= MAX_PACK_BYTES) readArchive(file) else null
        Kind.UNKNOWN -> null
      }
    } catch (_: IOException) { null
    } catch (_: SecurityException) { null
    } catch (_: IllegalArgumentException) { null }
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

  private fun readArchive(file: File): Set<String> = ZipFile(file).use { zip ->
    if (zip.size() > maxEntries) throw IOException("Icon pack entry limit")
    val result = linkedSetOf<String>()
    val entries = zip.entries()
    var descriptorBytes = 0L
    while (entries.hasMoreElements()) {
      val entry = entries.nextElement()
      if (entry.isDirectory || entry.size <= 0) continue
      val resource = resource(entry.name, allowFlat = false) ?: continue
      if (resource.descriptor) {
        if (entry.size > MAX_DESCRIPTOR_BYTES) throw IOException("Icon descriptor limit")
        descriptorBytes += entry.size
        if (descriptorBytes > MAX_DESCRIPTORS_TOTAL) throw IOException("Icon descriptors total limit")
      }
      if (resource.layer != null) {
        val config = zip.getEntry(resource.layerConfig) ?: continue
        if (config.isDirectory || config.size <= 0) continue
        if (config.size > MAX_DESCRIPTOR_BYTES) throw IOException("Icon layer config limit")
        descriptorBytes += config.size
        if (descriptorBytes > MAX_DESCRIPTORS_TOTAL) throw IOException("Icon descriptors total limit")
        val declared = zip.getInputStream(config).use {
          declaresLayer(it.readNBytes(MAX_DESCRIPTOR_BYTES + 1), resource.layer)
        }
        if (!declared) continue
      }
      val valid = zip.getInputStream(entry).use { input ->
        if (resource.descriptor) descriptor(input.readNBytes(MAX_DESCRIPTOR_BYTES + 1))
        else png(input.readNBytes(24))
      }
      if (valid) result += resource.name
    }
    result
  }

  private fun readDirectory(file: File): Set<String> {
    val root = file.toPath().toRealPath()
    val result = linkedSetOf<String>()
    var visited = 0
    var descriptorBytes = 0L
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (++visited > maxEntries) throw IOException("Icon directory entry limit")
        return if (root.relativize(dir).nameCount > 6) FileVisitResult.SKIP_SUBTREE
        else FileVisitResult.CONTINUE
      }

      override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (++visited > maxEntries) throw IOException("Icon directory entry limit")
        if (!attrs.isRegularFile || attrs.size() <= 0) return FileVisitResult.CONTINUE
        val resource = resource(root.relativize(path).joinToString("/"), allowFlat = true)
          ?: return FileVisitResult.CONTINUE
        if (resource.descriptor) {
          if (attrs.size() > MAX_DESCRIPTOR_BYTES) throw IOException("Icon descriptor limit")
          descriptorBytes += attrs.size()
          if (descriptorBytes > MAX_DESCRIPTORS_TOTAL) throw IOException("Icon descriptors total limit")
        }
        if (resource.layer != null) {
          val config = root.resolve(resource.layerConfig)
          if (!Files.isRegularFile(config, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return FileVisitResult.CONTINUE
          }
          val size = Files.size(config)
          if (size > MAX_DESCRIPTOR_BYTES) throw IOException("Icon layer config limit")
          descriptorBytes += size
          if (descriptorBytes > MAX_DESCRIPTORS_TOTAL) throw IOException("Icon descriptors total limit")
          val declared = Files.newInputStream(config).use {
            declaresLayer(it.readNBytes(MAX_DESCRIPTOR_BYTES + 1), resource.layer)
          }
          if (!declared) return FileVisitResult.CONTINUE
        }
        val valid = Files.newInputStream(path).use { input ->
          if (resource.descriptor) descriptor(input.readNBytes(MAX_DESCRIPTOR_BYTES + 1))
          else png(input.readNBytes(24))
        }
        if (valid) result += resource.name
        return FileVisitResult.CONTINUE
      }
    })
    return result
  }

  private data class Resource(val name: String, val descriptor: Boolean, val layer: String? = null) {
    val layerConfig get() = "layer_animating_icons/$name/config.xml"
  }

  private fun resource(path: String, allowFlat: Boolean): Resource? {
    if (path.length > 1024 || '\\' in path) return null
    val parts = path.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    val staticName = when {
      parts.size == 3 && parts[0] == "res" &&
        (parts[1] == "drawable" || parts[1].startsWith("drawable-")) -> parts[2]
      allowFlat && parts.size == 1 -> parts[0]
      else -> null
    }
    if (staticName?.endsWith(".png") == true) {
      return Resource(staticName.removeSuffix(".png"), descriptor = false)
    }
    return when {
      parts.size == 3 && parts[0] == "fancy_icons" && parts[2] == "manifest.xml" ->
        Resource(parts[1], descriptor = true)
      parts.size == 4 && parts[0] == "animating_icons" &&
        parts[2] == "fancy" && parts[3] == "manifest.xml" -> Resource(parts[1], descriptor = true)
      parts.size == 5 && parts[0] == "layer_animating_icons" &&
        parts[3] == "fancy" && parts[4] == "manifest.xml" ->
        Resource(parts[1], descriptor = true, layer = parts[2])
      else -> null
    }
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
    const val MAX_PACK_BYTES = 64L * 1024 * 1024
    const val MAX_DESCRIPTOR_BYTES = 64 * 1024
    const val MAX_DESCRIPTORS_TOTAL = 1024 * 1024L
    val LAYER_VARIABLE = Regex("layer[0-9]+")
    val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10).toList()
  }
}
