package os.kei.hyper.home.iconpack

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Untrusted display labels, never a source identity or an authenticity proof. */
internal data class IconThemeMetadata(
  val title: String?,
  val version: String?,
  val author: String? = null,
) {
  fun encode(): ByteArray {
    fun field(value: String?, limit: Int): String {
      require(value == null || (value.length in 1..limit && value.isNotBlank() && value.none { it.isISOControl() }))
      val bytes = value.orEmpty().toByteArray(Charsets.UTF_8)
      require(utf8(bytes) == value.orEmpty())
      return Base64.getEncoder().encodeToString(bytes)
    }
    return listOf("1", field(title, 120), field(version, 80), field(author, 120))
      .joinToString("\n").toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
  }

  companion object {
    const val PATH = "keihome-theme-metadata.txt"
    const val MAX_BYTES = 4 * 1024

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString()

    fun decode(bytes: ByteArray): IconThemeMetadata? {
      if (bytes.size !in 1..MAX_BYTES) return null
      return try {
        val fields = utf8(bytes).split('\n')
        if (fields.size != 4 || fields[0] != "1") return null
        fun field(value: String) = if (value.isEmpty()) null else utf8(Base64.getDecoder().decode(value))
        val metadata = IconThemeMetadata(field(fields[1]), field(fields[2]), field(fields[3]))
        metadata.takeIf { it.encode().contentEquals(bytes) }
      } catch (_: IllegalArgumentException) { null
      } catch (_: CharacterCodingException) { null }
    }
  }
}
