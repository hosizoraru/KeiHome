package os.kei.hyper.home.iconpack

import com.android.zipflinger.BytesSource
import com.android.zipflinger.Source
import com.android.zipflinger.ZipArchive
import com.android.zipflinger.ZipMap
import com.android.zipflinger.ZipSource
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites an immutable, already content/CRC-admitted native ZIP without inflating unchanged entries.
 * Rechecks all source metadata, including excluded entries. Does not replace reader admission.
 * Output is caller-owned empty/new staging; caller remains responsible for atomic publication.
 * Replacements are unconditional, retain an existing entry's method, and otherwise use DEFLATED.
 */
internal object RawIconArchive {
  data class Result(
    val entries: Int,
    val rawEntries: Int,
    val replacementEntries: Int,
    val expandedBytes: Long,
    val outputBytes: Long,
  )

  fun write(
    source: File,
    output: File,
    includes: (String) -> Boolean = { true },
    replacements: Map<String, ByteArray> = emptyMap(),
    check: () -> Unit = {},
    maxOutputBytes: Long,
    limits: IconPackLimits = IconPackLimits(),
  ): Result {
    require(maxOutputBytes > 0)
    val input = source.canonicalFile
    val target = output.canonicalFile
    require(input != target) { "Raw archive output must differ from source" }
    require(!Files.isSymbolicLink(output.toPath())) { "Raw archive staging must not be a symbolic link" }
    require(!target.exists() || (target.isFile && target.length() == 0L)) {
      "Raw archive output must be new or empty staging"
    }
    var owned = false
    try {
      if (!target.exists()) Files.createFile(target.toPath())
      owned = true
      check()
      if (!input.isFile || input.length() !in 1..limits.maxInputBytes) fail("Raw archive input size limit exceeded")
      val originalSize = input.length()
      val originalModified = input.lastModified()
      val sourceEntries = linkedMapOf<String, Metadata>()
      val sourcePaths = Paths()
      var sourceExpanded = 0L
      ZipFile(input).use { zip ->
        if (zip.size() > limits.maxEntries) fail("Raw archive entry limit exceeded")
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
          check()
          val entry = entries.nextElement()
          sourcePaths.add(entry.name)
          val metadata = Metadata(entry.method, entry.size, entry.compressedSize, entry.crc, entry.isDirectory)
          admit(entry.name, metadata, limits)
          sourceExpanded = addWithin(sourceExpanded, metadata.size, limits.maxExpandedBytes)
          sourceEntries[entry.name] = metadata
        }
      }
      sourcePaths.finish(check)
      // Callers keep these byte arrays immutable until return; only their map is snapshotted here.
      val additions = replacements.toMap()
      val selected = linkedMapOf<String, Metadata>()
      val outputPaths = Paths()
      var expanded = 0L
      var compressedLowerBound = 22L
      for ((name, entry) in sourceEntries) {
        check()
        if (!entry.directory && name !in additions && includes(name)) {
          selected[name] = entry
          outputPaths.add(name)
          expanded = addWithin(expanded, entry.size, limits.maxExpandedBytes)
          compressedLowerBound = addWithin(compressedLowerBound, entry.compressed, maxOutputBytes)
          compressedLowerBound = addWithin(compressedLowerBound, headerBytes(name), maxOutputBytes)
        }
      }
      for ((name, bytes) in additions) {
        check()
        outputPaths.add(name)
        if (name.endsWith('/')) fail("Replacement must be a file: $name")
        if (bytes.size.toLong() > entryLimit(name, limits)) fail("Raw archive replacement size limit exceeded")
        expanded = addWithin(expanded, bytes.size.toLong(), limits.maxExpandedBytes)
        compressedLowerBound = addWithin(compressedLowerBound, headerBytes(name), maxOutputBytes)
      }
      outputPaths.finish(check)
      if (selected.size.toLong() + additions.size > limits.maxEntries) fail("Raw archive output entry limit exceeded")
      val map = ZipMap.from(input.toPath())
      if (map.entries.keys != sourceEntries.keys) fail("Raw ZIP directory mismatch")
      for ((name, expected) in sourceEntries) {
        check()
        val entry = map.entries.getValue(name)
        val payload = entry.payloadLocation
        if (entry.compressionFlag.toInt() != expected.method ||
          entry.uncompressedSize != expected.size || entry.compressedSize != expected.compressed ||
          (entry.crc.toLong() and 0xffffffffL) != expected.crc || entry.isDirectory != expected.directory ||
          payload.first < 0 || payload.size() != expected.compressed ||
          payload.first > originalSize || expected.compressed > originalSize - payload.first
        ) fail("Raw ZIP entry metadata mismatch: $name")
      }
      fun checkpoint() {
        check()
        if (target.length() > maxOutputBytes) fail("Raw archive output size limit exceeded")
      }
      val raw = CheckedSource(map, ::checkpoint)
      for (name in selected.keys) {
        check()
        raw.select(name, name, ZipSource.COMPRESSION_NO_CHANGE, 0)
      }
      // Zipflinger treats every existing file as a ZIP, including createTempFile's empty staging.
      if (maxOutputBytes < 22) fail("Raw archive output size limit exceeded")
      ZipOutputStream(target.outputStream()).use { }
      ZipArchive(target).use { archive ->
        checkpoint()
        archive.add(raw)
        checkpoint()
        for ((name, bytes) in additions) {
          checkpoint()
          val method = sourceEntries[name]?.method ?: ZipEntry.DEFLATED
          val level = if (method == ZipEntry.STORED) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION
          val added = BytesSource(bytes, name, level)
          ratio(bytes.size.toLong(), added.compressedSize, limits)
          compressedLowerBound = addWithin(compressedLowerBound, added.compressedSize, maxOutputBytes)
          archive.add(added)
          checkpoint()
        }
      }
      checkpoint()
      if (input.length() != originalSize || input.lastModified() != originalModified) {
        fail("Raw archive source changed during transfer")
      }
      return Result(selected.size + additions.size, selected.size, additions.size, expanded, target.length())
    } catch (failure: Throwable) {
      if (owned && target.exists() && !target.delete()) {
        failure.addSuppressed(java.io.IOException("Could not remove raw archive staging: $target"))
      }
      throw failure
    }
  }

  private data class Metadata(
    val method: Int, val size: Long, val compressed: Long, val crc: Long, val directory: Boolean,
  )

  private fun admit(name: String, entry: Metadata, limits: IconPackLimits) {
    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) fail("Unsupported raw ZIP method")
    if (entry.size < 0 || entry.compressed < 0 || entry.crc !in 0..0xffffffffL ||
      entry.size > entryLimit(name, limits) ||
      (entry.size > 0 && entry.compressed == 0L) ||
      (entry.method == ZipEntry.STORED && entry.size != entry.compressed) ||
      (entry.directory && (entry.size != 0L || entry.crc != 0L))
    ) fail("Invalid raw ZIP entry metadata: $name")
    ratio(entry.size, entry.compressed, limits)
  }

  private fun ratio(size: Long, compressed: Long, limits: IconPackLimits) {
    val denominator = maxOf(1L, compressed)
    if (size / denominator > limits.maxCompressionRatio ||
      (size / denominator == limits.maxCompressionRatio.toLong() && size % denominator != 0L)
    ) fail("Raw archive compression ratio limit exceeded")
  }

  private fun headerBytes(name: String): Long = 76L + name.toByteArray(Charsets.UTF_8).size * 2L

  // Existing reader admission permits only the exact outer icons container to exceed maxEntryBytes.
  private fun entryLimit(name: String, limits: IconPackLimits): Long =
    if (name == "icons") limits.maxInputBytes else limits.maxEntryBytes

  private fun addWithin(total: Long, amount: Long, maximum: Long): Long {
    if (amount < 0 || total > maximum || amount > maximum - total) fail("Raw archive byte budget exceeded")
    return total + amount
  }

  private class Paths {
    private val names = hashSetOf<String>()
    private val files = hashSetOf<String>()

    fun add(path: String) {
      IconPackArchive.validatePath(path)
      if (path.toByteArray(Charsets.UTF_8).size > 65535) fail("Raw archive UTF-8 path too long")
      val name = path.removeSuffix("/")
      if (!names.add(name)) fail("Duplicate raw archive path: $name")
      if (!path.endsWith('/')) files += name
    }

    fun finish(check: () -> Unit) {
      for (name in names) {
        check()
        var slash = name.indexOf('/')
        while (slash >= 0) {
          if (name.substring(0, slash) in files) fail("Raw archive file/directory collision")
          slash = name.indexOf('/', slash + 1)
        }
      }
    }
  }

  /** ZipArchive iterates this public list while its source channel is open: one check per raw entry. */
  private class CheckedSource(map: ZipMap, private val checkpoint: () -> Unit) : ZipSource(map) {
    override fun getSelectedEntries(): List<Source> {
      val entries = super.getSelectedEntries()
      return object : AbstractList<Source>() {
        override val size: Int get() = entries.size
        override fun get(index: Int): Source {
          checkpoint()
          return entries[index]
        }
      }
    }
  }

  private fun fail(message: String): Nothing = throw IconPackException(message)
}
