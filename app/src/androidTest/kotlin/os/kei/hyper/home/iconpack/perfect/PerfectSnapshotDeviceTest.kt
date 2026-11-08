package os.kei.hyper.home.iconpack.perfect

import android.content.pm.LauncherApps
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.sha256

/** Explicit real-source acceptance; ordinary suites neither download nor modify this source. */
@RunWith(AndroidJUnit4::class)
class PerfectSnapshotDeviceTest {
  @Test fun realSnapshotGeneratesAndPreviewsOfflineAndChecksCurrentSourceOnce() = runBlocking {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue(arguments.containsKey("perfectSourcePath"))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val input = File(requireNotNull(arguments.getString("perfectSourcePath")))
    require(input.isFile)
    var requests = 0
    val settings = object : PerfectIconSettings {
      val values = mutableMapOf<String, String>()
      override fun read(key: String) = values[key]
      override fun write(key: String, value: String) { values[key] = value }
    }
    val transport = PerfectIconTransport { url, destination, limit, headers, progress ->
      check(url == PerfectIconUrls.SOURCE)
      check(input.length() <= limit)
      requests++
      val etag = requireNotNull(arguments.getString("perfectEtag"))
      if (headers["If-None-Match"] == etag) {
        return@PerfectIconTransport PerfectIconHttpResponse(304, mapOf("ETag" to etag))
      }
      check(headers.isEmpty())
      input.inputStream().use { source -> destination.outputStream().use { output -> source.copyTo(output) } }
      progress(PerfectIconProgress(input.length().toInt(), input.length().toInt(), "source.download"))
      PerfectIconHttpResponse(200, mapOf("ETag" to etag))
    }
    val root = File(context.filesDir, "perfect-icons")
    val repository = PerfectIconRepository(root, transport, settings)
    val catalog = requireNotNull(repository.catalog(refresh = true))
    assertEquals(1, requests)
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
    assertEquals("All generation, previews and cache repair stay offline", 1, requests)
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
