package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.RecommendedIconMappings
import os.kei.hyper.home.iconpack.apple.AppleAppMatch
import os.kei.hyper.home.iconpack.apple.AppleAppTarget
import os.kei.hyper.home.iconpack.apple.AppleIconClient
import os.kei.hyper.home.iconpack.apple.AppleIconOptions
import os.kei.hyper.home.iconpack.apple.AppleIconRepository
import os.kei.hyper.home.iconpack.apple.AppleIconTransport
import os.kei.hyper.home.iconpack.perfect.PerfectIconHttpResponse
import os.kei.hyper.home.iconpack.perfect.PerfectIconRepository
import os.kei.hyper.home.iconpack.perfect.PerfectIconSettings
import os.kei.hyper.home.iconpack.perfect.PerfectIconSourceZip
import os.kei.hyper.home.iconpack.perfect.PerfectIconTransport
import os.kei.hyper.home.iconpack.perfect.PerfectSystemFallback
import os.kei.hyper.home.iconpack.perfect.PerfectStaticNightRenderer

/** Real Android PNG/ZIP/export paths, with only private synthetic Perfect resources. */
class PerfectCompletionExportDeviceTest {
  private val application = ApplicationProvider.getApplicationContext<Context>()

  @Test fun applePackFallbackRetainsDistinctDayAndNightIconsOffline() = fixture { f ->
    val namespace = "apple_fallback_${System.nanoTime()}"
    val apple = AppleIconRepository(f.context, AppleIconClient(object : AppleIconTransport {
      override suspend fun get(url: String): ByteArray = error("Bundled Apple fixture must remain offline")
    }), File(f.root, "apple"), namespace)
    try {
      val options = AppleIconOptions(resolution = 256)
      val target = AppleAppTarget(PRIMARY.packageName, "Primary", false)
      apple.setMatch(target, options, AppleAppMatch("system_appstore", "App Store", "Apple"))
      val pack = runBlocking { apple.generate(listOf(target), options) {}.pack }
      val appleDay = f.export(pack, listOf(PRIMARY, COMPLETE), dark = false)
      val appleNight = f.export(pack, listOf(PRIMARY, COMPLETE), dark = true)
      f.assertPerfect(appleDay, COMPLETE, Color.MAGENTA)
      f.assertPerfect(appleNight, COMPLETE, Color.MAGENTA)
      assertFalse("Apple fallback must not reuse its day artwork in dark mode",
        f.bytes(appleDay, flatPath(COMPLETE)).contentEquals(f.bytes(appleNight, flatPath(COMPLETE))))
      f.assertPerfect(f.export(f.primary, listOf(COMPLETE), dark = false), COMPLETE, Color.MAGENTA)
      f.assertPerfect(f.export(f.primary, listOf(COMPLETE), dark = true), COMPLETE, Color.MAGENTA)
    } finally {
      AppLocalStorage.open(f.context, namespace).clearAll()
    }
  }

  @Test fun uninstalledThirdPartyCompletionWorksAcrossAllThreeSourceCombinations() = fixture { f ->
    assertThrows(PackageManager.NameNotFoundException::class.java) {
      f.context.packageManager.getApplicationInfo(COMPLETE.packageName, PackageManager.ApplicationInfoFlags.of(0))
    }
    val builtin = f.colorOs("coloros-classic-art-on")
    val runtime = f.colorOs("coloros-runtime-fallback")
    val combinations = listOf(
      builtin to null,
      f.primary to runtime,
      f.primary to null,
    )
    for ((primary, supplementary) in combinations) {
      for (dark in listOf(false, true)) {
        val targets = if (primary === builtin) listOf(COMPLETE) else listOf(PRIMARY, COMPLETE)
        val result = f.export(primary, targets, dark = dark, colorOs = supplementary)
        assertTrue(result.skippedTargets.map { it.component }.toSet().toString(), COMPLETE in result.exportedTargets.toSet())
        f.assertPerfect(result, COMPLETE, Color.MAGENTA)
        f.assertManifest(result, setOf(COMPLETE.packageName), supplementary != null)
        if (primary !== builtin) {
          f.assertSolid(result, PRIMARY, Color.GREEN)
          assertFalse("Exact primary artwork must not be reported as completion",
            PRIMARY.packageName in f.manifest(result).names)
        }
      }
    }
    assertEquals("Only the synthetic ZIP bootstrap may use transport", 1, f.downloads)
    assertFalse(File(f.repositoryRoot, "generated").exists())
    assertFalse(File(f.repositoryRoot, "previews").exists())
  }

