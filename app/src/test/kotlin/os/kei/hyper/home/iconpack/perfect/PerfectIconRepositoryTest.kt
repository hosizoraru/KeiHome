package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfectIconRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()

  private class Settings : PerfectIconSettings {
    val values = HashMap<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String) { values[key] = value }
  }

  private class Http(var bytes: ByteArray) : PerfectIconTransport {
    val calls = ArrayList<Pair<String, Map<String, String>>>()
    var status = 200
    var headers = mapOf("ETag" to "\"archive-etag\"")
    var intercept: (suspend (File) -> PerfectIconHttpResponse)? = null
    override suspend fun download(
      url: String, destination: File, limit: Long, headers: Map<String, String>,
      progress: (PerfectIconProgress) -> Unit,
    ): PerfectIconHttpResponse {
      assertEquals(PerfectIconUrls.SOURCE, url)
      calls += url to headers
      intercept?.let { return it(destination) }
      if (status == 200) FileOutputStream(destination).use { output ->
        val reporter = PerfectIconProgressReporter("source.download", progress)
        reporter.update(0, bytes.size.toLong(), force = true)
        // Small chunks exercise the streaming contract without any live HTTP.
        var offset = 0
        while (offset < bytes.size) {
          val count = minOf(113, bytes.size - offset)
          output.write(bytes, offset, count)
          offset += count
          reporter.update(offset.toLong(), bytes.size.toLong())
        }
        reporter.update(offset.toLong(), bytes.size.toLong(), force = true)
      }
      return PerfectIconHttpResponse(status, this.headers)
    }
  }

  private class Fixture(val root: File, val limits: PerfectIconZipLimits = PerfectIconZipLimits()) {
    val settings = Settings()
    var time = 1_800_000_000_000L
    val files = linkedMapOf(
      "icons/third.party/0.png" to png(0), "icons/third.party/1.png" to png(1),
      "icons/third.party.Activity/0.png" to png(0), "icons/third.party.Activity/1.png" to png(1),
      "themes/default/icons/com.miui.gallery/0.png" to png(2),
      "themes/default/icons/com.miui.gallery/1.png" to png(3),
      "themes/default/icons/com.android.settings.png" to png(4),
      "themes/default/icons/third.party/0.png" to png(5),
      "themes/default/icons/third.party/1.png" to png(1),
      "customize.sh" to "this must never be exported or executed".toByteArray(),
    )
    val http = Http(zip(files))
    val repo get() = PerfectIconRepository(root, http, settings, now = { time }, limits = limits)
    fun update(comment: String? = "1".repeat(40)) { http.bytes = zip(files, comment) }
    fun dynamic(id: String, style: String = "default", tint: Int = 1) {
      val prefix = "themes/$style/layer_animating_icons/$id/"
      files[prefix + "config.xml"] = """<Config><Variable name="layer0" value="0"/></Config>""".toByteArray()
      files[prefix + "0/quiet/quietImage.png"] = png(tint)
      files[prefix + "0/fancy/manifest.xml"] = """<Icon version="1"><Image src="image.png"/></Icon>""".toByteArray()
      files[prefix + "0/fancy/image.png"] = png(tint)
      files[prefix + "original.dat"] = byteArrayOf(0, 2, 3, -1)
    }
  }

  @Test fun nativeShapeChangesRebuildDerivedOutputsWithoutDownloadingTheSourceAgain() = runBlocking {
    val f = Fixture(temporary.newFolder())
    var mask = "M 0 0 L 100 0 L 100 100 Z"
    val repo = PerfectIconRepository(f.root, f.http, f.settings, nativeMask = { mask })
    repo.catalog(refresh = true)
    val options = PerfectIconOptions(dynamic = true)
    val first = repo.generate(options, setOf("third.party")) {}
    val firstPreview = repo.previewArchive(options)
    mask = "M 0 0 L 100 100 L 0 100 Z"
    val second = repo.generate(options, setOf("third.party")) {}
    val secondPreview = repo.previewArchive(options)
    assertNotEquals(first.file, second.file)
    assertNotEquals(firstPreview, secondPreview)
    assertEquals(1, f.http.calls.size)
    ZipFile(second.file).use { zip ->
      val xml = zip.getInputStream(zip.getEntry("transform_config.xml")).reader().readText()
      assertTrue(xml.contains(mask))
      assertTrue(xml.contains("name=\"UseDynamicIcon\" value=\"false\""))
    }
  }

  @Test fun dynamicFlagDescribesSelectedResourcesRatherThanTheRequestedOption() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.dynamic("com.miui.gallery")
    f.update()
    val repo = f.repo
    repo.catalog(refresh = true)
    val partial = repo.generate(PerfectIconOptions(), setOf("third.party")) {}
    val full = repo.generate(PerfectIconOptions(installedOnly = false), emptySet()) {}
    for ((result, expected) in listOf(partial to false, full to true)) {
      ZipFile(result.file).use { zip ->
        val xml = zip.getInputStream(zip.getEntry("transform_config.xml")).reader().readText()
        assertTrue(xml.contains("name=\"UseDynamicIcon\" value=\"$expected\""))
      }
    }
    assertEquals(1, f.http.calls.size)
  }

  @Test fun localCatalogAndMissingSnapshotGenerationNeverUseNetwork() = runBlocking {
    val f = Fixture(temporary.newFolder())
    assertNull(f.repo.catalog())
    assertEquals(PerfectIconOptions(), f.repo.options())
    rejectedSuspend { f.repo.generate(PerfectIconOptions(), setOf("third.party")) {} }
    assertEquals(0L, f.repo.cacheBytes())
    assertTrue(f.http.calls.isEmpty())
    val options = PerfectIconOptions("default", false, false)
    f.repo.setOptions(options)
    assertEquals(options, f.repo.options())
  }

  @Test fun explicitRefreshMakesExactlyOneCodeloadRequestAndNoRestOrRawRequests() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val catalog = f.repo.catalog(true)!!
    assertEquals("1".repeat(40), catalog.revision)
    assertEquals(64, catalog.sourceHash.length)
    assertEquals(listOf("default"), catalog.styles)
    assertEquals(2, catalog.iconCount)
    assertFalse(catalog.upToDate)
    assertEquals(f.http.bytes.size.toLong(), catalog.archiveBytes)
    assertEquals(0L, f.repo.cacheBytes())
    assertEquals(1, f.http.calls.size)
    assertEquals(PerfectIconUrls.SOURCE, f.http.calls.single().first)
    assertEquals(catalog, f.repo.catalog())
    assertEquals(1, f.http.calls.size)
  }

  @Test fun refreshReportsDownloadBytesThenVerifiedEntriesAndLocalReadsStaySilent() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val events = arrayListOf<PerfectIconProgress>()
    f.repo.catalog(refresh = true) { events += it }
    val download = events.filter { it.label == "source.download" }
    assertTrue(download.isNotEmpty())
    assertEquals(f.http.bytes.size, download.last().done)
    assertEquals(f.http.bytes.size, download.last().total)
    val verified = events.filter { it.label == "source.verify" }
    assertTrue(verified.isNotEmpty())
    assertEquals(f.files.size, verified.last().done)
    assertEquals(f.files.size, verified.last().total)
    assertTrue(events.indexOf(download.last()) < events.indexOf(verified.first()))
    events.clear()
    f.repo.catalog(refresh = false) { events += it }
    assertTrue(events.isEmpty())
    assertEquals(1, f.http.calls.size)
  }

  @Test fun progressThrottleUses250MillisecondsAndAlwaysEmitsFinalUnknownTotalsSafely() {
    var time = 0L
    val events = arrayListOf<PerfectIconProgress>()
    val reporter = PerfectIconProgressReporter("source.download", { events += it }, { time })
    reporter.update(0, 0, force = true)
    time = 100_000_000
    reporter.update(10, 0)
    time = 249_999_999
    reporter.update(20, 0)
    assertEquals(1, events.size)
    time = 250_000_000
    reporter.update(30, 0)
    assertEquals(2, events.size)
    reporter.update(40, -1, force = true)
    assertEquals(PerfectIconProgress(40, 0, "source.download"), events.last())
    reporter.update(Long.MAX_VALUE, Long.MAX_VALUE, force = true)
    assertEquals(Int.MAX_VALUE, events.last().done)
    assertEquals(Int.MAX_VALUE, events.last().total)
  }

  @Test fun blankStyleExactIdsAndNativeZipAreLocalWithNoSourceMetadataOrScripts() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
    val result = f.repo.generate(PerfectIconOptions(dynamic = false),
      setOf("com.miui.gallery", "com.android.settings", "third.party.Activity", "mark.via")) {}
    assertEquals(3, result.mapped)
    assertEquals(3, result.staticCount)
    assertEquals(0, result.dynamicCount)
    assertEquals(5, result.downloaded)
    assertEquals(1, f.http.calls.size)
    ZipFile(result.file).use { zip ->
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/com.miui.gallery/0.png"))
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/com.android.settings.png"))
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/third.party.Activity/1.png"))
      assertNull(zip.getEntry("res/drawable-xxhdpi/third.party/0.png"))
      for (name in listOf("module.prop", "customize.sh", "description.xml", "system/media/theme/default/icons")) {
        assertNull(zip.getEntry(name))
      }
      zip.entries().asSequence().forEach {
        assertFalse("mark.via" in it.name)
        assertEquals(if (it.name.endsWith(".png")) ZipEntry.STORED else ZipEntry.DEFLATED, it.method)
      }
    }
  }

  @Test fun winningStaticAndDynamicDirectoriesPreserveOriginalBytes() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.dynamic("com.miui.gallery", "common", 1)
    f.dynamic("com.miui.gallery", "default", 3)
    f.dynamic("third.party", "common", 2)
    f.update()
    f.repo.catalog(true)
    val result = f.repo.generate(PerfectIconOptions(), setOf("com.miui.gallery", "third.party")) {}
    assertEquals(2, result.mapped)
    assertEquals(2, result.dynamicCount)
    ZipFile(result.file).use { zip ->
      assertArrayEquals(png(5), zip.getInputStream(zip.getEntry("res/drawable-xxhdpi/third.party/0.png")).readBytes())
      assertArrayEquals(png(3), zip.getInputStream(zip.getEntry("layer_animating_icons/com.miui.gallery/0/fancy/image.png")).readBytes())
      assertArrayEquals(byteArrayOf(0, 2, 3, -1), zip.getInputStream(zip.getEntry("layer_animating_icons/com.miui.gallery/original.dat")).readBytes())
    }
    assertEquals(1, f.http.calls.size)
  }

  @Test fun repeatGenerationIsStableAndIssuesZeroHttpEvenAfterSourceChangesRemotely() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
    val options = PerfectIconOptions(dynamic = false)
    val first = f.repo.generate(options, setOf("com.miui.gallery")) {}
    f.http.calls.clear()
    f.http.intercept = { throw IOException("No network permitted") }
    val again = f.repo.generate(options, setOf("com.miui.gallery")) {}
    assertEquals(first.file, again.file)
    assertEquals(0, again.downloaded)
    assertEquals(2, again.reused)
    assertTrue(f.http.calls.isEmpty())
  }

  @Test fun conditional304RetainsFileDownloadTimeAndCommitButUpdatesCheckTime() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val initial = f.repo.catalog(true)!!
    val source = File(f.root, "source").listFiles()!!.single()
    val sourceBytes = source.readBytes()
    f.time += 5000
    f.http.status = 304
    val checked = f.repo.catalog(true)!!
    assertEquals(initial.revision, checked.revision)
    assertEquals(initial.sourceHash, checked.sourceHash)
    assertEquals(initial.downloadedAt, checked.downloadedAt)
    assertEquals(initial.archiveBytes, checked.archiveBytes)
    assertEquals(f.time, checked.updatedAt)
    assertTrue(checked.upToDate)
    assertArrayEquals(sourceBytes, source.readBytes())
    assertEquals(mapOf("If-None-Match" to "\"archive-etag\""), f.http.calls.last().second)
    assertEquals(2, f.http.calls.size)
  }

  @Test fun sameSizeDamagedSnapshotDoesNotSendConditionalValidatorAndIsRepaired() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val first = f.repo.catalog(refresh = true)!!
    val archive = File(f.root, "source").listFiles()!!.single()
    val corrupted = archive.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
    archive.writeBytes(corrupted)
    f.time += 60_000
    val repaired = f.repo.catalog(refresh = true)!!
    assertTrue(f.http.calls.last().second.isEmpty())
    assertArrayEquals(f.http.bytes, archive.readBytes())
    assertEquals(first.sourceHash, repaired.sourceHash)
    assertEquals(first.revision, repaired.revision)
    f.repo.clearDownloadCache()
    assertTrue(f.repo.generate(PerfectIconOptions(), setOf("third.party")) {}.mapped > 0)
  }

  @Test fun missingCommitCommentHasHonestEmptyRevisionAndSeparateArchiveHash() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.update(comment = null)
    val catalog = f.repo.catalog(true)!!
    assertEquals("", catalog.revision)
    assertTrue(catalog.sourceHash.matches(Regex("[0-9a-f]{64}")))
    assertEquals("", f.repo.generate(PerfectIconOptions(dynamic = false), setOf("third.party")) {}.revision)
  }

  @Test fun refreshedCommitReusesBlobsAcrossSnapshotsAndRetiresOldSourceOnlyOnSuccess() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
    val options = PerfectIconOptions(dynamic = false)
    val first = f.repo.generate(options, setOf("com.miui.gallery")) {}
    f.update("a".repeat(40))
    f.time++
    val refreshed = f.repo.catalog(true)!!
    assertEquals("a".repeat(40), refreshed.revision)
    assertFalse(refreshed.upToDate)
    val again = f.repo.generate(options, setOf("com.miui.gallery")) {}
    assertEquals(0, again.downloaded)
    assertEquals(2, again.reused)
    assertEquals(2, f.http.calls.size)
    assertEquals(1, File(f.root, "source").listFiles()!!.size)
    assertTrue(first.file.exists())
  }

  @Test fun interruptedDownloadAndCancellationNeverReplaceLastUsableSnapshot() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val initial = f.repo.catalog(true)
    val pointer = File(f.root, "catalog.json").readBytes()
    f.http.intercept = { file -> file.writeBytes(byteArrayOf(1, 2, 3)); throw IOException("Interrupted") }
    rejectedSuspend { f.repo.catalog(true) }
    assertEquals(initial, f.repo.catalog())
    assertArrayEquals(pointer, File(f.root, "catalog.json").readBytes())
    f.time += 31_000
    f.http.intercept = { file -> file.writeBytes(byteArrayOf(1, 2)); throw CancellationException("cancelled download") }
    try { f.repo.catalog(true); fail("Expected cancellation") } catch (_: CancellationException) { }
    assertEquals(initial, f.repo.catalog())
    assertFalse(f.root.walkTopDown().any { it.name.endsWith(".part") })
    assertTrue(f.repo.generate(PerfectIconOptions(dynamic = false), setOf("com.miui.gallery")) {}.file.exists())
  }

  @Test fun malformedTraversalForeignRootsAndLinksCannotOverwriteOldCatalog() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val initial = f.repo.catalog(true)
    val invalid = listOf(
      byteArrayOf(1, 2, 3), zip(mapOf("icons/../escape" to png(0))),
      zip(mapOf("icons/evil\\path" to png(0))), zip(mapOf("/absolute" to png(0))),
      zip(mapOf("icons/a/0.png" to png(0)), prefix = "Other-main/"),
      zip(mapOf("icons/a/0.png" to png(0))).also { bytes ->
        val at = signature(bytes, 0x02014b50)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at + 38, 0xa1ff shl 16)
      },
    )
    for (bytes in invalid) {
      f.http.bytes = bytes
      rejectedSuspend { f.repo.catalog(true) }
      assertEquals(initial, f.repo.catalog())
      assertEquals(1, File(f.root, "source").listFiles()!!.size)
    }
  }

  @Test fun zipCrcAndLocalCentralNameMismatchAreRejected() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val initial = f.repo.catalog(true)
    val corrupt = zip(f.files, stored = true)
    val b = ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN)
    val data = 30 + (b.getShort(26).toInt() and 65535) + (b.getShort(28).toInt() and 65535)
    corrupt[data] = (corrupt[data].toInt() xor 1).toByte()
    f.http.bytes = corrupt
    rejectedSuspend { f.repo.catalog(true) }
    assertEquals(initial, f.repo.catalog())
    val mismatched = zip(f.files)
    mismatched[30] = 'X'.code.toByte()
    f.http.bytes = mismatched
    rejectedSuspend { f.repo.catalog(true) }
    assertEquals(initial, f.repo.catalog())
  }

  @Test fun actualCompressedExpandedEntryAndCompressionRatioBoundsRejectBeforePublication() = runBlocking {
    val original = Fixture(temporary.newFolder())
    val size = original.http.bytes.size.toLong()
    for (limits in listOf(
      PerfectIconZipLimits(compressed = size - 1), PerfectIconZipLimits(expanded = 1),
      PerfectIconZipLimits(entries = 2),
    )) {
      val f = Fixture(temporary.newFolder(), limits)
      rejectedSuspend { f.repo.catalog(true) }
      assertNull(f.repo.catalog())
    }
    val f = Fixture(temporary.newFolder())
    val initial = f.repo.catalog(true)
    f.files["addition/bomb.txt"] = ByteArray(2 * 1024 * 1024)
    f.update()
    rejectedSuspend { f.repo.catalog(true) }
    assertEquals(initial, f.repo.catalog())
  }

  @Test fun codeloadCooldownPersistsAndLegacyRestCooldownDoesNotBlockIt() = runBlocking {
    for (status in listOf(403, 429)) {
      val f = Fixture(temporary.newFolder())
      f.settings.write("retryAt", (f.time + 1000000).toString())
      val initial = f.repo.catalog(true)
      f.http.status = status
      val reset = f.time + 7_200_000
      f.http.headers = mapOf("X-RateLimit-Reset" to (reset / 1000).toString(), "X-RateLimit-Remaining" to "0")
      rejectedSuspend { f.repo.catalog(true) }
      assertEquals(reset, f.repo.retryAtMillis())
      repeat(3) { rejectedSuspend { f.repo.catalog(true) } }
      assertEquals(initial, f.repo.catalog())
      assertEquals(2, f.http.calls.size)
    }
  }

  @Test fun ordinary403IsAnIoFailureWithShortLocalRetryDelayRatherThanRateLimit() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val catalog = f.repo.catalog(true)
    f.http.status = 403
    f.http.headers = emptyMap()
    try {
      f.repo.catalog(true)
      fail("Expected HTTP failure")
    } catch (failure: IOException) {
      assertFalse(failure is PerfectIconCooldownException)
    }
    assertEquals(f.time + 30_000, f.repo.retryAtMillis())
    assertEquals(catalog, f.repo.catalog())
    assertEquals(2, f.http.calls.size)
  }

  @Test fun clearDerivedCachePreservesSourceCatalogPublicationsAndOfflineGeneration() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val catalog = f.repo.catalog(true)
    val options = PerfectIconOptions(dynamic = false)
    val first = f.repo.generate(options, setOf("com.miui.gallery")) {}
    val imported = temporary.newFile("imported-independent.zip")
    first.file.copyTo(imported, overwrite = true)
    val before = f.repo.cacheBytes()
    val blobs = File(f.root, "blobs").listFiles()!!.sumOf { it.length() }
    assertEquals(blobs, before)
    assertEquals(blobs, f.repo.clearDownloadCache())
    assertEquals(before - blobs, f.repo.cacheBytes())
    assertEquals(catalog, f.repo.catalog())
    assertTrue(first.file.exists())
    assertTrue(imported.exists())
    val again = f.repo.generate(options, setOf("com.miui.gallery")) {}
    assertEquals(2, again.downloaded)
    assertEquals(1, f.http.calls.size)
    assertEquals(first.file, again.file)
  }

  @Test fun clearingCacheIsSerializedWithGeneration() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val generation = async {
      f.repo.generate(PerfectIconOptions(dynamic = false), setOf("com.miui.gallery")) {
        if (it.done == 1) { started.complete(Unit); runBlocking { release.await() } }
      }
    }
    started.await()
    val clearing = async { f.repo.clearDownloadCache() }
    assertFalse(clearing.isCompleted)
    release.complete(Unit)
    val result = generation.await()
    assertTrue(clearing.await() > 0)
    assertTrue(result.file.exists())
    assertTrue(File(f.root, "blobs").listFiles()!!.isEmpty())
  }

  @Test fun cachedCorruptBlobIsRepairedFromSourceLocallyAndCancellationPreservesPublication() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
    val options = PerfectIconOptions(dynamic = false)
    val first = f.repo.generate(options, setOf("com.miui.gallery")) {}
    val bytes = first.file.readBytes()
    File(f.root, "blobs/${PerfectIconFormat.sha(png(2))}").writeBytes(png(3))
    val repaired = f.repo.generate(options, setOf("com.miui.gallery")) {}
    assertEquals(1, repaired.downloaded)
    assertEquals(1, repaired.reused)
    try { f.repo.generate(options, setOf("com.miui.gallery")) { throw CancellationException() }; fail() }
    catch (_: CancellationException) { }
    assertArrayEquals(bytes, first.file.readBytes())
    assertEquals(1, f.http.calls.size)
  }

  @Test fun urlsAreFixedAndNeverAcceptApiRawCredentialsBranchesOrMirrors() {
    PerfectIconUrls.check(PerfectIconUrls.SOURCE)
    for (url in listOf("https://api.github.com/repos/${PerfectIconUrls.REPO}/commits/main",
      "https://raw.githubusercontent.com/${PerfectIconUrls.REPO}/main/icons/x/0.png",
      PerfectIconUrls.SOURCE.replace("main", "other"), PerfectIconUrls.SOURCE + "?token=secret",
      PerfectIconUrls.SOURCE.replace("https://", "https://user:pass@"),
      PerfectIconUrls.SOURCE.replace("codeload.github.com", "mirror.example"))) {
      rejected { PerfectIconUrls.check(url) }
    }
  }

  @Test fun previewUsesCurrentStyleWithoutChangingPublishedArchiveSettingsOrBlobCache() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.files["themes/HyperOS4/icons/com.miui.gallery/0.png"] = png(8)
    f.files["themes/HyperOS4/icons/com.miui.gallery/1.png"] = png(9)
    f.update()
    val catalog = f.repo.catalog(true)
    f.repo.setOptions(PerfectIconOptions(dynamic = false))
    val publication = f.repo.generate(PerfectIconOptions(dynamic = false), setOf("com.miui.gallery")) {}
    val original = publication.file.readBytes()
    val settings = f.settings.values.toMap()
    val blobs = File(f.root, "blobs").listFiles()!!.associate { it.name to it.readBytes().toList() }
    val preview = f.repo.previewArchive(PerfectIconOptions(style = "HyperOS4", dynamic = false), limit = 1)
    assertEquals("previews", requireNotNull(preview.parentFile).name)
    assertEquals(catalog, f.repo.catalog())
    assertEquals(settings, f.settings.values)
    assertArrayEquals(original, publication.file.readBytes())
    assertEquals(listOf(publication.file), File(f.root, "generated").listFiles()!!.toList())
    assertEquals(blobs, File(f.root, "blobs").listFiles()!!.associate { it.name to it.readBytes().toList() })
    ZipFile(preview).use { zip ->
      assertArrayEquals(png(8), zip.getInputStream(zip.getEntry("res/drawable-xxhdpi/com.miui.gallery/0.png")).readBytes())
      assertArrayEquals(png(9), zip.getInputStream(zip.getEntry("res/drawable-xxhdpi/com.miui.gallery/1.png")).readBytes())
      assertNull(zip.getEntry("description.xml"))
    }
    val otherStyle = f.repo.previewArchive(PerfectIconOptions(dynamic = false), 1)
    assertNotEquals(preview, otherStyle)
    ZipFile(otherStyle).use { zip ->
      assertArrayEquals(png(2), zip.getInputStream(zip.getEntry("res/drawable-xxhdpi/com.miui.gallery/0.png")).readBytes())
    }
    assertEquals(1, f.http.calls.size)
  }

  @Test fun previewPreservesDynamicOptionAndCatalogSamplingIgnoresInstalledOnly() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.dynamic("com.miui.gallery", tint = 7)
    f.update()
    f.repo.catalog(true)
    val animated = f.repo.previewArchive(PerfectIconOptions(dynamic = true, installedOnly = true), 1)
    val static = f.repo.previewArchive(PerfectIconOptions(dynamic = false), 1)
    assertNotEquals(animated, static)
    assertEquals(animated, f.repo.previewArchive(PerfectIconOptions(dynamic = true, installedOnly = false), 1))
    ZipFile(animated).use { zip ->
      assertArrayEquals(byteArrayOf(0, 2, 3, -1), zip.getInputStream(
        zip.getEntry("layer_animating_icons/com.miui.gallery/original.dat")).readBytes())
      assertArrayEquals(png(7), zip.getInputStream(
        zip.getEntry("layer_animating_icons/com.miui.gallery/0/fancy/image.png")).readBytes())
    }
    ZipFile(static).use { zip ->
      assertFalse(zip.entries().asSequence().any { it.name.startsWith("layer_animating_icons/") })
    }
    assertFalse(File(f.root, "generated").exists())
    assertFalse(File(f.root, "blobs").exists())
    assertEquals(1, f.http.calls.size)
  }

  @Test fun previewCacheReturnsSameFileWithoutReadingRepositoryAndRepairsDamagedCachedZip() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(true)
    val options = PerfectIconOptions(dynamic = false)
    val first = f.repo.previewArchive(options, 1)
    val original = first.readBytes()
    first.writeBytes(byteArrayOf(1, 2, 3))
    assertEquals(first, f.repo.previewArchive(options, 1))
    assertArrayEquals(original, first.readBytes())
    val source = File(f.root, "source").listFiles()!!.single()
    val sourceBytes = source.readBytes()
    source.writeBytes(ByteArray(sourceBytes.size))
    // A validated cached preview needs the catalog but never opens/re-hashes the source ZIP.
    assertEquals(first, f.repo.previewArchive(options, 1))
    assertArrayEquals(original, first.readBytes())
    assertEquals(1, f.http.calls.size)
  }

  @Test fun previewLimitIsClampedAndClearingCachePreservesFullPublicationAndSource() = runBlocking {
    val f = Fixture(temporary.newFolder())
    repeat(80) { index ->
      f.files["icons/sample.$index/0.png"] = png(0)
      f.files["icons/sample.$index/1.png"] = png(1)
    }
    f.update()
    val catalog = f.repo.catalog(true)
    val options = PerfectIconOptions(dynamic = false)
    val publication = f.repo.generate(options, setOf("com.miui.gallery")) {}
    val one = f.repo.previewArchive(options, 0)
    assertEquals(one, f.repo.previewArchive(options, 1))
    val sixty = f.repo.previewArchive(options, 999)
    assertEquals(sixty, f.repo.previewArchive(options, 60))
    ZipFile(sixty).use { zip ->
      val ids = zip.entries().asSequence().map { it.name }.filter { it.startsWith("res/drawable-xxhdpi/") }
        .map { it.removePrefix("res/drawable-xxhdpi/").substringBefore('/').removeSuffix(".png") }.toSet()
      assertEquals(60, ids.size)
      assertTrue("com.miui.gallery" in ids && "com.android.settings" in ids)
    }
    assertTrue(sixty.length() <= 33L * 1024 * 1024)
    val before = f.repo.cacheBytes()
    assertEquals(before, f.repo.clearDownloadCache())
    assertEquals(0L, f.repo.cacheBytes())
    assertFalse(one.exists())
    assertFalse(sixty.exists())
    assertTrue(publication.file.exists())
    assertEquals(catalog, f.repo.catalog())
    assertTrue(f.repo.previewArchive(options).exists())
    assertEquals(1, f.http.calls.size)
  }

  @Test fun missingSnapshotAndOversizedPreviewNeverRequestNetworkOrPublishGeneratedFiles() = runBlocking {
    val absent = Fixture(temporary.newFolder())
    rejectedSuspend { absent.repo.previewArchive(PerfectIconOptions()) }
    assertTrue(absent.http.calls.isEmpty())
    val f = Fixture(temporary.newFolder())
    f.dynamic("com.miui.gallery")
    repeat(2049) { f.files["themes/default/layer_animating_icons/com.miui.gallery/extra/$it.dat"] = byteArrayOf(1) }
    f.update()
    f.repo.catalog(true)
    rejectedSuspend { f.repo.previewArchive(PerfectIconOptions(), 1) }
    assertFalse(File(f.root, "generated").exists())
    assertFalse(File(f.root, "previews").walkTopDown().any { it.isFile })
    assertEquals(1, f.http.calls.size)
  }

  companion object {
    internal fun zip(
      files: Map<String, ByteArray>, comment: String? = "1".repeat(40),
      prefix: String = PerfectIconSourceZip.PREFIX, stored: Boolean = false,
    ): ByteArray = ByteArrayOutputStream().apply {
      ZipOutputStream(this, Charsets.UTF_8).use { zip ->
        if (comment != null) zip.setComment(comment)
        files.forEach { (path, bytes) ->
          zip.putNextEntry(ZipEntry(prefix + path).apply {
            if (stored) { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }
          })
          zip.write(bytes)
          zip.closeEntry()
        }
      }
    }.toByteArray()

    private fun signature(bytes: ByteArray, signature: Int): Int {
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      return (0..bytes.size - 4).first { buffer.getInt(it) == signature }
    }

    internal fun png(tint: Int): ByteArray {
      val output = ByteArrayOutputStream()
      DataOutputStream(output).use { data ->
        data.writeLong(0x89504e470d0a1a0aUL.toLong())
        fun chunk(type: String, bytes: ByteArray) {
          val name = type.toByteArray(Charsets.US_ASCII)
          data.writeInt(bytes.size)
          data.write(name)
          data.write(bytes)
          data.writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
        }
        val header = ByteArrayOutputStream().apply {
          DataOutputStream(this).apply { writeInt(1); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) }
        }.toByteArray()
        val compressed = ByteArrayOutputStream().apply {
          DeflaterOutputStream(this).use { it.write(byteArrayOf(0, tint.toByte(), 20, 30, -1)) }
        }.toByteArray()
        chunk("IHDR", header)
        chunk("IDAT", compressed)
        chunk("IEND", byteArrayOf())
      }
      return output.toByteArray()
    }

    private fun rejected(block: () -> Unit) {
      try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }
    private suspend fun rejectedSuspend(block: suspend () -> Unit) {
      try { block(); fail("Expected rejection") } catch (error: Exception) {
        if (error is CancellationException) throw error
      }
    }
  }
}
