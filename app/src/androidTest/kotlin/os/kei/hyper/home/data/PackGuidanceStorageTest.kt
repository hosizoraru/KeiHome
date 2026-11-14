package os.kei.hyper.home.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackGuidanceStorageTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val storageId = "pack_guidance_test_${UUID.randomUUID()}"
  private val storage by lazy { AppLocalStorage.open(context, storageId) }

  @After fun clean() {
    storage.clearAll()
    storage.sync()
  }

  @Test fun pageRevisionIsDurableAcrossNewPreferenceInstancesAndIgnoresLegacyCompactChoice() {
    val revision = PackGuidancePreferences.CURRENT_REVISION
    storage.encode("compact_pack_hints", false)
    PackGuidancePreferences(context, storageId).markSeen(PackGuidancePage.APPLE, revision - 1)
    val reopened = PackGuidancePreferences(context, storageId)
    assertFalse(reopened.hasSeen(PackGuidancePage.APPLE))
    reopened.markSeen(PackGuidancePage.APPLE)
    assertTrue(PackGuidancePreferences(context, storageId).hasSeen(PackGuidancePage.APPLE))
    assertFalse(PackGuidancePreferences(context, storageId).hasSeen(PackGuidancePage.PERFECT))
    assertFalse(storage.decodeBool("compact_pack_hints", true))
    storage.encode("compact_pack_hints", true)
    assertTrue(PackGuidancePreferences(context, storageId).hasSeen(PackGuidancePage.APPLE))
    assertFalse(PackGuidancePreferences(context, storageId).hasSeen(PackGuidancePage.IMPORTED))
  }
}
