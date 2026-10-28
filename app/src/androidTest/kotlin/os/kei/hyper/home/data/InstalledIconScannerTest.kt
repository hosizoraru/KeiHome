package os.kei.hyper.home.data

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledIconScannerTest {
  @Test
  fun includesVisibleLauncherPackagesForCurrentUser() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val expected = context.packageManager.queryIntentActivities(
      intent, PackageManager.ResolveInfoFlags.of(0),
    ).map { it.activityInfo.packageName }.toSet()
    val serial = context.getSystemService(UserManager::class.java)
      .getSerialNumberForUser(Process.myUserHandle())
    val capabilities = InstalledIconScanner(context).scan()
    val actual = capabilities.filter { it.userId == serial }.map { it.packageName }.toSet()
    assertTrue("Launcher query should include KeiHome", context.packageName in expected)
    assertEquals("Scanner and visible launcher package inventory differ", expected, actual)
    capabilities.filter {
      it.nightMode == IconCapabilityStatus.DETECTED ||
        it.monochrome == IconCapabilityStatus.DETECTED
    }.forEach {
      Log.i(
        "KeiHomeIconInventory",
        "${it.label} (${it.packageName}) user=${it.userId} " +
          "night=${it.nightMode} monochrome=${it.monochrome}",
      )
    }
  }
}
