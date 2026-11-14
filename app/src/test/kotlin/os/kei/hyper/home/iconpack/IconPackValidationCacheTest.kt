package os.kei.hyper.home.iconpack

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IconPackValidationCacheTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun freshInstancesReuseValidationButRebuildIdenticalClassicNativeAndMtzIndexes() {
    for (bytes in listOf(classic(), native(), zip("icons" to native()))) {
      val record = imported(bytes)
      val directory = temporary.newFolder()
      val sourceBefore = record.originalFile.readBytes()
      val nativeBefore = record.iconsFile?.readBytes()
      val cache = IconPackValidationCache(directory)
      val first = cache.inspect(record, 37)
      val warm = cache.inspect(record, 37)
      val second = IconPackValidationCache(directory).inspect(record, 37)
      assertFalse(first.reusedValidation)
      assertTrue(warm.reusedValidation)
      assertTrue(second.reusedValidation)
      assertEquals(first.index, warm.index)
      assertEquals(first.index, second.index)
      assertTrue(second.index.entries.isNotEmpty())
      assertArrayEquals(sourceBefore, record.originalFile.readBytes())
      if (nativeBefore != null) assertArrayEquals(nativeBefore, requireNotNull(record.iconsFile).readBytes())
    }
  }

  @Test fun receiptCannotHideChangedOriginalOrNativeModuleBytes() {
    for (bytes in listOf(classic(), zip("icons" to native()))) {
      val record = imported(bytes)
      val directory = temporary.newFolder()
      IconPackValidationCache(directory).inspect(record, 37)
      for (file in listOfNotNull(record.originalFile, record.iconsFile)) {
        val before = file.readBytes()
        val changed = before.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(changed)
        assertEquals("Same-size tampering must still be detected", before.size.toLong(), file.length())
        assertThrows(IconPackException::class.java) {
          IconPackValidationCache(directory).inspect(record, 37)
        }
        file.writeBytes(before)
        assertTrue(IconPackValidationCache(directory).inspect(record, 37).reusedValidation)
      }
    }
  }

  @Test fun changedShaAndCopiedOldReceiptStillRequireFullCrcValidation() {
    val record = imported(classic(stored = true))
    val directory = temporary.newFolder()
    IconPackValidationCache(directory).inspect(record, 37)
    val bytes = record.originalFile.readBytes()
    val start = (0..bytes.size - png.size).first { offset ->
      png.indices.all { bytes[offset + it] == png[it] }
    }
    bytes[start + png.lastIndex] = (bytes[start + png.lastIndex].toInt() xor 1).toByte()
    record.originalFile.writeBytes(bytes)
    val changed = record.copy(id = sha256(record.originalFile))
    receipt(directory, record).copyTo(receipt(directory, changed))
    assertThrows(IconPackException::class.java) {
      IconPackValidationCache(directory).inspect(changed, 37)
    }
  }

  @Test fun missingTruncatedAndCorruptReceiptsFallBackToFullValidation() {
    val record = imported(classic())
    val directory = temporary.newFolder()
    val cache = IconPackValidationCache(directory)
    assertFalse(cache.inspect(record, 37).reusedValidation)
    val file = receipt(directory, record)
    val valid = file.readBytes()
    for (bad in listOf(byteArrayOf(), valid.copyOf(valid.size / 2),
      valid.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }, valid + byteArrayOf(0), ByteArray(4096))) {
      file.writeBytes(bad)
      assertFalse(IconPackValidationCache(directory).inspect(record, 37).reusedValidation)
      assertArrayEquals(valid, file.readBytes())
    }
    assertTrue(file.delete())
    assertFalse(IconPackValidationCache(directory).inspect(record, 37).reusedValidation)
  }

  @Test fun failedFullXmlParseCannotPublishAReceipt() {
    val original = imported(classic())
    original.originalFile.writeBytes(zip(
      "appfilter.xml" to "<resources><item".toByteArray(), "res/drawable/icon.png" to png,
    ))
    val record = original.copy(id = sha256(original.originalFile))
    val directory = temporary.newFolder()
    assertThrows(IconPackException::class.java) {
      IconPackValidationCache(directory).inspect(record, 37)
    }
    assertTrue(directory.listFiles().orEmpty().isEmpty())
  }

  @Test fun sdkValidationRevisionAndEveryLimitInvalidateReceipts() {
    val record = imported(classic())
    val limits = IconPackLimits()
    val variants = listOf(
      limits.copy(maxInputBytes = limits.maxInputBytes - 1),
      limits.copy(maxExpandedBytes = limits.maxExpandedBytes - 1),
      limits.copy(maxEntryBytes = limits.maxEntryBytes - 1),
      limits.copy(maxEntries = limits.maxEntries - 1),
      limits.copy(maxCompressionRatio = limits.maxCompressionRatio - 1),
      limits.copy(maxXmlBytes = limits.maxXmlBytes - 1),
      limits.copy(maxMappings = limits.maxMappings - 1),
      limits.copy(maxStoredPacks = limits.maxStoredPacks - 1),
      limits.copy(maxStoredBytes = limits.maxStoredBytes - 1),
    )
    for (variant in variants) {
      val directory = temporary.newFolder()
      IconPackValidationCache(directory, limits).inspect(record, 37)
      assertFalse(IconPackValidationCache(directory, variant).inspect(record, 37).reusedValidation)
      assertTrue(IconPackValidationCache(directory, variant).inspect(record, 37).reusedValidation)
    }
    val directory = temporary.newFolder()
    IconPackValidationCache(directory).inspect(record, 37)
    assertFalse(IconPackValidationCache(directory).inspect(record, 38).reusedValidation)
    assertFalse(IconPackValidationCache(directory, validationRevision = 2).inspect(record, 38).reusedValidation)
    assertTrue(IconPackValidationCache(directory, validationRevision = 2).inspect(record, 38).reusedValidation)
  }

  @Test fun stricterLimitsStillRejectAndCannotReplaceAValidReceipt() {
    val record = imported(classic())
    val directory = temporary.newFolder()
    IconPackValidationCache(directory).inspect(record, 37)
    val before = receipt(directory, record).readBytes()
    assertThrows(IconPackException::class.java) {
      IconPackValidationCache(directory, IconPackLimits(maxEntries = 1)).inspect(record, 37)
    }
    assertArrayEquals(before, receipt(directory, record).readBytes())
  }

  @Test fun cancellationAtFinalPublicationFenceLeavesNoReceiptOrTemporaryFile() {
    val record = imported(classic())
    var checks = 0
    IconPackValidationCache(temporary.newFolder()).inspect(record, 37) { checks++ }
    val directory = temporary.newFolder()
    var current = 0
    assertThrows(CancellationException::class.java) {
      IconPackValidationCache(directory).inspect(record, 37) {
        if (++current == checks) throw CancellationException("last receipt publication fence")
      }
    }
    assertFalse(receipt(directory, record).exists())
    assertTrue(directory.listFiles().orEmpty().isEmpty())
    assertFalse(IconPackValidationCache(directory).inspect(record, 37).reusedValidation)
  }

  @Test fun cancellationOnReceiptHitPropagatesWithoutChangingTheReceipt() {
    val record = imported(native())
    val directory = temporary.newFolder()
    IconPackValidationCache(directory).inspect(record, 37)
    val before = receipt(directory, record).readBytes()
    var checks = 0
    assertThrows(CancellationException::class.java) {
      IconPackValidationCache(directory).inspect(record, 37) {
        if (++checks == 2) throw CancellationException("hash check")
      }
    }
    assertArrayEquals(before, receipt(directory, record).readBytes())
  }

  @Test fun unusableReceiptStorageDoesNotPreventFullValidation() {
    val record = imported(classic())
    val notDirectory = temporary.newFile()
    assertFalse(IconPackValidationCache(notDirectory).inspect(record, 37).reusedValidation)
  }

  @Test fun receiptCountIsBoundedAndUnrelatedFilesAreUntouched() {
    val directory = temporary.newFolder()
    val unrelated = File(directory, "keep.txt").also { it.writeText("keep") }
    val cache = IconPackValidationCache(directory, IconPackLimits(maxStoredPacks = 2))
    repeat(3) { i ->
      val record = imported(zip("appfilter.xml" to filter, "res/drawable/icon.png" to png,
        "note.txt" to "$i".toByteArray()))
      cache.inspect(record, 37)
    }
    assertEquals(2, directory.listFiles().orEmpty().count { it.name.endsWith(".receipt") })
    assertEquals("keep", unrelated.readText())
  }

  private fun imported(bytes: ByteArray): StoredIconPack =
    IconPackStore(temporary.newFolder()).import(bytes.inputStream(), "fixture.zip").record

  private fun receipt(directory: File, record: StoredIconPack) = File(directory, "${record.id}.receipt")
  private fun classic(stored: Boolean = false) =
    zip("appfilter.xml" to filter, "res/drawable/icon.png" to png, stored = stored)
  private fun native() = zip("res/drawable-xxhdpi/com.example.app.png" to png,
    "nightmode/res/drawable-xxhdpi/com.example.app.png" to png)

  private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray =
    ByteArrayOutputStream().also { output ->
      ZipOutputStream(output).use { zip ->
        for ((name, bytes) in entries) {
          zip.putNextEntry(ZipEntry(name).apply {
            if (stored) {
              method = ZipEntry.STORED
              size = bytes.size.toLong()
              compressedSize = size
              crc = CRC32().also { it.update(bytes) }.value
            }
          })
          zip.write(bytes)
          zip.closeEntry()
        }
      }
    }.toByteArray()

  private val filter =
    "<resources><item component='ComponentInfo{com.example.app/.Main}' drawable='icon'/></resources>".toByteArray()
  private val png = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH2kAAAAASUVORK5CYII=")
}
