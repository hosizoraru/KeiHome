package os.kei.hyper.home.data

import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesPreferencesTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun nativeUpgradeNoticePersistsIndependentlyFromReadingNotes() {
    val id = "release_notes_test_${UUID.randomUUID()}"
    try {
      ReleaseNotesPreferences(context, id, "1.10.0", false)
      val upgrade = ReleaseNotesPreferences(context, id, "1.11.0", true)
      assertEquals("1.11.0", upgrade.state.value.restartReminderVersion)
      upgrade.acknowledge()
      val reopened = ReleaseNotesPreferences(context, id, "1.11.0+g1234567.b9999", true)
      assertEquals("", reopened.state.value.pendingVersion)
      assertEquals("1.11.0", reopened.state.value.restartReminderVersion)
      reopened.acknowledgeRestart("1.11.0")
      assertEquals("", ReleaseNotesPreferences(context, id, "1.11.0", true).state.value.restartReminderVersion)
    } finally {
      AppLocalStorage.open(context, id).close()
      MMKV.removeStorage(id)
    }
  }

  @Test fun pendingUpgradeAndAcknowledgmentSurviveReopeningWithoutChangingAppSettings() {
    val id = "release_notes_test_${UUID.randomUUID()}"
    val before = AppPreferences.get(context).state.value
    fun store(version: String, updated: Boolean) = ReleaseNotesPreferences(
      context, id, version, updated, listOf("1.6.0", "1.5.0", "1.4.0"),
    )
    try {
      assertTrue(store("1.4.0", false).state.value.pendingVersions.isEmpty())
      val updated = store("1.7.0", true)
      assertEquals(listOf("1.6.0", "1.5.0"), updated.state.value.pendingVersions)
      assertEquals(updated.state.value, store("1.7.0", true).state.value)
      updated.acknowledge()
      assertEquals("", store("1.7.0", true).state.value.pendingVersion)
      assertEquals("", store("1.7.0+g7654321.b9999", true).state.value.pendingVersion)
      assertEquals(before, AppPreferences.get(context).state.value)
    } finally {
      AppLocalStorage.open(context, id).close()
      MMKV.removeStorage(id)
    }
  }

  @Test fun unfinishedGuideSuppressesOnlyLegacyBootstrapButPreservesKnownPrefixChanges() {
    val id = "release_notes_test_${UUID.randomUUID()}"
    try {
      fun store(version: String, updated: Boolean) = ReleaseNotesPreferences(
        context, id, version, updated, allowLegacyUpgrade = false,
      )
      assertEquals("", store("1.6.0", true).state.value.pendingVersion)
      assertEquals("1.7.0", store("1.7.0", true).state.value.pendingVersion)
      assertEquals("1.7.0", store("1.7.0+g1234567.b9999", true).state.value.pendingVersion)
    } finally {
      AppLocalStorage.open(context, id).close()
      MMKV.removeStorage(id)
    }
  }

  @Test fun release19UpgradeAcknowledgesOnceWhileFreshInstallSkipsChangelog() {
    val storageId = "release_notes_test_${UUID.randomUUID()}"
    val freshId = "release_notes_test_${UUID.randomUUID()}"
    val before = AppPreferences.get(context).state.value
    try {
      ReleaseNotesPreferences(context, storageId, "1.8.0", updatedInstallation = false)
      val upgrade = ReleaseNotesPreferences(context, storageId, "1.9.0+g1234567.b0001", updatedInstallation = true)
      assertEquals(listOf("1.9.0"), upgrade.state.value.pendingVersions)
      assertEquals("1.9.0", ReleaseNotesPreferences(context, storageId, "1.9.0+g1234567.b0002", true).state.value.pendingVersion)
      upgrade.acknowledge()
      assertEquals("", ReleaseNotesPreferences(context, storageId, "1.9.0+g7654321.b9999", true).state.value.pendingVersion)
      val fresh = ReleaseNotesPreferences(context, freshId, "1.9.0", updatedInstallation = false,
        allowLegacyUpgrade = false).state.value
      assertEquals("1.9.0", fresh.readThrough)
      assertEquals("", fresh.pendingVersion)
      assertEquals(before, AppPreferences.get(context).state.value)
    } finally {
      for (id in listOf(storageId, freshId)) {
        AppLocalStorage.open(context, id).close()
        MMKV.removeStorage(id)
      }
    }
  }
}
