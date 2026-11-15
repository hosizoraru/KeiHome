package os.kei.hyper.home.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource

class IconPackCatalogProjectionDeviceTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()

  @Test fun deeplyEqual25kReplacementDoesNotRescanMappingsOnUnrelatedRecompositions() {
    verifyReplacementAndControls(25_000)
  }

  @Test fun deeplyEqual50kReplacementDoesNotRescanMappingsOnUnrelatedRecompositions() {
    verifyReplacementAndControls(50_000)
  }

  @Test fun homeSkipsCatalogTraversalEvenAfterReplacementAndControlChanges() {
    val old = catalog(50_000)
    val fresh = catalog(50_000)
    proveDeepEqualityAndReset(old, fresh)
    val host = Host(old, initialSection = "home")
    start(host)
    rule.runOnIdle {
      assertRows(host, 0)
      assertUntouched(old, fresh, includeCatalogReads = true)
    }

    change(host) {
      host.state.value = IconPacksUiState(packs = fresh.cards)
      host.query.value = "atlas"
      host.active.value = "atlas"
      host.filter.value = IconPackCatalogFilter(status = IconPackCatalogStatus.CURRENT)
      host.sort.value = 3 to true
    }
    tickWithoutTraversal(host, old, fresh, includeCatalogReads = true)
    rule.runOnIdle {
      assertRows(host, 0)
      assertUntouched(old, fresh, includeCatalogReads = true)
    }

    // Positive control: the same nonempty payload becomes visible on a library section.
    change(host) { host.section.value = "installed" }
    rule.runOnIdle {
      assertRows(host, 2, fresh.atlas)
      assertTrue("Library projection must actually inspect the catalog", fresh.catalogReads.gets > 0)
      old.reset()
      fresh.reset()
    }
    change(host) { host.section.value = "home" }
    tickWithoutTraversal(host, old, fresh, includeCatalogReads = true)
    rule.runOnIdle { assertRows(host, 0) }
  }

  private fun verifyReplacementAndControls(mappingCount: Int) {
    val old = catalog(mappingCount)
    val fresh = catalog(mappingCount)
    proveDeepEqualityAndReset(old, fresh)
    val host = Host(old)
    start(host)
    rule.runOnIdle {
      assertRows(host, 2, old.atlas, old.zephyr)
      assertUntouched(old, fresh)
      old.reset()
      fresh.reset()
    }

    val beforeReplacement = host.projection
    change(host) { host.state.value = IconPacksUiState(packs = fresh.cards) }
    rule.runOnIdle {
      assertNotSame("Equal-valued replacement must invalidate the projection",
        beforeReplacement, host.projection)
      assertRows(host, 2, fresh.atlas, fresh.zephyr)
      assertSame("A stale descriptor must not survive an equal-valued package update",
        fresh.atlas.pack, host.projection.cards[0].pack)
      assertSame(fresh.atlas.pack.entries, host.projection.cards[0].pack.entries)
      assertUntouched(old, fresh)
      old.reset()
      fresh.reset()
    }
    tickWithoutTraversal(host, old, fresh, includeCatalogReads = true)

    change(host) { host.query.value = "ORG.KEIHOME.ZEPHYR" }
    rule.runOnIdle { assertRows(host, 2, fresh.zephyr) }
    change(host) { host.query.value = " atlas " }
    rule.runOnIdle { assertRows(host, 2, fresh.atlas) }
    change(host) {
      host.active.value = "atlas"
      host.filter.value = IconPackCatalogFilter(status = IconPackCatalogStatus.OTHER)
    }
    rule.runOnIdle { assertRows(host, 2) }
    change(host) {
      host.query.value = ""
      host.filter.value = IconPackCatalogFilter(matches = IconPackCatalogMatches.MATCHED)
    }
    rule.runOnIdle { assertRows(host, 2, fresh.atlas) }
    change(host) {
      host.filter.value = IconPackCatalogFilter(matches = IconPackCatalogMatches.UNMATCHED)
    }
    rule.runOnIdle { assertRows(host, 2, fresh.zephyr) }
    change(host) {
      host.filter.value = IconPackCatalogFilter(status = IconPackCatalogStatus.CURRENT)
      host.active.value = "zephyr"
    }
    rule.runOnIdle { assertRows(host, 2, fresh.zephyr) }
    change(host) {
      host.active.value = null
      host.filter.value = IconPackCatalogFilter()
      host.sort.value = 0 to true
    }
    rule.runOnIdle { assertRows(host, 2, fresh.zephyr, fresh.atlas) }
    change(host) { host.active.value = "atlas" }
    rule.runOnIdle { assertRows(host, 2, fresh.atlas, fresh.zephyr) }
    change(host) { host.section.value = "imported" }
    rule.runOnIdle { assertRows(host, 0) }
    change(host) {
      host.section.value = "installed"
      host.query.value = "no-such-pack"
    }
    rule.runOnIdle {
      assertRows(host, 2)
      assertUntouched(old, fresh)
      old.reset()
      fresh.reset()
    }
    tickWithoutTraversal(host, old, fresh, includeCatalogReads = true)
  }

  private fun start(host: Host) {
    rule.setContent {
      val tick = host.tick.intValue
      // Change an ignored field so even a skippable production composable must execute each tick.
      // Reusing identical arguments could hide the retained structural remember-key regression.
      val state = host.state.value.copy(importName = "unrelated-$tick")
      val projection = rememberIconPackCatalogProjection(
        state, host.section.value, host.active.value, host.query.value, host.sort.value, host.filter.value,
      )
      SideEffect {
        host.projection = projection
        host.observedTick = tick
        host.compositions++
      }
    }
    rule.waitForIdle()
  }

  private fun change(host: Host, update: () -> Unit) {
    var previousCompositions = 0
    rule.runOnIdle {
      previousCompositions = host.compositions
      update()
    }
    rule.runOnIdle {
      assertTrue("Control update must recompose the actual fixture", host.compositions > previousCompositions)
    }
  }

  private fun tickWithoutTraversal(
    host: Host, old: Catalog, fresh: Catalog, includeCatalogReads: Boolean,
  ) {
    val cached = host.projection
    val startTick = host.tick.intValue
    repeat(24) { offset ->
      change(host) { host.tick.intValue++ }
      rule.runOnIdle {
        assertEquals(startTick + offset + 1, host.observedTick)
        assertSame("Unrelated updates must reuse the projection", cached, host.projection)
        assertUntouched(old, fresh, includeCatalogReads)
      }
    }
  }

  private fun assertRows(host: Host, total: Int, vararg expected: IconPackCardData) {
    assertEquals("Section total is independent of query/filter", total, host.projection.total)
    assertEquals(expected.size, host.projection.cards.size)
    expected.forEachIndexed { index, card ->
      // Structural assertions here would themselves compare all mappings and mask a stale payload.
      assertSame("Projected row $index must come from the current payload", card, host.projection.cards[index])
    }
  }

  private fun proveDeepEqualityAndReset(old: Catalog, fresh: Catalog) {
    assertNotSame(old.cards, fresh.cards)
    assertNotSame(old.atlas.pack, fresh.atlas.pack)
    assertNotSame(old.atlas.pack.entries, fresh.atlas.pack.entries)
    assertEquals("Replacement payload really is structurally equal", old.cards, fresh.cards)
    val oldEntry = old.atlas.pack.entries.first()
    val freshEntry = fresh.atlas.pack.entries.first()
    assertNotSame(oldEntry, freshEntry)
    assertNotSame(oldEntry.component, freshEntry.component)
    assertTrue("The equality probe must reach the full large mapping list",
      old.mappingReads.gets >= old.atlas.pack.entries.size &&
        fresh.mappingReads.gets >= fresh.atlas.pack.entries.size)
    assertTrue(old.mappingReads.equalsCalls > 0)
    old.reset()
    fresh.reset()
  }

  private fun assertUntouched(old: Catalog, fresh: Catalog, includeCatalogReads: Boolean = false) {
    for ((name, catalog) in listOf("old" to old, "fresh" to fresh)) {
      assertEquals("$name mapping-list equals calls", 0, catalog.mappingReads.equalsCalls)
      assertEquals("$name mapping element reads", 0, catalog.mappingReads.gets)
      if (includeCatalogReads) assertEquals("$name catalog element reads", 0, catalog.catalogReads.gets)
    }
  }

  private class Host(catalog: Catalog, initialSection: String = "installed") {
    val state = mutableStateOf(IconPacksUiState(packs = catalog.cards), referentialEqualityPolicy())
    val tick = mutableIntStateOf(0)
    val section = mutableStateOf(initialSection)
    val active = mutableStateOf<String?>(null)
    val query = mutableStateOf("")
    val sort = mutableStateOf(0 to false)
    val filter = mutableStateOf(IconPackCatalogFilter())
    lateinit var projection: IconPackCatalogProjection
    var observedTick = -1
    var compositions = 0
  }

  private class Reads {
    var gets = 0
    var equalsCalls = 0
    fun reset() { gets = 0; equalsCalls = 0 }
  }

  private class CountingList<T>(private val values: List<T>, private val reads: Reads) : AbstractList<T>() {
    override val size: Int get() = values.size
    override fun get(index: Int): T {
      reads.gets++
      return values[index]
    }
    override fun equals(other: Any?): Boolean {
      reads.equalsCalls++
      return super.equals(other)
    }
  }

  private class Catalog(
    val cards: List<IconPackCardData>,
    val atlas: IconPackCardData,
    val zephyr: IconPackCardData,
    val catalogReads: Reads,
    val mappingReads: Reads,
  ) {
    fun reset() { catalogReads.reset(); mappingReads.reset() }
  }

  private fun catalog(mappingCount: Int): Catalog {
    val catalogReads = Reads()
    val mappingReads = Reads()
    fun card(id: String, name: String, count: Int, matches: Int): IconPackCardData {
      val values = List(count) { index ->
        val packageName = "org.keihome.mapping.app$index"
        IconPackEntry(key = "icon_$index",
          component = IconPackComponent(packageName, "$packageName.Main"))
      }
      val entries = CountingList(values, mappingReads)
      val descriptor = IconPackDescriptor(
        id = id, displayName = name, format = IconPackFormat.INSTALLED_APK,
        source = IconPackSource.Installed("org.keihome.$id", versionCode = 1, updatedAt = 1),
        entries = entries,
      )
      return IconPackCardData(descriptor, matches,
        previewEntries = CountingList(values.take(3), mappingReads))
    }
    val atlas = card("atlas", "Atlas", mappingCount, matches = 7)
    val zephyr = card("zephyr", "Zephyr", 1, matches = 0)
    return Catalog(CountingList(listOf(atlas, zephyr), catalogReads), atlas, zephyr,
      catalogReads, mappingReads)
  }
}
