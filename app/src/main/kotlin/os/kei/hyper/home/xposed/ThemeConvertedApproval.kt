package os.kei.hyper.home.xposed

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

internal data class ThemeConvertedSubject(
  val icon: ConvertedIconResource,
  val parent: ConvertedIconResource,
  val iconContent: String,
  val parentContent: String,
  val supplied: ConvertedIconSupplied? = null,
)

/** Fresh per-process evidence: IDs and an old warm snapshot alone never grant rights. */
internal class ThemeConvertedApproval(
  private val loadGrants: () -> Map<String, ConvertedIconGrant>,
  private val inspect: (ThemeConvertedSubject, ConvertedIconGrant) -> Boolean,
) {
  private val scoped = ConcurrentHashMap<ThemeConvertedSubject, ConvertedIconGrant>()

  fun approve(subject: ThemeConvertedSubject): Boolean {
    scoped.keys.removeIf { it.icon.localId == subject.icon.localId }
    // Import may register in another process after this process has initialized.
    val grant = loadGrants()[subject.icon.localId] ?: return false
    if (!grant.matches(subject.icon, subject.parent)) return false
    if (!inspect(subject, grant)) return false
    scoped[subject] = grant
    return true
  }

  // This only narrows the related-component set; rights still require a fresh preflight.
  fun iconsOnly(subject: ThemeConvertedSubject): Boolean =
    scoped[subject]?.matches(subject.icon, subject.parent) == true

  fun iconsOnly(supplied: ConvertedIconSupplied): Boolean =
    scoped.keys.any { it.supplied == supplied }

  companion object {
    /** Catch approval errors, never catch/replay an exception from the native call. */
    fun <T> dispatch(approval: () -> T?, original: () -> T, failed: (Exception) -> Unit): T {
      val approved = try { approval() } catch (failure: Exception) {
        failed(failure)
        null
      }
      return approved ?: original()
    }
  }
}

/** Reads the exact provider window, file, or ZIP entry, without broad filesystem fallback. */
internal class ThemeConvertedContent(
  private val cache: File,
  private val provider: (String) -> InputStream?,
) {
  private val limit = 256L * 1024 * 1024

  fun inspect(subject: ThemeConvertedSubject, grant: ConvertedIconGrant): Boolean {
    cache.mkdirs()
    val staged = Files.createTempFile(cache.toPath(), "keihome-rights-", ".mtz").toFile()
    try {
      open(subject.parentContent).use { input ->
        staged.outputStream().use { output -> copyBounded(input, output::write) }
      }
      // Themes may consume the parent archive into an empty placeholder after import.
      // Association checks and the fresh child digest still bind the registered payload.
      if (staged.length() == 0L) return matchesIcons(subject.iconContent, grant)
      val proof = ConvertedIconArchive.inspect(staged, grant.proof.origin)
      if (proof != grant.proof) return false
      return matchesIcons(subject.iconContent, grant)
    } finally {
      staged.delete()
    }
  }

  fun matchesIcons(path: String, grant: ConvertedIconGrant): Boolean {
    val sha1 = MessageDigest.getInstance("SHA-1")
    val sha256 = MessageDigest.getInstance("SHA-256")
    open(path).use { input ->
      copyBounded(input) { bytes, offset, count ->
        sha1.update(bytes, offset, count)
        sha256.update(bytes, offset, count)
      }
    }
    fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    return sha1.digest().hex() == grant.proof.icons.sha1 &&
      sha256.digest().hex() == grant.proof.icons.sha256
  }

  fun open(path: String): InputStream {
    require(path.isNotBlank() && path.length <= 4096 && '\u0000' !in path)
    if (path.startsWith("content://")) return requireNotNull(provider(path)) { "Unavailable theme provider" }
    val local = if (path.startsWith("file:")) File(java.net.URI(path)) else File(path)
    require(local.isAbsolute) { "Relative theme content path" }
    if (local.isFile) return local.inputStream()
    var archive = local.parentFile
    while (archive != null && !archive.isFile) archive = archive.parentFile
    requireNotNull(archive) { "Theme content unavailable" }
    val entry = local.path.removePrefix(archive.path + File.separator)
    require(entry.isNotBlank() && entry.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
      '\\' !in entry && !entry.startsWith('/')) { "Unsafe nested theme path" }
    val zip = ZipFile(archive)
    try {
      val matches = zip.entries().asSequence().filter { it.name == entry }.toList()
      require(matches.size == 1 && !matches.single().isDirectory &&
        matches.single().size in 0..limit) { "Ambiguous or oversized theme entry" }
      val stream = zip.getInputStream(matches.single())
      return object : java.io.FilterInputStream(stream) {
        override fun close() {
          try { super.close() } finally { zip.close() }
        }
      }
    } catch (failure: Exception) {
      zip.close()
      throw failure
    }
  }

  private fun copyBounded(input: InputStream, consume: (ByteArray, Int, Int) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
      if (Thread.currentThread().isInterrupted) throw InterruptedException("Theme content read retired")
      val count = input.read(buffer)
      if (count < 0) return
      if (count == 0) continue
      total += count
      require(total <= limit) { "Theme content exceeds approval bound" }
      consume(buffer, 0, count)
    }
  }
}
