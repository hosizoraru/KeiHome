package os.kei.hyper.home.status

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticScopeDeviceTest {
  @Test fun inspectsPackagedDeclarationAndOptionallyPreparesThemesAuthorization() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (FrameworkConnection.service.value == null && SystemClock.elapsedRealtime() < deadline)
      SystemClock.sleep(100)
    val service = requireNotNull(FrameworkConnection.service.value) { "Framework service did not connect" }
    val client = LibxposedStaticScopeClient(context, service)
    val before = client.readPlan()
    assertEquals(listOf(
      "system", "com.miui.home", "com.android.thememanager", "com.android.systemui",
      "com.android.intentresolver", "com.miui.securitycenter", "com.android.settings",
    ), before.declared)
    assertTrue(before.unconfirmed.isEmpty())
    val report = JSONObject().put("declared", JSONArray(before.declared))
      .put("eligible", JSONArray(before.eligible)).put("grantedBefore", JSONArray(service.scope))
    File(context.cacheDir, "static-scope-device.json").writeText(report.toString(2))
    // Opt-in QA operation. Never revoke Launcher, SystemUI or system-server scope.
    if (InstrumentationRegistry.getArguments().getString("removeThemesScope") == "true") {
      val themes = "com.android.thememanager"
      assertTrue("Themes scope must already be granted before this reversible test", themes in service.scope)
      service.removeScope(listOf(themes))
      val after = service.scope.toSet()
      report.put("grantedAfter", JSONArray(after))
      File(context.cacheDir, "static-scope-device.json").writeText(report.toString(2))
      assertFalse("Framework did not remove Themes scope", themes in after)
      assertEquals(before.granted - themes, after)
    }
  }
}
