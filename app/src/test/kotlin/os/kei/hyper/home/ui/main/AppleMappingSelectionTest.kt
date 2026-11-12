package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.apple.AppleAppMatch
import os.kei.hyper.home.iconpack.apple.AppleAppTarget
import os.kei.hyper.home.iconpack.apple.AppleAutoMatchPolicy
import os.kei.hyper.home.iconpack.apple.AppleIconOptions
import os.kei.hyper.home.iconpack.apple.AppleMappingTransfer
import os.kei.hyper.home.iconpack.apple.AppleMatchAttempt
import os.kei.hyper.home.iconpack.apple.AppleMatchAttemptStatus
import os.kei.hyper.home.iconpack.apple.ApplePlatform

class AppleMappingSelectionTest {
  private val now = 10_000L
  private val mapped = row("mapped", app = AppleAppMatch("123456", "App", "Publisher", country = "jp"))
  private val upstream = row("upstream", app = AppleAppMatch("456789", "Default", "Publisher"), custom = false)
  private val excluded = row("excluded")
  private val unmatched = row("unmatched", custom = false)
  private val coolingDown = row("cooldown", custom = false).copy(
    attempt = AppleMatchAttempt(AppleMatchAttemptStatus.NO_MATCH, now - 100, now + 100, "us"))

  @Test fun mixedSelectionSeparatesExportableChoicesFromEligibleUnmatchedApps() {
    val rows = listOf(mapped, upstream, excluded, unmatched, coolingDown)
    val selection = appleMappingSelection(rows, rows.map { it.target.packageName }, true, now)
    assertEquals(setOf(mapped, upstream, excluded, unmatched).packages(), selection.selectable)
    assertEquals(selection.selectable, selection.selected)
    assertEquals(setOf(unmatched).packages(), selection.match)
    assertEquals(listOf(mapped, upstream, excluded), selection.export)
  }

  @Test fun matchedGalleryNeverStartsAutoMatchButCanExportUpstreamArtwork() {
    val selection = appleMappingSelection(listOf(upstream, unmatched), listOf("test.upstream", "test.unmatched"),
      allowMatch = false, now = now)
    assertEquals(setOf("test.upstream"), selection.selected)
    assertEquals(listOf(upstream), selection.export)
    assertTrue(selection.match.isEmpty())
    assertFalse("Selection must not manufacture a custom mapping", selection.export.single().custom)
  }

  @Test fun exportsAndManualExclusionsRemainSelectableDuringRetryCooldown() {
    val rows = listOf(mapped, excluded, coolingDown).map { it.copy(attempt = coolingDown.attempt) }
    val selection = appleMappingSelection(rows, rows.map { it.target.packageName }, true, now)
    assertEquals(setOf(mapped, excluded).packages(), selection.selected)
    assertEquals(setOf(mapped, excluded).packages(), selection.export.toSet().packages())
    assertTrue(selection.match.isEmpty())
  }

  @Test fun rowBecomesMatchEligibleAtItsRetryBoundary() {
    assertTrue(appleMappingSelection(listOf(coolingDown), listOf("test.cooldown"), true, now).selected.isEmpty())
    assertEquals(setOf("test.cooldown"),
      appleMappingSelection(listOf(coolingDown), listOf("test.cooldown"), true, now + 100).match)
  }

  @Test fun batchLimitCountsOnlyMatchableRowsAndFollowsVisibleOrder() {
    val candidates = (0..AppleAutoMatchPolicy.BATCH_SIZE + 2).map { row("candidate$it", custom = false) }
    val rows = listOf(mapped, excluded) + candidates.reversed()
    val selection = appleMappingSelection(rows, rows.map { it.target.packageName }.reversed(), true, now)
    assertEquals(candidates.reversed().take(AppleAutoMatchPolicy.BATCH_SIZE).map { it.target.packageName },
      selection.match.toList())
    assertEquals(listOf(mapped, excluded), selection.export)
  }

  @Test fun changingVisibleScopeOrEligibilityDropsHiddenAndStaleSelections() {
    val requested = listOf("test.mapped", "test.unmatched", "test.cooldown", "test.mapped", "test.hidden")
    val selection = appleMappingSelection(listOf(mapped, coolingDown), requested, true, now)
    assertEquals(listOf("test.mapped"), retainedAppleSelection(requested, selection.selectable))
    assertEquals(setOf("test.mapped"), selection.selected)
    assertTrue(retainedAppleSelection(requested, emptySet()).isEmpty())
  }

  @Test fun exportRoundTripContainsOnlyChosenPlatformAndPreservesPerAppStorefrontsAndExclusions() {
    val options = AppleIconOptions(platform = ApplePlatform.IPADOS, country = "cn")
    val mappings = AppleMappingTransfer.decode(appleSelectedMappingDocument(listOf(mapped, upstream, excluded), options))
    assertEquals(setOf(mapped, upstream, excluded).packages(), mappings.map { it.packageName }.toSet())
    assertTrue(mappings.all { it.platform == ApplePlatform.IPADOS })
    assertEquals("jp", mappings.single { it.packageName == "test.mapped" }.country)
    assertEquals("cn", mappings.single { it.packageName == "test.upstream" }.country)
    assertNull(mappings.single { it.packageName == "test.excluded" }.app)
    assertEquals("456789", mappings.single { it.packageName == "test.upstream" }.app?.id)
  }

  @Test fun duplicateVisibleRowsDoNotDuplicateActionsOrTransferredMappings() {
    val selection = appleMappingSelection(listOf(mapped, mapped), listOf("test.mapped", "test.mapped"), true, now)
    assertEquals(setOf("test.mapped"), selection.selected)
    assertEquals(listOf(mapped), selection.export)
    assertEquals(1, AppleMappingTransfer.decode(appleSelectedMappingDocument(listOf(mapped, mapped), AppleIconOptions())).size)
  }

  @Test fun emptyAndUnresolvedSelectionsCannotProduceAnExportDocument() {
    assertThrows(IllegalArgumentException::class.java) {
      appleSelectedMappingDocument(emptyList(), AppleIconOptions())
    }
    assertThrows(IllegalArgumentException::class.java) {
      appleSelectedMappingDocument(listOf(unmatched), AppleIconOptions())
    }
  }

  private fun row(name: String, app: AppleAppMatch? = null, custom: Boolean = true) =
    AppleMappingRow(AppleAppTarget("test.$name", name, false), app, custom)

  private fun Set<AppleMappingRow>.packages() = map { it.target.packageName }.toSet()
}
