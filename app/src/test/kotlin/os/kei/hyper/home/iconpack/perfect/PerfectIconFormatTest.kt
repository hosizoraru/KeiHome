package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.NativeIconPngFixtures

class PerfectIconFormatTest {
  private val id = "com.example"
  private val png = NativeIconPngFixtures.rgba()
  private val prefix = "layer_animating_icons/$id/"

  @Test fun pairIsBackgroundAndForegroundAndSurvivesAlongsideFlatAndDynamicRepresentations() {
    val source = base() + dynamic() + mapOf("themes/default/icons/$id.png" to png)
    val (plan, read) = plan(source)
    val checked = PerfectIconFormat.validate(plan, read)
    assertEquals(emptySet<String>(), checked.staticIds)
    assertEquals(setOf(id), checked.dynamicIds)
    assertTrue("res/drawable-xxhdpi/$id/0.png" in checked.files)
    assertTrue("res/drawable-xxhdpi/$id/1.png" in checked.files)
    assertTrue("res/drawable-xxhdpi/$id.png" in checked.files)
    assertTrue(checked.files.keys.none { "night" in it })
    assertTrue(checked.staticIds.intersect(checked.dynamicIds).isEmpty())
  }

  @Test fun resourceDirectoryWithoutConfigDoesNotBecomeDynamicOrRemoveStaticPair() {
    val checked = checked(base() + mapOf("themes/common/${prefix}0/fancy/stray.png" to png))
    assertEquals(setOf(id), checked.staticIds)
    assertTrue(checked.dynamicIds.isEmpty())
    assertTrue(checked.files.keys.none { it.startsWith(prefix) })
  }

  @Test fun partialThemedDirectoryDoesNotOverrideConfiguredCommonGroup() {
    val common = dynamic(style = "common")
    val checked = checked(base() + common + mapOf("themes/default/$prefix/unrelated.dat" to byteArrayOf(1)))
    assertEquals(setOf(id), checked.dynamicIds)
    assertEquals("themes/common/${prefix}config.xml", checked.files.getValue("${prefix}config.xml").source)
  }

  @Test fun extraRootResourceIsPreservedAndThemedGroupWinsAsAWhole() {
    val extra = byteArrayOf(0, 2, 3, -1)
    val source = base() + dynamic(style = "common") + dynamic() +
      mapOf("themes/default/${prefix}original.dat" to extra)
    val (plan, read) = plan(source)
    val checked = PerfectIconFormat.validate(plan, read)
    assertArrayEquals(extra, read(checked.files.getValue("${prefix}original.dat")))
    assertTrue(checked.files.filterKeys { it.startsWith(prefix) }.values.all { "/default/" in it.source })
  }

  @Test fun namedLayerDirectoriesAndFancyOnlyWithoutQuietAreValid() {
    val checked = checked(dynamic(
      config = config("layer0" to "background", "layer_only_fancy0" to "true"),
      layer = "background", quiet = false,
    ))
    assertEquals(setOf(id), checked.dynamicIds)
    assertTrue(checked.rejectedDynamic.isEmpty())
  }

  @Test fun quietOnlyLayerIsStaticEvenWhenRawFancyRepresentationIsRetained() {
    val checked = checked(dynamic(config = config("layer0" to "0", "layer_only_quiet0" to "true")))
    assertEquals(setOf(id), checked.staticIds)
    assertTrue(checked.dynamicIds.isEmpty())
    assertTrue("${prefix}0/fancy/manifest.xml" in checked.files)
    assertTrue("${prefix}0/quiet/quietImage.png" in checked.files)
  }

  @Test fun providerBackedDynamicIconKeepsItsStaticFallback() {
    val checked = checked(
      base() + dynamic(
        manifest = """
          <VariableBinders>
            <ContentProviderBinder name="weather_provider"
              uri="content://weather/actualWeatherData/4"/>
          </VariableBinders>
        """.trimIndent(),
      ),
    )
    assertEquals(setOf(id), checked.staticIds)
    assertTrue(checked.dynamicIds.isEmpty())
    assertTrue(checked.files.keys.none { it.startsWith(prefix) })
    assertEquals(
      "external content provider is launch-sensitive; kept static fallback",
      checked.rejectedDynamic.getValue(id),
    )
  }

