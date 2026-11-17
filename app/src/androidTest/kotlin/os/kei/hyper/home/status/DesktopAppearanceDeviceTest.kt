package os.kei.hyper.home.status

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopAppearanceDeviceTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun diagnosticSyntaxAlsoWorksWithAndroidIcuRegex() {
    val wallpaper = """
SERVICE com.miui.miwallpaper/.server.MiuiWallPaperControllerService fixture pid=100 user=0
    miuiWallpaperData = MiuiWallpaperData{which=1, partIsDeep ={status_bar_rect=1}}
    mWallpaperChangeListeners.size=0
""".trimIndent()
    assertEquals(true to false, DesktopAppearanceDiagnostics.wallpaper(wallpaper, 0, "com.miui.home"))
    val window = """
  Window #1 Window{fixture u0 com.miui.home/com.miui.home.launcher.Launcher}:
    mDisplayId=0
    mAttrs={(0,0) ty=BASE_APPLICATION
      apr=LIGHT_STATUS_BARS}
""".trimIndent().prependIndent("  ")
    assertEquals(true, DesktopAppearanceDiagnostics.homeDarkIcons(window, 0, "com.miui.home"))
  }

  @Test fun appCannotPublishOrClaimPrivilegedRefresh() {
    for (method in listOf(DesktopAppearanceInbox.CLAIM, DesktopAppearanceInbox.REPORT)) {
      val failure = assertThrows(IllegalStateException::class.java) {
        context.contentResolver.call(Uri.parse("content://${RuntimeStatusInbox.AUTHORITY}"),
          method, null, Bundle())
      }
      assertEquals("Runtime status requires the system owner", failure.message)
    }
  }

  @Test fun consumedRefreshNonceSurvivesStoreReload() {
    val stores = mutableSetOf<String>()
    val prefix = "test-desktop-tools-${UUID.randomUUID()}-"
    val isolated = object : ContextWrapper(context) {
      override fun getSharedPreferences(name: String, mode: Int) =
        super.getSharedPreferences(prefix + name, mode).also { stores += prefix + name }
    }
    try {
      val id = UUID.randomUUID().toString()
      val user = Process.myUid() / 100_000
      assertTrue(DesktopAppearanceInbox.claim(isolated, id, user))
      DesktopAppearanceInbox.load(isolated)
      assertEquals(AppearanceRefreshResult.UNCONFIRMED, DesktopAppearanceInbox.lastRefresh.value?.result)
      assertFalse(DesktopAppearanceInbox.claim(isolated, id, user))
      assertFalse(DesktopAppearanceInbox.claim(isolated, UUID.randomUUID().toString(), user + 1))
    } finally {
      stores.forEach(context::deleteSharedPreferences)
      DesktopAppearanceInbox.load(context)
    }
  }

  @Test fun readsLiveSystemAndHomeInformation() = runBlocking {
    assumeTrue("Explicit live HyperOS acceptance fixture required",
      InstrumentationRegistry.getArguments().getString("desktopAppearanceLive") == "true")
    val service = withTimeout(15_000) { FrameworkConnection.service.filterNotNull().first() }
    val report = requireNotNull(RuntimeStatusProtocol.queryDesktopAppearance(service, DesktopAppearanceAction.INSPECT))
    assertNotNull("mode service", report.night)
    assertNotNull("wallpaper status bar region", report.wallpaperDark)
    assertNotNull("complete wallpaper listener snapshot", report.homeSubscribed)
    assertNotNull("Home window, independent of the foreground test app", report.homeDarkIcons)
    assertNotNull("launcher version", report.launcherVersion)
    assertNull("Inspection must not refresh appearance", report.refreshResult)
  }
}
