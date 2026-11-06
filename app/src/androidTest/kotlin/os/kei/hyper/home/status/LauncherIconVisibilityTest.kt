package os.kei.hyper.home.status

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.LauncherApps
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.LauncherIconVisibility

class LauncherIconVisibilityTest {
  @Test fun hiddenIconKeepsSettingsEntryAndRestoresLauncherWithoutKillingApp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val pm = context.packageManager
    val controller = LauncherIconVisibility(context)
    val original = controller.isHidden()
    val process = Process.myPid()
    fun entries(category: String) = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN)
      .addCategory(category).setPackage(BuildConfig.APPLICATION_ID), PackageManager.ResolveInfoFlags.of(0))
    try {
      controller.setHidden(true)
      assertTrue(controller.isHidden())
      assertTrue(entries(Intent.CATEGORY_LAUNCHER).isEmpty())
      // Android may still expose its own app-management entry; our desktop entry must be absent.
      assertTrue(context.getSystemService(LauncherApps::class.java)
        .getActivityList(BuildConfig.APPLICATION_ID, Process.myUserHandle())
        .none { it.componentName.className == "${BuildConfig.APPLICATION_ID}.LauncherEntry" })
      assertEquals(1, entries(Intent.CATEGORY_INFO).size)
      assertEquals(1, entries("de.robv.android.xposed.category.MODULE_SETTINGS").size)
      assertNotNull(pm.resolveActivity(Intent(Intent.ACTION_MAIN)
        .addCategory("de.robv.android.xposed.category.MODULE_SETTINGS").setPackage(BuildConfig.APPLICATION_ID),
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())))
      assertEquals(ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.MainActivity"),
        pm.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)?.component)
      assertTrue(pm.getActivityInfo(ComponentName(context, "os.kei.hyper.home.MainActivity"),
        PackageManager.ComponentInfoFlags.of(0)).enabled)
      controller.setHidden(false)
      assertFalse(controller.isHidden())
      assertEquals("${BuildConfig.APPLICATION_ID}.LauncherEntry", entries(Intent.CATEGORY_LAUNCHER)
        .single().activityInfo.name)
      assertEquals(process, Process.myPid())
    } finally {
      controller.setHidden(original)
    }
  }
}
