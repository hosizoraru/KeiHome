package os.kei.hyper.home.iconpack.apple

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative suggestions, not proof of publisher or cross-platform identity.
 * Callers retain review, manual overrides, storefront choice and exclusions.
 */
internal object AppleAutoMatchPolicy {
  const val BATCH_SIZE = 10
  const val HISTORY_TTL_MS = 7 * 24 * 60 * 60 * 1000L
  const val MANUAL_SEARCH_TTL_MS = 15 * 60 * 1000L
  private const val NAME_SEPARATORS = ".,:;!?\"'\u00b7\u3001\u3002\u30fb"

  /** Successful search history deduplicates automatic batches; it is not a network cooldown. */
  fun canAttempt(attempt: AppleMatchAttempt?, now: Long, explicit: Boolean): Boolean =
    attempt == null || attempt.retryAt <= now ||
      explicit && attempt.status != AppleMatchAttemptStatus.FAILED

  fun choose(target: AppleAppTarget, candidates: List<AppleAppMatch>): AppleAppMatch? {
    val bundleMatches = candidates.filter {
      target.packageName.isNotBlank() && it.bundleId == target.packageName
    }
    // An ambiguous strong match must not be resolved by a weaker title match.
    if (bundleMatches.isNotEmpty()) return unique(bundleMatches)
    val name = normalizedName(target.label)
    if (name.isEmpty()) return null
    return unique(candidates.filter { normalizedName(it.name) == name })
  }

  /** Keep exact alternatives visible for review without changing automatic selection. */
  fun reviewCandidates(target: AppleAppTarget, candidates: List<AppleAppMatch>): List<AppleAppMatch> {
    val name = normalizedName(target.label)
    val (exact, remaining) = candidates.partition {
      (target.packageName.isNotBlank() && it.bundleId == target.packageName) ||
        (name.isNotEmpty() && normalizedName(it.name) == name)
    }
    return (exact + remaining).distinctBy { it.id to it.country }.take(5)
  }

  /** Search text only; matching uses the complete label, never its truncated query. */
  fun query(label: String): String {
    val text = Normalizer.normalize(label, Normalizer.Form.NFKC)
    val points = text.codePoints().iterator()
    val result = StringBuilder()
    var separated = false
    while (points.hasNext()) {
      val point = points.nextInt()
      val type = Character.getType(point)
      if (Character.isWhitespace(point) || Character.isSpaceChar(point) ||
        type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) {
        separated = result.isNotEmpty()
        continue
      }
      val needed = Character.charCount(point) + if (separated) 1 else 0
      if (result.length + needed > 100) break
      if (separated) result.append(' ')
      result.appendCodePoint(point)
      separated = false
    }
    return result.toString().takeIf { containsNameCharacter(it) }.orEmpty()
  }

  private fun unique(matches: List<AppleAppMatch>): AppleAppMatch? =
    matches.distinctBy { it.id to it.country }.singleOrNull()

  private fun normalizedName(label: String): String {
    val text = Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val normalized = buildString {
      text.codePoints().forEach { point ->
        if (!benignSeparator(point)) appendCodePoint(point)
      }
    }
    return normalized.takeIf { containsNameCharacter(it) }.orEmpty()
  }

  private fun containsNameCharacter(text: String): Boolean =
    text.codePoints().anyMatch { Character.isLetterOrDigit(it) }

  private fun benignSeparator(point: Int): Boolean {
    if (Character.isWhitespace(point) || Character.isSpaceChar(point)) return true
    return when (Character.getType(point)) {
      Character.DASH_PUNCTUATION.toInt(), Character.CONNECTOR_PUNCTUATION.toInt(),
      Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
      Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt() -> true
      // Keep semantic symbols such as C++, C#, A&B and percentages distinct.
      else -> point <= Char.MAX_VALUE.code && point.toChar() in NAME_SEPARATORS
    }
  }
}