  @Test fun rootQuietSnapshotIsValidWithoutPerLayerQuietOrFancyResources() {
    val source = mapOf(
      "themes/default/${prefix}config.xml" to config("layer0" to "background", "layer1" to "foreground").toByteArray(),
      "themes/default/${prefix}quiet/quietImage.png" to png,
    )
    val checked = checked(source)
    assertEquals(setOf(id), checked.staticIds)
    assertTrue(checked.dynamicIds.isEmpty())
    assertTrue(checked.rejectedDynamic.isEmpty())
  }

  @Test fun quietOnlyDoesNotReadCorruptUnusedFancyResources() {
    val source = dynamic(config = config("layer0" to "0", "layer_only_quiet0" to "true"),
      manifest = "<Image src='missing.png'/>", fancy = mapOf("corrupt.png" to byteArrayOf(1))) +
      ("themes/default/${prefix}0/fancy/manifest.xml" to "<broken".toByteArray())
    assertEquals(setOf(id), checked(source).staticIds)
  }

  @Test fun fancyOnlyDoesNotReadCorruptUnusedRootOrLayerQuietResources() {
    val source = dynamic(config = config("layer0" to "0", "layer_only_fancy0" to "true")) + mapOf(
      "themes/default/${prefix}0/quiet/quietImage.png" to byteArrayOf(1),
      "themes/default/${prefix}quiet/quietImage.png" to byteArrayOf(2),
    )
    assertEquals(setOf(id), checked(source).dynamicIds)
  }

  @Test fun legacyModeFlagsArePreservedWithoutInventingOnlyModeSemantics() {
    val source = dynamic(config = config("layer0" to "0", "use_fancy_when_static_layer0" to "false",
      "use_quiet_when_anim_layer0" to "true"))
    assertEquals(setOf(id), checked(source).dynamicIds)
    rejected { checked(source - "themes/default/${prefix}0/fancy/icon.png") }
  }

  @Test fun originalBgAndFgManifestRootsRetainTheirResourceDependencies() {
    for (root in listOf("bg", "fg")) {
      val source = dynamic().mapValues { (path, bytes) ->
        if (path.endsWith("/manifest.xml")) bytes.toString(Charsets.UTF_8)
          .replace("<Icon ", "<$root ").replace("</Icon>", "</$root>").toByteArray() else bytes
      }
      assertEquals(setOf(id), checked(source).dynamicIds)
      rejected { checked(source - "themes/default/${prefix}0/fancy/icon.png") }
    }
  }

