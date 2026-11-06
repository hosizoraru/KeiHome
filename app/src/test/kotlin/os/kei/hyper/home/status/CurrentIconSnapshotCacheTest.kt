package os.kei.hyper.home.status

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackLimits

class CurrentIconSnapshotCacheTest {
  private val first = "a".repeat(64)
  private val second = "b".repeat(64)
  private val third = "c".repeat(64)

  private fun inCache(test: (File, CurrentIconSnapshotCache) -> Unit) {
    val directory = Files.createTempDirectory("snapshot-cache-test").toFile()
    try { test(directory, CurrentIconSnapshotCache(directory)) }
    finally { directory.deleteRecursively() }
  }

  private fun zip(file: File, vararg entries: Pair<String, ByteArray>) {
    ZipOutputStream(file.outputStream()).use { output ->
      for ((name, bytes) in entries) {
        output.putNextEntry(ZipEntry(name))
        output.write(bytes)
        output.closeEntry()
      }
    }
  }

  private fun zip(file: File) = zip(file, "res/drawable/com.example.png" to byteArrayOf(1, 2, 3))

  @Test fun writerInvalidatesMarkerBeforeTruncationAcrossHelperInstances() = inCache { dir, cache ->
    zip(cache.file(first))
    assertTrue(cache.ready(first))
    CurrentIconSnapshotCache(dir).openWriter(first) { file ->
      assertFalse(File(dir, "$first.ready").exists())
      assertFalse(cache.exists(first))
      file.writeBytes(byteArrayOf())
    }
    assertFalse(cache.exists(first))
    assertFalse(cache.ready(first))
    assertFalse(File(dir, "$first.ready").exists())
  }

  @Test fun markerRemovalFailurePreventsWriterFromOpening() = inCache { dir, cache ->
    zip(cache.file(first))
    val original = cache.file(first).readBytes()
    File(dir, "$first.ready").mkdir()
    File(dir, "$first.ready/child").writeText("blocked")
    assertThrows(IOException::class.java) {
      cache.openWriter(first) { error("Writer must not open") }
    }
    assertTrue(original.contentEquals(cache.file(first).readBytes()))
    assertFalse(cache.ready(first))
    assertFalse(cache.exists(first))
  }

  @Test fun missingMalformedAndTruncatedArchivesCannotBeReady() = inCache { dir, cache ->
    for (content in listOf<ByteArray?>(null, byteArrayOf(), "not a zip".toByteArray())) {
      cache.file(first).delete()
      if (content != null) cache.file(first).writeBytes(content)
      File(dir, "$first.ready").writeText("stale")
      assertFalse(cache.exists(first))
      assertFalse(cache.ready(first))
      assertFalse(File(dir, "$first.ready").exists())
    }
    zip(cache.file(first))
    RandomAccessFile(cache.file(first), "rw").use { it.setLength(it.length() - 12) }
    assertFalse(cache.ready(first))
  }

  @Test fun exactArchiveInputLimitAcceptedAndOversizeNeverExists() = inCache { dir, cache ->
    zip(cache.file(first))
    val size = cache.file(first).length()
    assertTrue(CurrentIconSnapshotCache(dir, IconPackLimits(maxInputBytes = size)).ready(first))
    val smaller = CurrentIconSnapshotCache(dir, IconPackLimits(maxInputBytes = size - 1))
    assertFalse(smaller.exists(first))
    assertFalse(smaller.ready(first))
    assertFalse(File(dir, "$first.ready").exists())
  }

  @Test fun exactEntryExpandedAndCountLimitsAccepted() = inCache { dir, cache ->
    zip(cache.file(first), "one" to byteArrayOf(1, 2), "two" to byteArrayOf(3, 4))
    val limits = IconPackLimits(maxEntryBytes = 2, maxExpandedBytes = 4, maxEntries = 2)
    assertTrue(CurrentIconSnapshotCache(dir, limits).ready(first))
    for (smaller in listOf(limits.copy(maxEntryBytes = 1), limits.copy(maxExpandedBytes = 3),
        limits.copy(maxEntries = 1))) {
      assertFalse(CurrentIconSnapshotCache(dir, smaller).ready(first))
      assertFalse(File(dir, "$first.ready").exists())
    }
  }

