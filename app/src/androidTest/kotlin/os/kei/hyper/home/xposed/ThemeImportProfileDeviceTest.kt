package os.kei.hyper.home.xposed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.luckypray.dexkit.wrap.DexMethod

@RunWith(AndroidJUnit4::class)
class ThemeImportProfileDeviceTest {
  @Test fun resolvesInstalledAndProvidedApksByRolesWithoutAnOemVersionTable() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val current = context.packageManager.getApplicationInfo(ThemeIconImportRuntime.PACKAGE_NAME, 0).sourceDir
    val arguments = InstrumentationRegistry.getArguments()
    val paths = linkedSetOf(current)
    arguments.getString("previousThemesApk")?.let(paths::add)
    arguments.getString("themesApks")?.let { value ->
      val provided = JSONArray(value)
      require(provided.length() in 1..16)
      repeat(provided.length()) { paths += provided.getString(it) }
    }
    val expected = setOf("rights", "apply", "detailReady", "validate", "parent", "inner", "registry",
      "iconContext", "controllerManager", "lookup", "appContext")
    val sharedLibraries = context.packageManager.getApplicationInfo(ThemeIconImportRuntime.PACKAGE_NAME,
      PackageManager.GET_SHARED_LIBRARY_FILES).sharedLibraryFiles.orEmpty()
    val sharedLoader = PathClassLoader(sharedLibraries.joinToString(File.pathSeparator),
      ClassLoader.getSystemClassLoader())
    val reports = JSONArray()
    for (path in paths) {
      val apk = File(path)
      assertTrue("Unreadable Themes fixture: $path", apk.isFile && apk.canRead())
      val started = System.nanoTime()
      val proof = ThemeImportProfile.inspect(path, context.codeCacheDir)
      assertEquals("Incomplete method roles: $path", expected, proof.keys)
      // Use each APK's own classes, without initializing or running the Themes application.
      val loader = PathClassLoader(path, sharedLoader)
      proof.forEach { (role, descriptor) ->
        val method = DexMethod(descriptor).getMethodInstance(loader)
        assertEquals("Role owner mismatch: $role in $path", method.declaringClass.classLoader, loader)
      }
      val info = requireNotNull(context.packageManager.getPackageArchiveInfo(path, 0))
      assertEquals(ThemeIconImportRuntime.PACKAGE_NAME, info.packageName)
      reports.put(JSONObject().put("apk", path).put("versionName", info.versionName)
        .put("versionCode", info.longVersionCode).put("roles", JSONObject(proof))
        .put("elapsedMs", (System.nanoTime() - started) / 1_000_000))
    }
    File(context.cacheDir, "theme-import-profile-diagnostics.json").writeText(reports.toString(2))
  }

  @Test fun refusesAnUnrelatedApkInsteadOfInventingAContract() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue(runCatching { ThemeImportProfile.inspect(context.applicationInfo.sourceDir, context.codeCacheDir) }.isFailure)
  }
}
