package os.kei.hyper.home.status

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.NativeDayNightIcon
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram

@RunWith(AndroidJUnit4::class)
class PublishedIconCompositeMaskDeviceTest {
  private val flat = "res/drawable-xxhdpi/com.example.wallet.png"
  private fun config(mask: String = NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA) =
    """<IconTransform><Config name="SupportLayerIcon" value="true"/>
      <Config name="ConfigIconMask" value="$mask"/></IconTransform>""".toByteArray()

  private fun png(size: Int = 64, height: Int = size): ByteArray {
    val bitmap = Bitmap.createBitmap(size, height, Bitmap.Config.ARGB_8888)
    return try {
      bitmap.eraseColor(Color.rgb(24, 38, 52))
      ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    } finally { bitmap.recycle() }
  }

  private fun entries(vararg extra: Pair<String, ByteArray>) = linkedMapOf(
    NativeIconShapeConfig.PATH to config(), flat to png()).apply { putAll(extra) }

  private fun archive(files: Map<String, ByteArray>, block: (File, File) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "published-mask-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    val source = File(root, "source.zip")
    val output = File(root, "published.zip")
    try {
      ZipOutputStream(source.outputStream()).use { zip ->
        for ((path, bytes) in files) {
          zip.putNextEntry(ZipEntry(path))
          zip.write(bytes)
          zip.closeEntry()
        }
      }
      val original = source.readBytes()
      try { block(source, output) }
      finally { assertArrayEquals("Source ZIP must remain byte-identical", original, source.readBytes()) }
    } finally { root.deleteRecursively() }
  }

  private fun read(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
    zip.entries().asSequence().associate { entry ->
      entry.name to zip.getInputStream(entry).use { it.readBytes() }
    }
  }

  private fun pixels(png: ByteArray, block: (Bitmap) -> Unit) {
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
    try { block(bitmap) } finally { bitmap.recycle() }
  }

  private fun rounded(bytes: ByteArray) = pixels(bytes) {
    assertEquals(0, Color.alpha(it.getPixel(0, 0)))
    assertEquals(Color.rgb(24, 38, 52), it.getPixel(32, 32))
  }

