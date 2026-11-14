package os.kei.hyper.home.iconpack

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException

/** App-private receipts cache validation, not parsed indexes or trust in mutable file metadata. */
internal class IconPackValidationCache(
  private val directory: File,
  private val limits: IconPackLimits = IconPackLimits(),
  private val validationRevision: Int = 1,
) {
  internal class VerifiedContents internal constructor(
    val original: File, val icons: File?, val nativeSha256: String?,
  )

  internal data class Inspection(val index: IconPackArchiveIndex, val reusedValidation: Boolean)

  fun inspect(record: StoredIconPack, sdk: Int, check: () -> Unit = {}): Inspection {
    check()
    if (!HASH.matches(record.id) || record.nativeIconsSha256?.let { !HASH.matches(it) } == true ||
      record.format !in setOf(IconPackFormat.CLASSIC_ZIP, IconPackFormat.MIUI_ICONS, IconPackFormat.MIUI_MTZ)
    ) throw IconPackException("Invalid stored archive identity")
    val originalBefore = identity(record.originalFile)
    if (sha256(record.originalFile, check) != record.id) throw IconPackException("Stored archive checksum mismatch")
    val icons = record.iconsFile
    if ((record.format == IconPackFormat.MIUI_MTZ) != (icons != null)) {
      throw IconPackException("Invalid stored icons module")
    }
    val iconsBefore = icons?.let(::identity)
    if (icons != null && sha256(icons, check) != record.nativeIconsSha256) {
      throw IconPackException("Stored icons module checksum mismatch")
    }
    val expected = receiptBytes(record, sdk)
    val reused = matches(record.id, expected, check)
    val scratch = File.createTempFile("icon-pack-index-", ".zip", directory.parentFile)
    val index = try {
      val archive = IconPackArchive(limits)
      if (reused) {
        archive.inspectPreviouslyValidated(
          VerifiedContents(record.originalFile, icons, record.nativeIconsSha256), scratch, sdk, check,
        )
      } else archive.inspect(record.originalFile, scratch, sdk, check)
    } finally { scratch.delete() }
    if (index.format != record.format || index.nativeIconsSha256 != record.nativeIconsSha256) {
      throw IconPackException("Stored archive identity mismatch")
    }
    if (identity(record.originalFile) != originalBefore || icons?.let(::identity) != iconsBefore) {
      throw IconPackException("Stored archive changed while reading")
    }
    check()
    if (!reused) publish(record.id, expected, check)
    return Inspection(index, reused)
  }

  private fun receiptBytes(record: StoredIconPack, sdk: Int): ByteArray =
    // Exact comparison avoids a persisted-index codec or accepting partially parsed receipts.
    // The limits data class includes every configured limit in this key.
    ("KeiHome archive validation\nrevision=$validationRevision\nsdk=$sdk\n" +
      "format=${record.format.name}\noriginal=${record.id}\nnative=${record.nativeIconsSha256}\n" +
      "limits=$limits\n").toByteArray(Charsets.UTF_8)

  private fun matches(id: String, expected: ByteArray, check: () -> Unit): Boolean {
    check()
    val file = File(directory, "$id.receipt")
    val matches = try {
      Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) &&
        Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == expected.size.toLong() &&
        file.inputStream().use { it.readNBytes(expected.size + 1).contentEquals(expected) }
    } catch (_: IOException) { false }
    check()
    return matches
  }

  private fun publish(id: String, bytes: ByteArray, check: () -> Unit) {
    var temporary: File? = null
    try {
      check()
      if (!directory.exists()) directory.mkdirs()
      if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return
      // Only generated receipt files are pruned; payloads and unrelated files are never touched.
      val old = directory.listFiles().orEmpty().filter {
        RECEIPT.matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.name != "$id.receipt"
      }.sortedBy { it.lastModified() }
      for (file in old.take((old.size - limits.maxStoredPacks + 1).coerceAtLeast(0))) {
        check()
        if (!file.delete()) return
      }
      temporary = File.createTempFile(".validation-", ".tmp", directory)
      temporary.writeBytes(bytes)
      check()
      Files.move(temporary.toPath(), File(directory, "$id.receipt").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      // No cancellation callback after atomic publication starts.
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: IOException) {
      // Receipt storage is optional; the archive has already passed full validation.
    } finally { temporary?.delete() }
  }

  private data class Identity(val size: Long, val modified: FileTime, val key: Any?)

  private fun identity(file: File): Identity {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!attributes.isRegularFile || attributes.size() !in 1..limits.maxInputBytes) {
      throw IconPackException("Invalid stored archive file")
    }
    return Identity(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
  }

  private companion object {
    val HASH = Regex("[0-9a-f]{64}")
    val RECEIPT = Regex("[0-9a-f]{64}\\.receipt")
  }
}
