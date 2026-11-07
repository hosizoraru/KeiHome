package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.apple.AppleArtworkPreview
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.util.Locale

internal enum class AppleMappingFilter {
  ALL, MAPPED, UNMAPPED, CUSTOM, AUTOMATIC, EXCLUDED, CROSS_REGION, SYSTEM, USER
}

internal enum class AppleMappingSort { NAME, NAME_DESC, PACKAGE, APPLE_NAME, REGION, CUSTOM_FIRST }

@Composable
internal fun AppleMappingFilterMenu(filter: AppleMappingFilter, region: String, regions: List<String>,
  matchedOnly: Boolean, onFilter: (AppleMappingFilter) -> Unit, onRegion: (String) -> Unit) {
  val locale = LocalConfiguration.current.locales[0]
  val choices = AppleMappingFilter.entries.filter { !matchedOnly ||
    it !in listOf(AppleMappingFilter.MAPPED, AppleMappingFilter.UNMAPPED, AppleMappingFilter.EXCLUDED) }
  val groups = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.apple_browser_filter), children = choices.map {
      DropdownItem(text = stringResource(it.label), selected = filter == it, onClick = { onFilter(it) })
    }),
    DropdownItem(text = stringResource(R.string.apple_region), children = (listOf("") + regions).map {
      DropdownItem(text = if (it.isBlank()) stringResource(R.string.apple_region_filter_all)
        else appleRegionName(it, locale), selected = region == it, onClick = { onRegion(it) })
    }),
  ))
  val reset = DropdownEntry(items = listOf(
    DropdownItem(text = stringResource(R.string.browser_reset), onClick = {
      onFilter(AppleMappingFilter.ALL); onRegion("")
    }),
  ))
  OverlayIconCascadingDropdownMenu(entries = listOf(groups, reset)) {
    Icon(MiuixIcons.Filter, stringResource(R.string.filter_title), tint =
      if (filter != AppleMappingFilter.ALL || region.isNotBlank()) MiuixTheme.colorScheme.primary
      else MiuixTheme.colorScheme.onBackground)
  }
}

@Composable
internal fun AppleMappingSortMenu(sort: AppleMappingSort, onSort: (AppleMappingSort) -> Unit) {
  val choices = DropdownEntry(items = AppleMappingSort.entries.map {
    DropdownItem(text = stringResource(it.label), selected = sort == it, onClick = { onSort(it) })
  })
  OverlayIconCascadingDropdownMenu(entries = listOf(choices)) {
    Icon(MiuixIcons.Sort, stringResource(R.string.browser_sort), tint =
      if (sort != AppleMappingSort.NAME) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground)
  }
}

internal val AppleMappingFilter.label: Int get() = when (this) {
  AppleMappingFilter.ALL -> R.string.apple_filter_all
  AppleMappingFilter.MAPPED -> R.string.apple_filter_mapped
  AppleMappingFilter.UNMAPPED -> R.string.apple_filter_unmapped
  AppleMappingFilter.CUSTOM -> R.string.apple_filter_custom
  AppleMappingFilter.AUTOMATIC -> R.string.apple_automatic
  AppleMappingFilter.EXCLUDED -> R.string.apple_excluded
  AppleMappingFilter.CROSS_REGION -> R.string.apple_filter_cross_region
  AppleMappingFilter.SYSTEM -> R.string.apple_filter_system
  AppleMappingFilter.USER -> R.string.apple_filter_user
}

internal val AppleMappingSort.label: Int get() = when (this) {
  AppleMappingSort.NAME -> R.string.apple_sort_name
  AppleMappingSort.NAME_DESC -> R.string.apple_sort_name_desc
  AppleMappingSort.PACKAGE -> R.string.apple_sort_package
  AppleMappingSort.APPLE_NAME -> R.string.apple_sort_apple
  AppleMappingSort.REGION -> R.string.apple_sort_region
  AppleMappingSort.CUSTOM_FIRST -> R.string.apple_sort_custom
}

