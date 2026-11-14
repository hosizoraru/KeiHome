package os.kei.hyper.home.iconpack.perfect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackExporter
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconPackStore
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackImportResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.RuntimeStatusProtocol
import os.kei.hyper.home.ui.main.HomeViewModel

/** Opt-in use of retained upstream inputs; no network or user theme changes. */
class PerfectDynamicNightSourceDeviceTest {
  companion object {
    private var preparedFile: File? = null
  }

  @Before fun prepareCurrentModule() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    if (preparedFile?.isFile == true) return@runBlocking
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val repository = PerfectIconRepository(context)
    assertNotNull("Acceptance requires the retained local snapshot; never download it", repository.catalog())
    val names = buildSet {
      context.getSystemService(android.content.pm.LauncherApps::class.java)
        .getActivityList(null, android.os.Process.myUserHandle()).forEach {
          add(it.componentName.packageName); add(it.componentName.className)
          add(it.componentName.flattenToString())
        }
      context.packageManager.getInstalledApplications(
        android.content.pm.PackageManager.ApplicationInfoFlags.of(0)).forEach { add(it.packageName) }
      addAll(retainedDynamicBlobs().keys)
    }
    val options = PerfectIconOptions("HyperOS4", installedOnly = true, dynamic = true, staticNight = true)
    val first = repository.generate(options, names) {}
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val firstHash = digest.digest(first.file.readBytes())
    val repeated = repository.generate(options, names) {}
    assertEquals("Repeated generation reuses its publication", first.file, repeated.file)
    assertArrayEquals(firstHash, digest.digest(repeated.file.readBytes()))
    val root = File(context.cacheDir, "perfect-dynamic-night-probe").apply { mkdirs() }
    first.file.copyTo(File(root, "generated.zip"), overwrite = true)
    File(root, "publication.json").writeText(JSONObject().put("file", first.file.name)
      .put("sha256", firstHash.joinToString("") { "%02x".format(it.toInt() and 255) })
      .put("static", first.staticCount).put("dynamic", first.dynamicCount)
      .put("darkDynamic", first.derivedDynamicCount).put("reused", repeated.reused)
      .put("nightRevision", first.nightRevision).toString(2))
    preparedFile = first.file
  }

  @Test fun generatedModuleMeetsNativeAdmission() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    os.kei.hyper.home.xposed.LocalNativeIconValidator.validate(
      File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip"))
  }

  @Test fun generatedCanonicalWeatherKeepsItsCompleteOriginalAndNightGlyphClosures() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip")).use { zip ->
      val paths = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
      fun read(path: String) = zip.getInputStream(requireNotNull(zip.getEntry(path)) { path })
        .use { it.readBytes() }
      val base = "layer_animating_icons/com.miui.weather2/1/fancy/"
      val published = requireNotNull(NativeWeatherLayerProgram.resources(
        read(base + "manifest.xml"), base, paths))
      assertEquals(26, published.size)
      val day = published.filterNot { it.startsWith(base + "keihome_night/") }
      assertEquals(13, day.size)
      for (path in day) {
        PerfectIconFormat.raster(read(path))
        val selected = read(base + "keihome_night/" + path.removePrefix(base))
        PerfectIconFormat.raster(selected)
        assertArrayEquals(selected, read("nightmode/$path"))
      }
      assertEquals(13, requireNotNull(NativeWeatherLayerProgram.resources(
        read("nightmode/$base" + "manifest.xml"), "nightmode/$base", paths)).size)
      val updater = PerfectIconFormat.xml(read(base + "manifest.xml")).elements.first()
        .attributes.getValue("useVariableUpdater")
      assertEquals("DateTime.Minute,DarkMode", updater)
    }
  }

  @Test fun retainedWeatherSelectsOrderedFullCanvasQuietLayers() {
    val originals = retainedDynamicBytes("com.miui.weather2")
    val group = requireNotNull(classify(originals))
    assertEquals("quiet/quietImage.png", group.compositeQuiet)
    assertEquals(listOf("0/quiet/quietImage.png", "1/quiet/quietImage.png"),
      group.compositeQuietLayers)
    assertEquals(group.bgAssets + group.foreground + "quiet/quietImage.png", group.nightPaths)
    val config = requireNotNull(NativeLayerConfig.parse(originals.getValue("config.xml"), IconPackLimits()))
    assertEquals(setOf("0"), config.quietOnly)
    assertEquals(setOf("1"), config.fancyOnly)
    assertEquals(13, requireNotNull(NativeWeatherLayerProgram.resources(
      originals.getValue("1/fancy/manifest.xml"), "1/fancy/", originals.keys)).size)
    for (directory in listOf("0", "1")) {
      assertArrayEquals(directory, originals.getValue("$directory/fancy/bg.png"),
        originals.getValue("$directory/quiet/quietImage.png"))
    }
    assertFalse("The baked daytime root is not the raw native sky layer",
      originals.getValue("quiet/quietImage.png").contentEquals(
        originals.getValue("0/quiet/quietImage.png")))

    for (path in group.compositeQuietLayers + requireNotNull(group.compositeQuiet)) {
      assertEquals(path, 351 to 351, imageDimensions(originals.getValue(path)))
    }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip")).use { zip ->
      for (directory in listOf("0", "1")) {
        val path = "$directory/fancy/manifest.xml"
        assertWeatherCanvas(path, originals.getValue(path))
        for (prefix in listOf("layer_animating_icons/com.miui.weather2/",
          "nightmode/layer_animating_icons/com.miui.weather2/")) {
          assertWeatherCanvas(prefix + path, zipBytes(zip, prefix + path))
        }
      }
    }
  }

  @Test fun generatedWeatherRootNightUsesNativeQuietCompositeWithoutBlueShard() {
    val originals = retainedDynamicBytes("com.miui.weather2")
    val layerPaths = listOf("0/quiet/quietImage.png", "1/quiet/quietImage.png")
    val layers = layerPaths.map(originals::getValue)
    val expected = PerfectStaticNightRenderer.render(layers, null, nativeViewport = true).night
    val flat = PerfectStaticNightRenderer.render(
      listOf(originals.getValue("quiet/quietImage.png")), null) {}.night
    assertFalse("The real weather fixture must distinguish flat segmentation from composition",
      flat.contentEquals(expected))
    assertArrayEquals("Repeated rendering must not retain the previous flat conversion",
      expected, PerfectStaticNightRenderer.render(layers, null, nativeViewport = true).night)

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip")).use { zip ->
      val prefix = "layer_animating_icons/com.miui.weather2/"
      val actual = zipBytes(zip, "nightmode/${prefix}quiet/quietImage.png")
      assertArrayEquals(expected, actual)
      assertArrayEquals("Native daytime root quiet must remain byte-identical",
        originals.getValue("quiet/quietImage.png"), zipBytes(zip, prefix + "quiet/quietImage.png"))
      for (path in originals.keys.filter { it.endsWith(".png") }) {
        assertArrayEquals("Original weather artwork: $path", originals.getValue(path),
          zipBytes(zip, prefix + path))
      }
      val night = requireNotNull(BitmapFactory.decodeByteArray(actual, 0, actual.size))
      val generic = requireNotNull(BitmapFactory.decodeByteArray(flat, 0, flat.size))
      val foregroundBytes = originals.getValue("1/quiet/quietImage.png")
      val foreground = requireNotNull(BitmapFactory.decodeByteArray(
        foregroundBytes, 0, foregroundBytes.size))
      try {
        // These sky samples exposed the cyan shard in the retained 351px source.
        val viewport = weatherNativeViewportBounds()
        val skySamples = listOf(212 to 212, 256 to 212, 336 to 190).filter { (x, y) ->
          Color.alpha(weatherSourcePixel(foreground, viewport, x, y)) == 0
        }
        assertTrue("Keep shard probes outside the enlarged native cloud", skySamples.size >= 2)
        for ((x, y) in skySamples) {
          val previous = generic.getPixel(x, y)
          assertTrue("The flat control must reproduce the blue shard at $x,$y",
            Color.blue(previous) - Color.red(previous) > 100)
          val pixel = night.getPixel(x, y)
          assertTrue("Sky coverage at $x,$y", Color.alpha(pixel) >= 254)
          assertEquals("Neutral sky at $x,$y", Color.red(pixel), Color.green(pixel))
          assertEquals("Neutral sky at $x,$y", Color.red(pixel), Color.blue(pixel))
          assertTrue("Dark sky at $x,$y", Color.red(pixel) < 64)
        }
      } finally {
        night.recycle()
        generic.recycle()
        foreground.recycle()
      }
    }
  }

  @Test fun generatedWeatherCloudMatchesFrameworkNativeViewportLandmarks() {
    val originals = retainedDynamicBytes("com.miui.weather2")
    val layers = listOf("0/quiet/quietImage.png", "1/quiet/quietImage.png")
      .map(originals::getValue)
    val foregroundBytes = layers[1]
    val foreground = requireNotNull(BitmapFactory.decodeByteArray(
      foregroundBytes, 0, foregroundBytes.size))
    val uncroppedBytes = PerfectStaticNightRenderer.render(layers, null).night
    val uncropped = requireNotNull(BitmapFactory.decodeByteArray(
      uncroppedBytes, 0, uncroppedBytes.size))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    try {
      ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip")).use { zip ->
        val actual = zipBytes(zip,
          "nightmode/layer_animating_icons/com.miui.weather2/quiet/quietImage.png")
        val night = requireNotNull(BitmapFactory.decodeByteArray(actual, 0, actual.size))
        try {
          assertEquals(512, night.width)
          assertEquals(512, night.height)
          val viewport = weatherNativeViewportBounds()
          assertTrue("Native layers extend past the icon viewport",
            viewport.left < 0 && viewport.top < 0 && viewport.right > 512 && viewport.bottom > 512)
          assertEquals(256, viewport.centerX())
          assertEquals(256, viewport.centerY())
          // Fixed left-cloud landmarks are opaque in the native crop and sky in the old
          // full-square rendering. The edge sample also rejects an early adaptive mask.
          for ((x, y) in listOf(0 to 480, 16 to 480, 40 to 420)) {
            val source = weatherSourcePixel(foreground, viewport, x, y)
            assertTrue("Native source must contain cloud at $x,$y", Color.alpha(source) >= 250)
            assertEquals("The old uncropped source misses that cloud at $x,$y", 0,
              Color.alpha(weatherSourcePixel(foreground, Rect(0, 0, 512, 512), x, y)))
            val previous = uncropped.getPixel(x, y)
            assertTrue("Uncropped control remains neutral sky at $x,$y",
              kotlin.math.abs(Color.blue(previous) - Color.red(previous)) <= 2)
            val pixel = night.getPixel(x, y)
            assertTrue("Native cloud must reach its desktop anchor at $x,$y",
              Color.blue(pixel) - Color.red(pixel) > 48)
            assertTrue("Cloud coverage must survive without an early mask at $x,$y",
              Color.alpha(pixel) >= 250)
          }
          for ((x, y) in listOf(16 to 256, 256 to 212)) {
            assertEquals("Source anchor remains above the cloud at $x,$y", 0,
              Color.alpha(weatherSourcePixel(foreground, viewport, x, y)))
            val pixel = night.getPixel(x, y)
            assertEquals("Sky anchor must remain neutral at $x,$y",
              Color.red(pixel), Color.blue(pixel))
            assertTrue("Sky anchor must remain dark at $x,$y", Color.red(pixel) < 64)
          }
        } finally { night.recycle() }
      }
    } finally {
      foreground.recycle()
      uncropped.recycle()
    }
  }

  @Test fun weatherAliasKeepsTheSameRootCompositeAndNativeDayArtwork() {
    val originals = retainedDynamicBytes("com.miui.weather2")
    val canonical = requireNotNull(classify(originals))
    val aliases = originals.mapValues { (path, bytes) ->
      val source = "themes/HyperOS4/layer_animating_icons/org.example.weather.Alias/$path"
      PerfectIconBlob(path, PerfectIconFormat.sha(bytes), bytes.size.toLong(), source)
    }
    val alias = requireNotNull(PerfectDynamicNight.group(aliases, { originals.getValue(it.path) }))
    assertEquals(canonical.compositeQuietLayers, alias.compositeQuietLayers)
    val canonicalNight = renderNight(canonical, originals)
    val aliasNight = renderNight(alias, originals)
    val root = "quiet/quietImage.png"
    assertArrayEquals(canonicalNight.getValue(root), aliasNight.getValue(root))
    val live = requireNotNull(PerfectDynamicNightSwitch.systemFiles(alias, originals, aliasNight))
    assertArrayEquals(originals.getValue(root), live.getValue(root))
    for (path in originals.keys.filter { it.endsWith(".png") }) {
      assertArrayEquals(path, originals.getValue(path), live.getValue(path))
    }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip")).use { zip ->
      assertArrayEquals(aliasNight.getValue(root),
        zipBytes(zip, "nightmode/layer_animating_icons/com.miui.weather2/$root"))
    }
  }

  @Test fun unknownWeatherSchemaAndExtraPathsCannotAcquireCompositeAdmission() {
    val originals = retainedDynamicBytes("com.miui.weather2")
    assertFalse(requireNotNull(classify(originals)).compositeQuietLayers.isEmpty())
    val path = "1/fancy/manifest.xml"
    val manifest = originals.getValue(path).toString(Charsets.UTF_8)
    val unknown = manifest.replace("content://weather/actualWeatherData/4",
      "content://weather/actualWeatherData/5")
    assertFalse(manifest == unknown)
    assertNull(classify(originals + (path to unknown.toByteArray(Charsets.UTF_8))))
    for (extraPath in listOf("quiet/extra.png", "2/quiet/quietImage.png")) {
      assertNull(extraPath, classify(originals +
        (extraPath to originals.getValue("1/quiet/quietImage.png"))))
    }
    assertNull("Unknown weather XML must still reject the entire group",
      classify(originals + ("1/fancy/extra.xml" to "<Extra/>".toByteArray(Charsets.UTF_8))))
    val extra = originals + ("1/fancy/extra.png" to originals.getValue("1/fancy/bg.png"))
    val group = requireNotNull(classify(extra))
    assertNull("An extra weather texture must not escape the exact MAML closure",
      PerfectDynamicNightSwitch.systemFiles(group, extra, renderNight(group, extra)))
    val valid = requireNotNull(classify(originals))
    assertNull("A stale layer order must be revalidated against the actual source bytes",
      PerfectDynamicNightSwitch.systemFiles(
        valid.copy(compositeQuietLayers = valid.compositeQuietLayers.reversed()),
        originals, renderNight(valid, originals)))
  }

  @Test fun weatherQuietCompositionFallsBackUnlessEveryQuietIsTheAuthoredSquarePng() {
    val originals = retainedDynamicBytes("com.miui.weather2")
    val paths = listOf("quiet/quietImage.png", "0/quiet/quietImage.png", "1/quiet/quietImage.png")
    assertEquals(paths.drop(1), requireNotNull(classify(originals)).compositeQuietLayers)
    for (path in paths) {
      for ((width, height) in listOf(350 to 350, 350 to 351, 351 to 350)) {
        val resized = resizedImage(originals.getValue(path), width, height)
        val changed = originals.toMutableMap().apply {
          put(path, resized)
          if (path != "quiet/quietImage.png") {
            put("${path.substringBefore('/')}/fancy/bg.png", resized)
          }
        }
        val fallback = requireNotNull(classify(changed))
        assertWeatherRootRetained("$path at ${width}x$height", fallback)
      }
      val jpeg = resizedImage(originals.getValue(path), 351, 351, Bitmap.CompressFormat.JPEG)
      val changed = originals.toMutableMap().apply {
        put(path, jpeg)
        if (path != "quiet/quietImage.png") {
          put("${path.substringBefore('/')}/fancy/bg.png", jpeg)
        }
      }
      assertEquals(path, 351 to 351, imageDimensions(jpeg))
      assertWeatherRootRetained("$path is not PNG even though its size and counterpart match",
        requireNotNull(classify(changed)))
    }
    val config = originals.getValue("config.xml").toString(Charsets.UTF_8)
    val threeLayers = config.replace("</Variables>", """
      <Variable name="layer2" type="string" value="2"/>
      <Variable name="layer_only_quiet2" type="string" value="true"/>
      </Variables>""")
    assertWeatherRootRetained("An extra native layer is not the canonical weather composition",
      requireNotNull(classify(originals + mapOf(
        "config.xml" to threeLayers.toByteArray(Charsets.UTF_8),
        "2/quiet/quietImage.png" to originals.getValue("1/quiet/quietImage.png"),
      ))))
    assertWeatherRootRetained("Missing root quiet must not invent a root conversion",
      requireNotNull(classify(originals - "quiet/quietImage.png")))
    assertWeatherRootRetained("Missing foreground quiet prevents canonical composition",
      requireNotNull(classify(originals - "1/quiet/quietImage.png")))
  }

  @Test fun weatherQuietCompositionRequiresMatchingNativeArtworkAndCenteredBackground() {
    val originals = retainedDynamicBytes("com.miui.weather2")
    assertFalse(requireNotNull(classify(originals)).compositeQuietLayers.isEmpty())
    for (directory in listOf("0", "1")) {
      for (path in listOf("$directory/quiet/quietImage.png", "$directory/fancy/bg.png")) {
        val changed = originals + (path to changedPixelPng(originals.getValue(path)))
        assertFalse("Mutation must change $path",
          originals.getValue(path).contentEquals(changed.getValue(path)))
        assertEquals(path, 351 to 351, imageDimensions(changed.getValue(path)))
        val fallback = requireNotNull(classify(changed))
        assertWeatherRootRetained("$path no longer matches its native counterpart", fallback)
        if (path == "1/quiet/quietImage.png") {
          val night = renderNight(fallback, changed)
          val live = requireNotNull(PerfectDynamicNightSwitch.systemFiles(fallback, changed, night))
          val root = "quiet/quietImage.png"
          assertFalse("Retained weather root must not be rendered", root in night)
          assertArrayEquals("Live namespace retains the authored root",
            originals.getValue(root), live.getValue(root))
          assertArrayEquals("Standalone night namespace retains the authored root",
            originals.getValue(root), (changed + night).getValue(root))
        }
      }
    }
    val path = "0/fancy/manifest.xml"
    val manifest = originals.getValue(path).toString(Charsets.UTF_8)
    for ((before, after) in listOf(
      "width=\"351\"" to "width=\"350\"",
      "height=\"351\"" to "height=\"350\"",
      "x=\"351 / 2\"" to "x=\"0\"",
      "y=\"351 / 2\"" to "y=\"0\"",
      "align=\"center\"" to "align=\"left\"",
      "alignV=\"center\"" to "alignV=\"top\"",
    )) {
      val changed = manifest.replace(before, after)
      assertFalse("Mutation must change $before in the real background program", manifest == changed)
      val fallback = requireNotNull(classify(originals +
        (path to changed.toByteArray(Charsets.UTF_8))))
      assertWeatherRootRetained("$after invalidates the canonical layout", fallback)
    }
  }

  @Test fun weatherQuietCompositionRequiresAuthoredQuietOnlyAndFancyOnlyRoles() {
    val originals = retainedDynamicBytes("com.miui.weather2")
    val config = originals.getValue("config.xml").toString(Charsets.UTF_8)
    assertEquals(listOf("0/quiet/quietImage.png", "1/quiet/quietImage.png"),
      requireNotNull(classify(originals)).compositeQuietLayers)
    for (flag in listOf("layer_only_quiet0", "layer_only_fancy1")) {
      val changed = Regex("""(name="$flag"[^>]*value=")true(")""").replace(config) {
        "${it.groupValues[1]}false${it.groupValues[2]}"
      }
      assertFalse("Mutation must disable the real $flag", config == changed)
      val fallback = requireNotNull(classify(originals +
        ("config.xml" to changed.toByteArray(Charsets.UTF_8))))
      assertWeatherRootRetained("$flag is required for safe root resynthesis", fallback)
    }
  }

  @Test fun rendersEveryAdmittedNativeLayerAndBuildsSafeSystemSwitches() {
    val sources = retainedDynamicBlobs()
    assertTrue("The opt-in catalog must contain dynamic source groups", sources.isNotEmpty())
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    var derived = 0
    ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip")).use { zip ->
      for ((name, blobs) in sources) {
        val originals = retainedBytes(blobs)
        val prefix = "layer_animating_icons/$name/"
        val group = PerfectDynamicNight.group(blobs, { originals.getValue(it.path) })
        if (group == null) {
          for ((path, bytes) in originals) assertArrayEquals(prefix + path,
            bytes, zipBytes(zip, prefix + path))
          assertFalse("Rejected $name must not publish a partial night closure",
            zip.entries().asSequence().any { it.name.startsWith("nightmode/$prefix") })
          continue
        }
        val converted = renderNight(group, originals)
        assertEquals(name, group.nightPaths, converted.keys)
        val live = requireNotNull(PerfectDynamicNightSwitch.systemFiles(group, originals, converted)) {
          "Admitted source group must retain safe native selectors: $name"
        }
        for ((path, bytes) in live) assertArrayEquals(prefix + path,
          bytes, zipBytes(zip, prefix + path))
        for ((path, bytes) in originals) {
          assertArrayEquals("Complete standalone night closure: $prefix$path",
            converted[path] ?: bytes, zipBytes(zip, "nightmode/$prefix$path"))
          if (path.endsWith(".png")) assertArrayEquals("Native day artwork: $prefix$path",
            bytes, zipBytes(zip, prefix + path))
        }
        derived++
      }
    }
    assertTrue("No retained dynamic source group was rendered", derived > 0)
    InstrumentationRegistry.getArguments().getString("perfectExpectedDerived")?.toInt()?.let {
      assertEquals("All retained groups must be probed", it, derived)
    }
  }

  @Test fun generatedCalendarAndClockKeepDateFramesNativeFunctionsAndCompositeFallbacks() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip")).use { zip ->
      fun read(path: String) = zip.getInputStream(requireNotNull(zip.getEntry(path)) { path })
        .use { it.readBytes() }
      val calendar = "layer_animating_icons/com.android.calendar/"
      val date = PerfectIconFormat.xml(read(calendar + "1/fancy/manifest.xml"))
        .elements.single { it.name == "Image" }.attributes
      assertEquals("#date", date["srcid"])
      assertEquals("num.png", date["src"])
      assertEquals("ifelse(#__darkmode,'keihome_night/num.png','num.png')", date["srcExp"])
      for (day in 1..31) {
        val path = "1/fancy/num_$day.png"
        PerfectIconFormat.raster(read(calendar + path))
        val selected = read(calendar + "1/fancy/keihome_night/num_$day.png")
        PerfectIconFormat.raster(selected)
        assertArrayEquals(selected, read("nightmode/$calendar$path"))
      }
      val weekday = PerfectIconFormat.xml(read(calendar + "2/fancy/manifest.xml"))
      assertEquals("#3482FF", weekday.elements.single { it.name == "Text" }.attributes["color"])
      val clock = "layer_animating_icons/com.android.deskclock/"
      val program = PerfectIconFormat.xml(read(clock + "1/fancy/manifest.xml"))
      assertTrue(program.elements.filter { it.name == "Function" }.map { it.attributes["name"] }
        .containsAll(listOf("start", "end", "fra")))
      assertEquals(setOf("'DateTime.Second'", "'DateTime.Minute'"),
        program.elements.filter { it.name == "ExternCommand" }.map {
          assertEquals("__datatimeVarUpdate", it.attributes["command"])
          it.attributes["strPara"]
        }.toSet())
      for (prefix in listOf(calendar, clock)) {
        val rootQuiet = read(prefix + "quiet/quietImage.png")
        val nightQuiet = read("nightmode/$prefix" + "quiet/quietImage.png")
        val originals = retainedDynamicBytes(prefix.removePrefix("layer_animating_icons/").removeSuffix("/"))
        val group = requireNotNull(classify(originals))
        assertArrayEquals(originals.getValue("quiet/quietImage.png"), rootQuiet)
        assertArrayEquals(renderRootQuietNight(group, originals), nightQuiet)
        for (path in listOf("0/fancy/manifest.xml", "1/fancy/manifest.xml")) {
          val updater = PerfectIconFormat.xml(read(prefix + path)).elements.first()
            .attributes.getValue("useVariableUpdater").split(",")
          assertEquals(1, updater.count { it == "DarkMode" })
        }
      }
    }
  }

  @Test fun generatedCalendarRootNightHasCleanNeutralDateAndBlueWeekday() {
    val originals = retainedDynamicBytes("com.android.calendar")
    val group = requireNotNull(classify(originals))
    assertTrue("Calendar keeps its authored flat preview geometry", group.compositeQuietLayers.isEmpty())
    assertEquals(listOf("0/quiet/quietImage.png", "1/quiet/quietImage.png"),
      group.compositeQuietProofLayers)
    val rootBytes = originals.getValue("quiet/quietImage.png")
    val foregroundBytes = originals.getValue("1/quiet/quietImage.png")
    val baseline = PerfectStaticNightRenderer.render(listOf(rootBytes), null)
    val owned = mutableListOf<Bitmap>()
    fun decode(bytes: ByteArray) = requireNotNull(BitmapFactory.decodeByteArray(
      bytes, 0, bytes.size)).also { owned += it }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    try {
      val day = decode(baseline.day)
      val previousNight = decode(baseline.night)
      val foreground = decode(foregroundBytes)
      val foreground512 = Bitmap.createScaledBitmap(foreground, 512, 512, true)
        .also { if (it !== foreground) owned += it }
      ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip")).use { zip ->
        val prefix = "layer_animating_icons/com.android.calendar/"
        assertArrayEquals("Native Calendar root day artwork must remain untouched",
          rootBytes, zipBytes(zip, prefix + "quiet/quietImage.png"))
        val actual = zipBytes(zip, "nightmode/${prefix}quiet/quietImage.png")
        assertArrayEquals(renderRootQuietNight(group, originals), actual)
        val night = decode(actual)
        assertEquals(512, night.width)
        assertEquals(512, night.height)
        val dayPixels = IntArray(512 * 512)
        val previousPixels = IntArray(dayPixels.size)
        val foregroundPixels = IntArray(dayPixels.size)
        val nightPixels = IntArray(dayPixels.size)
        day.getPixels(dayPixels, 0, 512, 0, 0, 512, 512)
        previousNight.getPixels(previousPixels, 0, 512, 0, 0, 512, 512)
        foreground512.getPixels(foregroundPixels, 0, 512, 0, 0, 512, 512)
        night.getPixels(nightPixels, 0, 512, 0, 0, 512, 512)
        var datePixels = 0
        var illegibleDatePixels = 0
        var weekdayPixels = 0
        var invalidWeekdayHue = 0
        var whiteWeekdayFringe = 0
        var darkestDateCore = 255
        var lightestDateCore = 0
        var changedAlphaPixels = 0
        for (i in dayPixels.indices) {
          val dayPixel = dayPixels[i]
          val fg = foregroundPixels[i]
          val pixel = nightPixels[i]
          if (Color.alpha(pixel) != Color.alpha(previousPixels[i])) changedAlphaPixels++
          // Opaque neutral date cores are independently selected from the actual native
          // foreground and original flat day, rather than from the repaired output.
          if (Color.alpha(fg) >= 250 &&
            maxOf(Color.red(fg), Color.green(fg), Color.blue(fg)) <= 96 &&
            maxOf(Color.red(dayPixel), Color.green(dayPixel), Color.blue(dayPixel)) <= 96) {
            datePixels++
            darkestDateCore = minOf(darkestDateCore, Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            lightestDateCore = maxOf(lightestDateCore, Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            if (minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) < 128) {
              illegibleDatePixels++
            }
          }
          // Select the authored blue text independently, including its pale source fringes.
          if (Color.alpha(fg) == 0 &&
            Color.blue(dayPixel) - Color.red(dayPixel) > 8 &&
            Color.blue(dayPixel) - Color.green(dayPixel) > 4 &&
            minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 128 &&
            maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
              minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) < 20) {
            whiteWeekdayFringe++
          }
          if (Color.alpha(fg) == 0 && Color.alpha(dayPixel) >= 250 &&
            Color.blue(dayPixel) - Color.red(dayPixel) > 96 &&
            Color.blue(dayPixel) - Color.green(dayPixel) > 32) {
            weekdayPixels++
            if (Color.blue(pixel) - Color.red(pixel) <= 48 ||
              Color.blue(pixel) - Color.green(pixel) <= 16) invalidWeekdayHue++
          }
        }
        assertTrue("Actual Calendar root must contain opaque black date artwork", datePixels > 1000)
        assertEquals("Every proven black date core must be legible at night", 0, illegibleDatePixels)
        assertTrue("Actual Calendar root must include its unmodeled blue weekday", weekdayPixels > 100)
        assertTrue("Neutral date interiors must not retain inverted gray contours",
          lightestDateCore - darkestDateCore <= 12)
        assertEquals("Weekday must retain its blue hue", 0, invalidWeekdayHue)
        assertEquals("Weekday must not retain a pale neutral matte", 0, whiteWeekdayFringe)
        assertEquals("Foreground repair must retain all caller alpha", 0, changedAlphaPixels)
      }
    } finally { owned.forEach { it.recycle() } }
  }

  @Test fun recordsRuntimeIdentityForOptInAcceptance() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    XposedServiceHelper.registerListener(FrameworkConnection)
    val service = withTimeoutOrNull(10_000) { FrameworkConnection.service.filterNotNull().first() }
    val report = service?.let { RuntimeStatusProtocol.query(it, includeIconTheme = true) }
    val identity = JSONObject()
      .put("installedVersion", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
      .put("runtimeVersion", report?.moduleVersion ?: -1)
      .put("scope", service?.scope?.toString() ?: "unavailable")
    File(context.cacheDir, "perfect-dynamic-night-probe/runtime-identity.json").writeText(identity.toString(2))
    assertEquals("Current runtime must answer before import acceptance",
      identity.getLong("installedVersion"), identity.getLong("runtimeVersion"))
    val store = ViewModelStore()
    try {
      val model = withContext(Dispatchers.Main) {
        ViewModelProvider(object : ViewModelStoreOwner { override val viewModelStore = store },
          ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as android.app.Application))
          .get(HomeViewModel::class.java).also { it.refresh(3) }
      }
      delay(12_000)
      val packs = model.iconPacks.value
      identity.put("framework", model.framework.value.toString())
        .put("runtime", model.runtime.value.toString())
        .put("preparing", packs.preparing).put("importing", packs.importing)
        .put("nativeImporting", packs.nativeImporting)
      File(context.cacheDir, "perfect-dynamic-night-probe/runtime-identity.json").writeText(identity.toString(2))
      if (InstrumentationRegistry.getArguments().getString("perfectDynamicApply") == "true") {
        val importedModule = IconPackRepository(context).importPerfectPublication(
          requireNotNull(preparedFile), "Perfect dynamic night QA.zip")
        var pack = requireNotNull((importedModule as? IconPackImportResult.Success)?.pack) {
          importedModule.toString()
        }
        if (InstrumentationRegistry.getArguments().getString("perfectDynamicProbe") == "true") {
          val probe = File(context.filesDir, "perfect-icons/generated/perfect-qa-music.zip")
          val prefix = "themes/HyperOS4/layer_animating_icons/com.miui.player/"
          ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/converted.zip")).use { converted ->
            ZipOutputStream(probe.outputStream()).use { output ->
              output.putNextEntry(ZipEntry("transform_config.xml"))
              output.write(HyperOs4IconModule.transform(true, null)); output.closeEntry()
              for (entry in converted.entries().asSequence().filter { !it.isDirectory }) {
                val night = entry.name.startsWith("nightmode/")
                val name = entry.name.removePrefix("nightmode/")
                if (!name.startsWith(prefix)) continue
                val bytes = converted.getInputStream(entry).use { it.readBytes() }
                for (target in listOf("com.android.settings", "com.android.settings.MainSettings")) {
                  val targetPath = (if (night) "nightmode/" else "") +
                    "layer_animating_icons/$target/" + name.removePrefix(prefix)
                  output.putNextEntry(ZipEntry(targetPath)); output.write(bytes); output.closeEntry()
                }
              }
            }
          }
          val imported = IconPackRepository(context).importPerfectPublication(probe, "KeiHome Music night QA.zip")
          pack = requireNotNull((imported as? IconPackImportResult.Success)?.pack) { imported.toString() }
        }
        val previous = model.iconPacks.value.nativeResult?.token
        withContext(Dispatchers.Main) { model.importNativeIconPack(pack) }
        val completed = withTimeout(180_000) {
          model.iconPacks.first { state ->
            state.nativeResult?.let { it.token != previous && !it.pending } == true
          }
        }.nativeResult!!
        identity.put("importResult", JSONObject(completed.encode()))
        File(context.cacheDir, "perfect-dynamic-night-probe/runtime-identity.json").writeText(identity.toString(2))
        assertTrue(completed.encode(), completed.imported && completed.convertedReady == true)
      }
    } finally { withContext(Dispatchers.Main) { store.clear() } }
  }

  @Test fun generatedLayeredNightExportsWithoutBeingFlattenedAsMonet() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "perfect-dynamic-night-probe")
    val source = File(root, "generated.zip")
    assertTrue(source.isFile)
    val work = File(root, "export-regression").apply { deleteRecursively(); mkdirs() }
    try {
      val record = source.inputStream().use { IconPackStore(File(work, "store")).import(it, "layered.zip").record }
      val index = IconPackArchive().inspect(record.originalFile, File(work, "inspect"))
      val pack = IconPackDescriptor(record.id, "Layered night", record.format, IconPackSource.Imported(record), index.entries)
      val targets = index.entries.mapNotNull { it.nativeName }.map { IconPackComponent(it) }
      val output = IconPackExporter(context, IconPackLimits()).export(pack, targets, true, 192) {}
      try {
        ZipFile(source).use { original ->
          ZipFile(output.archive).use { exported ->
            val groups = original.entries().asSequence().filter {
              it.name.startsWith("layer_animating_icons/") && "/keihome_night/" in it.name
            }.map { it.name.split('/')[1] }.toSet()
            assertTrue(groups.isNotEmpty())
            val resources = original.entries().asSequence().filter {
              !it.isDirectory && it.name.startsWith("layer_animating_icons/") &&
                it.name.split('/')[1] in groups
            }.toList()
            for (entry in resources) {
              val actual = requireNotNull(exported.getEntry(entry.name))
              assertArrayEquals(entry.name, original.getInputStream(entry).use { it.readBytes() },
                exported.getInputStream(actual).use { it.readBytes() })
            }
            assertTrue("Layered programs keep their animation owners",
              groups.none { it in output.fallbackPaletteNames })
          }
        }
      } finally { output.archive.delete() }
    } finally { work.deleteRecursively() }
  }

  @Test fun retainedBitmapProgramsProduceCompleteNightResourceClosures() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "perfect-dynamic-night-probe")
    val sources = retainedDynamicBlobs()
    assertTrue(sources.isNotEmpty())
    val results = JSONArray()
    ZipOutputStream(File(root, "converted.zip").outputStream()).use { output ->
      for ((id, blobs) in sources.toSortedMap()) {
        val prefix = "layer_animating_icons/$id/"
        val bytes = retainedBytes(blobs)
        val record = JSONObject().put("prefix", prefix)
        val group = PerfectDynamicNight.group(blobs, { bytes.getValue(it.path) })
        if (group == null) {
          record.put("status", "original-structure")
        } else {
          val converted = linkedMapOf<String, ByteArray>()
          for (name in group.bgAssets + group.foreground) {
            val image = PerfectDynamicNightRenderer.render(bytes.getValue(name), name in group.bgAssets) {}
              ?: break
            converted[name] = image
          }
          if (converted.size != group.bgAssets.size + group.foreground.size) record.put("status", "original-pixels")
          else {
            group.compositeQuiet?.let { path ->
              converted[path] = renderRootQuietNight(group, bytes)
            }
            assertEquals(group.nightPaths, converted.keys)
            val live = PerfectDynamicNightSwitch.systemFiles(group, bytes, converted)
            if (live == null) {
              record.put("status", "original-selector")
              results.put(record)
              continue
            }
            record.put("status", "derived").put("textures", converted.size)
              .put("backgroundAssets", JSONArray(group.bgAssets.toList()))
              .put("compositeQuiet", group.compositeQuiet ?: JSONObject.NULL)
              .put("compositeQuietLayers", JSONArray(group.compositeQuietLayers))
              .put("compositeQuietProofLayers", JSONArray(group.compositeQuietProofLayers))
            for ((name, image) in live) {
              output.putNextEntry(ZipEntry(prefix + name))
              output.write(image)
              output.closeEntry()
            }
            for ((name, original) in bytes) {
              output.putNextEntry(ZipEntry("nightmode/$prefix$name"))
              output.write(converted[name] ?: original)
              output.closeEntry()
            }
          }
        }
        results.put(record)
      }
    }
    File(root, "results.json").writeText(results.toString(2))
    assertTrue("No real-source dynamic group qualified: $results",
      (0 until results.length()).any { results.getJSONObject(it).getString("status") == "derived" })
    InstrumentationRegistry.getArguments().getString("perfectExpectedDerived")?.toInt()?.let { expected ->
      assertEquals(expected, (0 until results.length()).count {
        results.getJSONObject(it).optString("status") == "derived"
      })
    }
    (0 until results.length()).map { results.getJSONObject(it) }
      .firstOrNull { it.getString("prefix") == "layer_animating_icons/com.miui.player/" }
      ?.let { music ->
        assertEquals("Unmodified upstream Music must support live selection", "derived", music.getString("status"))
      }
  }

  private fun retainedDynamicBlobs(): Map<String, Map<String, PerfectIconBlob>> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.filesDir, "perfect-icons")
    val rows = JSONObject(File(root, "catalog.json").readText()).getJSONArray("entries")
    val selected = linkedMapOf<String, Map<String, PerfectIconBlob>>()
    for (style in listOf("common", "HyperOS4")) {
      val prefix = "themes/$style/layer_animating_icons/"
      val groups = linkedMapOf<String, MutableMap<String, PerfectIconBlob>>()
      for (index in 0 until rows.length()) {
        val row = rows.getJSONArray(index)
        val source = row.getString(0)
        if (!source.startsWith(prefix)) continue
        val relative = source.removePrefix(prefix)
        val name = relative.substringBefore('/')
        val path = relative.substringAfter('/')
        groups.getOrPut(name) { linkedMapOf() }[path] =
          PerfectIconBlob(path, row.getString(1), row.getLong(2), source)
      }
      selected.putAll(groups.filterValues { "config.xml" in it })
    }
    return selected
  }

  private fun retainedBytes(blobs: Map<String, PerfectIconBlob>): Map<String, ByteArray> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return blobs.mapValues { (path, blob) ->
      val file = File(context.filesDir, "perfect-icons/blobs/${blob.sha}")
      assertTrue("prepareCurrentModule must cache the retained source: ${blob.source}", file.isFile)
      file.readBytes().also {
        assertEquals(path, blob.size, it.size.toLong())
        assertEquals(path, blob.sha, PerfectIconFormat.sha(it))
      }
    }
  }

  private fun retainedDynamicBytes(name: String) =
    retainedBytes(retainedDynamicBlobs().getValue(name))

  private fun classify(bytes: Map<String, ByteArray>): PerfectDynamicNight.Group? {
    val blobs = bytes.mapValues { (path, content) ->
      PerfectIconBlob(path, PerfectIconFormat.sha(content), content.size.toLong())
    }
    return PerfectDynamicNight.group(blobs, { bytes.getValue(it.path) })
  }

  private fun assertWeatherRootRetained(reason: String, group: PerfectDynamicNight.Group) {
    assertNull(reason, group.compositeQuiet)
    assertTrue(reason, group.compositeQuietLayers.isEmpty())
    assertTrue("Weather must not enter the Calendar/Clock proof path: $reason",
      group.compositeQuietProofLayers.isEmpty())
    assertFalse("Authored root must not enter the conversion set: $reason",
      "quiet/quietImage.png" in group.nightPaths)
  }

  private fun renderNight(
    group: PerfectDynamicNight.Group,
    originals: Map<String, ByteArray>,
  ): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
    for (path in group.bgAssets + group.foreground) {
      put(path, requireNotNull(PerfectDynamicNightRenderer.render(
        originals.getValue(path), path in group.bgAssets) {}) { path })
    }
    group.compositeQuiet?.let { path ->
      put(path, renderRootQuietNight(group, originals))
    }
  }

  private fun renderRootQuietNight(
    group: PerfectDynamicNight.Group,
    originals: Map<String, ByteArray>,
  ): ByteArray {
    val path = requireNotNull(group.compositeQuiet)
    val nativeViewport = group.compositeQuietLayers.isNotEmpty()
    val layers = group.compositeQuietLayers.ifEmpty { listOf(path) }.map(originals::getValue)
    val proofLayers = if (nativeViewport) null else
      group.compositeQuietProofLayers.takeIf { it.isNotEmpty() }?.map(originals::getValue)
    return PerfectStaticNightRenderer.render(layers, null, equivalentLayers = proofLayers,
      nativeViewport = nativeViewport, nativeNightComposition = proofLayers != null).night
  }

  private fun weatherNativeViewportBounds(): Rect {
    val child = ColorDrawable(Color.TRANSPARENT)
    AdaptiveIconDrawable(child, null).setBounds(0, 0, 512, 512)
    return Rect(child.bounds)
  }

  private fun weatherSourcePixel(source: Bitmap, viewport: Rect, x: Int, y: Int): Int {
    val sourceX = ((x - viewport.left).toLong() * source.width / viewport.width()).toInt()
    val sourceY = ((y - viewport.top).toLong() * source.height / viewport.height()).toInt()
    return source.getPixel(sourceX, sourceY)
  }

  private fun zipBytes(zip: ZipFile, path: String): ByteArray =
    zip.getInputStream(requireNotNull(zip.getEntry(path)) { path }).use { it.readBytes() }

  private fun imageDimensions(bytes: ByteArray): Pair<Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return bounds.outWidth to bounds.outHeight
  }

  private fun assertWeatherCanvas(path: String, bytes: ByteArray) {
    val root = PerfectIconFormat.xml(bytes).elements.first().attributes
    assertEquals(path, "351", root["width"])
    assertEquals(path, "351", root["height"])
  }

  private fun resizedImage(
    bytes: ByteArray,
    width: Int,
    height: Int,
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
  ): ByteArray {
    val original = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    try {
      val resized = Bitmap.createScaledBitmap(original, width, height, true)
      try {
        return ByteArrayOutputStream().also {
          assertTrue(resized.compress(format, 100, it))
        }.toByteArray()
      } finally { if (resized !== original) resized.recycle() }
    } finally { original.recycle() }
  }

  private fun changedPixelPng(bytes: ByteArray): ByteArray {
    val options = BitmapFactory.Options().apply { inMutable = true; inScaled = false }
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    try {
      bitmap.setPixel(175, 175, bitmap.getPixel(175, 175) xor 0x00010000)
      return ByteArrayOutputStream().also {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }.toByteArray()
    } finally { bitmap.recycle() }
  }
}
