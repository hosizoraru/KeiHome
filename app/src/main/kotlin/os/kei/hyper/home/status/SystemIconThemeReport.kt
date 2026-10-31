package os.kei.hyper.home.status

import androidx.compose.runtime.Immutable
import java.util.Base64

/** Resource inventory of the active native source, not a rendering or night-mode receipt. */
@Immutable
internal data class SystemIconThemeReport(
  val source: Source,
  val title: String?,
  val archivePath: String?,
  val resourceCount: Int,
  val nightResourceCount: Int,
  val complete: Boolean,
  val revision: String,
  val localId: String? = null,
  val nativeHash: String? = null,
) {
  enum class Source { DEFAULT, THEME, UNAVAILABLE }

  fun encode(): String = listOf(source.name, text(title), text(archivePath), resourceCount,
    nightResourceCount, complete, text(revision), text(localId), text(nativeHash)).joinToString(":")

  companion object {
    private fun text(value: String?) = Base64.getEncoder().encodeToString(value.orEmpty().toByteArray())
    private fun read(value: String) = String(Base64.getDecoder().decode(value)).takeIf { it.isNotEmpty() }

    fun decode(value: String?): SystemIconThemeReport? = try {
      val fields = value?.split(':')
      if (fields == null || fields.size !in setOf(7, 9)) null else SystemIconThemeReport(
        Source.valueOf(fields[0]), read(fields[1]), read(fields[2]), fields[3].toInt(),
        fields[4].toInt(), fields[5].toBooleanStrict(), read(fields[6]).orEmpty(),
        fields.getOrNull(7)?.let(::read), fields.getOrNull(8)?.let(::read),
      ).takeIf { it.resourceCount >= 0 && it.nightResourceCount in 0..it.resourceCount }
    } catch (_: IllegalArgumentException) { null }
  }
}