internal fun appleMappingRows(rows: List<AppleMappingRow>, query: String, filter: AppleMappingFilter,
  sort: AppleMappingSort, country: String, region: String = "", matchedOnly: Boolean = false,
  locale: Locale = Locale.getDefault()): List<AppleMappingRow> {
  val needle = query.trim().lowercase(Locale.ROOT)
  val comparator = Collator.getInstance(locale)
  fun text(row: AppleMappingRow) = when (sort) {
    AppleMappingSort.PACKAGE -> row.target.packageName
    AppleMappingSort.APPLE_NAME -> row.app?.name.orEmpty()
    AppleMappingSort.REGION -> row.app?.let { it.country ?: country }.orEmpty()
    else -> row.target.label
  }
  return rows.filter { row ->
    (!matchedOnly || row.app != null) &&
      (region.isEmpty() || row.app?.let { it.country ?: country } == region) &&
      when (filter) {
        AppleMappingFilter.ALL -> true
        AppleMappingFilter.MAPPED -> row.app != null
        AppleMappingFilter.UNMAPPED -> row.app == null
        AppleMappingFilter.CUSTOM -> row.custom
        AppleMappingFilter.AUTOMATIC -> row.app != null && !row.custom
        AppleMappingFilter.EXCLUDED -> row.custom && row.app == null
        AppleMappingFilter.CROSS_REGION -> row.app?.country?.let { it != country } == true
        AppleMappingFilter.SYSTEM -> row.target.system
        AppleMappingFilter.USER -> !row.target.system
      } &&
      (needle.isEmpty() || listOfNotNull(row.target.label, row.target.packageName, row.app?.name,
        row.app?.publisher, row.app?.bundleId, row.app?.id, row.app?.let { it.country ?: country })
        .any { needle in it.lowercase(Locale.ROOT) })
  }.sortedWith { a, b ->
    val priority = when (sort) {
      AppleMappingSort.CUSTOM_FIRST -> b.custom.compareTo(a.custom)
      AppleMappingSort.APPLE_NAME, AppleMappingSort.REGION -> (a.app == null).compareTo(b.app == null)
      else -> 0
    }
    if (priority != 0) priority else {
      val order = comparator.compare(text(a), text(b))
      if (order == 0) a.target.packageName.compareTo(b.target.packageName)
      else if (sort == AppleMappingSort.NAME_DESC) -order else order
    }
  }
}

internal fun appleRegionName(country: String, locale: Locale = Locale.getDefault()): String {
  val code = country.uppercase(Locale.ROOT)
  val name = Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
  return if (name == code) code else "$name · $code"
}

internal fun LazyListScope.appleMappingCards(rows: List<AppleMappingRow>, state: ApplePackUiState,
  controller: ApplePackController, enabled: Boolean, onSelect: (AppleMappingRow) -> Unit) {
  if (rows.isEmpty()) item("no-mapping-results", contentType = "message") {
    PackInfoCard(stringResource(R.string.apple_browser_empty))
  }
  items(rows, key = { it.target.packageName }, contentType = { if (it.app == null) "unmapped" else "mapped" }) { row ->
    val preview by produceState<AppleArtworkPreview?>(null, row.app, state.options, state.previewRevision) {
      value = try { controller.cachedPreview(row) }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp),
      onClick = { if (enabled) onSelect(row) }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(row.target.label, Modifier.weight(1f), style = MiuixTheme.textStyles.body1,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
          MetadataPill(stringResource(when {
            row.custom && row.app == null -> R.string.apple_excluded
            row.custom -> R.string.apple_custom
            row.app != null -> R.string.apple_automatic
            else -> R.string.apple_filter_unmapped
          }), MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Text(row.target.packageName, style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
        row.app?.let { app ->
          Text("${app.name} · ${appleRegionName(app.country ?: state.options.country, LocalConfiguration.current.locales[0])}",
            style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
          val cached = preview
          Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
            if (cached != null) AppleArtworkPair(cached, compact = true) else {
              Text(stringResource(R.string.apple_no_cached_preview),
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun AppleArtworkPair(preview: AppleArtworkPreview, compact: Boolean = false) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
    AppleArtworkThumb(preview.light, stringResource(R.string.apple_preview_light),
      if (compact) 56.dp else 76.dp)
    AppleArtworkThumb(preview.dark ?: preview.light,
      stringResource(if (preview.dark == null) R.string.apple_preview_same else R.string.apple_preview_dark),
      if (compact) 56.dp else 76.dp, dark = true)
  }
}
