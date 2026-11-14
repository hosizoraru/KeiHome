package os.kei.hyper.home.ui.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import os.kei.hyper.home.iconpack.IconPackSource

internal val iconPackCatalogFamilies = setOf("builtin", "installed", "imported", "apple")

/** Callbacks stay on the caller's dispatcher; a failed source does not cancel sibling scans. */
internal suspend fun <K, V> publishCatalogFamilies(
  loaders: Map<K, suspend () -> V>,
  publish: (K, Result<V>) -> Unit,
) = coroutineScope {
  loaders.map { (family, load) ->
    async {
      val result = try { Result.success(load()) }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Exception) { Result.failure(failure) }
      currentCoroutineContext().ensureActive()
      publish(family, result)
    }
  }.awaitAll()
  Unit
}

internal fun iconPackCatalogFamily(card: IconPackCardData): String = when (card.pack.source) {
  is IconPackSource.Builtin -> "builtin"
  is IconPackSource.Installed -> "installed"
  is IconPackSource.Imported -> "imported"
  is IconPackSource.Generated -> "apple"
}

/** Replace whole families, keeping the previous cards until their replacement is ready. */
internal class ProgressiveIconPackCatalog(previous: List<IconPackCardData>) {
  private val families = previous.groupBy(::iconPackCatalogFamily).toMutableMap()

  fun replace(family: String, cards: List<IconPackCardData>): List<IconPackCardData> {
    families[family] = cards
    // Builtins win over legacy imported copies regardless of completion order.
    val builtinIds = families["builtin"].orEmpty().map { it.pack.id }.toSet()
    return iconPackCatalogFamilies.flatMap { source ->
      families[source].orEmpty().filterNot { source == "imported" && it.pack.id in builtinIds }
    }
  }
}

internal fun IconPacksUiState.isSectionLoading(section: String): Boolean = when (section) {
  // Imported APK files also appear in the installed section.
  "installed" -> loadingSections.any { it == "installed" || it == "imported" }
  "imported" -> "imported" in loadingSections
  "builtin", "coloros", "perfect" -> "builtin" in loadingSections
  "apple" -> "apple" in loadingSections
  else -> false
}
