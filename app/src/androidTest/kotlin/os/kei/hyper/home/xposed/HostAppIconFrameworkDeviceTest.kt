package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/** Read-only API checks: does not install hooks or change device configuration. */
class HostAppIconFrameworkDeviceTest {
  @Test fun packageManagerEntryRetainsExplicitApplicationIdentity() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val owner = Class.forName("android.app.ApplicationPackageManager", false, app.classLoader)
    val method = try {
      owner.getDeclaredMethod(
        "loadUnbadgedItemIcon", PackageItemInfo::class.java, ApplicationInfo::class.java)
    } catch (error: NoSuchMethodException) {
      val args = InstrumentationRegistry.getArguments()
      val verified = args.getString("verifiedRootProbe") ==
        "Landroid/app/ApplicationPackageManager;->loadUnbadgedItemIcon(Landroid/content/pm/PackageItemInfo;Landroid/content/pm/ApplicationInfo;)Landroid/graphics/drawable/Drawable;" &&
        args.getString("verifiedRootProbeFingerprint") == Build.FINGERPRINT
      // Main supplies these only after its read-only root app_process ABI probe.
      assertTrue("Ordinary instrumentation cannot see the non-SDK getter. " +
        "A verified root probe for this device fingerprint is required before skipping: $error", verified)
      Assume.assumeNoException("Root probe verified the native ABI; ordinary instrumentation " +
        "is hidden-API restricted. This does not validate the privileged module hook.", error)
      return
    }
    assertTrue(Modifier.isPublic(method.modifiers))
    assertFalse(Modifier.isStatic(method.modifiers))
    assertEquals(Drawable::class.java, method.returnType)
    assertEquals(listOf(PackageItemInfo::class.java, ApplicationInfo::class.java),
      method.parameterTypes.toList())
  }

  @Test fun mamlPackageItemEntryResolvesThroughEachHostClassLoader() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    for (pkg in listOf("com.miui.securitycenter", "com.android.settings", "com.android.intentresolver")) {
      val host = app.createPackageContext(pkg,
        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
      val helper = try {
        Class.forName("com.miui.maml.util.AppIconsHelper", false, host.classLoader)
      } catch (_: ClassNotFoundException) { null }
      // Security bundles MAML in the researched APK; other hosts may omit it.
      if (pkg == "com.miui.securitycenter") assertNotNull("Security MAML entry is missing", helper)
      if (helper == null) continue
      val method = helper.getDeclaredMethod("getIconDrawable",
        Context::class.java, PackageItemInfo::class.java, PackageManager::class.java)
      assertTrue(Modifier.isPublic(method.modifiers))
      assertTrue(Modifier.isStatic(method.modifiers))
      assertEquals(Drawable::class.java, method.returnType)
    }
  }

  @Test fun actualHostPublicAppIconGetterUsesExplicitOwnerAppInfo() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val ownerUser = Process.myUid() / 100000
    for (pkg in listOf("com.miui.securitycenter", "com.android.settings")) {
      val host = app.createPackageContext(pkg,
        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
      val pm = host.packageManager
      val getter = PackageManager::class.java.getMethod(
        "getApplicationIcon", ApplicationInfo::class.java)
      assertTrue(Modifier.isPublic(getter.modifiers))
      assertFalse(Modifier.isStatic(getter.modifiers))
      assertEquals(Drawable::class.java, getter.returnType)
      val info = pm.getApplicationInfo(app.packageName, PackageManager.ApplicationInfoFlags.of(0L))
      assertEquals(app.packageName, info.packageName)
      assertTrue(info.uid >= 0)
      assertEquals(Process.myUid(), info.uid)
      assertEquals(ownerUser, info.uid / 100000)
      assertTrue("$pkg getter did not return an app Drawable",
        getter.invoke(pm, info) is Drawable)
    }
  }
}
