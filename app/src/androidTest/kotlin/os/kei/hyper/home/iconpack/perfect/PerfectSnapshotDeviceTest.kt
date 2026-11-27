package os.kei.hyper.home.iconpack.perfect

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.LauncherApps
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackImportResult
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.sha256
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.withForegroundDeviceCheck

/** Explicit real-source acceptance; ordinary suites neither download nor modify this source. */
@RunWith(AndroidJUnit4::class)
class PerfectSnapshotDeviceTest {
  @Test fun retainedHyperSnapshotImportsAndPackagesOriginalAnimationsOffline() {
    val input = InstrumentationRegistry.getArguments().getString("perfectHyperSourcePath")
    assumeTrue(input != null)
    withForegroundDeviceCheck {
      runBlocking { verifyHyperHandoff(File(requireNotNull(input))) }
    }
  }

  private suspend fun verifyHyperHandoff(source: File) {
    require(source.isFile)
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val root = kotlin.io.path.createTempDirectory(base.cacheDir.toPath(), "perfect-hyper-test-").toFile()
    val context = object : ContextWrapper(base) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
      override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
      override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    }
    try {
      val settings = object : PerfectIconSettings {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) { values[key] = value }
      }
      val repoRoot = File(context.filesDir, "perfect-icons")
      val backend = ZipBackedPerfectGitBackend(repoRoot, source)
      val generator = PerfectIconRepository(repoRoot, settings,
        staticRenderer = { layers, mask, check ->
          PerfectStaticNightRenderer.render(layers, mask, check = check, nativeViewport = layers.size == 2)
        },
        staticEquivalentRenderer = { flat, layers, mask, check ->
          PerfectStaticNightRenderer.render(listOf(flat), mask, equivalentLayers = layers, check = check)
        },
        nativeCompositeRenderer = { layers, check ->
          PerfectStaticNightRenderer.render(layers, null, check = check, nativeViewport = true)
        },
        nativeSnapshotRenderer = { flat, layers, check ->
          PerfectStaticNightRenderer.render(listOf(flat), null, equivalentLayers = layers,
            nativeNightComposition = true, check = check)
        },
        dynamicNightRenderer = { bytes, background, check -> PerfectDynamicNightRenderer.render(bytes, background, check) },
        git = backend)
      val repository = IconPackRepository(context)
      val catalog = requireNotNull(generator.catalog(refresh = true, styles = setOf("Hyper", "HyperOSClassic")))
      val allStyles = InstrumentationRegistry.getArguments().getString("perfectHyperAll") == "true"
      for (style in listOf("Hyper", "HyperOSClassic")) {
        assertTrue(style, style in catalog.styles)
        // This fixture has 32 source groups. The weather group has incomplete layer declarations.
        val apps = ZipFile(source).use { upstream ->
          upstream.entries().asSequence().mapNotNull { entry ->
            val parts = entry.name.split('/')
            parts.getOrNull(4)?.takeIf {
              parts.size == 6 && parts[1] == "themes" && parts[2] in setOf("common", style) &&
                parts[3] == "layer_animating_icons" && parts[5] == "config.xml"
            }
          }.toSet()
        }
        val result = generator.generate(PerfectIconOptions(style, installedOnly = !allStyles, dynamic = true, staticNight = true), apps) {}
        assertTrue("$style retained ${result.dynamicCount} dynamic icons", result.dynamicCount >= 31)
        assertTrue("$style generated ${result.derivedDynamicCount} dark adaptations", result.derivedDynamicCount >= 13)
        val imported = repository.importPerfectPublication(result.file, "$style.zip")
        assertTrue(imported.toString(), imported is IconPackImportResult.Success)
        val pack = (imported as IconPackImportResult.Success).pack
        val publicationBytes = result.file.length()
        check(result.file.delete())
        val handoff = IconPackHandoffs.prepare(context, repository, IconPackPreviewRepository(context),
          pack, apps.map(::IconPackComponent), IconPackAppearance.SYSTEM,
          completeSystemIconConfig = false, applyCustomIcons = false)
        repository.removeImported(pack)
        val icons = File(root, "$style.icons")
        ZipFile(handoff.file).use { mtz ->
          mtz.getInputStream(mtz.getEntry("icons")).use { inputStream ->
            icons.outputStream().use { inputStream.copyTo(it) }
          }
        }
        check(handoff.file.delete())
        ZipFile(source).use { original ->
          ZipFile(icons).use { packed ->
            val nativeFiles = packed.entries().asSequence().filter {
              it.name.startsWith("layer_animating_icons/") && it.name.endsWith(".json")
            }.toList()
            assertTrue("$style retained ${nativeFiles.size} vector files", nativeFiles.size >= 12)
            for (entry in nativeFiles) {
              val bytes = packed.getInputStream(entry).use { it.readBytes() }
              val matchesOriginal = original.entries().asSequence().filter {
                it.name.endsWith("/themes/$style/${entry.name}") || it.name.endsWith("/themes/common/${entry.name}")
              }.any { candidate -> original.getInputStream(candidate).use { it.readBytes() }.contentEquals(bytes) }
              assertTrue("Missing or changed upstream animation: $style/${entry.name}", matchesOriginal)
            }
            for (calendar in listOf("com.android.calendar", "com.xiaomi.calendar")) {
              val path = "layer_animating_icons/$calendar/2/fancy/manifest.xml"
              val manifest = packed.getInputStream(requireNotNull(packed.getEntry(path))).use { it.reader().readText() }
              assertTrue(path, manifest.contains("size=\"43.83\"") && manifest.contains("strToUpperCase"))
            }
            android.util.Log.i("KeiHomePerfectTest",
              "$style ($publicationBytes bytes): ${result.staticCount} static, ${result.dynamicCount} dynamic, " +
                "${result.derivedDynamicCount} dark adaptations, ${nativeFiles.size} unchanged vector files")
          }
        }
        check(icons.delete())
      }
      assertEquals(1, backend.fetches)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun realSnapshotGeneratesAndPreviewsOfflineAndChecksCurrentSourceOnce() = runBlocking {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue(arguments.containsKey("perfectSourcePath"))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val input = File(requireNotNull(arguments.getString("perfectSourcePath")))
    require(input.isFile)
    val settings = object : PerfectIconSettings {
      val values = mutableMapOf<String, String>()
      override fun read(key: String) = values[key]
      override fun write(key: String, value: String) { values[key] = value }
    }
    val root = File(context.filesDir, "perfect-icons")
    val backend = ZipBackedPerfectGitBackend(root, input)
    val repository = PerfectIconRepository(root, settings, git = backend)
    val catalog = requireNotNull(repository.catalog(refresh = true, styles = setOf("default", "HyperOS4")))
    assertEquals(1, backend.fetches)
    assertTrue(catalog.iconCount > 2_000)
    assertTrue(catalog.revision.matches(Regex("[0-9a-f]{40}")))
    assertEquals(input.length(), catalog.archiveBytes)
    val targets = context.getSystemService(LauncherApps::class.java)
      .getActivityList(null, Process.myUserHandle()).map { it.componentName.packageName }.toSet()
    assertTrue(targets.isNotEmpty())
    val styles = listOf("", "HyperOS4").filter { it.isEmpty() || it in catalog.styles }
    val summary = JSONObject().put("revision", catalog.revision).put("sourceBytes", catalog.archiveBytes)
    for (style in styles) {
      val options = PerfectIconOptions(style = style)
      val first = repository.generate(options, targets) {}
      assertTrue(first.mapped > 0)
      val repeat = repository.generate(options, targets) {}
      assertEquals(first.file, repeat.file)
      assertEquals(0, repeat.downloaded)
      val preview = repository.previewArchive(options, 24)
      val coroutine = currentCoroutineContext()
      val scratch = File(context.cacheDir, "perfect-acceptance-icons.zip")
      val index = try {
        IconPackArchive().inspect(preview, scratch, android.os.Build.VERSION.SDK_INT) { coroutine.ensureActive() }
      } finally {
        scratch.delete()
      }
      assertTrue(index.entries.isNotEmpty())
      val descriptor = IconPackDescriptor(
        id = "perfect-acceptance:${style.ifEmpty { "default" }}",
        displayName = style.ifEmpty { "default" },
        format = index.format,
        source = IconPackSource.Generated(preview, sha256(preview) {}, preview.lastModified()),
        entries = index.entries,
        directives = index.directives,
      )
      val renderer = IconPackPreviewRepository(context)
      val rendered = index.entries.take(6).count { renderer.load(descriptor, it, 96).light != null }
      assertTrue("Sample icons must actually decode", rendered > 0)
      summary.put(style.ifEmpty { "default" }, JSONObject().put("mapped", first.mapped)
        .put("static", first.staticCount).put("dynamic", first.dynamicCount)
        .put("previewEntries", index.entries.size).put("renderedSamples", rendered))
    }
    repository.clearDownloadCache()
    assertEquals(0L, repository.cacheBytes())
    assertTrue(repository.previewArchive(PerfectIconOptions(), 24).isFile)
    assertEquals("All generation, previews and cache repair stay offline", 1, backend.fetches)
    if (arguments.getString("perfectOnline") == "true") {
      val online = PerfectIconRepository(context)
      val checked = requireNotNull(withTimeout(60_000) { online.catalog(refresh = true) })
      assertTrue("Actual conditional check should avoid another full transfer", checked.upToDate)
      assertEquals(catalog.revision, checked.revision)
      assertEquals(catalog.downloadedAt, checked.downloadedAt)
      summary.put("appUidConditionalCheck", true)
    }
    File(context.cacheDir, "perfect-snapshot-acceptance.json").writeText(summary.toString(2))
  }
}
