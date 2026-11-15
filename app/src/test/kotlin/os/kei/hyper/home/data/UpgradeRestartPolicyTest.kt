package os.kei.hyper.home.data

import org.junit.Assert.*
import org.junit.Test

class UpgradeRestartPolicyTest {
  private val versions = listOf("1.10.0", "1.11.0", "1.12.0", "1.13.0")
  private val native = listOf("1.11.0", "1.13.0")
  private fun observe(state: ReleaseNotesState, version: String = "1.11.0", updated: Boolean = true) =
    ReleaseNotesPolicy.observe(state, version, updated, versions, native)

  @Test fun upgradeRetainsReminderAfterReadingNotesAndAcrossBuilds() {
    val upgrade = observe(ReleaseNotesState("1.10.0", "1.10.0"))
    assertEquals("1.11.0", upgrade.restartReminderVersion)
    val read = ReleaseNotesPolicy.acknowledge(upgrade)
    assertEquals("", read.pendingVersion)
    assertEquals("1.11.0", read.restartReminderVersion)
    assertEquals(read, observe(read, "1.11.0+g7654321.b9999"))
  }

  @Test fun confirmationIsIndependentAndPersistsAcrossLaterBuilds() {
    val read = ReleaseNotesPolicy.acknowledge(observe(ReleaseNotesState("1.10.0", "1.10.0")))
    val confirmed = ReleaseNotesPolicy.acknowledgeRestart(read, "1.11.0")
    assertEquals("", confirmed.restartReminderVersion)
    assertEquals(confirmed, observe(confirmed))
    assertEquals("", observe(confirmed, "1.12.0").restartReminderVersion)
  }

  @Test fun futureNativeReleaseCanRequestItsOwnReminder() {
    val confirmed = ReleaseNotesPolicy.acknowledgeRestart(
      observe(ReleaseNotesState("1.10.0", "1.10.0")), "1.11.0")
    assertEquals("1.13.0", observe(confirmed, "1.13.0").restartReminderVersion)
  }

  @Test fun skippedNativeReleasesUseNewestApplicableReminder() {
    assertEquals("1.13.0", observe(ReleaseNotesState("1.10.0", "1.10.0"), "1.13.0").restartReminderVersion)
  }

  @Test fun ordinaryUpdatePreservesAnUnconfirmedReminder() {
    val pending = observe(ReleaseNotesState("1.10.0", "1.10.0"))
    assertEquals("1.11.0", observe(pending, "1.12.0").restartReminderVersion)
  }

  @Test fun freshInstallationUsesActivationGuideWithoutUpgradeReminder() {
    val fresh = observe(ReleaseNotesState(), updated = false)
    assertEquals("", fresh.restartReminderVersion)
    assertEquals("", observe(fresh).restartReminderVersion)
    assertEquals("1.13.0", observe(fresh, "1.13.0").restartReminderVersion)
  }

  @Test fun legacyUpdateStillGetsTheNotice() {
    assertEquals("1.11.0", observe(ReleaseNotesState()).restartReminderVersion)
  }

  @Test fun staleCallbackCannotAcknowledgeAnotherRelease() {
    val pending = observe(ReleaseNotesState("1.10.0", "1.10.0"), "1.13.0")
    assertEquals(pending, ReleaseNotesPolicy.acknowledgeRestart(pending, "1.11.0"))
  }

  @Test fun backupIntoFreshInstallAndDowngradeDoNotShowFutureNotice() {
    val pending = observe(ReleaseNotesState("1.10.0", "1.10.0"))
    assertEquals("", observe(pending, updated = false).restartReminderVersion)
    assertEquals("", observe(pending, "1.10.0").restartReminderVersion)
  }
}
