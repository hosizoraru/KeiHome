package os.kei.hyper.home.xposed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeImportProfileDeviceTest {
  @Test fun resolvesInstalledAndPreviousApksByRolesWithoutAnOemVersionTable() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val current = context.packageManager.getApplicationInfo(ThemeIconImportRuntime.PACKAGE_NAME, 0).sourceDir
    val proof = ThemeImportProfile.inspect(current, context.codeCacheDir)
    assertEquals(11, proof.size)
    val previous = InstrumentationRegistry.getArguments().getString("previousThemesApk")
    if (previous != null) {
      val old = ThemeImportProfile.inspect(previous, context.codeCacheDir)
      assertEquals(proof.keys, old.keys)
    }
  }

  @Test fun refusesAnUnrelatedApkInsteadOfInventingAContract() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue(runCatching { ThemeImportProfile.inspect(context.applicationInfo.sourceDir, context.codeCacheDir) }.isFailure)
  }
}
