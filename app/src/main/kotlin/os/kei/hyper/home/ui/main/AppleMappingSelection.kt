package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.apple.AppleAutoMatchPolicy
import os.kei.hyper.home.iconpack.apple.AppleIconOptions
import os.kei.hyper.home.iconpack.apple.AppleMappingTransfer
import os.kei.hyper.home.iconpack.apple.AppleTransferredMapping

internal data class AppleMappingSelection(
  val selectable: Set<String>,
  val selected: Set<String>,
  val match: Set<String>,
  val export: List<AppleMappingRow>,
)

internal fun appleMappingSelection(
  rows: List<AppleMappingRow>, selection: Collection<String>, allowMatch: Boolean, now: Long,
): AppleMappingSelection {
  val visible = rows.distinctBy { it.target.packageName }
  val selectable = visible.filter { it.app != null || it.custom || allowMatch && appleBatchSelectable(it, now) }
    .mapTo(linkedSetOf()) { it.target.packageName }
  val chosen = selection.toSet()
  val selected = visible.filter { it.target.packageName in selectable && it.target.packageName in chosen }
  return AppleMappingSelection(
    selectable,
    selected.mapTo(linkedSetOf()) { it.target.packageName },
    if (allowMatch) selected.filter { appleBatchSelectable(it, now) }
      .take(AppleAutoMatchPolicy.BATCH_SIZE).mapTo(linkedSetOf()) { it.target.packageName } else emptySet(),
    selected.filter { it.app != null || it.custom },
  )
}

internal fun retainedAppleSelection(selection: List<String>, selectable: Set<String>): List<String> =
  selection.distinct().filter { it in selectable }

/** Export effective choices for this page's platform, using the existing validated transfer format. */
internal fun appleSelectedMappingDocument(rows: List<AppleMappingRow>, options: AppleIconOptions): ByteArray {
  require(rows.isNotEmpty() && rows.all { it.app != null || it.custom })
  return AppleMappingTransfer.encode(rows.distinctBy { it.target.packageName }.map { row ->
    AppleTransferredMapping(row.target.packageName, options.platform,
      row.app?.country ?: options.country, row.app)
  })
}
