package os.kei.hyper.home.data

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesReadStateTest {
  private val versions = listOf("1.9.0", "1.10.0", "1.11.0")
  private fun upgrade() = ReleaseNotesPolicy.observe(
    ReleaseNotesState(observedVersion = "1.9.0", readThrough = "1.9.0"),
    "1.11.0+qa", true, versions, restartVersions = listOf("1.11.0"))

  @Test fun freshInstallationHasOnlyCurrentNotesUnreadWithoutAnUpgradeOrRestartPrompt() {
    val state = ReleaseNotesPolicy.observe(ReleaseNotesState(), "1.11.0", false, versions,
      restartVersions = listOf("1.11.0"))
    assertEquals(listOf("1.11.0"), state.unreadVersions)
    assertTrue(state.pendingVersions.isEmpty())
    assertEquals("", state.pendingVersion)
    assertEquals("", state.restartReminderVersion)
    assertEquals(state, ReleaseNotesPolicy.observe(state, "1.11.0+another", false, versions,
      restartVersions = listOf("1.11.0")))
  }

  @Test fun skippedUpgradeKeepsBothUnreleasedAndCurrentNotesUnread() {
    assertEquals(listOf("1.11.0", "1.10.0"), upgrade().unreadVersions)
    assertEquals(listOf("1.9.0"), upgrade().readVersions)
  }

  @Test fun readingNewestDoesNotLoseOlderUnreadNotesOrReintroduceReadVersions() {
    val read = ReleaseNotesPolicy.markRead(upgrade(), setOf("1.11.0"))
    assertEquals(listOf("1.10.0"), read.unreadVersions)
    val repeated = ReleaseNotesPolicy.observe(read, "1.11.0+other", true, versions)
    assertEquals(read.unreadVersions, repeated.unreadVersions)
    val next = ReleaseNotesPolicy.observe(repeated, "1.12.0", true, versions + "1.12.0")
    assertEquals(listOf("1.12.0", "1.10.0"), next.unreadVersions)
  }

  @Test fun noteAcknowledgementDoesNotAcknowledgeTheIndependentRestartReminder() {
    val read = ReleaseNotesPolicy.markRead(upgrade(), versions.toSet())
    assertTrue(read.unreadVersions.isEmpty())
    assertEquals("1.11.0", read.pendingVersion)
    val continued = ReleaseNotesPolicy.acknowledge(read)
    assertEquals("", continued.pendingVersion)
    assertEquals("1.11.0", continued.restartReminderVersion)
    assertEquals("", ReleaseNotesPolicy.acknowledgeRestart(continued, "1.11.0").restartReminderVersion)
  }

  @Test fun unknownLegacyHistoryUsesTheCurrentDeliveryGroup() {
    val state = ReleaseNotesPolicy.observe(ReleaseNotesState(), "1.11.0", true, versions,
      initialUnreadVersions = listOf("1.11.0", "1.10.0"))
    assertEquals(listOf("1.11.0", "1.10.0"), state.unreadVersions)
    assertEquals(state.unreadVersions, state.pendingVersions)
    assertTrue("1.9.0" in state.readVersions)
  }

  @Test fun restoringBackupIntoFreshInstallResetsOnlyTheReadingBaseline() {
    val state = ReleaseNotesPolicy.observe(upgrade(), "1.11.0", false, versions,
      restartVersions = listOf("1.11.0"), newInstallation = true)
    assertEquals(listOf("1.11.0"), state.unreadVersions)
    assertTrue(state.pendingVersions.isEmpty())
    assertEquals("", state.restartReminderVersion)
  }

  @Test fun aTemporaryDowngradeDoesNotEraseUnreadFutureHistory() {
    val older = ReleaseNotesPolicy.observe(upgrade(), "1.9.0", true, versions)
    assertEquals(listOf("1.11.0", "1.10.0"), older.unreadVersions)
    assertEquals(upgrade().unreadVersions,
      ReleaseNotesPolicy.observe(older, "1.11.0", true, versions).unreadVersions)
  }

  @Test fun closingUpgradeNoticeDoesNotMarkCollapsedUnreadHistoryAsRead() {
    val state = ReleaseNotesPolicy.acknowledge(ReleaseNotesPolicy.markRead(upgrade(), setOf("1.11.0")))
    assertEquals("", state.pendingVersion)
    assertTrue(state.pendingVersions.isEmpty())
    assertEquals(listOf("1.10.0"), state.unreadVersions)
    assertEquals(state.unreadVersions,
      ReleaseNotesPolicy.observe(state, "1.11.0+next", true, versions).unreadVersions)
  }
}
