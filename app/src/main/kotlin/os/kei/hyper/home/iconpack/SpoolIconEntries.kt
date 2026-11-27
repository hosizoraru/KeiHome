package os.kei.hyper.home.iconpack

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.IdentityHashMap

/** Single-owner disk staging for derived entries. Only offsets and lengths survive each batch. */
internal class SpoolIconEntries private constructor(
  private val file: File,
  private val storage: RandomAccessFile,
  private val limits: IconPackLimits,
) : Closeable {
  private data class Slice(val offset: Long, val length: Int)

  private val entries = linkedMapOf<String, Slice>()
  private var end = 0L
  val sizes: Map<String, Long> get() = entries.mapValues { it.value.length.toLong() }
  val isEmpty: Boolean get() = entries.isEmpty()
  private var expanded = 0L

  fun append(layers: Map<String, ByteArray>, check: () -> Unit) {
    writeEntries(layers, allowOverwrite = false, check = check)
  }

  fun putAll(layers: Map<String, ByteArray>, check: () -> Unit) {
    writeEntries(layers, allowOverwrite = true, check = check)
  }

  fun remove(name: String) {
    entries.remove(name)?.let { removed -> expanded -= removed.length }
  }

  operator fun contains(name: String): Boolean = name in entries

  private fun writeEntries(layers: Map<String, ByteArray>, allowOverwrite: Boolean, check: () -> Unit) {
    val newKeys = if (allowOverwrite) layers.keys.count { it !in entries } else layers.size
    if (entries.size.toLong() + newKeys > limits.maxEntries)
      throw IconPackException("Generated icon entry limit exceeded")
    val replacedBytes = if (allowOverwrite) layers.keys.sumOf { entries[it]?.length?.toLong() ?: 0L } else 0L
    val bytes = layers.values.sumOf { it.size.toLong() } - replacedBytes
    if (bytes > limits.maxExpandedBytes - expanded)
      throw IconPackException("Generated icon archive byte budget exceeded")
    val written = IdentityHashMap<ByteArray, Slice>()
    for ((name, payload) in layers) {
      check()
      if (!allowOverwrite) require(name !in entries) { "Duplicate generated icon entry: $name" }
      if (payload.size.toLong() > limits.maxEntryBytes)
        throw IconPackException("Generated icon entry size limit exceeded")
      val previous = entries[name]
      entries[name] = written.getOrPut(payload) {
        storage.seek(end)
        storage.write(payload)
        Slice(end, payload.size).also { end += payload.size }
      }
      expanded += payload.size.toLong() - (previous?.length?.toLong() ?: 0L)
    }
  }

  fun read(name: String): ByteArray {
    val (offset, length) = entries.getValue(name)
    storage.seek(offset)
    return ByteArray(length).also(storage::readFully)
  }

  override fun close() {
    try {
      storage.close()
    } finally {
      Files.deleteIfExists(file.toPath())
    }
  }

  companion object {
    fun open(directory: File, limits: IconPackLimits = IconPackLimits()): SpoolIconEntries {
      val file = File.createTempFile("icon-entries-", ".spool", directory)
      try {
        return SpoolIconEntries(file, RandomAccessFile(file, "rw"), limits)
      } catch (failure: Throwable) {
        if (!file.delete()) failure.addSuppressed(java.io.IOException("Unable to remove icon staging"))
        throw failure
      }
    }
  }
}
