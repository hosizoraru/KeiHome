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
    val fixtureDirectory = File(requireNotNull(directory))
    val inventoryFile = File(fixtureDirectory, "device-fixtures.json")
    assertTrue("Push the verified matrix inventory beside the APKs",
      inventoryFile.isFile && inventoryFile.length() in 1..64L * 1024)
    val inventory = JSONObject(inventoryFile.readText())
    assertEquals(1, inventory.getInt("schema"))
    val entries = inventory.getJSONArray("fixtures")
    assertTrue("Expected 1..16 inventory entries", entries.length() in 1..16)
    val receipts = (0 until entries.length()).map { entries.getJSONObject(it) }
      .associateBy { it.getString("file") }
    assertEquals("Duplicate fixture names", entries.length(), receipts.size)
    receipts.forEach { (name, receipt) ->
      assertTrue("Inventory filename must be a local APK basename",
        name == File(name).name && name.endsWith(".apk"))
      assertTrue("Inventory SHA256 must be complete",
        receipt.getString("sha256").matches(Regex("[0-9a-f]{64}")))
      assertTrue(receipt.getLong("bytes") in 1..512L * 1024 * 1024)
    }
    val apks = fixtureDirectory.listFiles()
      ?.filter { it.isFile && it.extension == "apk" }?.sortedBy { it.name }.orEmpty()
    assertTrue("Expected a bounded, nonempty OEM fixture matrix", apks.size in 1..16)
    assertEquals("Push exactly the inventoried APKs", receipts.keys, apks.map { it.name }.toSet())
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
        val proofsByVersion = mutableMapOf<String, MutableList<String>>()
        var sameVersionRejections = 0
        for (apk in apks) {
          val receipt = receipts.getValue(apk.name)
          assertEquals(receipt.getLong("bytes"), apk.length())
          val metadata = requireNotNull(app.packageManager.getPackageArchiveInfo(apk.path, 0))
          assertEquals(receipt.getString("package"), metadata.packageName)
          assertEquals(receipt.getString("versionCode"), metadata.longVersionCode.toString())
          assertEquals(receipt.getString("versionName"), metadata.versionName)
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
          val sameVersion = proofsByVersion.getOrPut(metadata.longVersionCode.toString()) {
            mutableListOf()
          }
          for (oldProof in sameVersion) {
            val oldSource = JSONObject(JSONObject(oldProof).getString("source"))
            if (oldSource.getString("sha256") != receipt.getString("sha256")) {
              assertEquals("Replacement must reuse the exact APK path",
                active.canonicalPath, oldSource.getString("path"))
              System.setProperty(HyperOsSidebarProfileCache.KEY, oldProof)
              assertNull("Same-version, same-path replacement must reject the previous APK proof",
                HyperOsSidebarProfileCache.restore(context, loader))
              assertNull(System.getProperty(HyperOsSidebarProfileCache.KEY))
              sameVersionRejections++
            }
          }
          val start = SystemClock.elapsedRealtime()
          val profile = HyperOsSidebarProfile.resolve(context, loader)
          val elapsed = SystemClock.elapsedRealtime() - start
          assertEquals(2, profile.bindMethods.size)
          assertTrue(profile.requestMethods.isNotEmpty())
          assertSame(loader, profile.grid.bind.declaringClass.classLoader)
          val proof = requireNotNull(System.getProperty(HyperOsSidebarProfileCache.KEY))
          val source = JSONObject(proof).getString("source")
          val identity = JSONObject(source)
          assertEquals(active.canonicalPath, identity.getString("path"))
          assertEquals("Device fixture bytes must match the offline inventory",
            receipt.getString("sha256"), identity.getString("sha256"))
          if (previousSource != null &&
            JSONObject(previousSource).getString("sha256") == identity.getString("sha256")) {
            assertEquals("Identical bytes at the same path preserve source identity",
              previousSource, source)
          } else {
            assertNotEquals("Same-path APK replacement must invalidate the previous proof",
              previousSource, source)
          }
          previousSource = source
          sameVersion.add(proof)
          val fresh = PathClassLoader(active.path, host.classLoader.parent)
          val restoreStart = SystemClock.elapsedRealtime()
          val restored = HyperOsSidebarProfile.resolve(context, fresh)
          assertSame(fresh, restored.grid.bind.declaringClass.classLoader)
          assertNotSame(profile.grid.bind.declaringClass, restored.grid.bind.declaringClass)
          assertEquals(proof, System.getProperty(HyperOsSidebarProfileCache.KEY))
          Log.i("KeiHomeSidebarMatrix", JSONObject()
            .put("apk", apk.name).put("resolveMs", elapsed)
            .put("versionCode", metadata.longVersionCode).put("versionName", metadata.versionName)
            .put("restoreMs", SystemClock.elapsedRealtime() - restoreStart)
            .put("source", source)
            .put("grid", profile.grid.bind.toString())
            .put("rail", profile.rail.bind.toString())
            .put("requests", profile.requestMethods.size).toString())
        }
        val expectedCollisions = receipts.values.groupBy { it.getString("versionCode") }
          .values.sumOf { group ->
            group.indices.sumOf { index ->
              group.take(index).count {
                it.getString("sha256") != group[index].getString("sha256")
              }
            }
          }
        assertEquals("Exercise every inventoried same-version replacement",
          expectedCollisions, sameVersionRejections)
        Log.i("KeiHomeSidebarMatrix", JSONObject()
          .put("fixtures", apks.size).put("sameVersionRejections", sameVersionRejections).toString())
      }.get(240, TimeUnit.SECONDS)
    } finally {
      worker.shutdownNow()
      HyperOsSidebarProfileCache.invalidate()
      active.delete()
      staged.delete()
    }
  }
}
