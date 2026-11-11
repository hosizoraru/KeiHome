package os.kei.hyper.home.data

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesPolicyTest {
  private val published = listOf("1.6.0", "1.5.0", "1.4.0")
  private fun observe(previous: ReleaseNotesState = ReleaseNotesState(),
    version: String = "1.7.0+g1234567.b0001", updated: Boolean = true, notes: List<String> = published) =
    ReleaseNotesPolicy.observe(previous, version, updated, notes)

  @Test fun firstInstallationPrimesHistoryWithoutPresentingAnything() {
    val fresh = observe(updated = false)
    assertEquals("1.6.0", fresh.readThrough)
    assertEquals("1.7.0", fresh.observedVersion)
    assertEquals("", fresh.pendingVersion)
    assertEquals(fresh, observe(fresh))
  }

  @Test fun backupRestoredIntoFreshInstallDoesNotPretendToBeAnUpgrade() {
    val restored = observe(ReleaseNotesState("1.4.0", "1.4.0", listOf("1.5.0"), "1.5.0"), updated = false)
    assertEquals("1.6.0", restored.readThrough)
    assertEquals("", restored.pendingVersion)
  }

  @Test fun legacyUpgradeWithoutPreviousTrackingPresentsHistoryOnce() {
    val upgraded = observe()
    assertEquals(published, upgraded.pendingVersions)
    assertEquals("1.7.0", upgraded.pendingVersion)
    val closed = ReleaseNotesPolicy.acknowledge(upgraded)
    assertEquals("1.6.0", closed.readThrough)
    assertEquals("", closed.pendingVersion)
    assertEquals(closed, observe(closed))
  }

  @Test fun skippedReleasesRemainSeparateWithoutInventingUnwrittenNotes() {
    val upgraded = observe(ReleaseNotesState("1.4.0", "1.4.0"))
    assertEquals(listOf("1.6.0", "1.5.0"), upgraded.pendingVersions)
    assertEquals("1.7.0", upgraded.pendingVersion)
  }

  @Test fun processDeathBeforeAcknowledgmentPreservesPendingPresentation() {
    val upgraded = observe(ReleaseNotesState("1.4.0", "1.4.0"))
    assertEquals(upgraded, observe(upgraded))
  }

  @Test fun samePrefixWithDifferentGitHashAndBuildNumberNeverRepeatsThePage() {
    val closed = ReleaseNotesPolicy.acknowledge(observe())
    assertEquals(closed, observe(closed, version = "1.7.0+g7654321.b9999"))
  }

  @Test fun newNotesInSamePrefixDoNotOverrideThePrefixOnlyRule() {
    val previous = ReleaseNotesPolicy.acknowledge(observe())
    assertEquals("", observe(previous, notes = listOf("1.7.0") + published).pendingVersion)
  }

  @Test fun prefixChangePresentsPageEvenWhenItsNotesAreNotWrittenYet() {
    val upgraded = observe(ReleaseNotesState("1.6.0", "1.6.0"))
    assertEquals("1.7.0", upgraded.pendingVersion)
    assertTrue(upgraded.pendingVersions.isEmpty())
    assertEquals("", observe(ReleaseNotesPolicy.acknowledge(upgraded)).pendingVersion)
  }

  @Test fun downgradeStillUsesChangedPrefixButNeverShowsFutureNotes() {
    val downgraded = observe(ReleaseNotesState("1.7.0", "1.6.0"), version = "1.5.0")
    assertEquals("1.5.0", downgraded.pendingVersion)
    assertTrue(downgraded.pendingVersions.isEmpty())
    assertEquals("1.6.0", downgraded.readThrough)
  }

  @Test fun futureCatalogEntriesNeverAppearInOlderApp() {
    assertEquals(listOf("1.5.0", "1.4.0"), observe(version = "1.5.0").pendingVersions)
  }

  @Test fun unknownDuplicateAndOutOfOrderMetadataCannotInventVersions() {
    assertEquals(published, observe(notes = listOf("broken", "1.4.0", "1.6.0", "1.5.0", "1.6.0")).pendingVersions)
  }

  @Test fun releaseOrderingUsesNumbersRatherThanAlphabeticalStrings() {
    assertTrue(ReleaseVersion.parse("1.10.0")!! > ReleaseVersion.parse("1.9.0")!!)
    assertEquals(ReleaseVersion(1, 7, 0), ReleaseVersion.parse("1.7.0+g1234567.b0999"))
    assertNull(ReleaseVersion.parse("-1.0.0"))
    assertNull(ReleaseVersion.parse("1.0"))
    assertNull(ReleaseVersion.parse("1.0.no"))
  }
}