  @Test fun originalNonsquareStaticLayersAndPngWithTrailingBytesArePreserved() {
    val rectangle = ByteArrayOutputStream().also {
      ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()
    val trailer = png + byteArrayOf(1, 2, 3)
    val source = mapOf("icons/$id/0.png" to rectangle, "icons/$id/1.png" to trailer)
    val (plan, read) = plan(source)
    val checked = PerfectIconFormat.validate(plan, read)
    assertEquals(setOf(id), checked.staticIds)
    assertArrayEquals(rectangle, read(checked.files.getValue("res/drawable-xxhdpi/$id/0.png")))
    assertArrayEquals(trailer, read(checked.files.getValue("res/drawable-xxhdpi/$id/1.png")))
  }

  @Test fun fancyWorkFalseDoesNotDisableDeclaredFancyLayers() {
    val checked = checked(dynamic(config = config("layer0" to "0", "fancy_work" to "false")))
    assertEquals(setOf(id), checked.dynamicIds)
  }

  @Test fun quietOnlyDoesNotRequireFancyAndOrdinaryLayerCanUseQuietFallback() {
    for (vars in listOf(config("layer0" to "0"), config("layer0" to "0", "layer_only_quiet0" to "true"))) {
      val source = dynamic(config = vars).filterKeys { "/fancy/" !in it }
      assertEquals(setOf(id), checked(source).staticIds)
    }
  }

  @Test fun missingDeclaredLayerRejectsEvenWhenNoResourcesRevealThatLayer() {
    val source = dynamic(config = config("layer0" to "0", "layer1" to "foreground"))
    rejected { checked(source) }
    assertFallback(base() + source)
  }

  @Test fun rejectsConflictingInvalidAndUndeclaredModeFlagsAndDuplicateDirectories() {
    val configs = listOf(
      config("layer0" to "0", "layer_only_quiet0" to "true", "layer_only_fancy0" to "true"),
      config("layer0" to "0", "layer_only_quiet0" to "yes"),
      config("layer0" to "0", "layer_only_quiet1" to "false"),
      config("layer0" to "0", "layer1" to "0"),
      config("layer1" to "0"),
      config("layer0" to "../0"),
      config("layer0" to "0", "layer8" to "8"),
    )
    configs.forEach { vars -> rejected { checked(dynamic(config = vars)) } }
  }

  @Test fun quietOnlyAndFancyOnlyRequireTheirOwnRepresentation() {
    rejected { checked(dynamic(config = config("layer0" to "0", "layer_only_quiet0" to "true"), quiet = false)) }
    rejected {
      checked(dynamic(config = config("layer0" to "0", "layer_only_fancy0" to "true"))
        .filterKeys { "/fancy/" !in it })
    }
  }

  @Test fun missingOrCorruptLiteralDependencyFallsBackToSameIdStatic() {
    val source = base() + dynamic()
    assertFallback(source - "themes/default/${prefix}0/fancy/icon.png")
    assertFallback(source + ("themes/default/${prefix}0/fancy/icon.png" to byteArrayOf(1, 2)))
  }

  @Test fun missingDependencyWithoutSameIdStaticIsExplicitFailure() {
    val source = dynamic() - "themes/default/${prefix}0/fancy/icon.png"
    rejected { checked(source) }
    val unrelated = base().mapKeys { it.key.replace(id, "com.other") }
    rejected { checked(source + unrelated) }
  }

  @Test fun fallbackDiagnosticsAndClassificationSurviveRepeatedValidation() {
    val source = base() + dynamic(manifest = "<Image src='missing.png'/>")
    val (plan, read) = plan(source)
    val checked = PerfectIconFormat.validate(plan, read)
    val again = PerfectIconFormat.validate(checked, read)
    assertEquals(checked, again)
    assertTrue(checked.rejectedDynamic.getValue(id).contains("missing.png"))
  }

  @Test fun cancellationNeverBecomesStaticFallback() {
    val (plan, read) = plan(base() + dynamic())
    try {
      PerfectIconFormat.validate(plan) {
        if (it.source.endsWith("config.xml")) throw CancellationException("cancel")
        read(it)
      }
      fail("Expected cancellation")
    } catch (_: CancellationException) { }
  }

  @Test fun dateAndWeekdayFramesNeedNoBaseFileButRequireEveryIndexedFrame() {
    for ((selector, range) in listOf("#date" to (1..31), "#day_of_week" to (1..7))) {
      val frames = range.associate { "num_$it.png" to png }
      val source = dynamic(manifest = "<Image src='num.png' srcid='$selector'/>", fancy = frames)
      assertEquals(setOf(id), checked(source).dynamicIds)
      range.forEach { frame ->
        rejected { checked(source - "themes/default/${prefix}0/fancy/num_$frame.png") }
      }
    }
  }

  @Test fun unresolvedSelectorsAndUnsafeImagePathsAreNotSilentlyAccepted() {
    for (body in listOf(
      "<Image src='icon.png' srcid='#month'/>", "<Image src='icon.png' srcid='#date+1'/>",
      "<Image src='../icon.png'/>", "<Image src='https://example.com/icon.png'/>",
      "<Image srcExp='@unknown'/>", "<Image srcid='#date'/>",
    )) rejected { checked(dynamic(manifest = body)) }
  }

  @Test fun boundedAnimationFramesRequireAllPrefixesAndIntermediateFrames() {
    val body = """
      <Var name="src_ani"><VariableAnimation><AniFrame value="0"/><AniFrame value="2"/></VariableAnimation></Var>
      <Var name="srcStr" expression="ifelse(#ratio}0,'right','left')" type="string"/>
      <Image srcExp="@srcStr+'_'+int(#src_ani)+'.png'"/>
    """.trimIndent()
    val frames = listOf("right", "left").flatMap { name -> (0..2).map { "${name}_$it.png" to png } }.toMap()
    val source = dynamic(manifest = body, fancy = frames)
    assertEquals(setOf(id), checked(source).dynamicIds)
    rejected { checked(source - "themes/default/${prefix}0/fancy/right_1.png") }
  }

  @Test fun temperatureCharacterFamiliesHonorCharNameMapAndDoNotNeedNumPng() {
    val body = "<ImageChars src='num.png' string=\"#weather_temperature+'°'\" charNameMap='°d,-m'/>"
    val glyphs = ("0123456789".map { "num_$it.png" } + listOf("num_d.png", "num_m.png")).associateWith { png }
    val source = dynamic(manifest = body, fancy = glyphs)
    assertEquals(setOf(id), checked(source).dynamicIds)
    rejected { checked(source - "themes/default/${prefix}0/fancy/num_m.png") }
  }

  @Test fun timeImageFamilyRequiresDigitFrames() {
    val source = dynamic(manifest = "<Time src='date.png' format='dd'/>",
      fancy = (0..9).associate { "date_$it.png" to png })
    assertEquals(setOf(id), checked(source).dynamicIds)
    rejected { checked(source - "themes/default/${prefix}0/fancy/date_0.png") }
  }

  @Test fun densityResourcesAreResolvedOnlyInsideDeclaredResourceDirectories() {
    val source = dynamic(manifest = "<Image src='icon.png'/>",
      rootAttributes = "extraResourcesDensity='440,480'",
      fancy = mapOf("den440/icon.png" to png, "den480/icon.png" to png, "den480/other.png" to png))
    assertEquals(setOf(id), checked(source).dynamicIds)
    rejected { checked(source - "themes/default/${prefix}0/fancy/den480/icon.png") }
    rejected { checked(dynamic(fancy = mapOf("unrelated/icon.png" to png))) }
  }

  @Test fun lottieJsonAndItsLocalImagesAndAssetReferencesMustBeComplete() {
    val json = """{"assets":[{"id":"image","u":"images/","p":"icon.png"}],"layers":[{"refId":"image"}]}"""
    val source = dynamic(manifest = "<Lottie src='animation.json'/>",
      fancy = mapOf("animation.json" to json.toByteArray(), "images/icon.png" to png))
    assertEquals(setOf(id), checked(source).dynamicIds)
    rejected { checked(source - "themes/default/${prefix}0/fancy/animation.json") }
    rejected { checked(source - "themes/default/${prefix}0/fancy/images/icon.png") }
    for (invalid in listOf(json.replace("images/", "../"), json.replace("images/", "https://host/"),
      json.replace("\"refId\":\"image\"", "\"refId\":\"missing\""), "{broken")) {
      rejected { checked(source + ("themes/default/${prefix}0/fancy/animation.json" to invalid.toByteArray())) }
    }
  }

  @Test fun malformedXmlAndUndeclaredResourceLayerRejectWithoutLosingUsableStatic() {
    assertFallback(base() + dynamic(config = "<Config>"))
    assertFallback(base() + dynamic() + ("themes/default/${prefix}unused/fancy/icon.png" to png))
  }

  @Test fun cachedHyperOs4AllTwentyConfigGroupsPassDependencyValidationWithoutWarnings() {
    val file = sequenceOf(
      File(".artifacts/research/perfect-icons-20261108/source/repository-complete.zip"),
      File("../.artifacts/research/perfect-icons-20261108/source/repository-complete.zip"),
    ).firstOrNull(File::isFile)
    assumeTrue("Optional cached pzcn snapshot unavailable", file != null)
    ZipFile(file!!).use { zip ->
      val root = PerfectIconSourceZip.PREFIX
      val groupPrefix = "themes/HyperOS4/layer_animating_icons/"
      val commonPrefix = "themes/common/layer_animating_icons/"
      val entries = zip.entries().asSequence().filter { !it.isDirectory &&
        (it.name.startsWith(root + groupPrefix) || it.name.startsWith(root + commonPrefix)) }
        .map { entry ->
          val path = entry.name.removePrefix(root)
          PerfectIconBlob(path, path, entry.size)
        }.toList()
      assertEquals(20, entries.count { it.path.endsWith("/config.xml") })
      val plan = planBlobs(entries, "HyperOS4")
      val checked = PerfectIconFormat.validate(plan) { zip.getInputStream(zip.getEntry(root + it.source)).use { it.readBytes() } }
      println("HyperOS4 config groups=${plan.dynamicIds.size}; selected=${checked.dynamicIds.size}; warnings=${checked.rejectedDynamic}")
      assertEquals("Each configured original group should remain selected", 20, checked.dynamicIds.size)
      assertTrue("Warnings: ${checked.rejectedDynamic}", checked.rejectedDynamic.isEmpty())
      assertTrue(checked.staticIds.isEmpty())
      println("HyperOS4 config groups=${checked.dynamicIds.size}; warnings=${checked.rejectedDynamic}")
      checked.dynamicIds.sorted().forEach { println("HyperOS4 valid: $it") }
    }
  }

  @Test fun cachedFullSourceReportsBeforeAfterCoverageAndRejectionsForEveryStyle() {
    val file = sequenceOf(
      File(".artifacts/research/perfect-icons-20261108/source/repository-complete.zip"),
      File("../.artifacts/research/perfect-icons-20261108/source/repository-complete.zip"),
    ).firstOrNull(File::isFile)
    assumeTrue("Optional cached pzcn snapshot unavailable", file != null)
    ZipFile(file!!).use { zip ->
      val root = PerfectIconSourceZip.PREFIX
      val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(root) }
        .map { entry ->
          val path = entry.name.removePrefix(root)
          PerfectIconBlob(path, path, entry.size)
        }.toList()
      val styles = entries.filter { it.path.startsWith("themes/") }.map { it.path.split('/')[1] }
        .filter { it != "common" && !it.startsWith('.') }.toSortedSet()
      val cache = HashMap<String, ByteArray>()
      val failures = mutableListOf<String>()
      val knownMissingLuck7Images = setOf("com.android.browser", "com.android.calendar", "com.android.fileexplorer",
        "com.android.providers.downloads.ui", "com.hengye.share", "com.mipay.wallet", "com.miui.compass",
        "com.miui.gallery", "com.miui.notes", "com.miui.securitycenter", "com.tencent.mm", "com.tencent.mobileqq",
        "com.xiaomi.market", "com.xiaomi.shop", "tv.danmaku.bili")
      val expectedRejections = mapOf(
        "lrone" to setOf("com.miui.weather2"),
        "luck7" to knownMissingLuck7Images,
      )
      assertEquals("Pinned local snapshot style coverage", 22, styles.size)
      styles.forEach { style ->
        val plan = planBlobs(entries, style)
        val staticResources = plan.files.filterKeys { it.startsWith("res/drawable-xxhdpi/") }
          .map { (path, blob) -> blob.copy(path = path.removePrefix("res/drawable-xxhdpi/")) }
        val rawStatic = PerfectIconFormat.staticGroups(staticResources).keys +
          staticResources.filter { '/' !in it.path && it.path.endsWith(".png") }
            .map { it.path.removeSuffix(".png") }.filter(PerfectIconFormat::id)
        try {
          val checked = PerfectIconFormat.validate(plan) { blob ->
            cache.getOrPut(blob.source) { zip.getInputStream(zip.getEntry(root + blob.source)).use { it.readBytes() } }
          }
          assertTrue(checked.staticIds.intersect(checked.dynamicIds).isEmpty())
          assertEquals(rawStatic + plan.dynamicIds, checked.staticIds + checked.dynamicIds)
          println("SOURCE $style: rawStatic=${rawStatic.size} rawDynamic=${plan.dynamicIds.size} " +
            "rawOverlap=${rawStatic.intersect(plan.dynamicIds).size}; " +
            "selectedStatic=${checked.staticIds.size} selectedDynamic=${checked.dynamicIds.size}; " +
            "rejected=${checked.rejectedDynamic}")
          assertEquals("Unexpected source rejection set for $style",
            expectedRejections[style].orEmpty(), checked.rejectedDynamic.keys)
        } catch (failure: Exception) {
          failures += "$style: ${failure.message}"
          println("SOURCE FAILURE $style: ${failure.message}")
        }
      }
      assertTrue("Styles failed validation: $failures", failures.isEmpty())
      println("SOURCE all styles=${styles.size}; failures=$failures")
    }
  }

