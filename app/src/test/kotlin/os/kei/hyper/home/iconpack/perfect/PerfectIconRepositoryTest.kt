package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfectIconRepositoryTest {
  @Test fun customPairPersistsAndGeneratesOfflineWithOriginalBytes() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val id = "third.party#other.Main"
    val layers = PerfectCustomStatic(png(6), png(7))
    val options = PerfectIconOptions(installedOnly = true, dynamic = false, staticNight = false)
    assertEquals(listOf(id), f.repo.saveCustomStatic(id, layers, create = true))
    assertArrayEquals(layers.foreground, f.repo.readCustomStatic(id).foreground)
    val result = f.repo.generate(options, setOf(id)) {}
    ZipFile(result.file).use { zip ->
      layers.layers().forEachIndexed { index, bytes ->
        val entry = zip.entries().asSequence().single { it.name.endsWith("/$id/$index.png") }
        assertArrayEquals(bytes, zip.getInputStream(entry).use { it.readBytes() })
      }
    }
    assertTrue(f.http.calls.isEmpty())
    assertTrue(f.repo.deleteCustomStatic(id).isEmpty())
    assertTrue(f.repo.listCustomStatic().isEmpty())
  }

  @Test fun customPairOverridesOnlyStaticAndSkipsGeneratedNightLayers() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val id = "third.party"
    f.dynamic(id)
    f.update()
    f.repo.catalog(refresh = true)
    val layers = PerfectCustomStatic(png(6), png(7))
    f.repo.saveCustomStatic(id, layers, create = true)
    val static = f.repo.generate(PerfectIconOptions(installedOnly = true, dynamic = false, staticNight = true),
      setOf(id)) {}
    ZipFile(static.file).use { zip ->
      layers.layers().forEachIndexed { index, bytes ->
        val entry = zip.entries().asSequence().single { it.name.endsWith("/$id/$index.png") }
        assertArrayEquals(bytes, zip.getInputStream(entry).use { it.readBytes() })
      }
      assertFalse(zip.entries().asSequence().any { it.name.contains("layer_animating_icons/$id/") })
    }
    val dynamic = f.repo.generate(PerfectIconOptions(installedOnly = false, dynamic = true), emptySet()) {}
    ZipFile(dynamic.file).use { zip ->
      assertTrue(zip.entries().asSequence().any { it.name.contains("layer_animating_icons/$id/") })
    }
  }

  @Test fun completedPublicationIsReusedWithoutRewritingAndChangedBytesAreRebuilt() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(refresh = true)
    val options = PerfectIconOptions(installedOnly = false, dynamic = false, staticNight = false)
    val original = f.repo.generate(options, emptySet()) {}
    val bytes = original.file.readBytes()
    assertTrue(original.file.setLastModified(1_234_000L))
    assertEquals(original.file, f.repo.generate(options, emptySet()) {}.file)
    assertEquals(1_234_000L, original.file.lastModified())
    original.file.appendBytes(byteArrayOf(1, 2, 3))
    assertArrayEquals(bytes, f.repo.generate(options, emptySet()) {}.file.readBytes())
    assertEquals(1, f.http.calls.size)
  }
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
    fun fallbackStyle() {
      files["icons/com.miui.gallery/0.png"] = png(2)
      files["icons/com.miui.gallery/1.png"] = png(3)
      files["themes/HyperOS4/icons/com.miui.gallery/0.png"] = png(2)
      files["themes/HyperOS4/icons/com.miui.gallery/1.png"] = png(6)
      update()
    }
    fun dynamic(id: String, style: String = "default", tint: Int = 1) {
      val prefix = "themes/$style/layer_animating_icons/$id/"
      files[prefix + "config.xml"] = """<Config><Variable name="layer0" value="0"/></Config>""".toByteArray()
      files[prefix + "0/quiet/quietImage.png"] = png(tint)
      files[prefix + "0/fancy/manifest.xml"] = """<Icon version="1"><Image src="image.png"/></Icon>""".toByteArray()
      files[prefix + "0/fancy/image.png"] = png(tint)
      files[prefix + "original.dat"] = byteArrayOf(0, 2, 3, -1)
    }
    fun bitmapDynamic(id: String) {
      files["icons/$id/0.png"] = png(20)
      files["icons/$id/1.png"] = png(21)
      val prefix = "themes/default/layer_animating_icons/$id/"
      files[prefix + "config.xml"] = """<Config><Variables>
        <Variable name="layer0" value="0" type="string"/>
        <Variable name="layer1" value="1" type="string"/>
        <Variable name="layer_only_quiet0" value="true" type="string"/>
        <Variable name="layer_only_quiet1" value="false" type="string"/>
        </Variables></Config>""".toByteArray()
      files[prefix + "0/quiet/quietImage.png"] = png(20)
      files[prefix + "1/quiet/quietImage.png"] = png(21)
      files[prefix + "1/fancy/frame.png"] = png(21)
      files[prefix + "1/fancy/manifest.xml"] =
        """<Icon version="1" width="2" height="2" frameRate="0" useVariableUpdater="none"><Image src="frame.png"/></Icon>""".toByteArray()
      update()
    }
  }

  @Test fun bitmapDynamicNightKeepsAnimationOwnersAndReusesDerivedTexturesAcrossPreview() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.files.clear()
    val id = "test.dynamic"
    f.bitmapDynamic(id)
    var renders = 0
    val repo = PerfectIconRepository(f.root, f.http, f.settings, now = { f.time },
      dynamicNightRenderer = { _, background, check ->
        check(); renders++; if (background) png(30) else png(40)
      })
    repo.catalog(true)
    val off = PerfectIconOptions("default")
    val on = off.copy(staticNight = true)
    val original = repo.generate(off, setOf(id)) {}.file
    assertEquals(0, renders)
    val generated = repo.generate(on, setOf(id)) {}
    assertEquals(1, generated.dynamicCount)
    assertEquals(1, generated.derivedDynamicCount)
    assertFalse(generated.needsContractUpdate(on))
    val prefix = "layer_animating_icons/$id/"
    ZipFile(generated.file).use { zip ->
      for ((path, bytes) in f.files.filterKeys { it.startsWith("themes/default/$prefix") }) {
        val relative = path.removePrefix("themes/default/")
        if (relative !in setOf("${prefix}config.xml", "${prefix}1/fancy/manifest.xml"))
          assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(relative)).readBytes())
        if (relative.endsWith(".xml"))
          assertArrayEquals(bytes, zip.getInputStream(zip.getEntry("nightmode/$relative")).readBytes())
      }
      assertArrayEquals(png(30), zip.getInputStream(zip.getEntry("nightmode/${prefix}0/quiet/quietImage.png")).readBytes())
      assertArrayEquals(png(40), zip.getInputStream(zip.getEntry("nightmode/${prefix}1/fancy/frame.png")).readBytes())
      val configuration = zip.getInputStream(zip.getEntry("${prefix}config.xml")).readBytes()
      assertTrue(os.kei.hyper.home.iconpack.NativeLayerConfig.parse(configuration,
        os.kei.hyper.home.iconpack.IconPackLimits()).fancyOnly.contains("0"))
      val manifest = zip.getInputStream(zip.getEntry("${prefix}1/fancy/manifest.xml")).readBytes().decodeToString()
      assertTrue(manifest.contains("srcExp="))
      assertTrue(manifest.contains("DarkMode"))
      assertNotNull(zip.getEntry("${prefix}0/fancy/manifest.xml"))
      assertNull(zip.getEntry("fancy_icons/$id/manifest.xml"))
      assertEquals(ZipEntry.STORED, zip.getEntry("nightmode/${prefix}1/fancy/frame.png").method)
    }
    assertEquals(2, renders)
    val first = generated.file.readBytes()
    repo.previewArchive(on, 1)
    assertEquals(2, renders)
    assertArrayEquals(first, repo.generate(on, setOf(id)) {}.file.readBytes())
    ZipFile(original).use { zip -> assertNull(zip.getEntry("nightmode/${prefix}config.xml")) }
    assertEquals(1, f.http.calls.size)
  }

  @Test fun rejectedDynamicPlateIsCachedAndNeverPublishesPartialNightTree() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.files.clear()
    val id = "test.dynamic"
    f.bitmapDynamic(id)
    var renders = 0
    val repo = PerfectIconRepository(f.root, f.http, f.settings, now = { f.time },
      dynamicNightRenderer = { _, background, _ -> renders++; assertTrue(background); null })
    repo.catalog(true)
    val options = PerfectIconOptions("default", staticNight = true)
    val result = repo.generate(options, setOf(id)) {}
    assertEquals(0, result.derivedDynamicCount)
    assertEquals(1, renders)
    ZipFile(result.file).use { zip ->
      assertNotNull(zip.getEntry("layer_animating_icons/$id/config.xml"))
      assertFalse(zip.entries().asSequence().any { it.name.startsWith("nightmode/") })
    }
    repo.previewArchive(options, 1)
    assertEquals(1, renders)
    assertEquals(1, f.http.calls.size)
  }

  @Test fun dynamicRootQuietUsesStaticCompositionWithoutMaskAndBackgroundAliasesUseBackgroundRole() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.bitmapDynamic("third.party")
    val source = "themes/default/layer_animating_icons/third.party/"
    f.files[source + "quiet/quietImage.png"] = png(99)
    f.files[source + "0/fancy/bg.png"] = png(19)
    f.files[source + "0/fancy/manifest.xml"] =
      """<Icon width="2" height="2" frameRate="0"><Image src="bg.png"/></Icon>""".toByteArray()
    f.update()
    var composites = 0
    val roles = mutableListOf<Pair<ByteArray, Boolean>>()
    val repo = PerfectIconRepository(f.root, f.http, f.settings, nativeMask = { "unused native mask" },
      staticRenderer = { inputs, mask, _ ->
        composites++
        assertNull(mask)
        assertEquals(1, inputs.size)
        assertArrayEquals(png(99), inputs.single())
        PerfectStaticPair(png(30), png(40))
      },
      dynamicNightRenderer = { bytes, background, _ ->
        roles += bytes to background
        png(if (background) 60 else 70)
      })
    repo.catalog(refresh = true)
    val options = PerfectIconOptions("default", staticNight = true)
    val result = repo.generate(options, setOf("third.party")) {}
    assertEquals(1, result.derivedDynamicCount)
    assertEquals(1, composites)
    assertEquals(3, roles.size)
    assertTrue(roles.any { it.second && it.first.contentEquals(png(20)) })
    assertTrue(roles.any { it.second && it.first.contentEquals(png(19)) })
    assertTrue(roles.any { !it.second && it.first.contentEquals(png(21)) })
    assertFalse(roles.any { it.first.contentEquals(png(99)) })
    ZipFile(result.file).use { zip ->
      fun read(path: String) = zip.getInputStream(requireNotNull(zip.getEntry(path))).readBytes()
      val day = "layer_animating_icons/third.party/"
      val night = "nightmode/$day"
      assertArrayEquals(png(99), read(day + "quiet/quietImage.png"))
      assertArrayEquals(png(40), read(night + "quiet/quietImage.png"))
      assertArrayEquals(png(60), read(night + "0/quiet/quietImage.png"))
      assertArrayEquals(png(60), read(night + "0/fancy/bg.png"))
      assertArrayEquals(png(60), read(day + "0/fancy/keihome_night/bg.png"))
      assertArrayEquals(png(70), read(night + "1/quiet/quietImage.png"))
      assertArrayEquals(png(70), read(night + "1/fancy/frame.png"))
      assertArrayEquals(png(70), read(day + "1/fancy/keihome_night/frame.png"))
      for ((path, bytes) in f.files.filterKeys { it.startsWith(source) }) {
        val relative = path.removePrefix(source)
        if (!relative.endsWith(".png")) assertArrayEquals(bytes, read(night + relative))
        else assertArrayEquals(bytes, read(day + relative))
      }
    }
    assertEquals(options.nightContract(), result.nightRevision)
    assertTrue(result.file.delete())
    val regenerated = repo.generate(options, setOf("third.party")) {}
    assertEquals(1, regenerated.derivedDynamicCount)
    assertEquals(1, composites)
    assertEquals(3, roles.size)
    assertEquals(1, f.http.calls.size)
  }

  @Test fun dynamicRootQuietUsesNativeSnapshotRendererAndReusesItsCache() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.bitmapDynamic("third.party")
    val source = "themes/default/layer_animating_icons/third.party/"
    f.files[source + "quiet/quietImage.png"] = png(99)
    f.files[source + "1/quiet/quietImage.png"] = png(12)
    f.update()
    var snapshots = 0
    val repo = PerfectIconRepository(f.root, f.http, f.settings,
      staticRenderer = { _, _, _ -> error("A proven dynamic root must use native night composition") },
      staticEquivalentRenderer = { _, _, _, _ -> error("Do not reapply static pair night treatment") },
      nativeSnapshotRenderer = { flat, layers, check ->
        check()
        snapshots++
        assertArrayEquals(png(99), flat)
        assertArrayEquals(png(20), layers[0])
        assertArrayEquals(png(12), layers[1])
        PerfectStaticPair(png(99), png(55))
      },
      dynamicNightRenderer = { bytes, _, check -> check(); bytes })
    repo.catalog(refresh = true)
    val options = PerfectIconOptions("default", staticNight = true)
    repeat(2) {
      val result = repo.generate(options, setOf("third.party")) {}
      ZipFile(result.file).use { zip ->
        assertArrayEquals(png(55), zip.getInputStream(zip.getEntry(
          "nightmode/layer_animating_icons/third.party/quiet/quietImage.png")).readBytes())
        assertArrayEquals(png(99), zip.getInputStream(zip.getEntry(
          "layer_animating_icons/third.party/quiet/quietImage.png")).readBytes())
      }
    }
    assertEquals("The derived root snapshot must have its own reusable cache", 1, snapshots)
  }

  @Test fun generationDoesNotImplicitlyPrunePreviousPublications() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(refresh = true)
    val prior = (1..5).map { index ->
      File(f.root, "generated/perfect-legacy-$index.zip").also {
        requireNotNull(it.parentFile).mkdirs()
        it.writeBytes(byteArrayOf(index.toByte()))
      }
    }
    f.repo.generate(PerfectIconOptions("default"), setOf("third.party")) {}
    prior.forEachIndexed { index, file ->
      assertArrayEquals("Retention belongs to successful import, not generation",
        byteArrayOf((index + 1).toByte()), file.readBytes())
    }
  }

  @Test fun authoredNightProgramsKeepTheirOriginalClosureAndNeverInvokeDerivedRenderers() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.bitmapDynamic("third.party")
    val source = "themes/default/layer_animating_icons/third.party/"
    val manifest = source + "1/fancy/manifest.xml"
    f.files[manifest] = f.files.getValue(manifest).toString(Charsets.UTF_8)
      .replace("useVariableUpdater=\"none\"", "useVariableUpdater=\"DateTime.Minute,DarkMode\"").toByteArray()
    f.files[source + "quiet/quietImage.png"] = png(99)
    f.update()
    val repo = PerfectIconRepository(f.root, f.http, f.settings,
      staticRenderer = { _, _, _ -> error("Authored night must remain intact") },
      dynamicNightRenderer = { _, _, _ -> error("Authored night must remain intact") })
    repo.catalog(refresh = true)
    val result = repo.generate(PerfectIconOptions("default", staticNight = true), setOf("third.party")) {}
    assertEquals(1, result.dynamicCount)
    assertEquals(0, result.derivedDynamicCount)
    ZipFile(result.file).use { zip ->
      assertFalse(zip.entries().asSequence().any { it.name.startsWith("nightmode/") })
      for ((path, bytes) in f.files.filterKeys { it.startsWith(source) }) {
        val entry = requireNotNull(zip.getEntry("layer_animating_icons/third.party/" + path.removePrefix(source)))
        assertArrayEquals(bytes, zip.getInputStream(entry).readBytes())
      }
    }
  }

  @Test fun missingCompositeRendererKeepsTheGroupWholeWithoutPartiallyRecoloringItsBackground() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.bitmapDynamic("third.party")
    f.files["themes/default/layer_animating_icons/third.party/quiet/quietImage.png"] = png(99)
    f.update()
    val repo = PerfectIconRepository(f.root, f.http, f.settings, staticRenderer = null,
      dynamicNightRenderer = { _, _, _ -> error("Cannot publish a group without its composite") })
    repo.catalog(refresh = true)
    val result = repo.generate(PerfectIconOptions("default", staticNight = true), setOf("third.party")) {}
    assertEquals(0, result.derivedDynamicCount)
    ZipFile(result.file).use { zip ->
      assertFalse(zip.entries().asSequence().any { it.name.startsWith("nightmode/") })
      assertArrayEquals(png(99),
        zip.getInputStream(zip.getEntry("layer_animating_icons/third.party/quiet/quietImage.png")).readBytes())
    }
  }

  @Test fun oldNightOutputsRequestRegenerationWithoutInvalidatingOriginalMode() {
    val old = PerfectIconResult(File("existing.zip"), 1, 0, 1, 0, 0, "a".repeat(40))
    assertFalse(old.needsContractUpdate(PerfectIconOptions("default")))
    assertTrue(old.needsContractUpdate(PerfectIconOptions("default", dynamic = false, staticNight = true)))
    assertTrue(old.needsContractUpdate(PerfectIconOptions("default", staticNight = true)))
    assertFalse(old.copy(nightRevision = PerfectIconOptions("default", staticNight = true).nightContract())
      .needsContractUpdate(PerfectIconOptions("default", staticNight = true)))
    val staticOnly = PerfectIconOptions("default", dynamic = false, staticNight = true)
    assertFalse(old.copy(nightRevision = staticOnly.nightContract()).needsContractUpdate(staticOnly))
    assertTrue(old.copy(nightRevision = staticOnly.nightContract())
      .needsContractUpdate(staticOnly.copy(dynamic = true)))
  }

  @Test fun optionalStaticNightReusesPairsAndOnlyRendersChangedSourceWithoutNetworkRepeats() = runBlocking {
    val f = Fixture(temporary.newFolder())
    var renders = 0
    val repo = PerfectIconRepository(f.root, f.http, f.settings, now = { f.time },
      staticRenderer = { _, _, check -> check(); renders++; PerfectStaticPair(png(30), png(40)) })
    repo.catalog(refresh = true)
    assertFalse(repo.options().staticNight)
    val options = PerfectIconOptions("default", staticNight = true)
    repo.setOptions(options)
    assertTrue(f.repo.options().staticNight)
    val first = repo.generate(options, setOf("third.party")) {}
    assertEquals(1, renders)
    val calls = f.http.calls.size
    val original = first.file.readBytes()
    ZipFile(first.file).use { zip ->
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/third.party/0.png"))
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/third.party/1.png"))
      assertNotNull(zip.getEntry("fancy_icons/third.party/night.png"))
      assertArrayEquals(png(40), zip.getInputStream(
        zip.getEntry("nightmode/res/drawable-xxhdpi/third.party.png")).readBytes())
      val xml = zip.getInputStream(zip.getEntry("fancy_icons/third.party/manifest.xml")).reader().readText()
      assertTrue(xml.contains("frameRate=\"0\""))
      assertFalse(xml.contains("ContentProviderBinder"))
    }
    assertArrayEquals(original, repo.generate(options, setOf("third.party")) {}.file.readBytes())
    assertEquals(1, renders)
    assertEquals(calls, f.http.calls.size)
    f.files["themes/default/icons/third.party/0.png"] = png(8)
    f.update()
    repo.catalog(refresh = true)
    repo.generate(options, setOf("third.party")) {}
    assertEquals(2, renders)
    assertEquals(calls + 1, f.http.calls.size)
  }

  @Test fun corruptDerivedCacheAndNativeMaskChangesAreRebuiltLocally() = runBlocking {
    val f = Fixture(temporary.newFolder())
    var renders = 0
    var mask: String? = null
    val repo = PerfectIconRepository(f.root, f.http, f.settings, now = { f.time },
      nativeMask = { mask },
      staticRenderer = { _, _, _ -> renders++; PerfectStaticPair(png(30), png(40)) })
    repo.catalog(refresh = true)
    val options = PerfectIconOptions("default", staticNight = true)
    val first = repo.generate(options, setOf("third.party")) {}
    File(f.root, "rendered-static-night").listFiles()!!.single().writeBytes(byteArrayOf(1, 2))
    // Force publication rebuilding to exercise the corrupted intermediate render cache.
    File(f.root, "publication-proofs").deleteRecursively()
    assertArrayEquals(first.file.readBytes(), repo.generate(options, setOf("third.party")) {}.file.readBytes())
    assertEquals(2, renders)
    mask = "M0,0 H100 V100 H0 Z"
    val changed = repo.generate(options, setOf("third.party")) {}
    assertNotEquals(first.file.name, changed.file.name)
    assertEquals(3, renders)
    assertEquals(1, f.http.calls.size)
    assertTrue(repo.cacheBytes() > 0)
  }

  @Test fun identicalStaticNightLeavesNativeLayersAndOmitsTheDescriptor() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val artwork = png(30)
    val repo = PerfectIconRepository(f.root, f.http, f.settings, now = { f.time },
      staticRenderer = { _, _, check -> check(); PerfectStaticPair(artwork, artwork.copyOf()) })
    repo.catalog(refresh = true)
    val result = repo.generate(PerfectIconOptions("default", staticNight = true), setOf("third.party")) {}
    ZipFile(result.file).use { zip ->
      val day = "res/drawable-xxhdpi/third.party.png"
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/third.party/0.png"))
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/third.party/1.png"))
      assertNull(zip.getEntry("fancy_icons/third.party/manifest.xml"))
      for (path in listOf(day, "nightmode/$day")) {
        assertArrayEquals(artwork, zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
      }
    }
  }

  @Test fun fixedDarkPublishesOrdinaryBitmapsAndKeepsDynamicPrograms() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.bitmapDynamic("test.dynamic")
    val night = png(40)
    val repo = PerfectIconRepository(f.root, f.http, f.settings, now = { f.time },
      staticRenderer = { _, _, _ -> PerfectStaticPair(png(30), night) },
      dynamicNightRenderer = { _, _, _ -> night })
    repo.catalog(true)
    val automatic = PerfectIconOptions("default", staticNight = true)
    val fixed = automatic.copy(fixedDark = true)
    repo.setOptions(fixed)
    assertEquals(fixed, repo.options())

    val ids = setOf("third.party", "test.dynamic")
    val ordinary = "res/drawable-xxhdpi/third.party"
    val manifest = "fancy_icons/third.party/manifest.xml"
    val live = "layer_animating_icons/test.dynamic/"
    val automaticPack = repo.generate(automatic, ids) {}
    val fixedPack = repo.generate(fixed, ids) {}
    assertNotEquals(automaticPack.file, fixedPack.file)
    assertTrue(automaticPack.needsContractUpdate(fixed))
    assertFalse(fixedPack.needsContractUpdate(fixed))

    ZipFile(automaticPack.file).use { auto ->
      assertNotNull(auto.getEntry(manifest))
      ZipFile(fixedPack.file).use { zip ->
        assertArrayEquals(night, zip.getInputStream(zip.getEntry("$ordinary.png")).use { it.readBytes() })
        for (path in listOf(manifest, "$ordinary/0.png", "$ordinary/1.png", "nightmode/$ordinary.png")) {
          assertNull(path, zip.getEntry(path))
        }
        val dynamicPaths = auto.entries().asSequence()
          .map { it.name }.filter { it.startsWith(live) || it.startsWith("nightmode/$live") }.toList()
        assertTrue(dynamicPaths.any { it.endsWith("manifest.xml") })
        for (path in dynamicPaths) {
          assertArrayEquals(auto.getInputStream(auto.getEntry(path)).use { it.readBytes() },
            zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
        }
      }
    }
    assertNotEquals(repo.previewArchive(automatic), repo.previewArchive(fixed))
    ZipFile(repo.previewArchive(fixed)).use { zip ->
      assertArrayEquals(night, zip.getInputStream(zip.getEntry("$ordinary.png")).use { it.readBytes() })
      assertNull(zip.getEntry(manifest))
    }
    val original = fixed.copy(staticNight = false)
    assertEquals(repo.generate(original, ids) {}.file,
      repo.generate(original.copy(fixedDark = false), ids) {}.file)
  }

  @Test fun staticNightNeverRewritesConfiguredNativeResources() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.dynamic("third.party", style = "common")
    f.update()
    val repo = PerfectIconRepository(f.root, f.http, f.settings,
      staticRenderer = { _, _, _ -> error("Configured icons must not be rasterized") })
    repo.catalog(refresh = true)
    val result = repo.generate(PerfectIconOptions("default", staticNight = true), setOf("third.party")) {}
    ZipFile(result.file).use { zip ->
      assertNull(zip.getEntry("fancy_icons/third.party/manifest.xml"))
      assertArrayEquals(f.files.getValue("themes/common/layer_animating_icons/third.party/config.xml"),
        zip.getInputStream(zip.getEntry("layer_animating_icons/third.party/config.xml")).readBytes())
    }
    assertEquals(1, result.dynamicCount)
  }

  @Test fun failedStaticConversionKeepsPreviousPublicationAndCleansPartialOutput() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.repo.catalog(refresh = true)
    val previous = f.repo.generate(PerfectIconOptions("default"), setOf("third.party")) {}
    val bytes = previous.file.readBytes()
    val repo = PerfectIconRepository(f.root, f.http, f.settings,
      staticRenderer = { _, _, _ -> throw CancellationException("cancel rendering") })
    try {
      repo.generate(PerfectIconOptions("default", staticNight = true), setOf("third.party")) {}
      fail("Expected cancellation")
    } catch (_: CancellationException) { }
    assertArrayEquals(bytes, previous.file.readBytes())
    assertFalse(File(f.root, "generated").listFiles().orEmpty().any { it.extension == "part" })
  }

  @Test fun fallbackUsesLocalStyleLayersAndSharesVerifiedBlobCacheWithoutNetwork() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.fallbackStyle()
    val repo = f.repo
    val catalog = repo.catalog(refresh = true)!!
    f.http.calls.clear()
    f.http.intercept = { throw IOException("No network permitted for fallback") }
    assertEquals(catalog.sourceHash, repo.fallbackSourceHash("HyperOS4"))
    val first = requireNotNull(repo.fallbackIcon("HyperOS4", listOf("missing.component", "com.miui.gallery")))
    assertEquals(2, first.layers.size)
    assertArrayEquals(f.files.getValue("icons/com.miui.gallery/0.png"), first.layers[0])
    assertArrayEquals(f.files.getValue("themes/HyperOS4/icons/com.miui.gallery/1.png"), first.layers[1])
    val cached = File(f.root, "blobs").listFiles()!!.first { it.readBytes().contentEquals(first.layers[1]) }
    cached.writeBytes(byteArrayOf(0))
    val repaired = requireNotNull(repo.fallbackIcon("HyperOS4", listOf("com.miui.gallery")))
    assertArrayEquals(first.layers[1], repaired.layers[1])
    assertNull(repo.fallbackIcon("HyperOS4", listOf("unknown.package")))
    assertTrue(f.http.calls.isEmpty())
    assertTrue(File(f.root, "generated").listFiles().isNullOrEmpty())
  }

  @Test fun fallbackPlansFollowLocalSnapshotChangesAndRejectMixedSourceGeneration() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.fallbackStyle()
    val repo = f.repo
    val old = repo.catalog(refresh = true)!!
    requireNotNull(repo.fallbackIcon("HyperOS4", listOf("com.miui.gallery"), old.sourceHash))
    f.files["themes/HyperOS4/icons/com.miui.gallery/1.png"] = png(44)
    f.files["themes/Extra/icons/com.miui.gallery/0.png"] = png(2)
    f.files["themes/Extra/icons/com.miui.gallery/1.png"] = png(45)
    f.update("2".repeat(40))
    f.time += 5000
    val updated = repo.catalog(refresh = true)!!
    assertNotEquals(old.sourceHash, updated.sourceHash)
    val fresh = requireNotNull(repo.fallbackIcon("HyperOS4", listOf("com.miui.gallery"), updated.sourceHash))
    assertArrayEquals(png(44), fresh.layers[1])
    val extra = requireNotNull(repo.fallbackIcon("Extra", listOf("com.miui.gallery")))
    assertArrayEquals(png(45), extra.layers[1])
    assertTrue(runCatching { repo.fallbackIcon("HyperOS4", listOf("com.miui.gallery"), old.sourceHash) }.isFailure)
  }

  @Test fun missingFallbackResourcesFailWithoutFetchingOrInventingAStyle() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.fallbackStyle()
    val repo = f.repo
    assertTrue(runCatching { repo.fallbackSourceHash("HyperOS4") }.isFailure)
    assertTrue(f.http.calls.isEmpty())
    repo.catalog(refresh = true)
    f.http.calls.clear()
    assertTrue(runCatching { repo.fallbackIcon("Uncached", listOf("com.miui.gallery")) }.isFailure)
    assertTrue(f.http.calls.isEmpty())
  }

  @Test fun fallbackRetainsLayerPairsAndHonorsFlatStyleOverrides() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.fallbackStyle()
    f.files["themes/Flat/icons/com.miui.gallery.png"] = png(11)
    f.update()
    val repo = f.repo
    repo.catalog(refresh = true)
    val layered = requireNotNull(repo.fallbackIcon("HyperOS4", listOf("com.miui.gallery")))
    assertEquals(2, layered.layers.size)
    assertArrayEquals(png(6), layered.layers.last())
    val flat = requireNotNull(repo.fallbackIcon("Flat", listOf("com.miui.gallery")))
    assertEquals(1, flat.layers.size)
    assertArrayEquals(png(11), flat.layers.single())
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

  @Test fun incompleteDynamicResourceKeepsExistingStaticArtAndCachedSource() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.dynamic("third.party")
    f.files.remove("themes/default/layer_animating_icons/third.party/0/fancy/image.png")
    f.update()
    f.repo.catalog(refresh = true)
    val first = f.repo.generate(PerfectIconOptions(), setOf("third.party")) {}
    assertEquals(1, first.rejectedDynamic)
    assertEquals(0, first.dynamicCount)
    assertEquals(PerfectIconRepository.LAYER_CONTRACT, first.contract)
    ZipFile(first.file).use { zip ->
      assertNotNull(zip.getEntry("res/drawable-xxhdpi/third.party/0.png"))
      assertNull(zip.getEntry("layer_animating_icons/third.party/config.xml"))
      val transform = zip.getInputStream(zip.getEntry("transform_config.xml")).reader().readText()
      assertTrue(transform.contains("name=\"UseDynamicIcon\" value=\"false\""))
    }
    val calls = f.http.calls.size
    val again = f.repo.generate(PerfectIconOptions(), setOf("third.party")) {}
    assertEquals(first.file, again.file)
    assertEquals(0, again.downloaded)
    assertEquals(calls, f.http.calls.size)
  }

  @Test fun quietOnlyLayersAreStaticButRetainNativeDirectoryAdmission() = runBlocking {
    val f = Fixture(temporary.newFolder())
    f.dynamic("third.party")
    f.files["themes/default/layer_animating_icons/third.party/config.xml"] =
      "<Config><Variables><Variable name='layer0' value='0'/><Variable name='layer_only_quiet0' value='true'/></Variables></Config>".toByteArray()
    f.files.remove("themes/default/layer_animating_icons/third.party/0/fancy/manifest.xml")
    f.files.remove("themes/default/layer_animating_icons/third.party/0/fancy/image.png")
    f.update()
    f.repo.catalog(refresh = true)
    val result = f.repo.generate(PerfectIconOptions(), setOf("third.party")) {}
    assertEquals(0, result.rejectedDynamic)
    assertEquals(0, result.dynamicCount)
    assertEquals(1, result.staticCount)
    assertEquals(1, result.mapped)
    ZipFile(result.file).use { zip ->
      assertNotNull(zip.getEntry("layer_animating_icons/third.party/config.xml"))
      val transform = zip.getInputStream(zip.getEntry("transform_config.xml")).reader().readText()
      assertTrue(transform.contains("name=\"UseDynamicIcon\" value=\"true\""))
    }
  }

  @Test fun localCatalogAndMissingSnapshotGenerationNeverUseNetwork() = runBlocking {
    val f = Fixture(temporary.newFolder())
    assertNull(f.repo.catalog())
    assertEquals(PerfectIconStyles.initialOptions, f.repo.options())
    rejectedSuspend { f.repo.generate(PerfectIconOptions(), setOf("third.party")) {} }
    assertEquals(0L, f.repo.cacheBytes())
    assertTrue(f.http.calls.isEmpty())
    val options = PerfectIconOptions("default", false, false)
    f.repo.setOptions(options)
    assertEquals(options, f.repo.options())
  }

  @Test fun optionsAreInitialOnlyForMissingOrInvalidSettingsAndPreserveSavedEmptyStyle() {
    val f = Fixture(temporary.newFolder())
    assertEquals("HyperOS4", f.repo.options().style)
    assertTrue(f.settings.values.isEmpty())
    listOf("", "default", "HyperOS3", "other-style").forEach { style ->
      val saved = PerfectIconOptions(style, installedOnly = false, dynamic = false, staticNight = true)
      f.repo.setOptions(saved)
      assertEquals(saved, f.repo.options())
    }
    listOf("broken JSON", """{"style":"HyperOS4"}""",
      """{"style":"../outside","installedOnly":true,"dynamic":true}""").forEach { invalid ->
      f.settings.values["options"] = invalid
      assertEquals(PerfectIconStyles.initialOptions, f.repo.options())
      assertEquals(invalid, f.settings.values["options"])
    }
    assertEquals("", PerfectIconOptions().style)
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

  @Test fun forcedDownloadSkipsValidatorAndReplacesSnapshotOnlyAfterValidation() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val initial = f.repo.catalog(true)!!
    f.time += 5000
    val downloaded = f.repo.catalog(refresh = true, forceDownload = true)!!
    assertTrue(f.http.calls.last().second.isEmpty())
    assertEquals(initial.sourceHash, downloaded.sourceHash)
    assertEquals(f.time, downloaded.downloadedAt)
    assertTrue(downloaded.upToDate)
    f.http.bytes = "invalid archive".toByteArray()
    rejectedSuspend { f.repo.catalog(refresh = true, forceDownload = true) }
    assertEquals(downloaded, f.repo.catalog())
    val source = File(f.root, "source").listFiles()!!.single()
    assertEquals(downloaded.archiveBytes, source.length())
  }

  @Test fun forcedDownloadRespectsCooldownAndNeverDeletesUsableSourceOnNetworkFailure() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val initial = f.repo.catalog(true)
    f.http.status = 429
    f.http.headers = mapOf("Retry-After" to "120")
    rejectedSuspend { f.repo.catalog(refresh = true, forceDownload = true) }
    val requests = f.http.calls.size
    repeat(3) { rejectedSuspend { f.repo.catalog(refresh = true, forceDownload = true) } }
    assertEquals(requests, f.http.calls.size)
    assertEquals(initial, f.repo.catalog())
    assertTrue(f.repo.generate(PerfectIconOptions(dynamic = false), setOf("com.miui.gallery")) {}.mapped > 0)
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
    val source = File(f.root, "source").listFiles()!!.single()
    val protectedBytes = source.length() + File(f.root, "catalog.json").length() + first.file.length()
    val storageBefore = f.repo.storageUsage()
    assertTrue(storageBefore.complete)
    assertEquals(protectedBytes + before, storageBefore.total)
    assertEquals(blobs + File(f.root, "publication-proofs").listFiles()!!.sumOf { it.length() }, before)
    assertEquals(before, f.repo.clearDownloadCache())
    assertEquals(0L, f.repo.cacheBytes())
    val storageAfter = f.repo.storageUsage()
    assertTrue(storageAfter.complete)
    assertEquals(protectedBytes, storageAfter.total)
    assertTrue(storageAfter.total > source.length())
    assertEquals(catalog!!.archiveBytes, source.length())
    assertEquals(catalog, f.repo.catalog())
    assertTrue(first.file.exists())
    assertTrue(imported.exists())
    val again = f.repo.generate(options, setOf("com.miui.gallery")) {}
    assertEquals(2, again.downloaded)
    assertEquals(1, f.http.calls.size)
    assertEquals(first.file, again.file)
  }

  @Test fun storageUsageCountsMetadataWithoutDecodingOrFollowingLinks() = runBlocking {
    val f = Fixture(temporary.newFolder())
    File(f.root, "catalog.json").writeBytes(ByteArray(7))
    File(f.root, "source").mkdirs()
    File(f.root, "source/invalid.zip").writeBytes(ByteArray(13))
    File(f.root, "generated").mkdirs()
    File(f.root, "generated/invalid.zip").writeBytes(ByteArray(17))
    val external = temporary.newFolder()
    val externalFile = File(external, "unrelated.zip").apply { writeBytes(ByteArray(100)) }
    Files.createSymbolicLink(File(f.root, "external-file").toPath(), externalFile.toPath())
    Files.createSymbolicLink(File(f.root, "external-directory").toPath(), external.toPath())

    val usage = f.repo.storageUsage()
    assertTrue(usage.complete)
    assertEquals(37L, usage.total)
    assertEquals(0L, f.repo.cacheBytes())
    assertTrue(f.http.calls.isEmpty())
    assertTrue(f.settings.values.isEmpty())
  }

  @Test fun storageUsageOfMissingResourcesIsCompleteAndDoesNotCreateOrDownload() = runBlocking {
    val root = File(temporary.newFolder(), "absent")
    val f = Fixture(root)
    val usage = f.repo.storageUsage()
    assertEquals(0L, usage.total)
    assertTrue(usage.complete)
    assertFalse(root.exists())
    assertTrue(f.http.calls.isEmpty())
  }

  @Test fun storageUsageDoesNotWaitForAnActiveDownloadMutex() = runBlocking {
    val f = Fixture(temporary.newFolder())
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    f.http.intercept = {
      started.complete(Unit)
      release.await()
      throw IOException("End blocked download fixture")
    }
    val download = async {
      try {
        f.repo.catalog(true)
        fail("Expected fixture download failure")
      } catch (_: IOException) { }
    }
    started.await()
    try {
      val usage = withTimeout(5_000) { f.repo.storageUsage() }
      assertEquals(0L, usage.total)
      assertTrue(usage.complete)
      assertFalse(download.isCompleted)
    } finally {
      release.complete(Unit)
    }
    download.await()
    assertEquals(1, f.http.calls.size)
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

  @Test fun staticNightPreviewTogglesBackToOriginalWithoutChangingPublicationOrRefetchingSource() = runBlocking {
    val f = Fixture(temporary.newFolder())
    var renders = 0
    val repo = PerfectIconRepository(f.root, f.http, f.settings, now = { f.time },
      staticRenderer = { _, _, check -> check(); renders++; PerfectStaticPair(png(30), png(40)) })
    repo.catalog(refresh = true)
    val original = PerfectIconOptions("default", dynamic = false)
    repo.setOptions(original)
    val published = repo.generate(original, setOf("third.party")) {}.file
    val publishedBytes = published.readBytes()
    val settings = f.settings.values.toMap()
    val off = repo.previewArchive(original)
    val on = repo.previewArchive(original.copy(staticNight = true))
    assertNotEquals(off, on)
    ZipFile(off).use { zip ->
      assertNull(zip.getEntry("fancy_icons/third.party/manifest.xml"))
      assertArrayEquals(png(5), zip.getInputStream(
        zip.getEntry("res/drawable-xxhdpi/third.party/0.png")).readBytes())
    }
    ZipFile(on).use { zip ->
      assertArrayEquals(png(40), zip.getInputStream(
        zip.getEntry("fancy_icons/third.party/night.png")).readBytes())
    }
    val rendered = renders
    assertTrue(rendered > 0)
    assertEquals(off, repo.previewArchive(original))
    assertEquals(on, repo.previewArchive(original.copy(staticNight = true)))
    assertEquals(rendered, renders)
    assertEquals(settings, f.settings.values)
    assertArrayEquals(publishedBytes, published.readBytes())
    assertEquals(1, f.http.calls.size)
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
