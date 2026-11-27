package os.kei.hyper.home.status

import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.SpoolIconEntries

/**
 * In-memory + spooled virtual overlay over a base icons ZIP so stamping, masking, generic Monet,
 * extra icons, and custom overrides compose in memory/spool and flush in a single RawIconArchive pass.
 * Maintains strict disjointness between [inMemory] and [spooled].
 */
internal class HandoffArchiveOverlay(
  private val base: ZipFile,
  val spooled: SpoolIconEntries,
) {
  val excluded = hashSetOf<String>()
  val inMemory = linkedMapOf<String, ByteArray>()
  private val basePaths: Set<String> = base.entries().asSequence()
    .filterNot { it.isDirectory }
    .map { it.name }
    .toCollection(linkedSetOf())

  fun paths(): Set<String> = buildSet(basePaths.size + inMemory.size + spooled.sizes.size) {
    basePaths.forEach { if (it !in excluded) add(it) }
    addAll(inMemory.keys)
    addAll(spooled.sizes.keys)
  }

  fun read(path: String, maxBytes: Int, check: () -> Unit = {}): ByteArray? {
    check()
    if (path in excluded) return null
    inMemory[path]?.let { return it.takeIf { bytes -> bytes.size <= maxBytes } }
    if (path in spooled) {
      val size = spooled.sizes[path] ?: return null
      if (size > maxBytes) return null
      return spooled.read(path)
    }
    val entry = base.getEntry(path) ?: return null
    if (entry.isDirectory || entry.size !in 0..maxBytes.toLong()) return null
    return base.getInputStream(entry).use { it.readNBytes(maxBytes + 1) }
      .takeIf { it.size <= maxBytes && it.size.toLong() == entry.size }
  }

  fun putInMemory(path: String, bytes: ByteArray) {
    excluded.remove(path)
    spooled.remove(path)
    inMemory[path] = bytes
  }

  fun putAllInMemory(entries: Map<String, ByteArray>) {
    entries.forEach { (path, bytes) -> putInMemory(path, bytes) }
  }

  fun putSpooled(entries: Map<String, ByteArray>, check: () -> Unit) {
    if (entries.isEmpty()) return
    entries.keys.forEach { path ->
      excluded.remove(path)
      inMemory.remove(path)
    }
    spooled.putAll(entries, check)
  }

  fun exclude(path: String) {
    excluded.add(path)
    inMemory.remove(path)
    spooled.remove(path)
  }

  fun excludeAll(paths: Collection<String>) {
    paths.forEach(::exclude)
  }
}
