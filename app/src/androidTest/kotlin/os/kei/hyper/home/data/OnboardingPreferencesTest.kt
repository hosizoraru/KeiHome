package os.kei.hyper.home.data

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OnboardingPreferencesTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun isolated(block: (String) -> Unit) {
    val id = "onboarding_test_${UUID.randomUUID()}"
    try { block(id) } finally {
      AppLocalStorage.open(context, id).close()
      MMKV.removeStorage(id)
    }
  }

  @Test fun freshProgressResumesAndCompletionSurvivesAnotherInstance() = isolated { id ->
    val before = AppPreferences.get(context).state.value
    val fresh = OnboardingPreferences(context, id, existingConfiguration = false)
    assertEquals(0, fresh.state.value)
    fresh.visit(3)
    assertEquals(3, OnboardingPreferences(context, id, existingConfiguration = true).state.value)
    fresh.complete()
    fresh.visit(2)
    assertEquals(OnboardingPreferences.COMPLETE,
      OnboardingPreferences(context, id, existingConfiguration = false).state.value)
    assertEquals(before, AppPreferences.get(context).state.value)
  }

  @Test fun bootstrapDistinguishesFreshAndExistingMmkvOrLegacyXml() {
    val root = File(context.cacheDir, "onboarding_bootstrap_${UUID.randomUUID()}")
    val probe = object : ContextWrapper(context) {
      override fun getFilesDir() = File(root, "files")
      override fun getApplicationInfo() = ApplicationInfo(context.applicationInfo).apply { dataDir = root.path }
    }
    try {
      isolated { id -> assertEquals(0, OnboardingPreferences(probe, id).state.value) }
      val mmkv = File(probe.filesDir, "mmkv/app_display_v1")
      mmkv.parentFile!!.mkdirs()
      mmkv.writeText("existing")
      isolated { id -> assertEquals(OnboardingPreferences.COMPLETE, OnboardingPreferences(probe, id).state.value) }
      mmkv.delete()
      val xml = File(root, "shared_prefs/app_display.xml")
      xml.parentFile!!.mkdirs()
      xml.writeText("<map/>")
      isolated { id -> assertEquals(OnboardingPreferences.COMPLETE, OnboardingPreferences(probe, id).state.value) }
    } finally { root.deleteRecursively() }
  }

  @Test fun malformedStoredProgressReturnsToWelcomeAndRejectsInvalidVisits() = isolated { id ->
    AppLocalStorage.open(context, id).encode("progress_v1", Int.MAX_VALUE)
    val store = OnboardingPreferences(context, id, existingConfiguration = true)
    assertEquals(0, store.state.value)
    assertThrows(IllegalArgumentException::class.java) { store.visit(-1) }
    assertThrows(IllegalArgumentException::class.java) { store.visit(OnboardingPreferences.PAGE_COUNT) }
  }
}
