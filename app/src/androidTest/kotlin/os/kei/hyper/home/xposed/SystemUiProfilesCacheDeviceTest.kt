package os.kei.hyper.home.xposed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemUiProfilesCacheDeviceTest {
  class Fixture {
    @JvmField var flag = false
    fun first(value: IntArray, other: Boolean): Boolean = value.isEmpty() && other
    fun second(): Boolean = false
  }

  private fun profile(): SystemUiProfilesCache.Profiles {
    val cls = Fixture::class.java
    val first = cls.getDeclaredMethod("first", IntArray::class.java, Boolean::class.javaPrimitiveType)
    val second = cls.getDeclaredMethod("second")
    val field = cls.getDeclaredField("flag")
    val proof = SystemUiMaterialProof("themed", "capability", "owner", "supported", "reader", "theme",
      "plugin", "system", "pluginField", "systemField")
    val background = ControlCenterTileBackgroundCacheProfile(first, second, field, field, first, listOf(second))
    val tile = ControlCenterSmallTileRefreshProfile(first, second, field, second, first, field,
      second, field, first, background)
    val center = SystemUiControlCenterRefreshProfile(first, field, second, first, field, first, field,
      first, second, cls, tile, listOf(first, second), ControlCenterSliderRefreshProfile(first, second, listOf(second)))
    return SystemUiProfilesCache.Profiles(SystemUiMaterialProfile(first, second, proof, listOf(first, second),
      VolumeMaterialRefreshProfile(second, field)), OptionalControlCenterRefresh(center, null), false)
  }
  private fun fixture(action: (File, File) -> Unit) {
    val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
      "profile-cache-test-${System.nanoTime()}").apply { mkdirs() }
    try {
      val apk = File(root, "host.apk")
      writeApk(apk, 1)
      action(apk, File(root, "cache").apply { mkdirs() })
    } finally { root.deleteRecursively() }
  }
  private fun writeApk(file: File, value: Int) {
    ZipOutputStream(file.outputStream()).use {
      it.putNextEntry(ZipEntry("classes.dex")); it.write(byteArrayOf(value.toByte())); it.closeEntry()
      it.putNextEntry(ZipEntry("AndroidManifest.xml")); it.write(byteArrayOf(9)); it.closeEntry()
    }
  }
  private fun resolve(apk: File, cache: File, fresh: () -> SystemUiProfilesCache.Profiles) =
    SystemUiProfilesCache.resolve(Fixture::class.java.classLoader!!, apk.path, cache, fresh)

  @Test fun restoresEveryRoleAndOptionalFeatureWithoutOpeningDexKit() = fixture { apk, cache ->
    val original = profile()
    assertFalse(resolve(apk, cache) { original }.restored)
    val restored = resolve(apk, cache) { error("Cache hit must not open another native library") }
    assertTrue(restored.restored)
    assertEquals(original.material, restored.material)
    assertEquals(original.controlCenter, restored.controlCenter)
    assertSame(Fixture::class.java.classLoader, restored.material.themedGate.declaringClass.classLoader)
  }
  @Test fun changedDexContentInvalidatesWithoutAnyVersionGate() = fixture { apk, cache ->
    val original = profile()
    resolve(apk, cache) { original }
    val timestamp = apk.lastModified()
    val length = apk.length()
    writeApk(apk, 2); apk.setLastModified(timestamp)
    assertEquals(length, apk.length())
    var calls = 0
    assertFalse(resolve(apk, cache) { calls++; original }.restored)
    assertEquals(1, calls)
    assertTrue(resolve(apk, cache) { error("Must restore new source") }.restored)
  }
  @Test fun malformedOversizedOrMissingMembersFallBackToResolution() = fixture { apk, cache ->
    val original = profile()
    val file = File(cache, "keihome-systemui-profiles.json")
    for (invalid in listOf("{broken", "x".repeat(256 * 1024 + 1))) {
      file.writeText(invalid)
      var calls = 0
      assertFalse(resolve(apk, cache) { calls++; original }.restored)
      assertEquals(1, calls)
    }
    file.writeText(file.readText().replace("\"name\":\"first\"", "\"name\":\"absent\""))
    var calls = 0
    assertFalse(resolve(apk, cache) { calls++; original }.restored)
    assertEquals(1, calls)
  }
}
