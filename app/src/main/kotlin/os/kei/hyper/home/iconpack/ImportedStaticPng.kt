package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32

/** Decode bounded static source artwork while retaining strict generated-resource validation. */
internal object ImportedStaticPng {
  private val animation = setOf("acTL", "fcTL", "fdAT")
  private val critical = setOf("IHDR", "PLTE", "IDAT", "IEND")

  fun normalize(bytes: ByteArray, check: () -> Unit): ByteArray? {
    check()
    val size = sourceSize(bytes, check) ?: return null
    try {
      if (GeneratedIconPng.squareSize(bytes) == size) return bytes
    } catch (_: IllegalArgumentException) { }
    check()
    val options = BitmapFactory.Options().apply {
      inScaled = false
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    try {
      check()
      if (bitmap.width != size || bitmap.height != size) return null
      val output = ByteArrayOutputStream()
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
      check()
      val encoded = output.toByteArray()
      return try { encoded.takeIf { GeneratedIconPng.squareSize(it) == size } }
        catch (_: IllegalArgumentException) { null }
    } finally { bitmap.recycle() }
  }

  fun sourceSize(bytes: ByteArray, check: () -> Unit = {}): Int? {
    if (bytes.size !in 57..4 * 1024 * 1024) return null
    val input = DataInputStream(ByteArrayInputStream(bytes))
    try {
      if (input.readLong() != 0x89504e470d0a1a0aUL.toLong()) return null
      var size: Int? = null
      var pixels = false
      while (input.available() >= 12) {
        check()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("PNG inspection interrupted")
        val length = input.readInt()
        if (length < 0 || length > input.available() - 8) return null
        val type = ByteArray(4).also(input::readFully)
        if (type.any { it.toInt().toChar() !in 'A'..'Z' && it.toInt().toChar() !in 'a'..'z' })
          return null
        if (type[2].toInt().toChar() !in 'A'..'Z') return null
        val name = String(type, Charsets.US_ASCII)
        if (name in animation || size == null && name != "IHDR") return null
        if (name !in critical && type[0].toInt().toChar() !in 'a'..'z')
          return null
        val data = ByteArray(length).also(input::readFully)
        if (CRC32().apply { update(type); update(data) }.value != (input.readInt().toLong() and 0xffffffffL))
          return null
        when (name) {
          "IHDR" -> {
            if (size != null || length != 13) return null
            val header = DataInputStream(ByteArrayInputStream(data))
            val width = header.readInt()
            if (width !in 32..512 || header.readInt() != width) return null
            size = width
          }
          "IDAT" -> pixels = true
          "IEND" -> return size.takeIf { length == 0 && pixels && input.available() == 0 }
        }
      }
      return null
    } catch (_: java.io.EOFException) { return null }
  }
}
