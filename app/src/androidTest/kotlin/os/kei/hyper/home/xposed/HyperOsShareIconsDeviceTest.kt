package os.kei.hyper.home.xposed

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HyperOsShareIconsDeviceTest {
  private fun app() = ResolveInfo().apply {
    activityInfo = ActivityInfo().apply {
      name = "com.example.app.Share"
      applicationInfo = ApplicationInfo().apply {
        packageName = "com.example.app"
        uid = 10_222
        icon = 7
      }
    }
  }

  @Test fun applicationIconsResolveToComponentAwareIdentity() {
    val info = app()
    assertEquals("com.example.app" to "com.example.app.Share", shareAppIdentity(info, 0))
    info.icon = 7
    info.activityInfo.icon = 7
    assertNotNull(shareAppIdentity(info, 0))
  }

  @Test fun customActionArtworkAndCrossProfileBadgesRemainNative() {
    val info = app()
    info.icon = 8
    assertNull(shareAppIdentity(info, 0))
    info.icon = 0
    info.activityInfo.icon = 9
    assertNull(shareAppIdentity(info, 0))
    info.activityInfo.icon = 0
    info.resolvePackageName = "com.example.resources"
    assertNull(shareAppIdentity(info, 0))
    info.resolvePackageName = null
    info.activityInfo.applicationInfo.uid = 1_010_222
    assertNull(shareAppIdentity(info, 0))
    assertNotNull(shareAppIdentity(info, 10))
  }

  @Test fun absentIdentityNeverReplacesNativeIcon() {
    assertNull(shareAppIdentity(ResolveInfo(), 0))
    val info = app()
    info.activityInfo.name = null
    assertNull(shareAppIdentity(info, 0))
    info.activityInfo.name = "com.example.app.Share"
    info.activityInfo.applicationInfo.packageName = ""
    assertNull(shareAppIdentity(info, 0))
  }

  @Test fun installedOemProfileAuthenticatesRealBindAndIconSource() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val host = target.createPackageContext(HyperOsShareProfile.PACKAGE,
      Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
    val context = object : ContextWrapper(host) {
      override fun getCodeCacheDir() = target.codeCacheDir
    }
    val worker = Executors.newSingleThreadExecutor()
    try {
      val profile = worker.submit<HyperOsShareProfile> {
        HyperOsShareProfile.resolve(context, host.classLoader)
      }.get(20, TimeUnit.SECONDS)
      assertEquals("onBindGroupTargetItem", profile.bind.name)
      assertEquals("com.android.intentresolver.chooser.DisplayResolveInfo", profile.ordinaryTarget.name)
      assertTrue(profile.accepts(profile.ordinaryTarget))
      assertNotNull(profile.groupedTarget)
      assertTrue(profile.accepts(profile.groupedTarget))
      assertFalse(profile.accepts(Class.forName(
        "com.android.intentresolver.chooser.SelectableTargetInfo", false, host.classLoader)))
    } finally { worker.shutdownNow() }
  }
}