  @Test fun disabledCompletionKeepsPrimaryAndColorOsPixelsWithoutOpeningPerfect() = fixture { f ->
    val runtime = f.colorOs("coloros-runtime-fallback")
    val ownApp = IconPackComponent(f.context.packageName, "os.kei.hyper.home.MainActivity")
    for (dark in listOf(false, true)) {
      val openedBefore = f.openedSources
      val result = f.export(f.primary, listOf(PRIMARY, COMPLETE, ownApp),
        dark = dark, colorOs = runtime, fallback = null)
      assertEquals(openedBefore, f.openedSources)
      assertEquals(setOf(PRIMARY, ownApp), result.exportedTargets.toSet())
      assertEquals(setOf(COMPLETE), result.skippedTargets.map { it.component }.toSet())
      f.assertSolid(result, PRIMARY, Color.GREEN)
      val generic = requireNotNull(ColorOsFallbackRenderer(f.context).render(ownApp, dark, SIZE))
      try { f.assertPixels(result, ownApp, generic) } finally { generic.recycle() }
      ZipFile(result.archive).use { zip ->
        assertNull(zip.getEntry(SystemIconFallbackManifest.PATH))
        assertNull(zip.getEntry(flatPath(COMPLETE)))
      }
    }
    assertEquals(0, f.openedSources)
  }

  @Test fun perfectMissRetainsColorOsGenericAndBothSourcesMissingIsSafe() = fixture { f ->
    val ownApp = IconPackComponent(f.context.packageName, "os.kei.hyper.home.MainActivity")
    for (dark in listOf(false, true)) {
      val generic = requireNotNull(ColorOsFallbackRenderer(f.context).render(ownApp, dark, SIZE))
      try {
        val result = f.export(f.primary, listOf(PRIMARY, COMPLETE, ownApp, MISSING),
          dark = dark, colorOs = f.colorOs("coloros-runtime-fallback"))
        assertEquals(setOf(PRIMARY, COMPLETE, ownApp), result.exportedTargets.toSet())
        assertEquals(setOf(MISSING), result.skippedTargets.map { it.component }.toSet())
        f.assertPixels(result, ownApp, generic)
        f.assertPerfect(result, COMPLETE, Color.MAGENTA)
        assertFalse("Perfect pixels must differ from the original-app ColorOS fallback",
          digest(f.bytes(result, flatPath(COMPLETE))).contentEquals(digest(png(generic))))
        f.assertManifest(result, setOf(COMPLETE.packageName), colorOs = true)
        ZipFile(result.archive).use { assertNull(it.getEntry(flatPath(MISSING))) }
      } finally { generic.recycle() }
      val withoutColorOs = f.export(f.primary, listOf(PRIMARY, ownApp, MISSING), dark = dark)
      assertEquals(setOf(PRIMARY), withoutColorOs.exportedTargets.toSet())
      assertEquals(setOf(ownApp, MISSING), withoutColorOs.skippedTargets.map { it.component }.toSet())
      f.assertSolid(withoutColorOs, PRIMARY, Color.GREEN)
      f.assertManifest(withoutColorOs, emptySet())
    }
  }

