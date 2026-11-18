package os.kei.hyper.home.xposed

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.NavigationBackdropChoice
import os.kei.hyper.home.data.NavigationBackdropPreferences
import os.kei.hyper.home.status.NavigationBackdropInbox
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NavigationBackdropDeviceTest {
  @Test fun remoteSetsRemainReadableWithoutTheModulesClassLoader() {
    var captured: Set<String>? = null
    lateinit var editor: android.content.SharedPreferences.Editor
    editor = java.lang.reflect.Proxy.newProxyInstance(
      android.content.SharedPreferences.Editor::class.java.classLoader,
      arrayOf(android.content.SharedPreferences.Editor::class.java),
    ) { _, method, arguments ->
      when (method.name) {
        "commit" -> true
        "putStringSet" -> {
          @Suppress("UNCHECKED_CAST")
          captured = arguments!![1] as Set<String>
          editor
        }
        else -> editor
      }
    } as android.content.SharedPreferences.Editor
    val preferences = java.lang.reflect.Proxy.newProxyInstance(
      android.content.SharedPreferences::class.java.classLoader,
      arrayOf(android.content.SharedPreferences::class.java),
    ) { _, _, _ -> editor } as android.content.SharedPreferences
    for (packages in listOf(emptySet(), setOf("one.app"), setOf("one.app", "two.app"))) {
      NavigationBackdropPreferences.write(preferences, NavigationBackdropChoice(packages = packages))
      assertEquals(packages, wireSet(captured!!))
    }
    assertThrows(android.os.BadParcelableException::class.java) { wireSet(emptySet()) }
  }

  private fun wireSet(packages: Set<String>): Set<String> {
    val source = Bundle().apply { putSerializable("put", hashMapOf("packages" to packages)) }
    val parcel = android.os.Parcel.obtain()
    return try {
      parcel.writeBundle(source)
      val bytes = parcel.marshall()
      parcel.unmarshall(bytes, 0, bytes.size)
      parcel.setDataPosition(0)
      val restored = parcel.readBundle(Bundle::class.java.classLoader)!!
      @Suppress("DEPRECATION", "UNCHECKED_CAST")
      (restored.getSerializable("put") as Map<String, Set<String>>).getValue("packages")
    } finally {
      parcel.recycle()
    }
  }

  @Test fun selectedPackagesCannotReceiveAnotherUidCompatibilityRules() {
    // The controlled OEM test needs am instrument --no-hidden-api-checks.
    org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments()
      .getString("navigationRuleDeliveryActive") == "true")
    val manager = Class.forName("com.miui.nbi.MiuiNBIManager")
      .getDeclaredMethod("getDefault").apply { isAccessible = true }.invoke(null)
    val method = manager.javaClass.getMethod("getSystemNBIRules", String::class.java)
      .apply { isAccessible = true }
    // These packages are selected for the controlled acceptance run. The caller
    // is KeiHome's UID, so it must receive the untouched OEM rules instead.
    assertNull(method.invoke(manager, "com.mi.health"))
    val result = method.invoke(manager, "tv.danmaku.bili") as Bundle
    assertEquals(0, JSONObject(result.getString("activityRules")!!)
      .getJSONObject("tv.danmaku.bili.MainActivityV2").getInt("mode"))
  }

  @Test fun copiedBundleRetainsNativeVersionGatesAndUnknownExtras() {
    val original = Bundle().apply {
      putBoolean("enable", false)
      putString("versionCode", "100,200")
      putInt("disableVersionCode", 99999)
      putString("future", "unchanged")
      putString("activityRules", """{"Main":{"mode":0,"dialogMode":0}}""")
    }
    val result = NavigationRuleOverlay.apply(original)!!
    assertNotSame(original, result)
    assertTrue(result.getBoolean("enable"))
    assertEquals("100,200", result.getString("versionCode"))
    assertEquals(99999, result.getInt("disableVersionCode"))
    assertEquals("unchanged", result.getString("future"))
    assertEquals(-1, JSONObject(result.getString("activityRules")!!).getJSONObject("Main").getInt("mode"))
    assertFalse(original.getBoolean("enable"))
    assertEquals(0, JSONObject(original.getString("activityRules")!!).getJSONObject("Main").getInt("mode"))
  }

  @Test fun malformedBundleIsReturnedByIdentityAndMissingRuleUsesNativeDefault() {
    val original = Bundle().apply { putString("activityRules", "{") }
    assertSame(original, NavigationRuleOverlay.apply(original))
    val fresh = NavigationRuleOverlay.apply(null)!!
    assertTrue(fresh.getBoolean("enable"))
    assertEquals("", fresh.getString("activityRules"))
    val future = Bundle().apply { putInt("activityRules", 42) }
    assertSame(future, NavigationRuleOverlay.apply(future))
  }

  @Test fun invalidRemoteChoicesAreRejectedAndEmptyPreferencesDefaultOff() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val name = "navigation_backdrop_test_${UUID.randomUUID()}"
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    try {
      assertFalse(NavigationBackdropPreferences.read(prefs).enabled)
      prefs.edit().putBoolean("enabled", true).putStringSet("packages", setOf("bad/package")).commit()
      assertThrows(IllegalArgumentException::class.java) { NavigationBackdropPreferences.read(prefs) }
      prefs.edit().putStringSet("packages", setOf("test.valid")).putInt("user", -1).commit()
      assertThrows(IllegalArgumentException::class.java) { NavigationBackdropPreferences.read(prefs) }
    } finally { context.deleteSharedPreferences(name) }
  }

  @Test fun staleSystemStatusCannotOverwriteCurrentRuntimeCapability() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val before = NavigationBackdropInbox.read(context)
    assertFalse(NavigationBackdropInbox.accept(context, Bundle().apply {
      putLong("version", BuildConfig.VERSION_CODE.toLong() - 1)
      putBoolean("available", true)
    }))
    assertFalse(NavigationBackdropInbox.accept(context, Bundle().apply {
      putLong("version", BuildConfig.VERSION_CODE.toLong() + 1)
      putBoolean("available", true)
    }))
    assertEquals(before, NavigationBackdropInbox.read(context))
  }
}
