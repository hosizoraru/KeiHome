package os.kei.hyper.home.iconpack

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class IconThemeMetadataTest {
  @Test fun unicodeDisplayLabelsRoundTripAtTheirLimits() {
    val metadata = IconThemeMetadata("\u4e3b".repeat(120), "\u7248".repeat(80), "\u4f5c".repeat(120))
    assertTrue(metadata.encode().size <= 4 * 1024)
    assertEquals(metadata, IconThemeMetadata.decode(metadata.encode()))
    assertEquals(IconThemeMetadata(null, null), IconThemeMetadata.decode(IconThemeMetadata(null, null).encode()))
    assertEquals(IconThemeMetadata("Title", null), IconThemeMetadata.decode(IconThemeMetadata("Title", null).encode()))
    assertEquals("keihome-theme-metadata.txt", IconThemeMetadata.PATH)
  }

  @Test fun oversizedFieldsAreRejectedOnBothEncodingAndDecoding() {
    for (metadata in listOf(
      IconThemeMetadata("t".repeat(121), "1"),
      IconThemeMetadata("Title", "v".repeat(81)),
      IconThemeMetadata("Title", "1", "a".repeat(121)),
    )) {
      assertThrows(IllegalArgumentException::class.java) { metadata.encode() }
      assertNull(IconThemeMetadata.decode(wire(metadata.title, metadata.version, metadata.author)))
    }
    assertNull(IconThemeMetadata.decode(ByteArray(4096)))
    assertNull(IconThemeMetadata.decode(ByteArray(4097)))
  }

  @Test fun malformedStructureBase64AndUtf8AreNotDisplayMetadata() {
    val invalidUtf8 = Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28))
    for (bytes in listOf(
      byteArrayOf(),
      "2\nVGl0bGU=\nMQ==\n".toByteArray(),
      "1\nVGl0bGU=\nMQ==".toByteArray(),
      "1\nVGl0bGU=\nMQ==\n\n".toByteArray(),
      "1\n%%%\nMQ==\n".toByteArray(),
      "1\n$invalidUtf8\nMQ==\n".toByteArray(),
      "1\nVGl0bGU\nMQ==\n".toByteArray(), // Noncanonical, missing Base64 padding.
      byteArrayOf(0xc3.toByte(), 0x28),
    )) assertNull(IconThemeMetadata.decode(bytes))
  }

  @Test fun blankControlAndUnpairedSurrogateLabelsAreRejected() {
    for (value in listOf("", " ", "A\nB", "A\u0000B", "A\u007fB", "\uD800")) {
      assertThrows(IllegalArgumentException::class.java) { IconThemeMetadata(value, null).encode() }
      if (value.isNotEmpty() && value != "\uD800") assertNull(IconThemeMetadata.decode(wire(value, null, null)))
    }
  }

  private fun wire(title: String?, version: String?, author: String?): ByteArray =
    (listOf("1") + listOf(title, version, author).map {
      Base64.getEncoder().encodeToString(it.orEmpty().toByteArray(Charsets.UTF_8))
    }).joinToString("\n").toByteArray(Charsets.UTF_8)
}