  @Test fun excludedPackagesNeverReachPrimaryOrPerfectOutput() = fixture { f ->
    val preserved = setOf(PRIMARY.packageName, EXCLUDED.packageName)
    val result = f.export(f.primary, listOf(PRIMARY, COMPLETE, EXCLUDED),
      colorOs = f.colorOs("coloros-runtime-fallback"), preserved = preserved)
    assertEquals(setOf(COMPLETE), result.exportedTargets.toSet())
    assertTrue(result.skippedTargets.isEmpty())
    f.assertPerfect(result, COMPLETE, Color.MAGENTA)
    f.assertManifest(result, setOf(COMPLETE.packageName), colorOs = true)
    ZipFile(result.archive).use { zip ->
      for (target in listOf(PRIMARY, EXCLUDED)) {
        assertFalse("No native artwork for excluded ${target.packageName}",
          zip.entries().asSequence().any { target.packageName in it.name })
      }
    }
  }

  @Test fun offlineCoverageIncludesExplicitPerfectPackagesOnlyWhenInstalledOnlyIsDisabled() = fixture { f ->
    val installed = f.export(f.primary, listOf(PRIMARY), installedOnly = true)
    assertEquals(setOf(PRIMARY), installed.exportedTargets.toSet())
    f.assertManifest(installed, emptySet())
    ZipFile(installed.archive).use { zip ->
      for (target in listOf(COMPLETE, OFFLINE, EXCLUDED)) assertNull(zip.getEntry(flatPath(target)))
    }

    val offline = f.export(f.primary, listOf(PRIMARY), installedOnly = false,
      preserved = setOf(EXCLUDED.packageName))
    assertEquals(setOf(PRIMARY, COMPLETE, OFFLINE), offline.exportedTargets.toSet())
    f.assertSolid(offline, PRIMARY, Color.GREEN)
    f.assertPerfect(offline, COMPLETE, Color.MAGENTA)
    f.assertPerfect(offline, OFFLINE, Color.CYAN)
    f.assertManifest(offline, setOf(COMPLETE.packageName, OFFLINE.packageName))
    ZipFile(offline.archive).use { zip ->
      assertNull(zip.getEntry(flatPath(EXCLUDED)))
      for (target in LIVE) assertNull(zip.getEntry(flatPath(target)))
    }
    assertEquals(1, f.downloads)
  }

  @Test fun offlineBareActivityStemsRespectKnownOwnersAndPreservedPackages() {
    val included = IconPackComponent(COMPLETE.packageName, "${COMPLETE.packageName}.MainActivity")
    val excluded = IconPackComponent(EXCLUDED.packageName, "${EXCLUDED.packageName}.MainActivity")
    val external = IconPackComponent(EXCLUDED.packageName, "dev.keihome.shared.Launcher")
    val separatePackage = IconPackComponent("${EXCLUDED.packageName}.channel")
    val extra = listOf(included.className!!, excluded.className!!, external.className!!,
      separatePackage.packageName).associate { "themes/$STYLE/icons/$it.png" to solid(Color.CYAN) }
    fixture(extra) { f ->
      for (dark in listOf(false, true)) {
        val result = f.export(f.primary, listOf(PRIMARY, included, excluded, external, separatePackage),
          dark = dark, installedOnly = false, preserved = setOf(EXCLUDED.packageName))
        assertEquals(setOf(PRIMARY, COMPLETE, included, OFFLINE, separatePackage), result.exportedTargets.toSet())
        assertFalse("A bare activity stem must not be exported as a synthetic package",
          IconPackComponent(included.className!!) in result.exportedTargets)
        f.assertManifest(result, setOf(COMPLETE.packageName, included.className!!,
          OFFLINE.packageName, separatePackage.packageName))
        f.assertSolid(result, PRIMARY, Color.GREEN)
        f.assertPerfect(result, COMPLETE, Color.MAGENTA)
        val fallbackPlate = if (dark) Color.rgb(32, 32, 32) else Color.CYAN
        f.assertSolid(result, IconPackComponent(included.className!!), fallbackPlate)
        f.assertSolid(result, separatePackage, fallbackPlate)
        ZipFile(result.archive).use { zip ->
          for (name in listOf(EXCLUDED.packageName, excluded.className!!, external.className!!)) {
            assertNull("Preserved package/alias must not be reintroduced: $name",
              zip.getEntry("res/drawable-xxhdpi/$name.png"))
          }
        }
      }
    }
  }

