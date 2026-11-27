package os.kei.hyper.home.ui.main

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.MainActivity

@RunWith(AndroidJUnit4::class)
class InspirationOverviewDeliveryDeviceTest {
  @Test(timeout = 360_000) fun overviewCanPublishWithoutFirstOpeningTheCatalog() {
    val args = InstrumentationRegistry.getArguments()
    Assume.assumeTrue(
      "Opt-in live device delivery test; pass -e keihome.liveDelivery=true to run",
      args.getString("keihome.liveDelivery") == "true",
    )
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var model: HomeViewModel
      scenario.onActivity { activity ->
        model = ViewModelProvider(activity,
          ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
        )[HomeViewModel::class.java]
      }
      fun diagnostic() = JSONObject().apply {
        put("versionCode", BuildConfig.VERSION_CODE)
        put("connected", model.framework.value.connected)
        put("themesInScope", model.framework.value.snapshot?.themesInScope)
        put("reportedVersion", model.runtime.value.report?.moduleVersion)
        put("preparing", model.iconPacks.value.preparing)
        put("nativeImporting", model.iconPacks.value.nativeImporting)
        put("loadedBuiltins", model.iconPacks.value.packs.count { it.pack.builtinKey != null })
      }
      val deadline = SystemClock.elapsedRealtime() + 30_000
      while (SystemClock.elapsedRealtime() < deadline) {
        if (model.framework.value.connected && model.framework.value.snapshot?.themesInScope == true &&
          model.runtime.value.report?.moduleVersion == BuildConfig.VERSION_CODE.toLong()) break
        SystemClock.sleep(100)
      }
      File(context.filesDir, "qa-inspiration-readiness.json").writeText(diagnostic().toString())
      assertTrue(diagnostic().toString(), model.framework.value.connected)
      assertEquals(diagnostic().toString(), true, model.framework.value.snapshot?.themesInScope)
      assertEquals(diagnostic().toString(), BuildConfig.VERSION_CODE.toLong(), model.runtime.value.report?.moduleVersion)
      assertFalse(diagnostic().toString(), model.iconPacks.value.preparing)
      assertNull(diagnostic().toString(), model.iconPacks.value.nativeImporting)
      assertTrue("Automatic apply preference is part of this test's existing device baseline",
        model.preferences.state.value.autoApplyIconPack)
      val previous = model.iconPacks.value.nativeResult?.token
      scenario.onActivity { model.applyColorOs(inspiration = true) }
      val submitted = SystemClock.elapsedRealtime()
      while (SystemClock.elapsedRealtime() - submitted < 300_000) {
        val state = model.iconPacks.value
        if (!state.preparing && state.nativeImporting == null && state.nativeResult?.token != previous) break
        if (!state.preparing && state.nativeImporting == null && state.error != null) break
        SystemClock.sleep(200)
      }
      val state = model.iconPacks.value
      val result = state.nativeResult
      File(context.filesDir, "qa-inspiration-delivery.json").writeText(diagnostic().apply {
        put("error", state.error)
        put("result", result?.encode())
        put("previousToken", previous)
      }.toString())
      assertNotEquals(state.error.orEmpty(), previous, result?.token)
      assertEquals(result?.message ?: state.error.orEmpty(), true, result?.imported)
      assertEquals(result?.message, true, result?.convertedReady)
    }
  }
}
