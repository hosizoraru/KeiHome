package os.kei.hyper.home.iconpack.perfect

import os.kei.hyper.home.iconpack.validNativeIconName
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class PerfectCustomStatic(val background: ByteArray, val foreground: ByteArray) {
  fun layers() = listOf(background, foreground)

  fun archive(): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip ->
      layers().forEachIndexed { index, bytes ->
        PerfectIconFormat.png(bytes, square = true)
        zip.putNextEntry(ZipEntry("$index.png"))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
  }.toByteArray()

  companion object {
    const val SOURCE_PREFIX = "custom-static/"

    fun validId(id: String) = PerfectIconFormat.id(id) && validNativeIconName(id) &&
      ('.' in id || id == "android")

    fun readLayer(input: InputStream): ByteArray =
      input.use { it.readNBytes(PerfectIconFormat.MAX_BLOB + 1) }
        .also { PerfectIconFormat.png(it, square = true) }

    fun read(file: File): PerfectCustomStatic = ZipFile(file).use { zip ->
      require(zip.size() == 2)
      fun layer(index: Int) = readLayer(zip.getInputStream(requireNotNull(zip.getEntry("$index.png"))))
      PerfectCustomStatic(layer(0), layer(1))
    }
  }
}