  @Test fun nativeColorOsAndPerfectKeepExplicitExclusionsInBothOfflinePasses() = fixture { f ->
    val originals = linkedMapOf(
      flatPath(PRIMARY) to solid(Color.GREEN),
      "nightmode/${flatPath(PRIMARY)}" to solid(Color.YELLOW),
      "fixture-marker.txt" to "keep native bytes".toByteArray(),
    )
    val input = File(f.root, "native-coloros-input.icons").apply { writeBytes(zip(originals)) }
    val native = f.importNative(input)
    // Limit offline OEM candidates; keep the real ColorOS runtime descriptor and rendering path.
    val colorOs = f.colorOs("coloros-runtime-fallback").copy(entries = emptyList(),
      directives = f.primary.directives)
    val result = f.export(native, listOf(PRIMARY, COMPLETE, EXCLUDED),
      colorOs = colorOs, installedOnly = false, preserved = setOf(EXCLUDED.packageName))
    assertTrue(result.preservesNativeResources)
    assertTrue(result.exportedTargets.containsAll(listOf(PRIMARY, COMPLETE, OFFLINE)))
    assertFalse(EXCLUDED in result.exportedTargets)
    f.assertManifest(result, setOf(COMPLETE.packageName, OFFLINE.packageName), colorOs = true)
    f.assertPerfect(result, COMPLETE, Color.MAGENTA)
    f.assertPerfect(result, OFFLINE, Color.CYAN)
    ZipFile(result.archive).use { output ->
      for ((path, bytes) in originals) {
        assertArrayEquals("Native resource changed: $path", bytes,
          output.getInputStream(requireNotNull(output.getEntry(path))).use { it.readBytes() })
      }
      assertFalse("Neither recursive pass may reintroduce the explicitly preserved package",
        output.entries().asSequence().any { EXCLUDED.packageName in it.name })
      for (target in listOf(COMPLETE, OFFLINE)) {
        val bitmap = decode(output.getInputStream(requireNotNull(
          output.getEntry("nightmode/${flatPath(target)}"))).use { it.readBytes() })
        try { assertPerfectPixels(bitmap, if (target == COMPLETE) Color.MAGENTA else Color.CYAN, dark = true) }
        finally { bitmap.recycle() }
      }
    }
    assertEquals(1, f.downloads)
  }

  @Test fun liveClockCalendarAndDeclaredDynamicTargetsAreNeverFlattened() = fixture { f ->
    val dynamicClock = IconPackComponent("dev.keihome.regression.declaredclock")
    val dynamicCalendar = IconPackComponent("dev.keihome.regression.declaredcalendar")
    val live = LIVE + dynamicClock + dynamicCalendar
    val declared = f.primary.copy(directives = listOf(
      IconPackDirective("dynamic-clock", mapOf("component" to dynamicClock.flattened), IconNightMode.ANY),
      IconPackDirective("calendar", mapOf("component" to dynamicCalendar.flattened), IconNightMode.ANY),
    ))
    val source = f.openSource(STYLE)
    try {
      assertTrue("The source really contains static clock/calendar art",
        live.all { it.packageName in source.nativeNames })
      for (target in LIVE) assertNotNull(source.icon(listOf(target.packageName)))
      source.validate()
    } finally { source.close() }
    SystemIconFallbackRenderer(f.context, SystemIconFallback.perfect(STYLE),
      clipOutput = false, perfectOpener = f::openSource).use { renderer ->
      for (target in LIVE) assertNull(renderer.render(target, SIZE) {})
      renderer.validateCompletionSource()
    }
    val result = f.export(declared, listOf(PRIMARY, COMPLETE) + live)
    assertEquals(setOf(PRIMARY, COMPLETE), result.exportedTargets.toSet())
    assertEquals(live.toSet(), result.skippedTargets.map { it.component }.toSet())
    f.assertManifest(result, setOf(COMPLETE.packageName))
    ZipFile(result.archive).use { zip ->
      for (target in live) {
        assertFalse("No flattened live icon for ${target.packageName}",
          zip.entries().asSequence().any { target.packageName in it.name })
      }
    }
  }

