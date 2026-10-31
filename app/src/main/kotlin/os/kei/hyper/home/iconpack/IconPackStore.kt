package os.kei.hyper.home.iconpack

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.CancellationException

internal data class StoredIconPack(
  val id: String,
  val originalName: String,
  val importedAt: Long,
  val format: IconPackFormat,
  val originalFile: File,
  val iconsFile: File?,
  val storedBytes: Long,
  val nativeIconsSha256: String?,
)

internal data class IconPackFailure(val sourceId: String, val message: String)
internal data class IconPackScanResult(
  val packs: List<IconPackDescriptor>,
  val failures: List<IconPackFailure> = emptyList(),
  /** Android package visibility can hide installed packs. An empty scan is not proof of none. */
  val visibilityLimited: Boolean = false,
)

internal sealed interface IconPackImportResult {
  data class Success(val pack: IconPackDescriptor, val alreadyPresent: Boolean) : IconPackImportResult
  data class Failure(val message: String) : IconPackImportResult
}

internal data class IconPackStoreListing(val records: List<StoredIconPack>, val failures: List<IconPackFailure>)
internal data class StoredIconPackImport(val record: StoredIconPack, val alreadyPresent: Boolean)

/** One repository owns this store. All writes are staged, bounded, and published as a directory
 * rename. Display names/URIs never become paths. Original archive bytes remain unchanged. */
internal class IconPackStore(
  private val directory: File,
  private val limits: IconPackLimits = IconPackLimits(),
) {
  @Synchronized
  fun import(
    input: InputStream,
    displayName: String,
    sdk: Int = 37,
    checkCancelled: () -> Unit = {},
    validateApk: (File) -> Unit = { throw IconPackException("APK import requires the Android resource reader") },
  ): StoredIconPackImport {
    ensureDirectory()
    val stage = Files.createTempDirectory(directory.toPath(), ".import-").toFile()
    try {
      val incoming = File(stage, "incoming")
      incoming.outputStream().use { copyBounded(input, it, limits.maxInputBytes, checkCancelled) }
      val hash = sha256(incoming, checkCancelled)
      val destination = File(directory, hash)
      if (destination.exists()) {
        val existing = read(hash)
        if (sha256(existing.originalFile, checkCancelled) != hash) throw IconPackException("Stored archive checksum mismatch")
        return StoredIconPackImport(existing, alreadyPresent = true)
      }
      val icons = File(stage, "icons")
      val index = IconPackArchive(limits).inspect(incoming, icons, sdk, checkCancelled)
      if (index.format == IconPackFormat.APK) validateApk(incoming)
      val bytes = incoming.length() + if (icons.isFile) icons.length() else 0
      val listing = list()
      // Corrupt catalog records still occupy disk and count toward the quota.
      val occupied = directory.listFiles().orEmpty().filter { ID.matches(it.name) }
      val usedBytes = occupied.sumOf { dir -> dir.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0 } }
      if (listing.records.size + listing.failures.size >= limits.maxStoredPacks ||
        bytes > limits.maxStoredBytes - usedBytes
      ) throw IconPackException("Icon-pack storage limit exceeded")
      val properties = Properties().apply {
        setProperty("schema", "1")
        setProperty("originalName", displayName.filterNot { it.isISOControl() }.take(256).ifBlank { "Icon pack" })
        setProperty("importedAt", System.currentTimeMillis().toString())
        setProperty("format", index.format.name)
        setProperty("storedBytes", bytes.toString())
        index.nativeIconsSha256?.let { setProperty("nativeIconsSha256", it) }
      }
      Files.move(incoming.toPath(), File(stage, payloadName(index.format)).toPath())
      File(stage, "metadata.properties").outputStream().use { properties.store(it, "KeiHome private icon-pack catalog v1") }
      checkCancelled()
      try {
        Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(stage.toPath(), destination.toPath())
      }
      return StoredIconPackImport(read(hash), alreadyPresent = false)
    } finally {
      // This is a generated staging directory; no archive entry controls its contents' paths.
      stage.deleteRecursively()
    }
  }

  @Synchronized
  fun list(): IconPackStoreListing {
    if (!directory.exists()) return IconPackStoreListing(emptyList(), emptyList())
    val packs = mutableListOf<StoredIconPack>()
    val failures = mutableListOf<IconPackFailure>()
    val children = directory.listFiles() ?: throw IconPackException("Cannot read icon-pack catalog")
    for (child in children.filter { ID.matches(it.name) }.take(limits.maxStoredPacks + 1)) {
      try { packs += read(child.name)
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { failures += IconPackFailure(child.name, failure.message ?: "Invalid catalog record") }
    }
    return IconPackStoreListing(packs.sortedByDescending { it.importedAt }, failures)
  }

  @Synchronized
  fun read(id: String): StoredIconPack {
    if (!ID.matches(id)) throw IconPackException("Invalid icon-pack id")
    val folder = File(directory, id)
    if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IconPackException("Missing icon pack")
    val metadata = File(folder, "metadata.properties")
    if (!regularFile(metadata) || metadata.length() !in 1..16_384) throw IconPackException("Invalid catalog metadata")
    val properties = Properties().apply { metadata.inputStream().use(::load) }
    if (properties.getProperty("schema") != "1") throw IconPackException("Unsupported catalog version")
    val format = try { IconPackFormat.valueOf(properties.getProperty("format"))
    } catch (failure: Exception) { throw IconPackException("Invalid stored format", failure) }
    if (format == IconPackFormat.INSTALLED_APK) throw IconPackException("Invalid stored format")
    val source = File(folder, payloadName(format))
    if (!regularFile(source) || source.length() !in 1..limits.maxInputBytes) throw IconPackException("Missing stored archive")
    val icons = if (format == IconPackFormat.MIUI_MTZ) File(folder, "icons") else null
    if (icons != null && (!regularFile(icons) || icons.length() !in 1..limits.maxInputBytes)) throw IconPackException("Missing icons module")
    val nativeHash = properties.getProperty("nativeIconsSha256")
    if (nativeHash != null && !ID.matches(nativeHash)) throw IconPackException("Invalid icons checksum")
    val bytes = source.length() + (icons?.length() ?: 0)
    if (properties.getProperty("storedBytes")?.toLongOrNull() != bytes) throw IconPackException("Stored archive size changed")
    return StoredIconPack(id, properties.getProperty("originalName", "Icon pack").take(256),
      properties.getProperty("importedAt")?.toLongOrNull() ?: 0L, format, source, icons, bytes, nativeHash)
  }

  private fun ensureDirectory() {
    if (!directory.isDirectory && !directory.mkdirs()) throw IconPackException("Cannot create icon-pack storage")
    if (Files.isSymbolicLink(directory.toPath())) throw IconPackException("Invalid icon-pack storage directory")
  }

  private fun regularFile(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
  private fun payloadName(format: IconPackFormat) = if (format == IconPackFormat.APK) "original.apk" else "original.zip"

  private companion object { val ID = Regex("[0-9a-f]{64}") }
}
