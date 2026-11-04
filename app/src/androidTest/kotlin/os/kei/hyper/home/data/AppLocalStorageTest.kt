package os.kei.hyper.home.data

import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.status.AppLogStore

class AppLocalStorageTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun logPreferenceMigrationRetainsLaterMmkvChoiceAfterReopen() {
    val id = "logs_probe_${UUID.randomUUID()}"
    val legacy = context.getSharedPreferences(id, 0)
    legacy.edit().putString("level", "WARN").commit()
    try {
      val first = AppLocalStorage.migrate(context, id, id)
      assertEquals("WARN", first.getString("level", null))
      assertTrue(first.encode("level", "DEBUG"))
      first.sync()
      first.close()
      legacy.edit().putString("level", "ERROR").commit()
      val reopened = AppLocalStorage.migrate(context, id, id)
      assertEquals("DEBUG", reopened.getString("level", null))
      assertEquals("ERROR", legacy.getString("level", null))
    } finally {
      AppLocalStorage.open(context, id).close()
      MMKV.removeStorage(id)
      context.deleteSharedPreferences(id)
    }
  }

  @Test fun existingLogLevelSurvivesTheExplicitUpgradeCheck() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("mmkv.verifyImportUpgrade") == "true")
    val old = context.getSharedPreferences("app_logs", 0).getString("level", "INFO")
    assertEquals(old, AppLogStore.level(context).name)
  }
}