  @Test fun nonSystemRendererUsesSelectedLocalStyleAndClosesItsSource() = fixture { f ->
    val openedBefore = f.openedSources
    lateinit var held: PerfectSystemFallback.Source
    SystemIconFallbackRenderer(f.context, SystemIconFallback.perfect(STYLE),
      clipOutput = false, perfectOpener = { style ->
        f.openSource(style).also { held = it }
      }).use { renderer ->
      assertFalse(renderer.isSystem(COMPLETE))
      repeat(2) {
        val bitmap = requireNotNull(renderer.render(COMPLETE, SIZE) {})
        try { assertPerfectPixels(bitmap, Color.MAGENTA) } finally { bitmap.recycle() }
      }
      assertNull(renderer.render(MISSING, SIZE) {})
      assertEquals(f.sourceHash, renderer.perfectSourceHash)
      renderer.validateCompletionSource()
    }
    assertEquals(openedBefore + 1, f.openedSources)
    assertThrows(IllegalStateException::class.java) { held.icon(listOf(COMPLETE.packageName)) }
    assertEquals(1, f.downloads)
  }

  @Test fun nativeBaseAndLiveProgramSurviveCompletionAndNativePreviewRoundTrip() = fixture { f ->
    val originals = linkedMapOf<String, ByteArray>().apply {
      put(flatPath(PRIMARY), solid(Color.GREEN))
      put("nightmode/${flatPath(PRIMARY)}", solid(Color.YELLOW))
      put("fancy_icons/com.android.deskclock/manifest.xml", """
        <Icon version="1" width="96" height="96" frameRate="1">
          <Text x="48" y="48" textExp="#hour24" />
        </Icon>
      """.trimIndent().toByteArray())
      put("fixture-marker.txt", "keep the original native base".toByteArray())
    }
    val input = File(f.root, "native-input.icons").apply { writeBytes(zip(originals)) }
    val native = f.importNative(input)
    val result = f.export(native, listOf(PRIMARY, COMPLETE) + LIVE)
    assertTrue(result.preservesNativeResources)
    assertTrue(COMPLETE in result.exportedTargets.toSet())
    assertFalse(PRIMARY.packageName in f.manifest(result).names)
    f.assertManifest(result, setOf(COMPLETE.packageName))
    ZipFile(result.archive).use { output ->
      for ((path, bytes) in originals) {
        assertArrayEquals("Native resource changed: $path", bytes,
          output.getInputStream(requireNotNull(output.getEntry(path))).use { it.readBytes() })
      }
      for (target in LIVE) assertNull(output.getEntry(flatPath(target)))
    }

    val roundTrip = f.importNative(result.archive)
    assertEquals(IconPackFormat.MIUI_ICONS, roundTrip.format)
    for (dark in listOf(false, true)) {
      IconPackRenderer(f.context).open(roundTrip, dark,
        maskPolicy = NO_MASK, clipOutput = false, preservedPackages = emptySet()).use { preview ->
        val exact = roundTrip.entries.single { it.nativeName == PRIMARY.packageName }
        val completed = roundTrip.entries.single { it.nativeName == COMPLETE.packageName }
        val primaryBitmap = requireNotNull(preview.render(exact, SIZE))
        try {
          assertEquals(if (dark) Color.YELLOW else Color.GREEN, primaryBitmap.getPixel(SIZE / 2, SIZE / 2))
        } finally { primaryBitmap.recycle() }
        val perfectBitmap = requireNotNull(preview.render(completed, SIZE))
        try { assertPerfectPixels(perfectBitmap, Color.MAGENTA, dark) } finally { perfectBitmap.recycle() }
      }
    }
    assertEquals(1, f.downloads)
  }

