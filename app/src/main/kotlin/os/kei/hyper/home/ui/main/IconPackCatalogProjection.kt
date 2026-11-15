package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

internal data class IconPackCatalogProjection(val cards: List<IconPackCardData>, val total: Int)

private val emptyCatalogProjection = IconPackCatalogProjection(emptyList(), 0)

@Composable
internal fun rememberIconPackCatalogProjection(
  state: IconPacksUiState,
  section: String,
  activePackId: String?,
  query: String,
  sort: Pair<Int, Boolean>,
  filter: IconPackCatalogFilter,
): IconPackCatalogProjection {
  if (section == "home") return emptyCatalogProjection
  return remember(SnapshotIdentity(state.packs), section, activePackId, query, sort, filter) {
    IconPackCatalogProjection(
      catalogPackRows(state, section, activePackId, query, sort.first, sort.second, filter),
      catalogPackCount(state, section),
    )
  }
}