  @Test fun flatAliasesAndClosedDayNightCompositesAreMaskedWithoutChangingXmlOrLayers() {
    val files = entries().apply {
      putAll(NativeDayNightIcon.entries("com.example.wallet", png(), png(), legacyExpanded = true))
      put("nightmode/res/drawable-xxhdpi/com.example.wallet#com.example.Wallet.png", png())
      put("res/drawable-xxhdpi/com.example.wallet/0.png", png())
      put("fancy_icons/com.example.unknown/manifest.xml",
        """<Icon width="64"><Image src="day.png"/></Icon>""".toByteArray())
      put("fancy_icons/com.example.unknown/day.png", png())
    }
    val original = png()
    val alreadyMasked = requireNotNull(BitmapFactory.decodeByteArray(original, 0, original.size))
    try {
      val clipped = IconMaskPolicy.clip(alreadyMasked,
        requireNotNull(NativeAdaptiveIconMask.fromPathData("fixture", NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA)))
      try {
        files["res/drawable-xhdpi/com.example.rounded.png"] = ByteArrayOutputStream().also {
          assertTrue(clipped.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray()
      } finally { clipped.recycle() }
    } finally { alreadyMasked.recycle() }
    archive(files) { source, output ->
      assertTrue(PublishedIconCompositeMask.write(source, output, true))
      val result = read(output)
      assertEquals(files.keys, result.keys)
      for ((name, bytes) in files) {
        if (name == flat || name.startsWith("nightmode/res/") ||
          name in setOf("fancy_icons/com.example.wallet/day.png", "fancy_icons/com.example.wallet/night.png"))
          rounded(result.getValue(name))
        else assertArrayEquals(name, bytes, result.getValue(name))
      }
    }
    for (size in listOf(351, 512)) archive(entries(flat to png(size))) { source, output ->
      assertTrue(PublishedIconCompositeMask.write(source, output, true))
      pixels(read(output).getValue(flat)) {
        assertEquals(size, it.width)
        assertEquals(0, Color.alpha(it.getPixel(0, 0)))
        assertEquals(Color.rgb(24, 38, 52), it.getPixel(size / 2, size / 2))
      }
    }
    for ((format, mime) in listOf(Bitmap.CompressFormat.JPEG to "image/jpeg",
        Bitmap.CompressFormat.WEBP_LOSSLESS to "image/webp")) {
      val decoded = requireNotNull(BitmapFactory.decodeByteArray(original, 0, original.size))
      val retained = try {
        ByteArrayOutputStream().also { assertTrue(decoded.compress(format, 100, it)) }.toByteArray()
      } finally { decoded.recycle() }
      val inputType = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(retained, 0, retained.size, inputType)
      assertEquals(mime, inputType.outMimeType)
      for (needsClip in listOf(true, false)) {
        val policy = if (needsClip) config() else config("M0 0H100V100H0Z")
        archive(entries(flat to retained, NativeIconShapeConfig.PATH to policy)) { source, output ->
          assertTrue(PublishedIconCompositeMask.write(source, output, true))
          val published = read(output).getValue(flat)
          if (needsClip) {
            val outputType = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(published, 0, published.size, outputType)
            assertEquals("Clipped $mime must publish PNG", "image/png", outputType.outMimeType)
            pixels(retained) { before ->
              pixels(published) { after ->
                assertEquals(0, Color.alpha(after.getPixel(0, 0)))
                assertEquals(before.getPixel(32, 32), after.getPixel(32, 32))
              }
            }
          } else assertArrayEquals("Already conforming $mime must retain its bytes", retained, published)
        }
      }
    }
  }

  @Test fun usesActualAuthoredCustomMaskInsteadOfStockCornersAndPreservesItsXml() {
    val custom = "M0 0H100V30H30V100H0Z"
    val files = entries(NativeIconShapeConfig.PATH to config(custom))
    archive(files) { source, output ->
      assertTrue(PublishedIconCompositeMask.write(source, output, true))
      val result = read(output)
      assertArrayEquals(files.getValue(NativeIconShapeConfig.PATH), result.getValue(NativeIconShapeConfig.PATH))
      pixels(result.getValue(flat)) {
        assertEquals(255, Color.alpha(it.getPixel(8, 48)))
        assertEquals(255, Color.alpha(it.getPixel(48, 8)))
        assertEquals(0, Color.alpha(it.getPixel(32, 32)))
        assertEquals(0, Color.alpha(it.getPixel(48, 48)))
      }
    }
  }

  @Test fun recognizedOriginalAndGeneratedWeatherRootCompositesAreMaskedButDynamicClosureIsExact() {
    val original = weather()
    val paths = weatherImages.mapTo(linkedSetOf()) { "1/fancy/$it" }
    val switched = requireNotNull(NativeWeatherLayerProgram.darkVariant(original, "1/fancy/", paths))
    val files = entries()
    for ((prefix, program) in listOf("" to switched, "nightmode/" to original)) {
      val root = "${prefix}layer_animating_icons/com.miui.weather2/"
      files[root + "config.xml"] = layers()
      files[root + "quiet/quietImage.png"] = png()
      files[root + "0/quiet/quietImage.png"] = png()
      files[root + "1/fancy/manifest.xml"] = program
      for (name in weatherImages) {
        files[root + "1/fancy/$name"] = png()
        if (prefix.isEmpty()) files[root + "1/fancy/keihome_night/$name"] = png()
      }
    }
    archive(files) { source, output ->
      assertTrue(PublishedIconCompositeMask.write(source, output, true))
      val result = read(output)
      for ((name, bytes) in files) {
        if (name == flat || name in setOf(
            "layer_animating_icons/com.miui.weather2/quiet/quietImage.png",
            "nightmode/layer_animating_icons/com.miui.weather2/quiet/quietImage.png"))
          rounded(result.getValue(name))
        else assertArrayEquals("Dynamic resource changed: $name", bytes, result.getValue(name))
      }
    }
  }

  @Test fun unprovedWeatherAndArbitraryGroupQuietRemainByteIdentical() {
    val root = "layer_animating_icons/com.miui.weather2/"
    val files = entries(
      root + "config.xml" to layers(),
      root + "quiet/quietImage.png" to png(),
      root + "0/quiet/quietImage.png" to png(),
      root + "1/fancy/manifest.xml" to weather().toString(Charsets.UTF_8)
        .replace("content://weather/", "content://unknown/").toByteArray(),
      "layer_animating_icons/com.example.dynamic/quiet/quietImage.png" to png())
    for (name in weatherImages) files[root + "1/fancy/$name"] = png()
    archive(files) { source, output ->
      assertTrue(PublishedIconCompositeMask.write(source, output, true))
      val result = read(output)
      for ((name, bytes) in files) if (name != flat)
        assertArrayEquals(name, bytes, result.getValue(name))
    }
  }

  @Test fun disabledLegacyMissingAndAuthoredNonFullPoliciesDoNotPublishRewrittenOutput() {
    val variants = listOf(
      entries() to false,
      entries("icon_mask.png" to png()) to true,
      entries(NativeIconShapeConfig.PATH to
        config().toString(Charsets.UTF_8).replace("value=\"true\"", "value=\"false\"").toByteArray()) to true,
      linkedMapOf(flat to png()) to true)
    for ((files, enabled) in variants) archive(files) { source, output ->
      assertFalse(PublishedIconCompositeMask.write(source, output, enabled))
      assertFalse(output.exists())
    }
  }

  @Test fun unsupportedImagesPassThroughButUnsafePathsLimitsAndCancellationRejectOwnedOutput() {
    val gif = java.util.Base64.getDecoder()
      .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    for (retained in listOf(png(1025), png(64, 48), gif)) archive(entries(flat to retained)) { source, output ->
      assertTrue(PublishedIconCompositeMask.write(source, output, true))
      assertArrayEquals("Unsupported image must retain its original bytes", retained, read(output).getValue(flat))
    }
    archive(entries(flat to ByteArray(4 * 1024 * 1024 + 1))) { source, output ->
      assertTrue(PublishedIconCompositeMask.write(source, output, true))
      assertArrayEquals("An admitted oversized asset must be streamed without decoding",
        read(source).getValue(flat), read(output).getValue(flat))
    }
    archive(entries("../escape.png" to png())) { source, output ->
      val failure = assertThrows(Exception::class.java) {
        PublishedIconCompositeMask.write(source, output, true)
      }
      assertTrue(failure is IllegalArgumentException || failure is java.util.zip.ZipException)
      assertFalse(output.exists())
    }
    archive(entries()) { source, output ->
      assertThrows(IllegalArgumentException::class.java) {
        PublishedIconCompositeMask.write(source, output, true, limits = IconPackLimits(maxEntries = 1))
      }
      assertFalse(output.exists())
      val cancellation = CancellationException("fixture cancellation")
      val failure = assertThrows(CancellationException::class.java) {
        PublishedIconCompositeMask.write(source, output, true, check = {
          if (output.exists()) throw cancellation
        })
      }
      assertSame(cancellation, failure)
      assertFalse(output.exists())
      output.writeText("unrelated output")
      assertThrows(IllegalArgumentException::class.java) {
        PublishedIconCompositeMask.write(source, output, true)
      }
      assertEquals("unrelated output", output.readText())
    }
  }

  private fun layers() = """<Config><Variables>
    <Variable name="layer0" value="0"/><Variable name="layer1" value="1"/>
    <Variable name="layer_only_quiet0" value="true"/><Variable name="layer_only_fancy1" value="true"/>
    </Variables></Config>""".toByteArray()

  private val weatherImages = listOf("bg.png") + (0..9).map { "num_$it.png" } +
    listOf("num_-.png", "num_\u00b0.png")

  private fun weather() = """<Icon version="1" width="351" height="351" frameRate="0" useVariableUpdater="DateTime.Minute" screenWidth="1080">
    <VariableBinders><ContentProviderBinder name="weather_provider" uri="content://weather/actualWeatherData/4" columns="temperature" countName="hasweather">
      <Variable name="temperature" type="int" column="temperature"/>
      <Trigger><VariableCommand name="success_time" type="number" expression="#time_sys"/></Trigger>
    </ContentProviderBinder></VariableBinders>
    <ExternalCommands><Trigger action="init,resume">
      <VariableCommand name="update_weather_time" type="number" expression="#time_sys"/>
      <MultiCommand condition="(#time_sys - #success_time) } 600000">
        <BinderCommand name="weather_provider" command="refresh" delay="550" delayCondition="(#time_sys - #update_weather_time) } 500"/>
      </MultiCommand>
    </Trigger></ExternalCommands>
    <Image x="351 / 2" y="351 / 2" align="center" alignV="center" src="bg.png"/>
    <Group visibility="#hasweather"><ImageChars x="351 / 2 - 84" y="351 / 2 - 102" src="num.png" string="#temperature + '&#176;'"/></Group>
    </Icon>""".toByteArray()
}
