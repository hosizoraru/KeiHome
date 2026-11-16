package os.kei.hyper.home.iconpack

import android.content.Context
import android.R as AndroidR
import android.content.ContextWrapper
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.util.TypedValue
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.xposed.ConvertedIconArchive

/** Explicit device regression: -e installedMonet true. Never applies the generated MTZ. */
class InstalledMonetPackDeviceTest {
  private data class Snapshot(
    val color: ByteArray, val foreground: ByteArray?, val background: ByteArray?,
    val declared: Boolean, val palette: Boolean,
  )
  private data class Sample(
    val target: IconPackComponent, val light: Snapshot, val dark: Snapshot,
  ) {
    val aliases: List<String> get() = listOfNotNull(target.packageName, target.className)
  }

  private fun optIn() {
    assumeTrue("Requires -e installedMonet true",
      InstrumentationRegistry.getArguments().getString("installedMonet") == "true")
  }

  private fun report(message: String) {
    Log.i("InstalledMonetPackTest", message)
    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
      putString("stream", "InstalledMonet: $message\n")
    })
  }

  @Test fun installedResourceMonochromeSurvivesRenderingExportAndPaletteHandoff() {
    optIn()
    runBlocking {
      val base = InstrumentationRegistry.getInstrumentation().targetContext
      val root = Files.createTempDirectory(base.cacheDir.toPath(), "installed-monet-").toFile()
      val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
          base.getSharedPreferences(if (name == "custom_icons_v1") "${root.name}-$name" else name, mode)
      }
      val previews = IconPackPreviewRepository(context)
      try {
        val targets = launcherTargets(context)
        val resources = AndroidIconPackResources(context)
        val repository = IconPackRepository(context)
        val provenTargets = mutableSetOf<IconPackComponent>()
        val paletteTargets = mutableSetOf<IconPackComponent>()
        val dayNightTargets = mutableSetOf<IconPackComponent>()
        var withoutPalette = 0
        for (packageName in PACKAGES) {
          val info = try {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
          } catch (_: PackageManager.NameNotFoundException) {
            report("$packageName: not installed or not visible")
            continue
          }
          val version = "$packageName version=${info.versionName} code=${info.longVersionCode}"
          val index = resources.index(info)
          if (index == null) {
            report("$version: no supported resource-only appfilter; Monet not established")
            continue
          }
          val pack = IconPackDescriptor("installed:$packageName", index.displayName, IconPackFormat.INSTALLED_APK,
            IconPackSource.Installed(packageName, info.longVersionCode, info.lastUpdateTime, info.versionName),
            index.entries, notices = index.notices, directives = index.directives)
          val samples = targets.mapNotNull { target ->
            val entry = index.entries.singleOrNull { it.component == target }
            if (entry == null) {
              report("$version: no exact mapping for ${target.flattened}")
              return@mapNotNull null
            }
            val light = snapshot(context, resources, info, pack, entry, false)
            val dark = snapshot(context, resources, info, pack, entry, true)
            for ((night, image) in listOf(false to light, true to dark)) {
              val capability = when {
                image.declared -> "declared adaptive monochrome"
                image.palette -> "resource-proven adaptive palette foreground"
                else -> "no proven palette geometry"
              }
              report("$version ${target.flattened} dark=$night: $capability")
              if (image.declared) provenTargets += target
              if (image.foreground == null) withoutPalette++
              if (packageName == "com.donnnno.arcticons.you") {
                assertFalse("$version keeps its original absence of standard monochrome", image.declared)
                assertTrue("$version actual source references must prove palette geometry", image.palette)
                paletteTargets += target
              }
              if (packageName == "com.donnnno.arcticons.daynight") {
                assertFalse("$version literal night colors are not palette proof", image.palette)
                assertNull("$version must not invent Monet masks", image.foreground)
                dayNightTargets += target
              }
            }
            val preview = previews.load(pack, entry, SIZE)
            assertNull("$version light preview", preview.lightFailure)
            assertNull("$version dark preview", preview.darkFailure)
            assertPixels("$version light preview", light.color, requireNotNull(preview.light))
            assertPixels("$version dark preview", dark.color, requireNotNull(preview.dark))
            Sample(target, light, dark)
          }
          if (samples.isEmpty()) continue
          val selected = samples.map { it.target }
          val light = repository.exportNativeIcons(pack, selected, darkMode = false, sizePx = SIZE, installedOnly = true,
            preservedPackages = PhoneCompanionIcons.TARGETS.toSet(), recommendedMappings = os.kei.hyper.home.data.RecommendedIconMappings.NONE)
          try {
            val dark = repository.exportNativeIcons(pack, selected, darkMode = true, sizePx = SIZE, installedOnly = true,
              preservedPackages = PhoneCompanionIcons.TARGETS.toSet(), recommendedMappings = os.kei.hyper.home.data.RecommendedIconMappings.NONE)
            try {
              assertExport(light, samples, false)
              assertExport(dark, samples, true)
              for (includePalette in listOf(true, false)) {
                val handoff = IconPackHandoffs.prepare(context, repository, previews, pack, selected,
                  IconPackAppearance.SYSTEM, includePalette = includePalette, installedOnly = true)
                val nested = File.createTempFile("handoff-", ".icons", context.cacheDir)
                try {
                  assertEquals("CONVERTED_APK", handoff.convertedOrigin)
                  ConvertedIconArchive.inspect(handoff.file, "CONVERTED_APK", context.cacheDir)
                  ZipFile(handoff.file).use { mtz ->
                    mtz.getInputStream(requireNotNull(mtz.getEntry("icons"))).use { input ->
                      nested.outputStream().use { input.copyTo(it) }
                    }
                  }
                  ZipFile(nested).use { icons -> assertHandoff(icons, samples, includePalette) }
                } finally { handoff.file.delete(); nested.delete() }
              }
              report("$version: ${samples.size} exact targets; both exports and palette on/off handoffs verified")
            } finally { dark.archive.delete() }
          } finally { light.archive.delete() }
          previews.clear()
        }
        assertEquals("Both real launcher targets need a genuine monochrome positive control",
          targets.toSet(), provenTargets)
        assertEquals("Both real targets need the installed palette-reference positive control",
          targets.toSet(), paletteTargets)
        assertEquals("Both real targets need the installed day/night negative control",
          targets.toSet(), dayNightTargets)
        assertTrue("Need an installed colored/vector negative control; coverage must not silently disappear",
          withoutPalette > 0)
      } finally {
        previews.clear()
        base.deleteSharedPreferences("${root.name}-custom_icons_v1")
        root.deleteRecursively()
      }
    }
  }

  @Test fun declaredMonochromeUsesItsOwnAlphaAndBecomesWhiteInBothModes() {
    optIn()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Deliberately different geometry: the colored foreground fills the entire viewport.
    val mono = InsetDrawable(ColorDrawable(Color.CYAN), SIZE / 2)
    val drawable = AdaptiveIconDrawable(ColorDrawable(Color.YELLOW), ColorDrawable(Color.RED), mono)
    val renderer = ColorOsFallbackRenderer(context)
    var day: ByteArray? = null
    for (dark in listOf(false, true)) {
      val rendered = renderer.rasterize(drawable, dark, SIZE, monochrome = true) {}
      try {
        assertEquals("Colored foreground must not become the mask", 0, Color.alpha(rendered.getPixel(0, 0)))
        assertEquals("Declared cyan monochrome is flattened to white", Color.WHITE, rendered.getPixel(SIZE / 2, SIZE / 2))
        val bytes = png(rendered)
        assertWhiteMask("synthetic monochrome dark=$dark", bytes)
        if (day == null) day = bytes else assertPixels("Dark mode must not dim the monochrome mask", day, rendered)
      } finally { rendered.recycle() }
    }
  }

  @Test fun diagnoseInstalledPaletteResources() {
    optIn()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val android = AndroidIconPackResources(context)
    val targets = launcherTargets(context)
    val failures = mutableListOf<String>()
    for ((packageName, expected) in listOf(
      "com.donnnno.arcticons.you" to true, "com.donnnno.arcticons.daynight" to false,
    )) {
      val info = context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
      val index = requireNotNull(android.index(info)) { "$packageName missing resource-only index" }
      for (dark in listOf(false, true)) android.installed(requireNotNull(info.applicationInfo), dark).use { session ->
        for (target in targets) {
          val entry = index.entries.single { it.component == target }
          val artwork = requireNotNull(entry.artwork(dark) as? IconPackArtwork.Resource)
          val id = session.drawableId(artwork.drawable)
          val result = AndroidPaletteIcon.foreground(session.resources, id)
          val label = "$packageName version=${info.versionName}/${info.longVersionCode} " +
            "${target.flattened} dark=$dark id=0x${id.toUInt().toString(16)}"
          report("$label result=$result expected=$expected config=${session.resources.configuration}")
          // Covers foreground wrappers followed by framework palette aliases without mixing their depths.
          if (result != expected) {
            paletteDiagnostic(session.resources, id).lineSequence().forEach { report("$label $it") }
          }
          if (result != expected) failures += "$label result=$result expected=$expected"
        }
      }
    }
    assertTrue("Palette resource diagnostics:\n${failures.joinToString("\n")}", failures.isEmpty())
  }

  private fun launcherTargets(context: Context): List<IconPackComponent> {
    val launcher = requireNotNull(context.getSystemService(LauncherApps::class.java))
    return listOf(
      "com.android.settings" to "com.android.settings.MainSettings",
      "com.miui.gallery" to "com.miui.gallery.MainActivity",
    ).map { (packageName, preferred) ->
      val actual = launcher.getActivityList(packageName, Process.myUserHandle()).map { it.componentName }
      assertTrue("Missing real LauncherApps target $packageName", actual.isNotEmpty())
      val component = actual.firstOrNull { it.className == preferred } ?: actual.sortedBy { it.className }.first()
      report("LauncherApps target=${component.flattenToString()}")
      IconPackComponent(component.packageName, component.className)
    }
  }

  private fun snapshot(
    context: Context, android: AndroidIconPackResources, info: PackageInfo,
    pack: IconPackDescriptor, entry: IconPackEntry, dark: Boolean,
  ): Snapshot {
    val label = "${info.packageName} ${entry.key} dark=$dark"
    android.installed(requireNotNull(info.applicationInfo), dark).use { resourceSession ->
      val artwork = requireNotNull(entry.artwork(dark) as? IconPackArtwork.Resource) { label }
      val id = resourceSession.drawableId(artwork.drawable)
      assertTrue("$label resource must resolve", id != 0)
      val original = resourceSession.resources.getDrawable(id, null).mutate()
      val before = sourcePng(original)
      val drawable = original as? AdaptiveIconDrawable
      val declared = declaresMonochrome(resourceSession.resources, id)
      assertEquals("$label XML and genuine AdaptiveIconDrawable.monochrome must agree",
        declared, drawable?.monochrome != null)
      val palette = AndroidPaletteIcon.foreground(resourceSession.resources, id)
      if (!palette && info.packageName == "com.donnnno.arcticons.you") {
        val diagnostic = paletteDiagnostic(resourceSession.resources, id)
        report("$label palette proof rejected:\n$diagnostic")
        assertTrue("$label expected actual palette source proof:\n$diagnostic", palette)
      }
      assertPngPixels("$label proof must not mutate original artwork", before, sourcePng(original))
      assertEquals("$label original monochrome must remain unchanged", declared, drawable?.monochrome != null)
      assertFalse("$label a scalar color cannot prove an adaptive foreground",
        AndroidPaletteIcon.foreground(resourceSession.resources, AndroidR.color.system_accent1_100))
      val cancellation = CancellationException("palette proof cancellation")
      var checkpoints = 0
      val cancelAt = if (palette) 8 else 1
      try {
        AndroidPaletteIcon.foreground(resourceSession.resources, id) {
          if (++checkpoints == cancelAt) throw cancellation
        }
        fail("$label swallowed proof cancellation")
      } catch (actual: CancellationException) {
        assertSame(cancellation, actual)
      }
      IconPackRenderer(context).open(pack, dark).use { renderer ->
        val color = requireNotNull(renderer.render(entry, SIZE)) { "$label colored render unavailable" }
        val layers = renderer.themedLayers(entry, SIZE) {}
        try {
          val after = requireNotNull(renderer.render(entry, SIZE))
          try { assertPixels("$label themed layers must not recolor the colored renderer", png(color), after) }
          finally { after.recycle() }
          assertPngPixels("$label themed layers must not mutate the original source", before, sourcePng(original))
          assertEquals("$label standard monochrome source remains unchanged", declared, drawable?.monochrome != null)
          if (!declared && !palette) {
            assertNull("$label must not invent a mask without source proof", layers)
            return Snapshot(png(color), null, null, declared, palette)
          }
          val adaptive = requireNotNull(drawable)
          val actual = requireNotNull(layers) { "$label proven palette geometry was lost" }
          assertWhiteMask("$label foreground", png(actual.first))
          assertWhiteMask("$label background", png(actual.second))
          // Preserve the complete foreground wrapper graph for resource-proven palette artwork.
          val expected = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
          val background = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
          try {
            adaptive.setBounds(0, 0, SIZE, SIZE)
            Canvas(background).drawPath(NativeAdaptiveIconMask.load()?.pathFor(adaptive) ?: adaptive.iconMask,
              Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
            val source = requireNotNull(if (declared) adaptive.monochrome else adaptive.foreground)
            val previousBounds = Rect(source.bounds)
            source.bounds = ColorOsFallbackRenderer.adaptiveBounds(SIZE)
            val canvas = Canvas(expected)
            try { source.draw(canvas) } finally { source.bounds = previousBounds }
            canvas.drawColor(Color.WHITE, PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(background, 0f, 0f, Paint().apply {
              xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            assertAlpha("$label proven source geometry", expected, actual.first)
            assertAlpha("$label native background mask", background, actual.second)
          } finally { expected.recycle(); background.recycle() }
          return Snapshot(png(color), png(actual.first), png(actual.second), declared, palette)
        } finally { color.recycle(); layers?.first?.recycle(); layers?.second?.recycle() }
      }
    }
  }

  /** Only used by opted-in device diagnostics; production stays silent and fail-closed. */
  private fun paletteDiagnostic(resources: Resources, id: Int): String = buildString {
    try {
      val proof = AndroidPaletteIcon::class.java.declaredClasses.single { it.simpleName == "Proof" }
      val constructor = proof.declaredConstructors.single().apply { isAccessible = true }
      val check: () -> Unit = {}
      val instance = constructor.newInstance(resources, check)
      val method = proof.getDeclaredMethod("foreground", Int::class.javaPrimitiveType).apply { isAccessible = true }
      appendLine("Uncaught proof result=${method.invoke(instance, id)}")
    } catch (failure: InvocationTargetException) {
      val cause = failure.targetException
      if (cause is CancellationException) throw cause
      appendLine(cause.stackTraceToString())
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
      appendLine("Reflection diagnostic unavailable: $failure")
    }
    val references = linkedSetOf(id)
    try {
      resources.getXml(id).use { xml ->
        var events = 0
        while (xml.eventType != XmlPullParser.END_DOCUMENT && ++events <= 1024) {
          if (xml.eventType == XmlPullParser.START_TAG) {
            appendLine("XML ${xml.name} depth=${xml.depth} namespace=${xml.namespace}")
            for (index in 0 until xml.attributeCount.coerceAtMost(16)) {
              val attributeId = xml.getAttributeNameResource(index)
              val reference = xml.getAttributeResourceValue(index, 0)
              if (reference != 0) references += reference
              val values = resources.obtainAttributes(xml, intArrayOf(attributeId))
              try {
                val value = TypedValue()
                val present = values.getValue(0, value)
                appendLine("  ${xml.getAttributeName(index)} attr=0x${attributeId.toUInt().toString(16)} " +
                  "ref=0x${reference.toUInt().toString(16)} present=$present " +
                  "type=${value.type} data=0x${value.data.toUInt().toString(16)} " +
                  "text=${value.string?.take(96)}")
              } finally { values.recycle() }
            }
          }
          xml.next()
        }
      }
      val seen = mutableSetOf<Int>()
      fun reference(current: Int, depth: Int) {
        if (depth > 8 || seen.size >= 32 || !seen.add(current)) return
        val raw = TypedValue()
        val resolved = TypedValue()
        resources.getValue(current, raw, false)
        resources.getValue(current, resolved, true)
        appendLine("RESOURCE depth=$depth ${resources.getResourceName(current)} " +
          "id=0x${current.toUInt().toString(16)} " +
          "rawType=${raw.type} rawData=0x${raw.data.toUInt().toString(16)} " +
          "resolvedType=${resolved.type} resolvedData=0x${resolved.data.toUInt().toString(16)}")
        if (raw.type == TypedValue.TYPE_REFERENCE) reference(raw.data, depth + 1)
      }
      references.forEach { reference(it, 0) }
    } catch (failure: Exception) {
      if (failure is CancellationException) throw failure
      appendLine("Resource diagnostic unavailable: $failure")
    }
  }

  private fun sourcePng(drawable: Drawable): ByteArray {
    val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    val previous = Rect(drawable.bounds)
    try {
      drawable.setBounds(0, 0, SIZE, SIZE)
      drawable.draw(Canvas(bitmap))
      return png(bitmap)
    } finally { drawable.bounds = previous; bitmap.recycle() }
  }

  private fun declaresMonochrome(resources: Resources, id: Int): Boolean {
    val value = TypedValue()
    resources.getValue(id, value, true)
    if (value.string?.toString()?.endsWith(".xml") != true) return false
    resources.getXml(id).use { xml ->
      var adaptive = false
      while (xml.eventType != XmlPullParser.END_DOCUMENT) {
        if (xml.eventType == XmlPullParser.START_TAG) {
          if (xml.depth == 1) adaptive = xml.name == "adaptive-icon"
          if (adaptive && xml.depth == 2 && xml.name == "monochrome") return true
        }
        xml.next()
      }
    }
    return false
  }

  private fun assertExport(result: IconPackExportResult, samples: List<Sample>, dark: Boolean) {
    assertEquals(samples.map { it.target }.toSet(), result.exportedTargets.toSet())
    assertTrue("No exact sample may be silently skipped: ${result.skippedTargets}", result.skippedTargets.isEmpty())
    assertFalse(result.preservesNativeResources)
    assertTrue("Explicit APK layers are not system fallback masks", result.fallbackPaletteNames.isEmpty())
    val expectedMasks = samples.filter { (if (dark) it.dark else it.light).foreground != null }
      .flatMap { it.aliases }.toSet()
    assertEquals(expectedMasks, result.monochrome.keys)
    assertEquals(expectedMasks, result.monochromeBackgrounds.keys)
    ZipFile(result.archive).use { zip ->
      for (sample in samples) {
        val image = if (dark) sample.dark else sample.light
        for (alias in sample.aliases) {
          assertPngPixels("$alias export dark=$dark", image.color, bytes(zip, "res/drawable-xxhdpi/$alias.png"))
          image.foreground?.let { assertPngPixels("$alias exported foreground", it, result.monochrome.getValue(alias)) }
          image.background?.let { assertPngPixels("$alias exported background", it, result.monochromeBackgrounds.getValue(alias)) }
        }
      }
    }
  }

  private fun assertHandoff(zip: ZipFile, samples: List<Sample>, includePalette: Boolean) {
    for (sample in samples) for (alias in sample.aliases) {
      val root = "fancy_icons/$alias/"
      assertPngPixels("$alias handoff day", sample.light.color, bytes(zip, "res/drawable-xxhdpi/$alias.png"))
      assertPngPixels("$alias handoff night", sample.dark.color, bytes(zip, "nightmode/res/drawable-xxhdpi/$alias.png"))
      val palette = includePalette && sample.light.foreground != null
      val manifest = zip.getEntry(root + "manifest.xml")?.let { bytes(zip, it.name).toString(Charsets.UTF_8) }
      if (palette) {
        val xml = requireNotNull(manifest) { "$alias lost requested palette selector" }
        assertTrue(xml.contains("content://settings/system/${NativePaletteIcon.STATE_KEY}"))
        assertTrue(xml.contains("type=\"double\""))
        assertTrue(xml.contains("frameRate=\"0\""))
        assertTrue(xml.contains("updateInterval=\"-1\""))
        assertFalse(xml.contains("FramerateController"))
        assertFalse(xml.contains("Folme"))
        assertPngPixels("$alias handoff mask", requireNotNull(sample.light.foreground), bytes(zip, root + "mask.png"))
        assertPngPixels("$alias handoff background", requireNotNull(sample.light.background), bytes(zip, root + "bg.png"))
      } else {
        assertNull("$alias has no requested/proven Monet mask", zip.getEntry(root + "mask.png"))
        assertNull("$alias has no requested/proven Monet background", zip.getEntry(root + "bg.png"))
        assertFalse("$alias must not acquire a palette binder", manifest?.contains(NativePaletteIcon.STATE_KEY) == true)
      }
      if (manifest != null) {
        assertPngPixels("$alias selector day", sample.light.color, bytes(zip, root + "day.png"))
        // Identical day/night artwork has a canonical compact selector with no night.png.
        if (zip.getEntry(root + "night.png") != null) {
          assertPngPixels("$alias selector night", sample.dark.color, bytes(zip, root + "night.png"))
        } else assertPngPixels("$alias compact selector", sample.light.color, sample.dark.color)
      }
    }
  }

  private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    it.toByteArray()
  }

  private fun bytes(zip: ZipFile, path: String): ByteArray =
    zip.getInputStream(requireNotNull(zip.getEntry(path)) { "Missing $path" }).use { it.readBytes() }

  private fun assertPngPixels(label: String, expected: ByteArray, actual: ByteArray) {
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(actual, 0, actual.size)) { label }
    try { assertPixels(label, expected, bitmap) } finally { bitmap.recycle() }
  }

  private fun assertPixels(label: String, expected: ByteArray, actual: Bitmap) {
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(expected, 0, expected.size)) { label }
    try {
      assertEquals(label, bitmap.width, actual.width)
      assertEquals(label, bitmap.height, actual.height)
      val first = IntArray(bitmap.width * bitmap.height)
      val second = IntArray(first.size)
      bitmap.getPixels(first, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      actual.getPixels(second, 0, actual.width, 0, 0, actual.width, actual.height)
      assertArrayEquals(label, first, second)
    } finally { bitmap.recycle() }
  }

  private fun assertAlpha(label: String, expected: Bitmap, actual: Bitmap) {
    assertEquals(label, expected.width, actual.width)
    assertEquals(label, expected.height, actual.height)
    for (y in 0 until expected.height) for (x in 0 until expected.width) {
      assertEquals("$label ($x,$y)", Color.alpha(expected.getPixel(x, y)), Color.alpha(actual.getPixel(x, y)))
    }
  }

  private fun assertWhiteMask(label: String, png: ByteArray) {
    GeneratedIconPng.requireAlphaMask(png, SIZE)
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { label }
    try {
      for (y in 0 until SIZE) for (x in 0 until SIZE) {
        val pixel = bitmap.getPixel(x, y)
        if (Color.alpha(pixel) != 0) assertEquals("$label ($x,$y)", 0xffffff, pixel and 0xffffff)
      }
    } finally { bitmap.recycle() }
  }

  companion object {
    private const val SIZE = 192
    private val PACKAGES = listOf(
      "app.lawnchair.lawnicons", "com.donnnno.arcticons.you", "com.donnnno.arcticons.daynight",
      "website.leifs.delta.foss", "io.github.perfecticons",
    )
  }
}
