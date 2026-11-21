package os.kei.hyper.home.iconpack

import com.android.zipflinger.ZipMap
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawIconArchiveTest {
  @Test fun diskStagedEntriesReplaceResourcesWithoutRetainingPayloads() {
    val directory = java.nio.file.Files.createTempDirectory("icon-spool-test").toFile()
    try {
      val source = File(directory, "original.zip")
      ZipOutputStream(source.outputStream()).use {
        it.putNextEntry(ZipEntry("kept.png"))
        it.write(byteArrayOf(7))
        it.closeEntry()
        it.putNextEntry(ZipEntry("old.png"))
        it.write(byteArrayOf(8))
        it.closeEntry()
      }
      val output = File(directory, "rewritten.zip")
      SpoolIconEntries.open(directory, IconPackLimits(maxExpandedBytes = 5)).use { spool ->
        val pixels = byteArrayOf(1, 2)
        spool.append(mapOf("day.png" to pixels, "alias.png" to pixels)) {}
        assertEquals(2L, directory.listFiles()!!.single { it.extension == "spool" }.length())
        pixels.fill(0)
        assertThrows(IconPackException::class.java) {
          spool.append(mapOf("overflow.png" to byteArrayOf(3, 4))) {}
        }
        RawIconArchive.write(source, output, includes = { it != "old.png" },
          spooled = spool, maxOutputBytes = MAX_OUTPUT)
      }
      ZipFile(output).use {
        assertEquals(setOf("kept.png", "day.png", "alias.png"), it.entries().asSequence().map { e -> e.name }.toSet())
        assertArrayEquals(byteArrayOf(1, 2), it.getInputStream(it.getEntry("day.png")).use { input -> input.readBytes() })
      }
      assertTrue(directory.listFiles()!!.none { it.extension == "spool" })
    } finally { directory.deleteRecursively() }
  }

  @get:Rule val temporary = TemporaryFolder()
  private data class Entry(val name: String, val bytes: ByteArray, val method: Int = ZipEntry.DEFLATED)

  @Test fun nullTransformMatchesDefaultWriteAndPreservesRawPayloads() {
    val source = fixture()
    val baseline = temporary.newFile()
    val transformed = temporary.newFile()
    val before = source.readBytes()
    val expected = RawIconArchive.write(source, baseline, maxOutputBytes = MAX_OUTPUT)
    val names = mutableListOf<String>()
    val actual = RawIconArchive.write(source, transformed, maxOutputBytes = MAX_OUTPUT,
      transform = { _, entry -> names += entry.name; null })
    assertEquals(expected.copy(outputBytes = transformed.length()), actual)
    assertArrayEquals(baseline.readBytes(), transformed.readBytes())
    assertEquals(listOf("stored.png", "deflated.png", "skip.txt", "metadata.txt"), names)
    assertArrayEquals(before, source.readBytes())
    for (name in names) assertArrayEquals(raw(source, name), raw(transformed, name))
  }

  @Test fun nullTransformPreservesOpaqueRawSidecarsBesideChangedImages() {
    val stored = randomBytes(3072)
    val deflated = randomBytes(4096)
    val source = archive(listOf(
      Entry("image.png", randomBytes(1024)),
      Entry("image.png.raw", stored, ZipEntry.STORED),
      Entry("independent.raw", deflated),
    ))
    val output = temporary.newFile()
    val visited = mutableListOf<String>()
    val result = RawIconArchive.write(source, output, maxOutputBytes = MAX_OUTPUT,
      transform = { _, entry ->
        visited += entry.name
        if (entry.name == "image.png") randomBytes(2048) else null
      })
    assertEquals(listOf("image.png", "image.png.raw", "independent.raw"), visited)
    assertEquals(1, result.transformedEntries)
    assertEquals(2, result.rawEntries)
    for ((name, expected) in listOf("image.png.raw" to stored, "independent.raw" to deflated)) {
      assertArrayEquals(raw(source, name), raw(output, name))
      ZipFile(output).use { zip ->
        assertArrayEquals(expected, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
      }
    }
  }

  @Test fun progressCountsSelectedEntriesAndReplacements() {
    val checkpoints = mutableListOf<Pair<Int, Int>>()
    val result = RawIconArchive.write(fixture(), temporary.newFile(),
      includes = { it != "skip.txt" },
      replacements = mapOf("metadata.txt" to byteArrayOf(1), "new.txt" to byteArrayOf(2)),
      onProgress = { done, total -> checkpoints += done to total },
      maxOutputBytes = MAX_OUTPUT)
    val total = result.rawEntries + result.replacementEntries
    assertEquals((0..total).toList(), checkpoints.map { it.first }.distinct())
    assertTrue(checkpoints.all { it.second == total })
  }

  @Test fun transformationsWriteImmediatelyAndRespectFilterAndReplacementPrecedence() {
    val source = fixture()
    val output = temporary.newFile()
    val visited = mutableListOf<String>()
    val changed = randomBytes(4096)
    val metadata = "explicit replacement".toByteArray()
    var beforeFirstWrite = 0L
    val result = RawIconArchive.write(source, output,
      includes = { it != "skip.txt" && it != "metadata.txt" },
      replacements = linkedMapOf("metadata.txt" to metadata, "added.txt" to byteArrayOf(9)),
      maxOutputBytes = MAX_OUTPUT,
      transform = { zip, entry ->
        visited += entry.name
        if (entry.name == "stored.png") {
          assertArrayEquals(randomBytes(1024), zip.getInputStream(entry).use { it.readBytes() })
          beforeFirstWrite = output.length()
          changed
        } else {
          assertEquals("deflated.png", entry.name)
          assertTrue("Previous transformed payload must be written before the next callback",
            output.length() >= beforeFirstWrite + changed.size)
          null
        }
      })
    assertEquals(listOf("stored.png", "deflated.png"), visited)
    assertEquals(4, result.entries)
    assertEquals(1, result.transformedEntries)
    assertEquals(1, result.rawEntries)
    assertEquals(2, result.replacementEntries)
    ZipFile(output).use { zip ->
      assertEquals(ZipEntry.STORED, zip.getEntry("stored.png").method)
      assertArrayEquals(changed, zip.getInputStream(zip.getEntry("stored.png")).use { it.readBytes() })
      assertArrayEquals(metadata, zip.getInputStream(zip.getEntry("metadata.txt")).use { it.readBytes() })
      assertNull(zip.getEntry("skip.txt"))
      assertEquals(zip.entries().asSequence().sumOf { it.size }, result.expandedBytes)
    }
    assertArrayEquals(raw(source, "deflated.png"), raw(output, "deflated.png"))
  }

  @Test fun transformedDeflatedEntriesRetainTheirMethodAndAreDeterministic() {
    val source = fixture()
    val bytes = randomBytes(2048)
    val outputs = List(2) { temporary.newFile() }
    for (output in outputs) {
      val result = RawIconArchive.write(source, output, maxOutputBytes = MAX_OUTPUT,
        transform = { _, entry -> bytes.takeIf { entry.name == "deflated.png" } })
      assertEquals(1, result.transformedEntries)
      ZipFile(output).use { zip ->
        val entry = zip.getEntry("deflated.png")
        assertEquals(ZipEntry.DEFLATED, entry.method)
        assertEquals(CRC32().also { it.update(bytes) }.value, entry.crc)
        assertArrayEquals(bytes, zip.getInputStream(entry).use { it.readBytes() })
      }
    }
    assertArrayEquals(outputs[0].readBytes(), outputs[1].readBytes())
  }

  @Test fun newEncodedAssetsAreStoredWithoutChangingAuthoredEntryMethods() {
    val source = fixture()
    val output = temporary.newFile()
    val png = randomBytes(2048)
    val path = "fancy_icons/com.example/day.png"
    val manifest = "fancy_icons/com.example/manifest.xml"
    SpoolIconEntries.open(output.parentFile).use { additions ->
      additions.append(mapOf(path to png)) {}
      RawIconArchive.write(source, output, maxOutputBytes = MAX_OUTPUT, spooled = additions,
        replacements = mapOf("deflated.png" to png, manifest to "<Icon />".encodeToByteArray()))
    }
    ZipFile(output).use { zip ->
      val image = zip.getEntry(path)
      assertEquals(ZipEntry.STORED, image.method)
      assertArrayEquals(png, zip.getInputStream(image).use { it.readBytes() })
      assertEquals(ZipEntry.DEFLATED, zip.getEntry(manifest).method)
      assertEquals(ZipEntry.DEFLATED, zip.getEntry("deflated.png").method)
    }
    assertArrayEquals(raw(source, "stored.png"), raw(output, "stored.png"))
  }

  @Test fun transformedOutputBudgetsUseNewSizesAndIncludeExplicitAdditions() {
    val source = archive(listOf(Entry("image.png", randomBytes(4096), ZipEntry.STORED)))
    val output = temporary.newFile()
    val result = RawIconArchive.write(source, output, maxOutputBytes = 400,
      replacements = mapOf("new.txt" to byteArrayOf(1)),
      limits = IconPackLimits(maxExpandedBytes = 4096),
      transform = { _, _ -> byteArrayOf(2) })
    assertEquals(2L, result.expandedBytes)
    assertTrue(output.length() <= 400)

    val small = archive(listOf(Entry("image.png", randomBytes(50), ZipEntry.STORED)))
    val expandedOutput = temporary.newFile()
    assertThrows(IconPackException::class.java) {
      RawIconArchive.write(small, expandedOutput, maxOutputBytes = MAX_OUTPUT,
        replacements = mapOf("new.txt" to ByteArray(60)),
        limits = IconPackLimits(maxExpandedBytes = 100),
        transform = { _, _ -> ByteArray(60) })
    }
    assertFalse(expandedOutput.exists())
  }

  @Test fun transformedEntryExpandedRatioAndPhysicalOutputLimitsFailClosed() {
    val source = archive(listOf(Entry("image.png", randomBytes(50))))
    for (case in 0..3) {
      val output = temporary.newFile()
      assertThrows(IconPackException::class.java) {
        RawIconArchive.write(source, output,
          maxOutputBytes = if (case == 3) 200 else MAX_OUTPUT,
          limits = when (case) {
            0 -> IconPackLimits(maxEntryBytes = 100)
            1 -> IconPackLimits(maxExpandedBytes = 100)
            2 -> IconPackLimits(maxCompressionRatio = 2)
            else -> IconPackLimits()
          },
          transform = { _, _ -> if (case == 2) ByteArray(8192) else randomBytes(1024) })
      }
      assertFalse("case $case", output.exists())
    }
    val fallback = temporary.newFile()
    assertThrows(IconPackException::class.java) {
      RawIconArchive.write(source, fallback, maxOutputBytes = 150, transform = { _, _ -> null })
    }
    assertFalse(fallback.exists())
  }

  @Test fun allSourceMetadataAndOutputEntryCountsAreAdmittedBeforeTransforming() {
    var calls = 0
    val unsafe = archive(listOf(Entry("../unsafe", byteArrayOf(1))))
    val output = temporary.newFile()
    assertThrows(IconPackException::class.java) {
      RawIconArchive.write(unsafe, output, includes = { false }, maxOutputBytes = MAX_OUTPUT,
        transform = { _, _ -> calls++; null })
    }
    assertFalse(output.exists())
    val valid = archive(listOf(Entry("valid.png", byteArrayOf(1))))
    val countOutput = temporary.newFile()
    assertThrows(IconPackException::class.java) {
      RawIconArchive.write(valid, countOutput, replacements = mapOf("extra" to byteArrayOf(2)),
        limits = IconPackLimits(maxEntries = 1), maxOutputBytes = MAX_OUTPUT,
        transform = { _, _ -> calls++; null })
    }
    assertEquals(0, calls)
    assertFalse(countOutput.exists())
  }

  @Test fun cancellationAndTransformFailuresDeletePartialOutputAndCloseReader() {
    val source = fixture()
    val before = source.readBytes()
    for (cancelViaCheck in listOf(false, true)) {
      val output = temporary.newFile()
      val failure = if (cancelViaCheck) CancellationException("cancel after transform") else IOException("transform")
      var cancel = false
      var reader: ZipFile? = null
      val actual = assertThrows(failure.javaClass) {
        RawIconArchive.write(source, output, maxOutputBytes = MAX_OUTPUT,
          check = { if (cancel) throw failure },
          transform = { zip, entry ->
            reader = zip
            if (entry.name == "deflated.png") {
              if (cancelViaCheck) cancel = true else throw failure
            }
            randomBytes(2048)
          })
      }
      assertSame(failure, actual)
      assertFalse(output.exists())
      assertThrows(IllegalStateException::class.java) { requireNotNull(reader).size() }
      assertArrayEquals(before, source.readBytes())
    }
  }

  @Test fun cancellationDuringRawFallbackStillRemovesPartialOutput() {
    val source = fixture()
    val output = temporary.newFile()
    val cancelled = CancellationException("stop during raw fallback")
    var calls = 0
    assertSame(cancelled, assertThrows(CancellationException::class.java) {
      RawIconArchive.write(source, output, maxOutputBytes = MAX_OUTPUT,
        check = { if (calls == 2) throw cancelled },
        transform = { _, _ -> calls++; null })
    })
    assertEquals(2, calls)
    assertFalse(output.exists())
  }

  @Test fun fortyThousandEntriesUseRawCopiesExceptForTheSingleChangedImage() {
    val count = 40_228
    val source = temporary.newFile()
    val bytes = randomBytes(64)
    ZipOutputStream(source.outputStream().buffered()).use { zip ->
      repeat(count) { index -> put(zip, Entry("res/drawable/icon$index.png", bytes,
        if (index % 2 == 0) ZipEntry.STORED else ZipEntry.DEFLATED)) }
    }
    val output = temporary.newFile()
    var calls = 0
    val result = RawIconArchive.write(source, output, maxOutputBytes = MAX_OUTPUT * 8,
      transform = { _, entry -> calls++; byteArrayOf(7).takeIf { entry.name == "res/drawable/icon0.png" } })
    assertEquals(count, calls)
    assertEquals(count, result.entries)
    assertEquals(count - 1, result.rawEntries)
    assertEquals(1, result.transformedEntries)
    ZipFile(output).use { assertEquals(count, it.size()) }
    for (index in listOf(1, 2, 20_001, 40_226, 40_227)) {
      val name = "res/drawable/icon$index.png"
      assertArrayEquals(raw(source, name), raw(output, name))
    }
  }

  @Test fun manyLargeTransformsAdvanceOutputBeforeAllocatingTheNextImage() {
    val count = 64
    val imageSize = 2 * 1024 * 1024
    val source = temporary.newFile()
    val inputBytes = randomBytes(imageSize)
    ZipOutputStream(source.outputStream().buffered()).use { zip ->
      repeat(count) { put(zip, Entry("image$it.png", inputBytes, ZipEntry.STORED)) }
    }
    val output = temporary.newFile()
    var calls = 0
    val result = RawIconArchive.write(source, output, maxOutputBytes = MAX_OUTPUT * 40,
      transform = { _, _ ->
        assertTrue("All earlier images must already be emitted", output.length() >= calls.toLong() * imageSize)
        calls++
        randomBytes(imageSize)
      })
    assertEquals(count, calls)
    assertEquals(count, result.transformedEntries)
    assertEquals(0, result.rawEntries)
    assertEquals(count.toLong() * imageSize, result.expandedBytes)
  }

  private fun fixture() = archive(listOf(
    Entry("empty/", byteArrayOf()),
    Entry("stored.png", randomBytes(1024), ZipEntry.STORED),
    Entry("deflated.png", randomBytes(1024)),
    Entry("skip.txt", byteArrayOf(1)),
    Entry("metadata.txt", byteArrayOf(2)),
  ))

  private fun archive(entries: List<Entry>) = temporary.newFile().also { file ->
    ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { put(zip, it) } }
  }

  private fun put(zip: ZipOutputStream, entry: Entry) {
    zip.putNextEntry(ZipEntry(entry.name).apply {
      method = entry.method
      if (method == ZipEntry.STORED) {
        size = entry.bytes.size.toLong()
        compressedSize = size
        crc = CRC32().also { it.update(entry.bytes) }.value
      }
    })
    zip.write(entry.bytes)
    zip.closeEntry()
  }

  private fun raw(file: File, name: String): ByteArray {
    val location = ZipMap.from(file.toPath()).entries.getValue(name).payloadLocation
    return RandomAccessFile(file, "r").use {
      it.seek(location.first)
      ByteArray(location.size().toInt()).also(it::readFully)
    }
  }

  private fun randomBytes(size: Int) = ByteArray(size).also { Random(410).nextBytes(it) }

  companion object {
    private const val MAX_OUTPUT = 4L * 1024 * 1024
  }
}
