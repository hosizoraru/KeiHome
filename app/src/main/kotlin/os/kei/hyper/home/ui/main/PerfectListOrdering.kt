package os.kei.hyper.home.ui.main

import java.text.Collator
import java.util.Locale

internal enum class PerfectPreviewSort { ORIGINAL, NAME, IDENTITY }
internal enum class PerfectResourceFilter { ALL, DOWNLOADED, NOT_DOWNLOADED }
internal enum class PerfectResourceSort { RECOMMENDED, NAME, DOWNLOADS, SIZE }

internal data class PerfectPreviewListItem(
  val key: String,
  val identity: String,
  val label: String?,
  val searchNames: List<String>,
  val installed: Boolean,
  val dynamic: Boolean,
  val independentNight: Boolean,
)

internal fun perfectPreviewRows(
  rows: List<PerfectPreviewListItem>, query: String, category: Int, installedOnly: Boolean,
  sort: PerfectPreviewSort, reverse: Boolean, locale: Locale,
): List<PerfectPreviewListItem> {
  val needle = query.trim()
  val filtered = rows.filter {
    (!installedOnly || it.installed) && when (category) {
      1 -> !it.dynamic
      2 -> it.dynamic
      3 -> it.independentNight
      else -> true
    } && (needle.isEmpty() || it.label?.contains(needle, ignoreCase = true) == true ||
      it.identity.contains(needle, ignoreCase = true) || it.key.contains(needle, ignoreCase = true) ||
      it.searchNames.any { name -> name.contains(needle, ignoreCase = true) })
  }
  if (sort == PerfectPreviewSort.ORIGINAL) return if (reverse) filtered.reversed() else filtered
  val collator = Collator.getInstance(locale)
  return filtered.sortedWith { a, b ->
    val first = if (sort == PerfectPreviewSort.NAME) a.label ?: a.identity else a.identity
    val second = if (sort == PerfectPreviewSort.NAME) b.label ?: b.identity else b.identity
    val compared = collator.compare(first, second).takeUnless { it == 0 }
      ?: a.identity.compareTo(b.identity).takeUnless { it == 0 } ?: a.key.compareTo(b.key)
    if (reverse) -compared.sign() else compared
  }
}

internal fun perfectResourceRows(
  recommended: List<String>, downloaded: Set<String>, sizes: Map<String, Long>,
  filter: PerfectResourceFilter, sort: PerfectResourceSort, reverse: Boolean,
  defaultName: String, locale: Locale,
): List<String> {
  val filtered = recommended.filter {
    when (filter) {
      PerfectResourceFilter.ALL -> true
      PerfectResourceFilter.DOWNLOADED -> it in downloaded
      PerfectResourceFilter.NOT_DOWNLOADED -> it !in downloaded
    }
  }
  if (sort == PerfectResourceSort.RECOMMENDED) return if (reverse) filtered.reversed() else filtered
  val collator = Collator.getInstance(locale)
  fun name(value: String) = if (value == "default") defaultName else value
  return filtered.sortedWith { a, b ->
    val sizeA = sizes[a]?.takeIf { a in downloaded && it > 0 }
    val sizeB = sizes[b]?.takeIf { b in downloaded && it > 0 }
    // Unknown sizes stay last in either direction; they are not zero-byte downloads.
    if (sort == PerfectResourceSort.SIZE && (sizeA == null) != (sizeB == null)) {
      return@sortedWith if (sizeA == null) 1 else -1
    }
    val primary = when (sort) {
      PerfectResourceSort.DOWNLOADS -> (b in downloaded).compareTo(a in downloaded)
      PerfectResourceSort.SIZE -> if (sizeA != null && sizeB != null) sizeB.compareTo(sizeA) else 0
      else -> 0
    }
    val compared = primary.takeUnless { it == 0 }
      ?: collator.compare(name(a), name(b)).takeUnless { it == 0 } ?: a.compareTo(b)
    if (reverse) -compared.sign() else compared
  }
}

internal fun retainedPerfectSelection(selection: List<String>, visible: List<String>): List<String> {
  val keys = visible.toHashSet()
  return selection.filter { it in keys }
}

private fun Int.sign(): Int = compareTo(0)
