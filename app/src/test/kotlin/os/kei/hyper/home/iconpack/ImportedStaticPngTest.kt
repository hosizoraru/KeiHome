package os.kei.hyper.home.iconpack

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class ImportedStaticPngTest {
  private fun source() = NativeIconPngFixtures.rgba(64)

  @Test fun ancillaryMetadataIsAcceptedWithoutRelaxingGeneratedValidation() {
    val bytes = withChunk(source(), "eXIf", byteArrayOf(1, 2, 3, 4))
    assertEquals(64, ImportedStaticPng.sourceSize(bytes))
    assertThrows(IllegalArgumentException::class.java) { GeneratedIconPng.squareSize(bytes) }
  }

  @Test fun authoredAnimationAndUnknownCriticalChunksAreNotFlattened() {
    for (name in listOf("acTL", "fcTL", "fdAT", "ABCD")) {
      assertNull(ImportedStaticPng.sourceSize(withChunk(source(), name, ByteArray(8))))
    }
  }

  @Test fun corruptCrcTruncationAndTrailingDataAreRejected() {
    val bytes = withChunk(source(), "tEXt", "Author\u0000Local".toByteArray())
    val corrupt = bytes.clone().apply { this[42] = (this[42].toInt() xor 1).toByte() }
    assertNull(ImportedStaticPng.sourceSize(corrupt))
    assertNull(ImportedStaticPng.sourceSize(bytes.copyOf(bytes.size - 1)))
    assertNull(ImportedStaticPng.sourceSize(bytes + byteArrayOf(0)))
  }

  @Test fun sourceBoundsAndCancellationStayBounded() {
    assertNull(ImportedStaticPng.sourceSize(NativeIconPngFixtures.rgba(16)))
    assertThrows(InterruptedException::class.java) {
      ImportedStaticPng.sourceSize(source()) { throw InterruptedException("cancelled") }
    }
  }

  private fun withChunk(png: ByteArray, name: String, data: ByteArray): ByteArray {
    val type = name.toByteArray(Charsets.US_ASCII)
    val chunk = ByteArrayOutputStream().also { bytes ->
      DataOutputStream(bytes).apply {
        writeInt(data.size); write(type); write(data)
        writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
      }
    }.toByteArray()
    return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
  }
}
