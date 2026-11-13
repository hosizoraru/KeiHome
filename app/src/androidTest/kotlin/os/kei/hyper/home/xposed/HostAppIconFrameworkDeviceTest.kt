package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
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
  @Suppress("UNUSED_PARAMETER")
  private class Signatures {
    fun framework(item: PackageItemInfo?, app: ApplicationInfo?): Drawable? = error("Signature only")
    fun maml(context: Context, item: PackageItemInfo?, pm: PackageManager): Drawable? = error("Signature only")
  }

  private fun frameworkArguments() = requireNotNull(HostPublicIconArguments.resolve(
    Signatures::class.java.getDeclaredMethod("framework", PackageItemInfo::class.java, ApplicationInfo::class.java)))

  @Test fun fixedArgumentsKeepFirstApplicationIdentityAndReadEachPositionOnce() {
    val first = ApplicationInfo().apply { packageName = "os.first.owner"; uid = 200123 }
    val explicit = ApplicationInfo().apply { packageName = "os.other.owner"; uid = 123 }
    val values = arrayOf(first, explicit)
    val reads = IntArray(2)
    var nativeCalls = 0
    val native = Any()
    val result = frameworkArguments().withIdentity({ index -> reads[index]++; values[index] }) { item, info ->
      assertSame(first, item)
      assertSame(first, info)
      assertEquals(2, requireNotNull(info).uid / 100000)
      nativeCalls++
      native
    }
    assertSame(native, result)
    assertEquals(1, nativeCalls)
    assertEquals(listOf(1, 1), reads.toList())
  }

  @Test fun nullItemAndComponentFallbackKeepFormerTypePrecedence() {
    val owner = ApplicationInfo().apply { packageName = "os.owner.app" }
    frameworkArguments().withIdentity({ null }) { item, info ->
      assertNull(item)
      assertNull(info)
    }
    frameworkArguments().withIdentity({ index -> if (index == 1) owner else null }) { item, info ->
      assertSame(owner, item)
      assertSame(owner, info)
    }
    val component = ActivityInfo().apply { packageName = owner.packageName; applicationInfo = owner }
    val explicit = ApplicationInfo().apply { packageName = "os.explicit.app" }
    for (second in listOf(null, explicit)) {
      frameworkArguments().withIdentity({ index -> if (index == 0) component else second }) { item, info ->
        assertSame(component, item)
        assertSame(second ?: owner, info)
      }
    }
  }

  @Test fun mamlArgumentsOnlyReadTheCapturedItemAndKeepNativeFailureOnce() {
    val positions = requireNotNull(HostPublicIconArguments.resolve(Signatures::class.java.getDeclaredMethod(
      "maml", Context::class.java, PackageItemInfo::class.java, PackageManager::class.java)))
    val owner = ApplicationInfo().apply { packageName = "os.owner.app" }
    val component = ActivityInfo().apply { packageName = owner.packageName; applicationInfo = owner }
    val reads = mutableListOf<Int>()
    var nativeCalls = 0
    val failure = IllegalStateException("native failure")
    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      positions.withIdentity<Any?>({ index -> reads += index; component }) { item, info ->
        assertSame(component, item)
        assertSame(owner, info)
        nativeCalls++
        throw failure
      }
    })
    assertEquals(listOf(1), reads)
    assertEquals(1, nativeCalls)
    assertNull(HostPublicIconArguments.resolve(String::class.java.getDeclaredMethod("length")))
  }

  @Test fun directPackageValidationMatchesUriPolicyWithoutConstructingAnIconUri() {
    for (name in listOf("os.kei.hyper.home", "org._2name", "org.\u5e94\u7528", "org." + "x".repeat(236))) {
      assertEquals(name, sidebarIconPackageName(name))
      assertEquals(name, sidebarIconPackage("pkg_icon://$name"))
    }
    for (name in listOf("", "android", ".org.app", "org..app", "org.app.", "org.2app",
      "org.app/path", "org.app?x=1", "org.app\$alias", "org." + "x".repeat(237))) {
      assertNull(name, sidebarIconPackageName(name))
      assertNull(name, sidebarIconPackage("pkg_icon://$name"))
    }
    assertNull(sidebarIconPackageName(null))
  }

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
