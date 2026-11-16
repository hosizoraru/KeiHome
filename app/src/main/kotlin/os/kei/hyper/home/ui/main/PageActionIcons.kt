package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.composables.icons.lucide.R as LucideR
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.HomeOutline
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.Item
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.icon.glass.Update
import top.yukonga.miuix.kmp.icon.glass.Edit

internal enum class PageActionIcon {
  UPDATE, APPLY, GENERATE, EXPORT, CLEAR, FETCH, OPEN_EXTERNAL, OPEN_INTERNAL,
  STATUS, APPS, PACKS, DESKTOP, SETTINGS, IMPORT, SEARCH, MATCH, HISTORY, CUSTOMIZE,
}

/** Keep operation glyphs semantic; rereading a list is separate from updating imported resources. */
@Composable
internal fun actionIcon(action: PageActionIcon): ImageVector = when (action) {
  PageActionIcon.UPDATE -> MiuixIcons.Glass.Update
  PageActionIcon.APPLY -> MiuixIcons.Glass.Ok
  PageActionIcon.CLEAR -> MiuixIcons.Glass.Delete
  PageActionIcon.FETCH -> MiuixIcons.Glass.Download
  PageActionIcon.OPEN_INTERNAL -> MiuixIcons.Glass.ChevronForward
  PageActionIcon.APPS -> MiuixIcons.Glass.GridView
  PageActionIcon.SETTINGS -> MiuixIcons.Glass.SettingsOutline
  PageActionIcon.SEARCH -> MiuixIcons.Glass.Search
  PageActionIcon.MATCH -> MiuixIcons.Glass.Link
  PageActionIcon.CUSTOMIZE -> MiuixIcons.Glass.Edit
  PageActionIcon.IMPORT -> ImageVector.vectorResource(LucideR.drawable.lucide_ic_file_input)
  PageActionIcon.GENERATE -> ImageVector.vectorResource(LucideR.drawable.lucide_ic_package_plus)
  PageActionIcon.EXPORT -> ImageVector.vectorResource(LucideR.drawable.lucide_ic_file_output)
  PageActionIcon.OPEN_EXTERNAL -> ImageVector.vectorResource(LucideR.drawable.lucide_ic_square_arrow_out_up_right)
  PageActionIcon.STATUS -> ImageVector.vectorResource(LucideR.drawable.lucide_ic_activity)
  PageActionIcon.PACKS -> MiuixIcons.Glass.Item
  PageActionIcon.DESKTOP -> MiuixIcons.Glass.HomeOutline
  PageActionIcon.HISTORY -> ImageVector.vectorResource(LucideR.drawable.lucide_ic_history)
}
