package os.kei.hyper.home.xposed

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Local archived APKs are analysed in separate loaders, never installed over the host. */
class HyperOsSidebarMatrixDeviceTest {
  @Test fun archivedApksResolveAndRestoreInIndependentLoaders() {
    val directory = InstrumentationRegistry.getArguments().getString("sidebarFixtureDirectory")
    assumeTrue("Pass sidebarFixtureDirectory to run the optional OEM matrix", directory != null)
    val apks = File(requireNotNull(directory)).listFiles()
      ?.filter { it.extension == "apk" }?.sortedBy { it.name }.orEmpty()
    assertTrue("Expected a bounded, nonempty OEM fixture matrix", apks.size in 1..16)
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val host = app.createPackageContext(HyperOsSidebarProfile.PACKAGE,
      Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
    val worker = Executors.newSingleThreadExecutor()
    val active = File.createTempFile("sidebar-matrix-", ".apk", app.codeCacheDir)
    val staged = File(active.path + ".staged")
    try {
      worker.submit {
        HyperOsSidebarProfileCache.invalidate()
        var previousSource: String? = null
        for (apk in apks) {
          apk.copyTo(staged, overwrite = true)
          check(staged.setReadOnly())
          check(staged.renameTo(active))
          val info = ApplicationInfo(host.applicationInfo).apply {
            sourceDir = active.path
            publicSourceDir = active.path
          }
          val context = object : ContextWrapper(host) {
            override fun getApplicationInfo() = info
            override fun getCacheDir() = app.cacheDir
          }
          val loader = PathClassLoader(active.path, host.classLoader.parent)
          val start = SystemClock.elapsedRealtime()
          val profile = HyperOsSidebarProfile.resolve(context, loader)
          val elapsed = SystemClock.elapsedRealtime() - start
          assertEquals(2, profile.bindMethods.size)
          assertTrue(profile.requestMethods.isNotEmpty())
          assertSame(loader, profile.grid.bind.declaringClass.classLoader)
          val proof = requireNotNull(System.getProperty(HyperOsSidebarProfileCache.KEY))
          val source = JSONObject(proof).getString("source")
          assertNotEquals("Same-path APK replacement must invalidate the previous proof",
            previousSource, source)
          previousSource = source
          val fresh = PathClassLoader(active.path, host.classLoader.parent)
          val restoreStart = SystemClock.elapsedRealtime()
          val restored = HyperOsSidebarProfile.resolve(context, fresh)
          assertSame(fresh, restored.grid.bind.declaringClass.classLoader)
          assertNotSame(profile.grid.bind.declaringClass, restored.grid.bind.declaringClass)
          assertEquals(proof, System.getProperty(HyperOsSidebarProfileCache.KEY))
          Log.i("KeiHomeSidebarMatrix", JSONObject()
            .put("apk", apk.name).put("resolveMs", elapsed)
            .put("restoreMs", SystemClock.elapsedRealtime() - restoreStart)
            .put("source", source)
            .put("grid", profile.grid.bind.toString())
            .put("rail", profile.rail.bind.toString())
            .put("requests", profile.requestMethods.size).toString())
        }
      }.get(240, TimeUnit.SECONDS)
    } finally {
      worker.shutdownNow()
      HyperOsSidebarProfileCache.invalidate()
      active.delete()
      staged.delete()
    }
  }
}
