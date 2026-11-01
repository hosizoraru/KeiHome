package os.kei.hyper.home.iconpack

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal object NativeIconPngFixtures {
  fun rgba(size: Int = 2, alpha: (Int, Int) -> Int = { _, _ -> 255 }): ByteArray {
    val raw = ByteArrayOutputStream()
    repeat(size) { y ->
      raw.write(0)
      repeat(size) { x -> raw.write(byteArrayOf(12, 34, 56, alpha(x, y).toByte())) }
    }
    return png(size, 8, 6, raw.toByteArray())
  }

  fun mask(size: Int = 2): ByteArray = rgba(size) { x, y ->
    if (x == 0 && y == 0) 0 else if (x == 0) 128 else 255
  }

  fun indexedMask(): ByteArray = png(2, 4, 3, byteArrayOf(0, 0x01, 0, 0x10),
    palette = ByteArray(16 * 3), transparency = byteArrayOf(0, -1))

  fun png(size: Int, depth: Int, type: Int, raw: ByteArray,
    palette: ByteArray? = null, transparency: ByteArray? = null): ByteArray =
    ByteArrayOutputStream().also { bytes ->
      val output = DataOutputStream(bytes)
      output.writeLong(-8552249625308161526L)
      fun chunk(name: String, data: ByteArray) {
        val kind = name.toByteArray(Charsets.US_ASCII)
        output.writeInt(data.size); output.write(kind); output.write(data)
        output.writeInt(CRC32().apply { update(kind); update(data) }.value.toInt())
      }
      chunk("IHDR", ByteArrayOutputStream().also { header -> DataOutputStream(header).apply {
        writeInt(size); writeInt(size); write(byteArrayOf(depth.toByte(), type.toByte(), 0, 0, 0))
      } }.toByteArray())
      palette?.let { chunk("PLTE", it) }
      transparency?.let { chunk("tRNS", it) }
      chunk("IDAT", ByteArrayOutputStream().also { compressed ->
        DeflaterOutputStream(compressed).use { it.write(raw) }
      }.toByteArray())
      chunk("IEND", byteArrayOf())
    }.toByteArray()
}
