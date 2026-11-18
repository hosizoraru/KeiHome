package os.kei.hyper.home.data

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libxposed.service.XposedServiceHelper
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.withForegroundDeviceCheck
import org.junit.runner.RunWith

/** Explicit release gate. The host must first apply a genuinely Monet-capable theme. */
@RunWith(AndroidJUnit4::class)
class MonetRuntimeReleaseGateTest {
  private data class IconSample(val pixels: IntArray, val hash: String)

  @Test fun nativeAcknowledgementAndLauncherIconsFollowBothSeedsAndNightModes() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    assumeTrue(InstrumentationRegistry.getArguments().getString("realMonetRelease") == "1")
    val context = instrumentation.targetContext
    val arguments = InstrumentationRegistry.getArguments()
    val animatedBackdrop = arguments.getString("monetAnimatedBackdrop") == "1"
    val screenSize = requireNotNull(arguments.getString("monetScreenSize")).split(",").map(String::toInt)
    val coordinates = requireNotNull(arguments.getString("monetIconBounds")).split(",").map(String::toInt)
    require(screenSize.size == 2 && coordinates.size == 4)
    val iconBounds = Rect(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
    val folder = File(context.cacheDir, "monet-release-runtime").apply { mkdirs() }
    fun root(command: String): String {
      val marker = "\n__KEIHOME_MONET_EXIT__"
      val checked = "$command\nkeihome_gate_exit=\$?\nprintf '\\n__KEIHOME_MONET_EXIT__%s\\n' \"\$keihome_gate_exit\""
      val pipes = instrumentation.uiAutomation.executeShellCommandRw("su 0 sh")
      ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use {
        it.write("$checked\nexit\n")
      }
      val output = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
      val index = output.lastIndexOf(marker)
      check(index >= 0 && output.substring(index + marker.length).trim() == "0") {
        "Root command failed: $command\n$output"
      }
      return output.substring(0, index).trim()
    }
    fun setting(namespace: String, key: String): String =
      (if (namespace == "secure") Settings.Secure.getString(context.contentResolver, key)
      else Settings.System.getString(context.contentResolver, key)) ?: "null"
    fun assertDisabled() {
      assertEquals("Monet app flag", "0", setting("system", "key_monochrome"))
      assertEquals("Legacy MAML must remain disabled", "0", setting("system", NativePaletteIcon.ENABLED_KEY))
      assertEquals("Packed MAML must remain disabled", "0", setting("system", NativePaletteIcon.STATE_KEY))
    }
    fun assertLauncherForeground() {
      check(root("dumpsys activity activities").lineSequence().any {
        "topResumedActivity=" in it && "com.miui.home/" in it
      }) { "The icon witness requires the actual foreground launcher" }
    }
    fun captureIcon(): IconSample {
      val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
      check(screen.width == screenSize[0] && screen.height == screenSize[1] &&
        Rect(0, 0, screen.width, screen.height).contains(iconBounds) && !iconBounds.isEmpty) {
        "Launcher fixture geometry changed; revalidate the pinned Settings icon bounds"
      }
      val crop = Bitmap.createBitmap(screen, iconBounds.left, iconBounds.top, iconBounds.width(), iconBounds.height())
      val image = Bitmap.createScaledBitmap(crop, 192, 192, false)
      screen.recycle()
      if (crop !== image) crop.recycle()
      try {
        // MAML publishes encoded RGB values. Compare the screenshot channels before
        // getPixels converts a Display P3 screenshot to sRGB.
        if (image.colorSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3)) {
          image.setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        val pixels = IntArray(image.width * image.height).also {
          image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
        }
        check(pixels.any { it ushr 24 != 0 }) { "Launcher returned an empty Settings icon" }
        val digest = MessageDigest.getInstance("SHA-256")
        pixels.forEach { digest.update(byteArrayOf((it ushr 24).toByte(), (it ushr 16).toByte(),
          (it ushr 8).toByte(), it.toByte())) }
        return IconSample(pixels, digest.digest().joinToString("") { "%02x".format(it) })
      } finally { image.recycle() }
    }
    fun saveIcon(name: String, sample: IconSample) {
      val image = Bitmap.createBitmap(sample.pixels, 192, 192, Bitmap.Config.ARGB_8888)
      try {
        File(folder, "$name-icon.png").outputStream().use {
          check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
      } finally { image.recycle() }
    }
    fun colorPixels(sample: IconSample, color: Int): Int = sample.pixels.count { pixel ->
      // Count opaque interiors, allowing two sRGB levels for the native raster/compositing path.
      pixel ushr 24 == 255 &&
        kotlin.math.abs((pixel ushr 16 and 255) - (color ushr 16 and 255)) <= 2 &&
        kotlin.math.abs((pixel ushr 8 and 255) - (color ushr 8 and 255)) <= 2 &&
        kotlin.math.abs((pixel and 255) - (color and 255)) <= 2
    }
    fun sameArtwork(first: IconSample, second: IconSample): Boolean {
      if (first.hash == second.hash) return true
      if (!animatedBackdrop) return false
      // Rounded or partially transparent icons can expose the live wallpaper and Dock glass.
      // Keep requiring the two expected opaque palette roles for enabled samples.
      val changed = first.pixels.indices.count { index ->
        val a = first.pixels[index]
        val b = second.pixels[index]
        kotlin.math.abs((a ushr 16 and 255) - (b ushr 16 and 255)) > 2 ||
          kotlin.math.abs((a ushr 8 and 255) - (b ushr 8 and 255)) > 2 ||
          kotlin.math.abs((a and 255) - (b and 255)) > 2
      }
      return changed * 50 <= first.pixels.size
    }
    fun settledIcon(name: String, expected: MonetIconRoles? = null, disabled: Boolean = false): IconSample {
      root("input -d 0 keyevent 3")
      assertLauncherForeground()
      val deadline = SystemClock.elapsedRealtime() + 15_000
      var previous: IconSample? = null
      var stable = 0
      var sample: IconSample
      do {
        if (disabled) assertDisabled()
        sample = captureIcon()
        val colorsMatch = expected == null ||
          colorPixels(sample, expected.foreground) >= 16 && colorPixels(sample, expected.background) >= 16
        stable = if (colorsMatch) {
          if (animatedBackdrop && expected != null || previous?.let { sameArtwork(it, sample) } == true) {
            stable + 1
          } else 1
        } else 0
        previous = sample
        if (stable >= 3) {
          assertLauncherForeground()
          saveIcon(name, sample)
          return sample
        }
        SystemClock.sleep(500)
      } while (SystemClock.elapsedRealtime() < deadline)
      saveIcon(name, sample)
      error("$name: Settings icon did not settle with the required colors; " +
        "foreground=${expected?.foreground?.let { "%08X".format(it) }}, " +
        "background=${expected?.background?.let { "%08X".format(it) }}, " +
        "foregroundPixels=${expected?.let { colorPixels(sample, it.foreground) }}, " +
        "backgroundPixels=${expected?.let { colorPixels(sample, it.background) }}. " +
        "The pinned launcher icon must expose both expected colors; unthemed/cached icons cannot pass.")
    }
    val keys = listOf("system" to "key_monochrome", "system" to "key_monochrome_color",
      "secure" to "keihome_monet_auto_color", "secure" to "keihome_monet_customization") +
      listOf(NativePaletteIcon.FOREGROUND_KEY, NativePaletteIcon.BACKGROUND_KEY,
        NativePaletteIcon.ENABLED_KEY, NativePaletteIcon.STATE_KEY).map { "system" to it }
    val original = keys.associateWith { setting(it.first, it.second) }
    val originalNight = root("cmd uimode night").substringAfterLast(": ").trim()
    val modes = mapOf("yes" to "yes", "no" to "no", "auto" to "auto")
    check(originalNight in modes) { "Unsupported night mode restoration: $originalNight" }
    val oldChoice = MonetCustomization.decode(original["secure" to "keihome_monet_customization"])
    withForegroundDeviceCheck {
      XposedServiceHelper.registerListener(FrameworkConnection)
      val bound = runBlocking {
        withTimeout(30_000) { FrameworkConnection.service.filterNotNull().first() }
      }
        fun apply(choice: MonetCustomization, enabled: Boolean = true, preserveEnabled: Boolean = false) {
          val beforeEnabled = setting("system", "key_monochrome")
          val request = MonetAction.submit(bound, choice, enabled = enabled, preserveEnabled = preserveEnabled)
          fun applied() = MonetAction.isApplied(context.contentResolver, request)
          val deadline = SystemClock.elapsedRealtime() + 25_000
          while (!applied() && SystemClock.elapsedRealtime() < deadline) {
            if (preserveEnabled) {
              assertEquals(beforeEnabled, setting("system", "key_monochrome"))
              if (beforeEnabled == "0") assertDisabled()
            }
            SystemClock.sleep(100)
          }
          check(applied()) { "Missing native Monet APPLIED ACK for ${request.revision}" }
          assertEquals(if (preserveEnabled) beforeEnabled else if (enabled) "1" else "0",
            setting("system", "key_monochrome"))
          if (preserveEnabled && beforeEnabled == "0") assertDisabled()
        }
        val results = JSONArray()
        val disabledEvidence = JSONObject()
        val daySamples = mutableMapOf<String, Pair<String, MonetIconRoles>>()
        var testFailure: Throwable? = null
        try {
          apply(MonetCustomization(source = MonetSeedSource.BASIC_COLOR), enabled = false)
          assertDisabled()
          val disabledBefore = settledIcon("disabled-before", disabled = true)
          val disabledChoice = MonetCustomization(source = MonetSeedSource.BASIC_COLOR, color = 0xFF087F8C.toInt())
          apply(disabledChoice, preserveEnabled = true)
          assertDisabled()
          assertEquals(disabledChoice, MonetPaletteReader(context).read().customization)
          val disabledAfter = settledIcon("disabled-after", disabled = true)
          assertTrue("Applying colors while disabled must not recolor the real icon",
            sameArtwork(disabledBefore, disabledAfter))
          disabledEvidence.put("beforeHash", disabledBefore.hash).put("afterHash", disabledAfter.hash)
            .put("scalarEnabled", setting("system", NativePaletteIcon.ENABLED_KEY))
            .put("packedState", setting("system", NativePaletteIcon.STATE_KEY)).put("unchanged", true)
          apply(disabledChoice, enabled = true)
          for (night in listOf("no", "yes")) {
            root("cmd uimode night $night")
            val expectedQualifier = if (night == "yes") "night" else "notnight"
            // The instrumentation context can retain its old resources while Home is foreground.
            fun systemConfiguration() = root("am get-config").lineSequence()
              .first { it.startsWith("config: ") }.removePrefix("config: ")
            var configuration = systemConfiguration()
            val modeDeadline = SystemClock.elapsedRealtime() + 10_000
            while (expectedQualifier !in configuration.split('-') &&
              SystemClock.elapsedRealtime() < modeDeadline) {
              SystemClock.sleep(100)
              configuration = systemConfiguration()
            }
            assertTrue("Framework configuration lacks $expectedQualifier: $configuration",
              expectedQualifier in configuration.split('-'))
            val hashes = mutableListOf<String>()
            for ((name, color) in listOf("blue" to 0xFF1976D2.toInt(), "pink" to 0xFFB53F6F.toInt())) {
              val choice = MonetCustomization(source = MonetSeedSource.BASIC_COLOR, color = color)
              val expected = requireNotNull(MonetSchemes.iconRoles(
                MonetSchemes.generate(color, choice.style, choice.paletteOverrides), night == "yes", choice))
              apply(choice, preserveEnabled = true)
              assertEquals(choice, MonetPaletteReader(context).read().customization)
              assertEquals("1", setting("system", "key_monochrome"))
              assertEquals(expected.background.toString(), setting("system", "key_monochrome_color"))
              assertEquals("1", setting("system", NativePaletteIcon.ENABLED_KEY))
              assertEquals("#%08X".format(expected.foreground), setting("system", NativePaletteIcon.FOREGROUND_KEY))
              assertEquals("#%08X".format(expected.background), setting("system", NativePaletteIcon.BACKGROUND_KEY))
              assertEquals(NativePaletteIcon.packedState(expected.foreground, expected.background).toString(),
                setting("system", NativePaletteIcon.STATE_KEY))
              val sample = settledIcon("$night-$name", expected)
              hashes += sample.hash
              if (night == "no") daySamples[name] = sample.hash to expected else {
                val (dayHash, dayColors) = daySamples.getValue(name)
                assertNotEquals("Day/night foreground roles must differ", dayColors.foreground, expected.foreground)
                assertNotEquals("Day/night background roles must differ", dayColors.background, expected.background)
                assertNotEquals("Real icon ignored night mode for $name", dayHash, sample.hash)
              }
              val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
              try {
                File(folder, "$night-$name-home.png").outputStream().use { stream ->
                  check(screen.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
              } finally { screen.recycle() }
              results.put(JSONObject().put("night", night).put("seed", name).put("iconHash", sample.hash)
                .put("background", setting("system", "key_monochrome_color"))
                .put("expectedForeground", "#%08X".format(expected.foreground))
                .put("expectedBackground", "#%08X".format(expected.background))
                .put("foregroundPixels", colorPixels(sample, expected.foreground))
                .put("backgroundPixels", colorPixels(sample, expected.background))
                .put("uiMode", context.resources.configuration.uiMode))
              File(folder, "result.json").writeText(JSONObject().put("samples", results).put("preservedDisabled", true)
                .put("iconSource", "foreground-launcher-settings-dock")
                .put("screenSize", JSONArray(screenSize)).put("iconBounds", JSONArray(coordinates))
                .put("disabled", disabledEvidence)
                .put("preservedEnabledSamples", results.length()).toString(2))
            }
            assertNotEquals("Framework icon ignored the seed in night=$night", hashes[0], hashes[1])
          }
          apply(MonetCustomization(source = MonetSeedSource.BASIC_COLOR), false)
          assertDisabled()
        } catch (failure: Throwable) {
          testFailure = failure
          throw failure
        } finally {
          var restorationFailure: Throwable? = null
          fun restoreStep(name: String, restore: () -> Unit) {
            try { restore() } catch (failure: Throwable) {
              val labelled = IllegalStateException("Restoration failed: $name", failure)
              val previous = restorationFailure
              if (previous == null) restorationFailure = labelled else previous.addSuppressed(labelled)
            }
          }
          // Restore mode before the acknowledged colors, and attempt every step even if native is unavailable.
          restoreStep("night mode") { root("cmd uimode night ${modes.getValue(originalNight)}") }
          restoreStep("native/MAML selection") {
            apply(oldChoice ?: MonetCustomization(), original[keys.first()] == "1")
          }
          // Keep consumed ACK revisions; restore exact raw choices and MAML keys independently.
          for ((namespace, key) in keys.filter { it.second != "key_monochrome" } + keys.first()) {
            restoreStep("$namespace/$key") {
              val value = requireNotNull(original[namespace to key])
              root(if (value == "null") "settings delete $namespace $key"
                else "settings put $namespace $key '${value.replace("'", "'\\''")}'")
              assertEquals("Restoring $key", value, setting(namespace, key))
            }
          }
          restoreStep("night mode verification") {
            assertEquals(originalNight, root("cmd uimode night").substringAfterLast(": ").trim())
          }
          restorationFailure?.let { failure ->
            val primary = testFailure
            if (primary == null) throw failure else primary.addSuppressed(failure)
          }
        }
    }
  }
}
