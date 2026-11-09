package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.android.zipflinger.ZipMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RawIconArchiveDeviceTest {
  private data class Entry(val name: String, val bytes: ByteArray, val method: Int = ZipEntry.DEFLATED)
  private data class Verified(val size: Long, val crc: Long, val method: Int, val content: String, val raw: String)

  @Test fun storedAndDeflatedPayloadsSurviveFilteringReplacementAndAddition() = folder { directory ->
    val source = fixture(directory)
    val before = hash(source)
    val original = inventory(source)
    val replacements = linkedMapOf(
      "keihome-build-source.txt" to "replacement\n".toByteArray(),
      "fancy_icons/com.android.settings/manifest.xml" to
        "<Root frameRate=\"0\"><Image src=\"day.png\"/></Root>".toByteArray(),
      "new-metadata.txt" to "added\n".toByteArray(),
    )
    val output = File(directory, "output.zip").apply { createNewFile() }
    val result = RawIconArchive.write(source, output,
      includes = { it != "skip.txt" && it != "keihome-build-source.txt" },
      replacements = replacements, maxOutputBytes = MAX_OUTPUT)
    assertEquals(3, result.rawEntries)
    assertEquals(3, result.replacementEntries)
    assertEquals(6, result.entries)
    assertEquals(output.length(), result.outputBytes)
    val actual = inventory(output)
    assertEquals(result.entries, actual.size)
    assertEquals(actual.values.sumOf { it.size }, result.expandedBytes)
    assertEquals(ZipEntry.STORED, actual.getValue("keihome-build-source.txt").method)
    assertEquals(ZipEntry.DEFLATED, actual.getValue("fancy_icons/com.android.settings/manifest.xml").method)
    assertEquals(ZipEntry.DEFLATED, actual.getValue("new-metadata.txt").method)
    for ((name, expected) in original) {
      if (name == "skip.txt" || name in replacements) continue
      assertEquals("$name CRC, decoded content, method and raw bytes", expected, actual.getValue(name))
    }
    ZipFile(source).use { from -> ZipFile(output).use { to ->
      for (name in original.keys.filter { it.endsWith(".png") }) {
        assertArrayEquals(name, from.getInputStream(from.getEntry(name)).use { it.readBytes() },
          to.getInputStream(to.getEntry(name)).use { it.readBytes() })
      }
      for ((name, bytes) in replacements) {
        assertArrayEquals(name, bytes, to.getInputStream(to.getEntry(name)).use { it.readBytes() })
      }
      assertFalse(to.entries().asSequence().any { it.isDirectory })
    } }
    // The pinned library writes fixed DOS timestamps; no current wall clock enters these outputs.
    val second = File(directory, "second.zip")
    RawIconArchive.write(source, second,
      includes = { it != "skip.txt" && it != "keihome-build-source.txt" },
      replacements = replacements, maxOutputBytes = MAX_OUTPUT)
    assertEquals("Identical rewrites must be deterministic", hash(output), hash(second))
    assertEquals("Input is never modified", before, hash(source))
  }

  @Test fun rejectsUnsafeDuplicateCollidingAndMalformedSourcesEvenWhenExcluded() = folder { directory ->
    val cases = listOf(
      listOf(Entry("../outside", byteArrayOf(1))),
      listOf(Entry("/absolute", byteArrayOf(1))),
      listOf(Entry("parent\\child", byteArrayOf(1))),
      listOf(Entry("parent", byteArrayOf(1)), Entry("parent/child", byteArrayOf(2))),
      listOf(Entry("same", byteArrayOf(1)), Entry("same/", byteArrayOf())),
      listOf(Entry("directory/", byteArrayOf(1))),
    )
    for ((index, entries) in cases.withIndex()) {
      val source = File(directory, "bad-$index.zip").also { zip(it, entries) }
      val output = File(directory, "bad-$index-output.zip")
      rejected { RawIconArchive.write(source, output, includes = { false }, maxOutputBytes = MAX_OUTPUT) }
      assertFalse(output.exists())
    }
    val duplicate = File(directory, "duplicate.zip").also {
      zip(it, listOf(Entry("one.bin", byteArrayOf(1)), Entry("two.bin", byteArrayOf(2))))
      val bytes = it.readBytes()
      val old = "two.bin".toByteArray()
      val replacement = "one.bin".toByteArray()
      for (offset in 0..bytes.size - old.size) {
        if (old.indices.all { bytes[offset + it] == old[it] }) replacement.copyInto(bytes, offset)
      }
      it.writeBytes(bytes)
    }
    val duplicateOutput = File(directory, "duplicate-output.zip")
    rejected { RawIconArchive.write(duplicate, duplicateOutput, maxOutputBytes = MAX_OUTPUT) }
    assertFalse(duplicateOutput.exists())
    val corrupt = File(directory, "corrupt.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    val corruptOutput = File(directory, "corrupt-output.zip")
    rejected { RawIconArchive.write(corrupt, corruptOutput, maxOutputBytes = MAX_OUTPUT) }
    assertFalse(corruptOutput.exists())
  }

  @Test fun budgetsAndReplacementPathsFailClosedWithoutChangingSourceOrExistingOutput() = folder { directory ->
    val source = fixture(directory)
    val before = hash(source)
    val bounds = listOf(
      IconPackLimits(maxInputBytes = source.length() - 1),
      IconPackLimits(maxEntries = 1),
      IconPackLimits(maxExpandedBytes = 1),
      IconPackLimits(maxEntryBytes = 1),
    )
    for ((index, limits) in bounds.withIndex()) {
      val output = File(directory, "limit-$index.zip")
      rejected { RawIconArchive.write(source, output, includes = { false }, maxOutputBytes = MAX_OUTPUT, limits = limits) }
      assertFalse(output.exists())
    }
    for ((index, replacements) in listOf(
      mapOf("../metadata" to byteArrayOf(1)),
      mapOf("metadata/" to byteArrayOf()),
      mapOf("res" to byteArrayOf(1)),
    ).withIndex()) {
      val output = File(directory, "replacement-$index.zip")
      rejected { RawIconArchive.write(source, output, replacements = replacements, maxOutputBytes = MAX_OUTPUT) }
      assertFalse(output.exists())
    }
    val tooSmall = File(directory, "output-budget.zip")
    rejected { RawIconArchive.write(source, tooSmall, maxOutputBytes = 1) }
    assertFalse(tooSmall.exists())
    val emptySource = File(directory, "empty.zip").also { zip(it, emptyList()) }
    val outputEntryLimit = File(directory, "output-entry-budget.zip")
    rejected {
      RawIconArchive.write(emptySource, outputEntryLimit,
        replacements = mapOf("one" to byteArrayOf(1), "two" to byteArrayOf(2)),
        maxOutputBytes = MAX_OUTPUT, limits = IconPackLimits(maxEntries = 1))
    }
    assertFalse(outputEntryLimit.exists())
    val replacementExpansion = File(directory, "replacement-expanded-budget.zip")
    rejected {
      RawIconArchive.write(emptySource, replacementExpansion,
        replacements = mapOf("metadata" to ByteArray(100)), maxOutputBytes = MAX_OUTPUT,
        limits = IconPackLimits(maxExpandedBytes = 1))
    }
    assertFalse(replacementExpansion.exists())
    val bomb = File(directory, "ratio.zip").also { zip(it, listOf(Entry("data", ByteArray(500_000)))) }
    val bombOutput = File(directory, "ratio-output.zip")
    rejected { RawIconArchive.write(bomb, bombOutput, includes = { false }, maxOutputBytes = MAX_OUTPUT) }
    assertFalse(bombOutput.exists())
    val replacementRatio = File(directory, "replacement-ratio.zip")
    rejected {
      RawIconArchive.write(emptySource, replacementRatio,
        replacements = mapOf("data" to ByteArray(500_000)), maxOutputBytes = MAX_OUTPUT)
    }
    assertFalse(replacementRatio.exists())
    val existing = File(directory, "existing.zip").apply { writeText("must survive") }
    val existingHash = hash(existing)
    rejected { RawIconArchive.write(source, existing, maxOutputBytes = MAX_OUTPUT) }
    assertEquals(existingHash, hash(existing))
    rejected { RawIconArchive.write(source, File(directory, "./source.zip"), maxOutputBytes = MAX_OUTPUT) }
    assertEquals(before, hash(source))
  }

  @Test fun cancellationBeforeAndDuringRawTransferDeletesOnlyStaging() = folder { directory ->
    val source = fixture(directory)
    val before = hash(source)
    for (during in listOf(false, true)) {
      val output = File(directory, "cancel-$during.zip").apply { createNewFile() }
      val cancellation = CancellationException("raw rewrite cancelled")
      var sawOutput = false
      try {
        RawIconArchive.write(source, output, check = {
          if (!during || output.length() > 22) {
            sawOutput = output.length() > 22
            throw cancellation
          }
        }, maxOutputBytes = MAX_OUTPUT)
        fail("Cancellation swallowed")
      } catch (failure: CancellationException) {
        assertSame(cancellation, failure)
      }
      if (during) assertTrue("Cancellation must reach actual transfer, not only preflight", sawOutput)
      assertFalse(output.exists())
      assertEquals(before, hash(source))
    }
  }

  /** -e rawArchivePath /app-private/source.zip, or -e monetMtzPath /app-private/source.mtz. */
  @Test fun fullAdmittedNativeFixturePreservesEveryEntry() = folder { directory ->
    val arguments = InstrumentationRegistry.getArguments()
    val path = arguments.getString("rawArchivePath") ?: arguments.getString("monetMtzPath")
      ?: arguments.getString("rawIconArchiveSource")
    assumeTrue("Requires -e rawIconArchive true or an explicit fixture path",
      arguments.getString("rawIconArchive") == "true" || path != null)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val supplied = path?.let(::File) ?: File(context.cacheDir, "keihome-raw-source.zip").takeIf { it.isFile }
      ?: File(context.cacheDir, "keihome-monet-source.mtz")
    assertTrue("Missing app-private admitted native fixture: $supplied", supplied.isFile)
    val suppliedHash = hash(supplied)
    val source = ZipFile(supplied).use { outer ->
      val icons = outer.getEntry("icons")
      if (icons == null) supplied else File(directory, "inner-icons.zip").also { extracted ->
        val crc = CRC32()
        var count = 0L
        outer.getInputStream(icons).use { input -> extracted.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            assertTrue(count <= IconPackLimits().maxInputBytes)
            crc.update(buffer, 0, read)
            output.write(buffer, 0, read)
          }
        } }
        assertEquals(icons.size, count)
        assertEquals(icons.crc, crc.value)
      }
    }
    val original = inventory(source)
    assertTrue("Full native fixture should exercise at least 40k entries", original.size >= 40_000)
    val additions = mapOf("keihome-raw-device-test.txt" to "raw fixture verified\n".toByteArray())
    assertFalse(original.containsKey(additions.keys.single()))
    val output = File(directory, "full-output.zip")
    var checks = 0
    val result = measured("full") {
      RawIconArchive.write(source, output, replacements = additions, check = { checks++ },
        maxOutputBytes = IconPackLimits().maxInputBytes)
    }
    val actual = inventory(output)
    assertEquals(original.size, result.rawEntries)
    assertEquals(original.size + 1, result.entries)
    assertEquals(original.size + 1, actual.size)
    assertTrue("Entry-level cancellation checkpoints", checks >= original.size)
    for ((name, expected) in original) assertEquals(name, expected, actual.getValue(name))
    val selected = linkedSetOf<String>()
    var budget = 22L
    ZipFile(source).use { zip ->
      val entries = zip.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.isDirectory) continue
        val needed = entry.compressedSize + 76L + entry.name.toByteArray(Charsets.UTF_8).size * 2L
        if (needed <= MAX_OUTPUT - 4096 - budget) {
          selected += entry.name
          budget += needed
        }
      }
    }
    assertTrue("Selection should exercise a meaningful bounded snapshot", budget > 3L * 1024 * 1024)
    val snapshot = File(directory, "selected-output.zip")
    val selectedResult = measured("selected-4MiB") {
      RawIconArchive.write(source, snapshot, includes = { it in selected },
        replacements = mapOf("keihome-raw-snapshot-test.txt" to "selected fixture verified\n".toByteArray()),
        maxOutputBytes = MAX_OUTPUT)
    }
    assertTrue(snapshot.length() <= MAX_OUTPUT)
    assertEquals(selected.size, selectedResult.rawEntries)
    val selectedActual = inventory(snapshot)
    assertEquals(selected.size + 1, selectedActual.size)
    for (name in selected) assertEquals(name, original.getValue(name), selectedActual.getValue(name))
    assertEquals(suppliedHash, hash(supplied))
    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
      putString("stream", "RawIconArchive entries=${result.entries} bytes=${result.outputBytes} " +
        "checks=$checks selectedEntries=${selectedResult.entries} selectedBytes=${selectedResult.outputBytes} " +
        "all source CRC/content/method/raw hashes preserved\n")
    })
  }

  private fun measured(label: String, write: () -> RawIconArchive.Result): RawIconArchive.Result {
    val runtime = Runtime.getRuntime()
    val active = AtomicBoolean(true)
    val heapPeak = AtomicLong()
    val nativePeak = AtomicLong()
    fun sample() {
      heapPeak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory()) { old, value -> maxOf(old, value) }
      nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { old, value -> maxOf(old, value) }
    }
    sample()
    val sampler = Thread({
      while (active.get()) {
        sample()
        try { Thread.sleep(10) } catch (_: InterruptedException) { return@Thread }
      }
    }, "raw-archive-heap-sampler").apply { isDaemon = true; start() }
    val started = SystemClock.elapsedRealtime()
    var elapsed = 0L
    val result = try {
      write().also { elapsed = SystemClock.elapsedRealtime() - started }
    } finally {
      sample()
      active.set(false)
      sampler.interrupt()
      sampler.join()
    }
    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
      putString("stream", "RawIconArchive $label elapsedMs=$elapsed heapSampledPeak=${heapPeak.get()} " +
        "nativeHeapSampledPeak=${nativePeak.get()} intervalMs=10; approximate process-wide values, not isolated peaks\n")
    })
    return result
  }

  private fun fixture(directory: File): File {
    val png = png()
    return File(directory, "source.zip").also { zip(it, listOf(
      Entry("fancy_icons/", byteArrayOf(), ZipEntry.STORED),
      Entry("res/drawable-xxhdpi/com.android.settings.png", png, ZipEntry.STORED),
      Entry("fancy_icons/com.android.settings/day.png", png),
      Entry("fancy_icons/com.android.settings/manifest.xml", "<Root><Image src=\"day.png\"/></Root>".toByteArray()),
      Entry("labels/\u56fe\u6807.txt", "utf8 entry\n".toByteArray()),
      Entry("keihome-build-source.txt", "original\n".toByteArray(), ZipEntry.STORED),
      Entry("skip.txt", ByteArray(1024) { (it * 31).toByte() }),
    )) }
  }

  private fun zip(file: File, entries: List<Entry>) {
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
      for (entry in entries) {
        val metadata = ZipEntry(entry.name).apply {
          time = 0
          method = entry.method
          if (method == ZipEntry.STORED) {
            size = entry.bytes.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(entry.bytes) }.value
          }
        }
        zip.putNextEntry(metadata)
        zip.write(entry.bytes)
        zip.closeEntry()
      }
    }
  }

  private fun inventory(file: File): Map<String, Verified> {
    val map = ZipMap.from(file.toPath())
    val result = linkedMapOf<String, Verified>()
    RandomAccessFile(file, "r").use { raw -> ZipFile(file).use { zip ->
      val entries = zip.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.isDirectory) continue
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var count = 0L
        zip.getInputStream(entry).use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            crc.update(buffer, 0, read)
            count += read
          }
        }
        assertEquals(entry.name, entry.size, count)
        assertEquals(entry.name, entry.crc, crc.value)
        val payload = map.entries.getValue(entry.name).payloadLocation
        raw.seek(payload.first)
        val compressed = MessageDigest.getInstance("SHA-256")
        var left = entry.compressedSize
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
          val read = raw.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
          assertTrue(read > 0)
          compressed.update(buffer, 0, read)
          left -= read
        }
        assertNull("Duplicate fixture path", result.put(entry.name,
          Verified(count, crc.value, entry.method, hex(digest.digest()), hex(compressed.digest()))))
      }
    } }
    return result
  }

  private fun png(): ByteArray {
    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    try {
      for (y in 0 until 16) for (x in 0 until 16) bitmap.setPixel(x, y, Color.rgb(x * 15, y * 15, (x + y) * 7))
      return ByteArrayOutputStream().use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.toByteArray()
      }
    } finally { bitmap.recycle() }
  }

  private fun hash(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
    }
    hex(digest.digest())
  }

  private fun hex(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    val text = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
      val value = bytes[index].toInt() and 255
      text[index * 2] = alphabet[value ushr 4]
      text[index * 2 + 1] = alphabet[value and 15]
    }
    return String(text)
  }

  private fun rejected(block: () -> Unit) {
    try { block(); fail("Expected bounded raw archive rejection") }
    catch (_: IOException) { }
    catch (_: IllegalArgumentException) { }
  }

  private fun folder(block: (File) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = Files.createTempDirectory(context.cacheDir.toPath(), "raw-archive-test-").toFile()
    try { block(directory) } finally { directory.deleteRecursively() }
  }

  companion object {
    private const val MAX_OUTPUT = 4L * 1024 * 1024
  }
}
