package os.kei.hyper.home.iconpack

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.xposed.ConvertedIconArchive

class ImageArchiveZipTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun encodedImagesAndArchivesAreStoredWithExactSizeAndCrc() {
    val bytes = ByteArray(100_003) { (it * 31).toByte() }
    val names = listOf("day.png", "night.PNG", "preview.jpg", "a.jpeg", "a.webp", "a.gif",
      "a.avif", "a.heic", "a.heif", "a.zip", "a.mtz", "a.apk", "icons")
    val file = archive { output ->
      names.forEach { ImageArchiveZip.writeBytes(output, it, bytes) }
      ImageArchiveZip.writeBytes(output, "empty.png", byteArrayOf())
    }
    ZipFile(file).use { zip ->
      for (name in names) assertEntry(zip, name, bytes, ZipEntry.STORED)
      assertEntry(zip, "empty.png", byteArrayOf(), ZipEntry.STORED)
    }
    // Also exercise local headers, including size/CRC before the first payload byte.
    ZipInputStream(file.inputStream()).use { input ->
      for (name in names) {
        val entry = input.nextEntry
        assertEquals(name, entry.name)
        assertEquals(bytes.size.toLong(), entry.size)
        assertEquals(crc(bytes), entry.crc)
        assertArrayEquals(bytes, input.readBytes())
      }
    }
  }

  @Test fun xmlAndTextStayDeflatedEvenWhenOutputDefaultIsStored() {
    val xml = "<Icon/>".repeat(500).toByteArray()
    val file = archive { output ->
      output.setMethod(ZipEntry.STORED)
      for (name in listOf("manifest.xml", "source.txt", "icon.svg", "unknown.bin")) {
        ImageArchiveZip.writeBytes(output, name, xml)
      }
    }
    ZipFile(file).use { zip ->
      zip.entries().asSequence().forEach { assertEntry(zip, it.name, xml, ZipEntry.DEFLATED) }
      assertTrue(zip.getEntry("manifest.xml").compressedSize < xml.size)
    }
  }

  @Test fun preservedEntriesStreamOnceKeepNamesTimesAndEveryPayloadByte() {
    val bytes = ByteArray(100_003) { it.toByte() }
    val time = 1_700_000_000_000L
    val original = archive { output ->
      for (name in listOf("nightmode/res/drawable/org.example.png", "fancy_icons/org.clock/manifest.xml", "opaque.bin", "empty/")) {
        output.putNextEntry(ZipEntry(name).apply { this.time = time })
        if (!name.endsWith('/')) output.write(bytes)
        output.closeEntry()
      }
    }
    var opens = 0
    val copied = archive { output ->
      object : ZipFile(original) {
        override fun getInputStream(entry: ZipEntry): InputStream {
          opens++
          return super.getInputStream(entry)
        }
      }.use { source ->
        source.entries().asSequence().forEach { ImageArchiveZip.copyEntry(output, source, it) }
      }
    }
    assertEquals(3, opens)
    ZipFile(copied).use { zip ->
      assertEquals(4, zip.size())
      zip.entries().asSequence().forEach { entry ->
        assertEquals(time, entry.time)
        assertEntry(zip, entry.name, if (entry.isDirectory) byteArrayOf() else bytes,
          if (entry.name.endsWith(".png")) ZipEntry.STORED else ZipEntry.DEFLATED)
      }
    }
  }

  @Test fun copiedImagesCanBeRenamedIntoNightTreeWithoutDecoding() {
    val png = NativeIconPngFixtures.rgba()
    val source = archive { ImageArchiveZip.writeBytes(it, "day.png", png) }
    val copied = archive { output ->
      ZipFile(source).use {
        ImageArchiveZip.copyEntry(output, it, it.getEntry("day.png"), "nightmode/day.png", time = 0)
      }
    }
    ZipFile(copied).use { assertEntry(it, "nightmode/day.png", png, ZipEntry.STORED) }
  }

  @Test fun missingCopyMetadataUsesStreamingCrcInsteadOfBufferingPayload() {
    val bytes = ByteArray(100_003) { it.toByte() }
    val source = archive { ImageArchiveZip.writeBytes(it, "day.png", bytes) }
    val copied = archive { output ->
      ZipFile(source).use { ImageArchiveZip.copyEntry(output, it, ZipEntry("day.png")) }
    }
    ZipFile(copied).use { assertEntry(it, "day.png", bytes, ZipEntry.STORED) }
  }

  @Test fun copyRejectsIncorrectKnownSizeOrCrc() {
    val source = archive { ImageArchiveZip.writeBytes(it, "day.png", byteArrayOf(1, 2, 3)) }
    for (wrongSize in listOf(false, true)) {
      assertThrows(ZipException::class.java) {
        archive { output ->
          ZipFile(source).use { zip ->
            val entry = ZipEntry(zip.getEntry("day.png"))
            if (wrongSize) entry.size += 1 else entry.crc = entry.crc xor 1L
            ImageArchiveZip.copyEntry(output, zip, entry)
          }
        }
      }
    }
  }

  @Test fun nestedStoredZipPassesNativeAndConvertedValidatorsWithoutChangingGeneratedBytes() {
    val name = "org.example.Main"
    val light = NativeIconPngFixtures.rgba()
    val dark = NativeIconPngFixtures.rgba { _, _ -> 128 }
    for (palette in listOf(false, true)) {
      for (night in listOf(light, dark)) {
        val entries = if (palette) NativePaletteIcon.entries(name, light, night,
          NativeIconPngFixtures.mask(), NativeIconPngFixtures.indexedMask())
        else NativeDayNightIcon.entries(name, light, night) +
          ("nightmode/res/drawable-xxhdpi/$name.png" to night)
        val inner = archive { output ->
          entries.forEach { (path, bytes) -> ImageArchiveZip.writeBytes(output, path, bytes) }
        }
        ZipFile(inner).use { zip ->
          entries.forEach { (path, bytes) ->
            assertEntry(zip, path, bytes, if (path.endsWith(".png")) ZipEntry.STORED else ZipEntry.DEFLATED)
          }
        }
        val mtz = archive { output ->
          ImageArchiveZip.writeBytes(output, "description.xml",
            ("<MIUI-Theme><title>Test</title><author>KeiHome</author><designer>KeiHome</designer>" +
              "<version>1</version><uiVersion>12</uiVersion><description>Test icons</description></MIUI-Theme>").toByteArray())
          ImageArchiveZip.writeFile(output, "icons", inner)
          ImageArchiveZip.writeBytes(output, "preview/preview_icons_0.png", light)
        }
        ZipFile(mtz).use { assertEntry(it, "icons", inner.readBytes(), ZipEntry.STORED) }
        assertEquals(IconPackFormat.MIUI_ICONS, IconPackArchive().inspect(inner, temporary.newFile()).format)
        assertEquals(IconPackFormat.MIUI_MTZ, IconPackArchive().inspect(mtz, temporary.newFile()).format)
        val proof = ConvertedIconArchive.inspect(mtz, "CONVERTED_CLASSIC_ZIP")
        assertEquals(ConvertedIconArchive.digest(inner), proof.icons)
      }
    }
  }

  @Test fun fileXmlKeepsDeflateAndFileImagesUseExactMetadata() {
    val source = temporary.newFile().apply { writeBytes(ByteArray(100_003) { it.toByte() }) }
    val output = archive {
      ImageArchiveZip.writeFile(it, "original.png", source)
      ImageArchiveZip.writeFile(it, "manifest.xml", source)
    }
    ZipFile(output).use {
      assertEntry(it, "original.png", source.readBytes(), ZipEntry.STORED)
      assertEntry(it, "manifest.xml", source.readBytes(), ZipEntry.DEFLATED)
    }
  }

  @Test fun cancellationPropagatesBeforeHeaderAndDuringCrcCopyAndFilePasses() {
    val bytes = ByteArray(200_000) { it.toByte() }
    val source = archive { ImageArchiveZip.writeBytes(it, "day.png", bytes) }
    val cancelled = CancellationException("stop")
    val empty = ByteArrayOutputStream()
    ZipOutputStream(empty).use { output ->
      assertSame(cancelled, assertThrows(CancellationException::class.java) {
        ImageArchiveZip.writeBytes(output, "day.png", bytes, check = { throw cancelled })
      })
      assertEquals(0, empty.size())
    }
    // Seven chunks per pass: exercise byte CRC, byte output, copy, file CRC and file output.
    for (operation in 0..2) for (stop in if (operation == 1) listOf(3) else listOf(3, 10)) {
      var checks = 0
      val check = { if (++checks == stop) throw cancelled }
      assertSame(cancelled, assertThrows(CancellationException::class.java) {
        archive { output ->
          when (operation) {
            0 -> ImageArchiveZip.writeBytes(output, "day.png", bytes, check = check)
            1 -> ZipFile(source).use { ImageArchiveZip.copyEntry(output, it, it.getEntry("day.png"), check = check) }
            else -> ImageArchiveZip.writeFile(output, "icons", source, check = check)
          }
        }
      })
      assertEquals(stop, checks)
    }
  }

  private fun archive(write: (ZipOutputStream) -> Unit): File = temporary.newFile().also { file ->
    ZipOutputStream(file.outputStream()).use(write)
  }

  private fun assertEntry(zip: ZipFile, name: String, bytes: ByteArray, method: Int) {
    val entry = zip.getEntry(name)
    assertEquals(method, entry.method)
    assertEquals(bytes.size.toLong(), entry.size)
    if (method == ZipEntry.STORED) assertEquals(entry.size, entry.compressedSize)
    assertEquals(crc(bytes), entry.crc)
    assertArrayEquals(bytes, zip.getInputStream(entry).use { it.readBytes() })
  }

  private fun crc(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value
}
