package os.kei.hyper.home.data

import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesPreferencesTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

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
}