  private fun fixture(extraPerfectFiles: Map<String, ByteArray> = emptyMap(), block: (Fixture) -> Unit) {
    val root = Files.createTempDirectory(application.cacheDir.toPath(), "perfect-completion-export-").toFile()
    try {
      val f = Fixture(root, extraPerfectFiles)
      f.acquire()
      block(f)
    } finally {
      assertTrue("Remove only this test's temporary resources: $root", root.deleteRecursively())
    }
  }

  private inner class Fixture(val root: File, extraPerfectFiles: Map<String, ByteArray>) {
    val context: Context = object : ContextWrapper(application) {
      private val staging = File(root, "cache").apply { mkdirs() }
      override fun getCacheDir(): File = staging
      override fun getApplicationContext(): Context = this
    }
    val repositoryRoot = File(root, "perfect")
    var downloads = 0
    var openedSources = 0
    lateinit var sourceHash: String
    private val settings = object : PerfectIconSettings {
      private val values = mutableMapOf<String, String>()
      override fun read(key: String): String? = values[key]
      override fun write(key: String, value: String) { values[key] = value }
    }
    private val sourceFiles = linkedMapOf<String, ByteArray>().apply {
      val colors = linkedMapOf(
        PRIMARY to Color.RED, COMPLETE to Color.MAGENTA, OFFLINE to Color.CYAN,
        EXCLUDED to Color.YELLOW,
      )
      (LIVE + IconPackComponent("dev.keihome.regression.declaredclock") +
        IconPackComponent("dev.keihome.regression.declaredcalendar")).forEach { colors[it] = Color.RED }
      for ((target, color) in colors) {
        put("icons/${target.packageName}/0.png", solid(Color.BLUE))
        put("icons/${target.packageName}/1.png", solid(Color.TRANSPARENT))
        // ZIP bootstrap requires usable base pairs and an explicit default theme.
        put("themes/default/icons/${target.packageName}/0.png", solid(Color.BLUE))
        put("themes/default/icons/${target.packageName}/1.png", foreground(Color.WHITE))
        put("themes/HyperOS4/icons/${target.packageName}/0.png", solid(Color.BLUE))
        put("themes/HyperOS4/icons/${target.packageName}/1.png", foreground(Color.WHITE))
        put("themes/$STYLE/icons/${target.packageName}/0.png", solid(Color.BLUE))
        put("themes/$STYLE/icons/${target.packageName}/1.png", foreground(color))
      }
      putAll(extraPerfectFiles)
    }
    private val repository = PerfectIconRepository(repositoryRoot,
      PerfectIconTransport { _, destination, limit, _, _ ->
        check(++downloads == 1) { "Completion must remain offline after ZIP bootstrap" }
        val archive = zip(sourceFiles, PerfectIconSourceZip.PREFIX, "1".repeat(40))
        check(archive.size.toLong() <= limit)
        destination.writeBytes(archive)
        PerfectIconHttpResponse(200)
      }, settings,
      staticRenderer = { layers, mask, check -> PerfectStaticNightRenderer.render(layers, mask, check = check) },
      staticEquivalentRenderer = { flat, layers, mask, check ->
        PerfectStaticNightRenderer.render(listOf(flat), mask, equivalentLayers = layers, check = check)
      })
    private val exporter = IconPackExporter(context, IconPackLimits(), perfectSources = ::openSource)
    private val stores = IconPackStore(File(root, "imports"))
    private val builtinCatalog by lazy { BuiltinIconPackStore(context.assets).list() }
    val primary: IconPackDescriptor

    init {
      val input = File(root, "primary.zip")
      input.writeBytes(zip(linkedMapOf(
        "appfilter.xml" to """
          <resources>
            <item component="${PRIMARY.packageName}" drawable="primary" />
            <dynamic-clock component="dev.keihome.regression.declaredclock" />
            <calendar component="dev.keihome.regression.declaredcalendar" />
          </resources>
        """.trimIndent().toByteArray(),
        "res/drawable-nodpi/primary.png" to solid(Color.GREEN),
      )))
      val record = input.inputStream().use { stores.import(it, "primary.zip", Build.VERSION.SDK_INT).record }
      val index = IconPackArchive().inspect(record.originalFile, File(root, "primary-scratch.icons"), Build.VERSION.SDK_INT)
      primary = IconPackDescriptor(record.id, "Synthetic primary", index.format,
        IconPackSource.Imported(record), index.entries, directives = index.directives)
    }

    fun acquire() = runBlocking {
      val catalog = requireNotNull(repository.catalog(refresh = true))
      assertTrue("Bootstrap must include the required default theme", "default" in catalog.downloadedStyles)
      assertTrue(STYLE in catalog.downloadedStyles)
      sourceHash = catalog.sourceHash
      assertEquals(1, downloads)
    }

    fun openSource(style: String): PerfectSystemFallback.Source {
      assertEquals(STYLE, style)
      openedSources++
      return PerfectSystemFallback.Source(runBlocking { repository.openFallbackSession(style) })
    }

    fun colorOs(key: String): IconPackDescriptor {
      val record = builtinCatalog.single { it.key == key }
      val index = IconPackArchive().classicIndex(record.paths, Build.VERSION.SDK_INT, {}) { path, max ->
        record.read(path, max)
      }
      return BuiltinColorOsMappings.withAliases(IconPackDescriptor(record.id, key, index.format,
        IconPackSource.Builtin(record), index.entries, directives = index.directives,
        colorOsProfile = requireNotNull(ColorOsPackProfile.load(record) {}), builtinKey = key))
    }

    fun export(
      pack: IconPackDescriptor,
      targets: List<IconPackComponent>,
      dark: Boolean = false,
      colorOs: IconPackDescriptor? = null,
      fallback: SystemIconFallback? = SystemIconFallback.perfect(STYLE),
      installedOnly: Boolean = true,
      preserved: Set<String> = emptySet(),
    ): IconPackExportResult {
      val result = exporter.export(pack, targets, darkMode = dark, sizePx = SIZE,
        systemFallback = fallback, colorOsFallback = colorOs, installedOnly = installedOnly,
        maskPolicy = NO_MASK, preservedPackages = preserved,
        recommendedMappings = RecommendedIconMappings.NONE,
        phoneCompanionExclusions = PhoneCompanionIcons.TARGETS.toSet()) {}
      assertTrue("Export staging must stay under this test's private root",
        result.archive.canonicalPath.startsWith(root.canonicalPath + File.separator))
      return result
    }

    fun importNative(file: File): IconPackDescriptor {
      val record = file.inputStream().use { stores.import(it, file.name, Build.VERSION.SDK_INT).record }
      val index = IconPackArchive().inspect(record.originalFile, File(root, "native-scratch.icons"), Build.VERSION.SDK_INT)
      assertEquals(IconPackFormat.MIUI_ICONS, index.format)
      return IconPackDescriptor(record.id, file.name, index.format, IconPackSource.Imported(record),
        index.entries, directives = index.directives)
    }

    fun bytes(result: IconPackExportResult, path: String): ByteArray = ZipFile(result.archive).use { zip ->
      zip.getInputStream(requireNotNull(zip.getEntry(path)) { "Missing exported resource: $path" }).use { it.readBytes() }
    }

    fun manifest(result: IconPackExportResult): SystemIconFallbackManifest =
      SystemIconFallbackManifest.decode(bytes(result, SystemIconFallbackManifest.PATH))

    fun assertManifest(result: IconPackExportResult, names: Set<String>, colorOs: Boolean = false) {
      val manifest = manifest(result)
      assertEquals(STYLE, manifest.style.perfectStyle)
      assertEquals(sourceHash, manifest.perfectSource)
      assertEquals(names, manifest.names)
      assertEquals(colorOs, manifest.colorOsFallback)
    }

    fun assertPerfect(result: IconPackExportResult, target: IconPackComponent, color: Int) {
      val bitmap = decode(bytes(result, flatPath(target)))
      try { assertPerfectPixels(bitmap, color, result.darkMode) } finally { bitmap.recycle() }
    }

    fun assertSolid(result: IconPackExportResult, target: IconPackComponent, color: Int) {
      val bitmap = decode(bytes(result, flatPath(target)))
      try {
        assertEquals(color, bitmap.getPixel(SIZE / 2, SIZE / 2))
        assertEquals(color, bitmap.getPixel(8, SIZE / 2))
      } finally { bitmap.recycle() }
    }

    fun assertPixels(result: IconPackExportResult, target: IconPackComponent, expected: Bitmap) {
      val bitmap = decode(bytes(result, flatPath(target)))
      val normalized = decode(png(expected))
      try {
        assertTrue("Export changed prior fallback pixels for ${target.packageName}", normalized.sameAs(bitmap))
      } finally { bitmap.recycle(); normalized.recycle() }
    }
  }