  private fun base() = mapOf("icons/$id/0.png" to png, "icons/$id/1.png" to png)

  private fun config(vararg variables: Pair<String, String>) = "<Config><Variables>" +
    variables.joinToString("") { (key, value) -> "<Variable name='$key' value='$value' type='string'/>" } +
    "</Variables></Config>"

  private fun dynamic(
    style: String = "default",
    config: String = config("layer0" to "0"),
    layer: String = "0",
    quiet: Boolean = true,
    manifest: String = "<Image src='icon.png'/>",
    rootAttributes: String = "",
    fancy: Map<String, ByteArray> = mapOf("icon.png" to png),
  ): Map<String, ByteArray> {
    val base = "themes/$style/$prefix"
    return buildMap {
      put(base + "config.xml", config.toByteArray())
      if (quiet) put(base + "$layer/quiet/quietImage.png", png)
      put(base + "$layer/fancy/manifest.xml", "<Icon $rootAttributes>$manifest</Icon>".toByteArray())
      fancy.forEach { (path, bytes) -> put(base + "$layer/fancy/$path", bytes) }
    }
  }

  private fun plan(source: Map<String, ByteArray>): Pair<PerfectIconPlan, (PerfectIconBlob) -> ByteArray> {
    val entries = source.map { (path, bytes) -> PerfectIconBlob(path, path, bytes.size.toLong()) }
    return planBlobs(entries, "default") to
      { blob: PerfectIconBlob -> source.getValue(blob.source) }
  }

  private fun planBlobs(entries: List<PerfectIconBlob>, style: String): PerfectIconPlan {
    fun branch(prefix: String) = entries.filter { it.path.startsWith("$prefix/") }
      .map { it.copy(path = it.path.removePrefix("$prefix/")) }
    return PerfectIconFormat.plan(branch("icons"), branch("themes/$style/icons"),
      branch("themes/common/layer_animating_icons"), branch("themes/$style/layer_animating_icons"),
      PerfectIconOptions(style, installedOnly = false), emptySet())
  }

  private fun checked(source: Map<String, ByteArray>): PerfectIconPlan {
    val (plan, read) = plan(source)
    return PerfectIconFormat.validate(plan, read)
  }

  private fun assertFallback(source: Map<String, ByteArray>) {
    val checked = checked(source)
    assertEquals(setOf(id), checked.staticIds)
    assertTrue(checked.dynamicIds.isEmpty())
    assertTrue(checked.files.keys.none { it.startsWith(prefix) })
    assertTrue(checked.rejectedDynamic.getValue(id).isNotBlank())
  }

  private fun rejected(block: () -> Unit) {
    try { block() } catch (expected: Exception) { return }
    fail("Expected invalid dynamic resource rejection")
  }
}
