package os.kei.hyper.home.xposed

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -e systemui.material.apk <readable plugin ApplicationInfo.sourceDir>. */
class SystemUiMaterialProfileDiagnosticTest {
  @Test
  fun reportMaterialContractFromActualPluginApk() {
    val apkPath = InstrumentationRegistry.getArguments().getString("systemui.material.apk")
    assumeTrue("Pass systemui.material.apk to inspect an actual plugin APK", !apkPath.isNullOrBlank())
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val cache = instrumentation.targetContext.codeCacheDir
    val proof = SystemUiMaterialProfile.inspect(requireNotNull(apkPath), cache)
    // Exercise a second bridge after the first was closed; no Method invocation,
    // plugin class initialization, hook, theme mutation or SystemUI restart.
    assertEquals(proof, SystemUiMaterialProfile.inspect(apkPath, cache))
    assertNotEquals(proof.themedGate, proof.capabilityGate)
    assertNotEquals(proof.pluginThemeField, proof.sysUiThemeField)

    val digest = MessageDigest.getInstance("SHA-256")
    File(apkPath).inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    instrumentation.sendStatus(0, Bundle().apply {
      putString("systemui.material.apk", apkPath)
      putString("systemui.material.sha256", sha256)
      putString("systemui.material.proof", proof.toString())
    })
  }
}