  private companion object {
    const val SIZE = 96
    const val STYLE = "OriginOS"
    val NO_MASK = IconMaskPolicy(false)
    val PRIMARY = IconPackComponent("dev.keihome.regression.primary")
    val COMPLETE = IconPackComponent("dev.keihome.regression.complete")
    val OFFLINE = IconPackComponent("dev.keihome.regression.offline")
    val EXCLUDED = IconPackComponent("dev.keihome.regression.excluded")
    val MISSING = IconPackComponent("dev.keihome.regression.missing")
    val LIVE = listOf("com.android.deskclock", "com.android.calendar", "com.xiaomi.calendar").map(::IconPackComponent)

    fun flatPath(target: IconPackComponent) = "res/drawable-xxhdpi/${target.packageName}.png"

    fun assertPerfectPixels(bitmap: Bitmap, color: Int, dark: Boolean = false) {
      assertEquals(SIZE, bitmap.width)
      assertEquals(SIZE, bitmap.height)
      val foreground = bitmap.getPixel(SIZE / 2, SIZE / 2)
      val background = bitmap.getPixel(8, SIZE / 2)
      if (dark) {
        fun dim(channel: Int) = (channel * 214 + 127) / 255
        assertEquals(Color.rgb(dim(Color.red(color)), dim(Color.green(color)), dim(Color.blue(color))), foreground)
        assertEquals(Color.red(background), Color.green(background))
        assertEquals(Color.green(background), Color.blue(background))
        assertTrue("Night plate must be dark", Color.red(background) in 10..60)
      } else {
        assertEquals("Selected OriginOS foreground must override HyperOS4", color, foreground)
        assertEquals("The base layer must remain behind the style foreground", Color.BLUE, background)
      }
    }

    fun solid(color: Int): ByteArray {
      val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
      try {
        bitmap.eraseColor(color)
        return png(bitmap)
      } finally { bitmap.recycle() }
    }

    fun foreground(color: Int): ByteArray {
      val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
      try {
        bitmap.eraseColor(Color.TRANSPARENT)
        Canvas(bitmap).drawRect(16f, 16f, 80f, 80f, Paint().apply { this.color = color })
        return png(bitmap)
      } finally { bitmap.recycle() }
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
      output.toByteArray()
    }

    fun decode(bytes: ByteArray): Bitmap =
      requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun zip(files: Map<String, ByteArray>, prefix: String = "", comment: String? = null): ByteArray =
      ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { output ->
          if (comment != null) output.setComment(comment)
          for ((path, data) in files) {
            output.putNextEntry(ZipEntry(prefix + path))
            output.write(data)
            output.closeEntry()
          }
        }
        bytes.toByteArray()
      }
  }
}
