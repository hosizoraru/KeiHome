package os.kei.hyper.home.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import os.kei.hyper.home.R
import os.kei.hyper.home.iconpack.apple.AppleArtworkPreview
import os.kei.hyper.home.iconpack.apple.AppleMatchAttemptStatus
import os.kei.hyper.home.iconpack.apple.AppleAutoMatchPolicy
import os.kei.hyper.home.iconpack.apple.AppleDynamicArtwork
import os.kei.hyper.home.iconpack.NativeAppleDynamicIcon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.glass.Filter
import top.yukonga.miuix.kmp.icon.glass.Sort
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.util.Locale

internal enum class AppleMappingFilter {
  ALL, MAPPED, UNMAPPED, CUSTOM, AUTOMATIC, EXCLUDED, CROSS_REGION, SYSTEM, USER, MISSING_DARK,
  RECENTLY_TRIED, NEEDS_REVIEW
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
    Icon(MiuixIcons.Glass.Filter, stringResource(R.string.filter_title), tint =
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
    Icon(MiuixIcons.Glass.Sort, stringResource(R.string.browser_sort), tint =
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
  AppleMappingFilter.MISSING_DARK -> R.string.apple_filter_missing_dark
  AppleMappingFilter.RECENTLY_TRIED -> R.string.apple_batch_filter_recent
  AppleMappingFilter.NEEDS_REVIEW -> R.string.apple_batch_filter_review
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
  locale: Locale = Locale.getDefault(), now: Long = System.currentTimeMillis()): List<AppleMappingRow> {
  val needle = query.trim().lowercase(Locale.ROOT)
  val comparator = Collator.getInstance(locale)
  fun regionOf(row: AppleMappingRow): String? =
    row.app?.let { it.country ?: country } ?: row.attempt?.country
  fun text(row: AppleMappingRow) = when (sort) {
    AppleMappingSort.PACKAGE -> row.target.packageName
    AppleMappingSort.APPLE_NAME -> row.app?.name.orEmpty()
    AppleMappingSort.REGION -> regionOf(row).orEmpty()
    else -> row.target.label
  }
  return rows.filter { row ->
    (!matchedOnly || row.app != null) &&
      (region.isEmpty() || regionOf(row) == region) &&
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
        AppleMappingFilter.MISSING_DARK -> row.app != null &&
          row.darkCapability in listOf(AppleDarkCapability.MISSING, AppleDarkCapability.SHARED)
        AppleMappingFilter.RECENTLY_TRIED -> row.attempt?.let {
          now - it.attemptedAt in 0..AppleAutoMatchPolicy.HISTORY_TTL_MS
        } == true
        AppleMappingFilter.NEEDS_REVIEW -> row.app == null && !row.custom &&
          row.attempt?.status == AppleMatchAttemptStatus.REVIEW
      } &&
      (needle.isEmpty() || listOfNotNull(row.target.label, row.target.packageName, row.app?.name,
        row.app?.publisher, row.app?.bundleId, row.app?.id, regionOf(row))
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

internal fun appleBatchSelectable(row: AppleMappingRow, now: Long): Boolean =
  row.app == null && !row.custom && (row.attempt?.retryAt ?: 0L) <= now

internal fun LazyListScope.appleMappingCards(rows: List<AppleMappingRow>, state: ApplePackUiState,
  controller: ApplePackController, enabled: Boolean,
  currentPreviewProvider: AppleCurrentThemePreviewProvider? = null,
  selectionMode: Boolean = false, selectedPackages: Set<String> = emptySet(),
  selectablePackages: Set<String> = emptySet(), onToggleSelection: (String) -> Unit = {},
  onSelect: (AppleMappingRow) -> Unit) {
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
      onClick = {
        if (enabled) {
          if (!selectionMode) onSelect(row)
          else if (row.target.packageName in selectablePackages) onToggleSelection(row.target.packageName)
        }
      }) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val app = row.app
        val mappingDescription = if (app != null) {
          stringResource(R.string.apple_mapping_accessibility, row.target.label, app.name, row.target.packageName)
        } else {
          stringResource(R.string.apple_mapping_package_accessibility, row.target.label, row.target.packageName)
        }
        Row(Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (selectionMode) {
            val selectionDescription = stringResource(R.string.apple_batch_select_app, row.target.label)
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
              Checkbox(state = if (row.target.packageName in selectedPackages) ToggleableState.On else ToggleableState.Off,
                onClick = { onToggleSelection(row.target.packageName) },
                modifier = Modifier.semantics { contentDescription = selectionDescription },
                enabled = enabled && row.target.packageName in selectablePackages)
            }
          }
          Row(Modifier.weight(1f).clearAndSetSemantics { contentDescription = mappingDescription },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.target.label, Modifier.weight(1f), style = MiuixTheme.textStyles.body1,
              maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (app != null) {
              Icon(MiuixIcons.Basic.ArrowRight, null, Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceSecondary)
              Text(app.name, Modifier.weight(1f), style = MiuixTheme.textStyles.body1,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
          }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)) {
          if (app != null) {
            val country = app.country ?: state.options.country
            val regionDescription = appleRegionName(country, LocalConfiguration.current.locales[0])
            MetadataPill(country.uppercase(Locale.ROOT), MiuixTheme.colorScheme.onSurfaceSecondary,
              Modifier.clearAndSetSemantics { contentDescription = regionDescription })
          }
          MetadataPill(stringResource(when {
            row.custom && app == null -> R.string.apple_excluded
            row.custom -> R.string.apple_custom
            app != null -> R.string.apple_automatic
            else -> R.string.apple_filter_unmapped
          }), MiuixTheme.colorScheme.onSurfaceSecondary)
          app?.let { AppleDynamicArtwork.kind(it, state.options) }?.let { kind ->
            MetadataPill(stringResource(if (kind == NativeAppleDynamicIcon.Kind.CLOCK)
              R.string.apple_dynamic_clock else R.string.apple_dynamic_calendar),
              MiuixTheme.colorScheme.onSurfaceSecondary)
          }
          if (app != null && row.darkCapability in listOf(AppleDarkCapability.MISSING, AppleDarkCapability.SHARED)) {
            val shared = row.darkCapability == AppleDarkCapability.SHARED
            val missingDarkDescription = stringResource(if (shared) R.string.apple_shared_dark_badge else R.string.apple_missing_dark_badge)
            MetadataPill(stringResource(if (shared) R.string.apple_shared_dark_badge else R.string.apple_missing_dark_badge_compact),
              MiuixTheme.colorScheme.onSurfaceSecondary,
              Modifier.clearAndSetSemantics { contentDescription = missingDarkDescription })
          }
          row.attempt?.let { attempt ->
            val label = when (attempt.status) {
              AppleMatchAttemptStatus.MATCHED -> stringResource(R.string.apple_batch_status_matched)
              AppleMatchAttemptStatus.REVIEW -> stringResource(R.string.apple_batch_status_review, attempt.candidates.size)
              AppleMatchAttemptStatus.NO_MATCH -> stringResource(R.string.apple_batch_status_unmatched)
              AppleMatchAttemptStatus.FAILED -> stringResource(R.string.apple_batch_status_failed)
            }
            if (!row.custom && (app == null || attempt.status == AppleMatchAttemptStatus.MATCHED))
              MetadataPill(label, if (attempt.status == AppleMatchAttemptStatus.MATCHED ||
                attempt.status == AppleMatchAttemptStatus.REVIEW)
                  MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
            if (app == null && !row.custom) {
              val regionDescription = appleRegionName(attempt.country, LocalConfiguration.current.locales[0])
              MetadataPill(attempt.country.uppercase(Locale.ROOT), MiuixTheme.colorScheme.onSurfaceSecondary,
                Modifier.clearAndSetSemantics { contentDescription = regionDescription })
            }
          }
        }
        if (app != null) {
          if (currentPreviewProvider != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                ComparisonHeading(stringResource(R.string.apple_preview_current_theme))
                AppleCurrentThemeArtwork(row.target.label, row.target.packageName, currentPreviewProvider)
              }
              Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                ComparisonHeading(stringResource(R.string.apple_preview_comparison_apple))
                AppleCachedArtwork(preview, comparison = true)
              }
            }
          } else AppleCachedArtwork(preview)
          if (AppleDynamicArtwork.kind(app, state.options) != null) {
            Text(stringResource(R.string.apple_dynamic_preview_note), style = MiuixTheme.textStyles.footnote2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
          }
        } else if (currentPreviewProvider != null) {
          ComparisonHeading(stringResource(R.string.apple_preview_current_theme))
          AppleCurrentThemeArtwork(row.target.label, row.target.packageName, currentPreviewProvider)
        }
        row.attempt?.let { attempt ->
          val context = LocalContext.current
          val date = java.util.Date(attempt.attemptedAt)
          val attempted = android.text.format.DateFormat.getDateFormat(context).format(date) + " · " +
            android.text.format.DateFormat.getTimeFormat(context).format(date)
          val retryDate = java.util.Date(attempt.retryAt)
          val retry = android.text.format.DateFormat.getDateFormat(context).format(retryDate) + " · " +
            android.text.format.DateFormat.getTimeFormat(context).format(retryDate)
          Text(if (app == null && !row.custom && attempt.retryAt > System.currentTimeMillis())
            stringResource(R.string.apple_batch_attempt_retry, attempted, retry)
          else stringResource(R.string.apple_batch_attempt_time, attempted),
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
  }
}

@Composable
private fun ComparisonHeading(label: String) {
  Text(label, style = MiuixTheme.textStyles.footnote1,
    color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 2, textAlign = TextAlign.Center)
}

@Composable
private fun AppleCachedArtwork(preview: AppleArtworkPreview?, comparison: Boolean = false) {
  Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
    if (preview != null) AppleArtworkPair(preview, compact = true,
      iconSize = if (comparison) 44.dp else 56.dp) else {
      Text(stringResource(R.string.apple_no_cached_preview), textAlign = TextAlign.Center,
        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
  }
}

@Composable
private fun AppleCurrentThemeArtwork(label: String, packageName: String, provider: AppleCurrentThemePreviewProvider) {
  key(packageName, provider) {
    val loaded by produceState<Pair<Boolean, AppleCurrentThemePreview?>>(false to null) {
      value = true to loadPreviewOrEmptyCurrent { provider.load(packageName) }
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
      val preview = loaded.second
      if (!loaded.first) {
        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
      } else if (preview == null) {
        Text(stringResource(R.string.apple_preview_current_unavailable), textAlign = TextAlign.Center,
          style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
          CurrentThemeThumb(preview.light, label, stringResource(if (preview.dark == null)
            R.string.apple_preview_current_shared else R.string.apple_preview_light), Modifier.weight(1f))
          preview.dark?.let {
            CurrentThemeThumb(it, label, stringResource(R.string.apple_preview_dark), Modifier.weight(1f), dark = true)
          }
        }
      }
    }
  }
}

private suspend fun loadPreviewOrEmptyCurrent(load: suspend () -> AppleCurrentThemePreview?): AppleCurrentThemePreview? =
  try { load() }
  catch (cancelled: CancellationException) { throw cancelled }
  catch (failure: Exception) {
    android.util.Log.w("KeiHomeApple", "Applied theme preview is temporarily unavailable", failure)
    null
  }

@Composable
private fun CurrentThemeThumb(bitmap: android.graphics.Bitmap, appLabel: String, caption: String,
  modifier: Modifier, dark: Boolean = false) {
  val description = stringResource(R.string.apple_preview_current_accessibility, appLabel, caption)
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(Modifier.size(44.dp).background(
      if (dark) Color(0xff16171b) else Color(0xfff2f3f7), RoundedCornerShape(12.dp)),
      contentAlignment = Alignment.Center) {
      val image = remember(bitmap) { bitmap.asImageBitmap() }
      Image(image, description, Modifier.size(44.dp))
    }
    Text(caption, style = MiuixTheme.textStyles.footnote2, textAlign = TextAlign.Center,
      color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
internal fun AppleArtworkPair(preview: AppleArtworkPreview, compact: Boolean = false,
  iconSize: Dp = if (compact) 56.dp else 76.dp) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
      AppleArtworkThumb(preview.light, stringResource(R.string.apple_preview_light),
        iconSize)
    }
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
      AppleArtworkThumb(preview.dark ?: preview.light,
        stringResource(if (preview.dark == null)
          if (preview.limited) R.string.apple_dark_pending else R.string.apple_preview_same else R.string.apple_preview_dark),
        iconSize, dark = true)
    }
  }
}
