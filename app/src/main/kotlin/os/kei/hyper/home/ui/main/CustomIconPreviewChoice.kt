package os.kei.hyper.home.ui.main

import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackEntryIndex

internal data class CustomIconPreviewChoice(val match: IconPackEntry?, val examples: List<IconPackEntry>)

/** Preview matching never infers package ownership from a filename prefix. */
internal fun customIconPreviewChoice(pack: IconPackDescriptor, target: IconPackComponent): CustomIconPreviewChoice {
  val match = IconPackEntryIndex(pack.entries).find(target)
    ?: pack.entries.firstOrNull { it.component?.packageName == target.packageName }
  if (match != null) return CustomIconPreviewChoice(match, listOf(match))
  val preferred = listOf("com.android.settings", "com.android.contacts", "com.android.camera")
    .mapNotNull { name -> pack.entries.firstOrNull { it.component?.packageName == name || it.nativeName == name } }
  return CustomIconPreviewChoice(null, (preferred + pack.entries.take(3)).distinctBy { it.key }.take(3))
}
