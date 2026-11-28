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

  @Test fun upgradeUsesTheReleaseAuthorsExpansionSelection() {
    assertEquals(listOf("1.9.0", "1.8.0", "1.7.0", "1.6.0"),
      ReleaseNotesCatalog.initiallyExpandedVersions("1.9.0+g1234567.b0999", upgrade = true))
    assertEquals(listOf("1.8.0", "1.7.0", "1.6.0"),
      ReleaseNotesCatalog.initiallyExpandedVersions("1.8.0+g1234567.b0999", upgrade = true))
    assertEquals(listOf("1.7.0", "1.6.0"),
      ReleaseNotesCatalog.initiallyExpandedVersions("1.7.0+g1234567.b0999", upgrade = true))
  }

  @Test fun manualHistoryOpensOnlyTheCurrentRelease() {
    assertEquals(listOf("1.9.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.9.0", upgrade = false))
    assertEquals(listOf("1.8.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.8.0", upgrade = false))
    assertEquals(listOf("1.7.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.7.0", upgrade = false))
    assertEquals(listOf("1.6.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.6.0", upgrade = false))
  }

  @Test fun unconfiguredReleasesDoNotInheritAnotherReleasesExpansionSelection() {
    assertEquals(listOf("1.6.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.6.0", upgrade = true))
    assertEquals(listOf("1.11.0", "1.10.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.11.0", upgrade = true))
    assertEquals(listOf("1.11.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.11.0", upgrade = false))
    assertEquals(emptyList<String>(), ReleaseNotesCatalog.initiallyExpandedVersions("broken", upgrade = true))
  }

  @Test fun release18UpgradePresentsNewNotesOnceWithoutChangingThePrefixRule() {
    val releases = listOf("1.8.0", "1.7.0") + published
    val updated = observe(ReleaseNotesState("1.5.0", "1.5.0"),
      version = "1.8.0+g1234567.b0001", notes = releases)
    assertEquals("1.8.0", updated.pendingVersion)
    assertEquals(listOf("1.8.0", "1.7.0", "1.6.0"), updated.pendingVersions)
    val closed = ReleaseNotesPolicy.acknowledge(updated)
    assertEquals(closed, observe(closed, version = "1.8.0+g7654321.b9999", notes = releases))
    assertEquals("", observe(version = "1.8.0", updated = false, notes = releases).pendingVersion)
  }
  @Test fun release19From18ShowsOnlyTheNewUnreadVersionAndAcknowledgesItAcrossBuilds() {
    val releases = ReleaseNotesCatalog.releases.map { it.version }
    val upgraded = observe(ReleaseNotesState("1.8.0", "1.8.0"), "1.9.0+g1234567.b0001", notes = releases)
    assertEquals("1.9.0", upgraded.pendingVersion)
    assertEquals(listOf("1.9.0"), upgraded.pendingVersions)
    assertEquals(upgraded, observe(upgraded, "1.9.0+g1234567.b0002", notes = releases))
    val closed = ReleaseNotesPolicy.acknowledge(upgraded)
    assertEquals("1.9.0", closed.readThrough)
    assertEquals(closed, observe(closed, "1.9.0+g7654321.b9999", notes = releases))
  }

  @Test fun release19FreshInstallAndSkippedUpgradeKeepTheirDistinctStartupRules() {
    val releases = ReleaseNotesCatalog.releases.map { it.version }
    val fresh = observe(version = "1.9.0", updated = false, notes = releases)
    assertEquals("", fresh.pendingVersion)
    assertEquals("1.9.0", fresh.readThrough)
    assertEquals(fresh, observe(fresh, "1.9.0+g7654321.b9999", notes = releases))
    val skipped = observe(ReleaseNotesState("1.5.0", "1.5.0"), "1.9.0", notes = releases)
    assertEquals(listOf("1.9.0", "1.8.0", "1.7.0", "1.6.0"), skipped.pendingVersions)
  }

  @Test fun release110UpgradeFrom19ShowsOnlyNewNotesOnceAcrossBuilds() {
    val releases = ReleaseNotesCatalog.releases.map { it.version }
    val updated = observe(ReleaseNotesState("1.9.0", "1.9.0"),
      "1.10.0+g1234567.b0001", notes = releases)
    assertEquals("1.10.0", updated.pendingVersion)
    assertEquals(listOf("1.10.0"), updated.pendingVersions)
    assertEquals(updated, observe(updated, "1.10.0+g1234567.b0001", notes = releases))
    val closed = ReleaseNotesPolicy.acknowledge(updated)
    assertEquals("1.10.0", closed.readThrough)
    assertEquals(closed, observe(closed, "1.10.0+g7654321.b9999", notes = releases))
    assertEquals(listOf("1.10.0"),
      ReleaseNotesCatalog.initiallyExpandedVersions("1.10.0", upgrade = true))
    assertEquals(listOf("1.10.0"),
      ReleaseNotesCatalog.initiallyExpandedVersions("1.10.0", upgrade = false))
  }

  @Test fun release110FreshInstallAndSkippedUpgradeRetainTheirOwnRules() {
    val releases = ReleaseNotesCatalog.releases.map { it.version }
    val fresh = observe(version = "1.10.0", updated = false, notes = releases)
    assertEquals("", fresh.pendingVersion)
    assertEquals("1.10.0", fresh.readThrough)
    val skipped = observe(ReleaseNotesState("1.8.0", "1.8.0"), "1.10.0", notes = releases)
    assertEquals(listOf("1.10.0", "1.9.0"), skipped.pendingVersions)
  }

  @Test fun release112FreshInstallOnlyMarksCurrentNotesUnread() {
    val releases = ReleaseNotesCatalog.releases.map { it.version }
    val fresh = observe(version = "1.12.0+g1234567.b0600", updated = false, notes = releases)
    assertEquals("1.12.0", fresh.observedVersion)
    assertEquals("1.12.0", fresh.readThrough)
    assertTrue(fresh.pendingVersions.isEmpty())
    assertEquals(listOf("1.12.0"), fresh.unreadVersions)
    assertTrue(fresh.readVersions.containsAll(listOf("1.11.0", "1.10.0")))
    assertEquals("", fresh.restartReminderVersion)
  }

  @Test fun release112UpgradeIsUnreadUntilAcknowledgedAcrossBuilds() {
    val releases = ReleaseNotesCatalog.releases.map { it.version }
    val upgrade = observe(ReleaseNotesState("1.11.0", "1.11.0"),
      version = "1.12.0+gfirst.b0600", notes = releases)
    assertEquals(listOf("1.12.0"), upgrade.pendingVersions)
    assertEquals(listOf("1.12.0"), upgrade.unreadVersions)
    val nextBuild = observe(upgrade, version = "1.12.0+gsecond.b0601", notes = releases)
    assertEquals(upgrade, nextBuild)
    val dismissed = ReleaseNotesPolicy.acknowledge(nextBuild)
    assertEquals(listOf("1.12.0"), dismissed.unreadVersions)
    val read = ReleaseNotesPolicy.markRead(dismissed, setOf("1.12.0"))
    assertEquals("1.12.0", read.readThrough)
    assertTrue(read.pendingVersions.isEmpty())
    assertTrue(read.unreadVersions.isEmpty())
    assertTrue(observe(read, version = "1.12.0", notes = releases).pendingVersions.isEmpty())
    assertFalse(ReleaseNotesCatalog.releases.first { it.version == "1.12.0" }.recommendSystemRestart)
  }

  @Test fun release118ShowsCurrentNotesAndKeepsNativeRestartPendingAfterReading() {
    val releases = ReleaseNotesCatalog.releases
    val upgraded = ReleaseNotesPolicy.observe(ReleaseNotesState("1.17.0", "1.17.0"),
      "1.18.0+g1234567.b0930", true, releases.map { it.version },
      releases.filter { it.recommendSystemRestart }.map { it.version })
    assertEquals(listOf("1.18.0"), upgraded.pendingVersions)
    assertEquals(listOf("1.18.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.18.0", upgrade = true))
    assertTrue(releases.first { it.version == "1.18.0" }.recommendSystemRestart)
    val read = ReleaseNotesPolicy.markRead(ReleaseNotesPolicy.acknowledge(upgraded), setOf("1.18.0"))
    assertTrue(read.unreadVersions.isEmpty())
    assertEquals("1.18.0", read.restartReminderVersion)
  }

  @Test fun release117ShowsCurrentNotesWithoutRequiringANativeRestart() {
    val releases = ReleaseNotesCatalog.releases
    val upgraded = ReleaseNotesPolicy.observe(ReleaseNotesState("1.16.0", "1.16.0"),
      "1.17.0+g1234567.b0820", true, releases.map { it.version },
      releases.filter { it.recommendSystemRestart }.map { it.version })
    assertEquals(listOf("1.17.0"), upgraded.pendingVersions)
    assertEquals(listOf("1.17.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.17.0", upgrade = true))
    assertFalse(releases.first { it.version == "1.17.0" }.recommendSystemRestart)
    val read = ReleaseNotesPolicy.markRead(ReleaseNotesPolicy.acknowledge(upgraded), setOf("1.17.0"))
    assertTrue(read.unreadVersions.isEmpty())
    assertEquals("", read.restartReminderVersion)
  }

  @Test fun release116ShowsCurrentNotesAndKeepsNativeRestartPendingAfterReading() {
    val releases = ReleaseNotesCatalog.releases
    val upgraded = ReleaseNotesPolicy.observe(ReleaseNotesState("1.15.0", "1.15.0"),
      "1.16.0+g1234567.b0810", true, releases.map { it.version },
      releases.filter { it.recommendSystemRestart }.map { it.version })
    assertEquals(listOf("1.16.0"), upgraded.pendingVersions)
    assertEquals(listOf("1.16.0"), ReleaseNotesCatalog.initiallyExpandedVersions("1.16.0", upgrade = true))
    val read = ReleaseNotesPolicy.markRead(ReleaseNotesPolicy.acknowledge(upgraded), setOf("1.16.0"))
    assertTrue(read.unreadVersions.isEmpty())
    assertEquals("1.16.0", read.restartReminderVersion)
  }

  @Test fun release112SkippedUpgradeRetainsInterveningUnreadNotesAndNativeReminder() {
    val releases = ReleaseNotesCatalog.releases.map { it.version }
    val previous = ReleaseNotesState("1.9.0", "1.9.0")
    val upgraded = ReleaseNotesPolicy.observe(previous, "1.12.0", true, releases,
      ReleaseNotesCatalog.releases.filter { it.recommendSystemRestart }.map { it.version })
    assertEquals(listOf("1.12.0", "1.11.0", "1.10.0"), upgraded.pendingVersions)
    val acknowledged = ReleaseNotesPolicy.acknowledge(upgraded)
    assertTrue(acknowledged.pendingVersions.isEmpty())
    assertEquals(listOf("1.12.0", "1.11.0", "1.10.0"), acknowledged.unreadVersions)
    assertEquals("1.11.0", acknowledged.restartReminderVersion)
  }
}