  @Test fun excessiveCompressionRatioIsRejected() = inCache { dir, cache ->
    zip(cache.file(first), "one" to ByteArray(4096))
    assertFalse(CurrentIconSnapshotCache(dir, IconPackLimits(maxCompressionRatio = 1)).ready(first))
    assertTrue(cache.ready(first))
  }

  @Test fun unsafeDuplicateAndCollidingPathsAreRejected() = inCache { _, cache ->
    for (name in listOf("../escape", "/absolute", "a\\b", "a:b", "a//b", "a/./b",
        (1..13).joinToString("/") { "a" })) {
      zip(cache.file(first), name to byteArrayOf(1))
      assertFalse(cache.ready(first))
    }
    zip(cache.file(first), "parent" to byteArrayOf(1), "parent/child" to byteArrayOf(2))
    assertFalse(cache.ready(first))
    zip(cache.file(first), "same" to byteArrayOf(), "same/" to byteArrayOf())
    assertFalse(cache.ready(first))
  }

  @Test fun readyDoesNotInflateOrCrcCheckPayloadOnBinder() = inCache { _, cache ->
    zip(cache.file(first))
    // Damage only the local payload. The bounded central directory remains valid.
    RandomAccessFile(cache.file(first), "rw").use {
      it.seek(26)
      val nameLength = it.readUnsignedByte() or (it.readUnsignedByte() shl 8)
      val extraLength = it.readUnsignedByte() or (it.readUnsignedByte() shl 8)
      it.seek(30L + nameLength + extraLength)
      it.write(255)
    }
    assertTrue(cache.ready(first))
    assertTrue(cache.exists(first))
  }

  @Test fun cleanupPreservesCurrentAndMostRecentCompletePairsTogether() = inCache { dir, cache ->
    zip(cache.file(first))
    assertTrue(cache.ready(first))
    File(dir, "$first.ready").setLastModified(1000)
    zip(cache.file(second))
    assertTrue(cache.ready(second))
    // Deliberately interleave pair mtimes; cleanup must never select individual files.
    cache.file(first).setLastModified(9000)
    File(dir, "$second.ready").setLastModified(2000)
    cache.file(second).setLastModified(500)
    val orphan = "d".repeat(64)
    zip(cache.file(orphan))
    File(dir, "${"e".repeat(64)}.ready").writeText("orphan")
    zip(cache.file(third))
    assertTrue(cache.ready(third))
    assertEquals(setOf("$second.zip", "$second.ready", "$third.zip", "$third.ready"),
      dir.list()!!.toSet())
    assertTrue(cache.exists(second))
    assertTrue(cache.exists(third))
  }

  @Test fun failureToCreateMarkerIsNotAccepted() = inCache { dir, cache ->
    zip(cache.file(first))
    File(dir, "$first.ready.tmp").mkdir()
    File(dir, "$first.ready.tmp/child").writeText("blocked")
    assertFalse(cache.ready(first))
    assertFalse(cache.exists(first))
    assertFalse(File(dir, "$first.ready").exists())
  }

  @Test fun cleanupFailureCannotPublishMarkerAndKeepsPriorCompletePair() = inCache { dir, cache ->
    zip(cache.file(first))
    assertTrue(cache.ready(first))
    zip(cache.file(second))
    val blocked = File(dir, "$third.ready")
    blocked.mkdir()
    File(blocked, "child").writeText("blocked")
    assertFalse(cache.ready(second))
    assertFalse(File(dir, "$second.ready").exists())
    assertTrue(cache.exists(first))
    assertFalse(cache.exists(second))
  }

  @Test fun invalidKeysCannotExpandCachePaths() = inCache { _, cache ->
    for (key in listOf("../outside", "", "a".repeat(63), "A".repeat(64))) {
      assertThrows(IllegalArgumentException::class.java) { cache.file(key) }
      assertThrows(IllegalArgumentException::class.java) { cache.ready(key) }
      assertThrows(IllegalArgumentException::class.java) { cache.openWriter(key) {} }
    }
  }
}
