package os.kei.hyper.home.iconpack.perfect

import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PerfectDynamicNightTest {
  @Test fun slotOrderMapsToDirectoriesInsteadOfSortingDirectoryNumbers() {
    val fixture = fixture(background = "7", foreground = "2")
    val group = admitted(fixture)

    assertEquals("7/quiet/quietImage.png", group.background)
    assertEquals(setOf("2/quiet/quietImage.png", "2/fancy/icon.png"), group.foreground)
  }

  @Test fun namedDirectoriesAndMisleadingBgFilenameDoNotDetermineRoles() {
    val fixture = fixture(background = "foreground", foreground = "background",
      images = listOf("bg.png", "hand.png"))
    val group = admitted(fixture)

    assertEquals("foreground/quiet/quietImage.png", group.background)
    assertEquals(setOf("background/quiet/quietImage.png", "background/fancy/bg.png",
      "background/fancy/hand.png"), group.foreground)
  }

  @Test fun configDeclarationOrderDoesNotChangeSlotOrderOrSlotFlags() {
    val fixture = fixture(background = "plate", foreground = "art", config = config(
      "layer_only_quiet0" to "true", "layer1" to "art", "layer0" to "plate",
      "layer_only_quiet1" to "false", "fancy_work" to "false",
    ))

    assertEquals("plate/quiet/quietImage.png", admitted(fixture).background)
  }

  @Test fun quietBackgroundAndAnimatedForegroundPreserveTheWholeOriginalClosure() {
    val fixture = fixture(body = """
      <Group name="art" x="12" y="24">
        <Image name="icon" src="icon.png">
          <PositionAnimation><Item time="0" x="0" y="0"/><Item time="600" x="3" y="9"/></PositionAnimation>
          <RotationAnimation><Item time="0" angle="0"/><Item time="600" angle="360"/></RotationAnimation>
        </Image>
      </Group>
      <ExternalCommands><Trigger action="resume">
        <AnimationCommand target="icon" command="play"/>
      </Trigger></ExternalCommands>
    """.trimIndent())
    val originalConfig = fixture.bytes.getValue("config.xml").copyOf()
    val originalManifest = fixture.bytes.getValue("1/fancy/manifest.xml").copyOf()
    val group = admitted(fixture)

    assertSame(fixture.files, group.files)
    assertArrayEquals(originalConfig, fixture.read(group.files.getValue("config.xml")))
    assertArrayEquals(originalManifest, fixture.read(group.files.getValue("1/fancy/manifest.xml")))
    assertTrue("config.xml" !in group.foreground)
    assertTrue("1/fancy/manifest.xml" !in group.foreground)
  }

  @Test fun classificationIsIndependentOfPackageAndUpstreamStyleNames() {
    val relative = fixture()
    for (source in listOf(
      "themes/common/layer_animating_icons/com.example.unlisted/",
      "themes/HyperOS4/layer_animating_icons/org.example.replacement/",
      "themes/other/layer_animating_icons/org.example.dark.lottie/",
    )) {
      val files = relative.files.mapValues { (path, blob) -> blob.copy(path = source + path, source = source + path) }
      val group = PerfectDynamicNight.group(files, relative::read)
      assertNotNull(source, group)
      assertEquals("0/quiet/quietImage.png", group!!.background)
      assertEquals(admitted(relative).foreground, group.foreground)
    }
  }

  @Test fun backgroundMustBeDeclaredQuietOnly() {
    val fixture = fixture(config = config("layer0" to "0", "layer1" to "1"))
    assertNull(fixture.classify())
  }

  @Test fun foregroundMayUseQuietFallbackWithOrWithoutAFancyProgram() {
    val quietOnlyResources = fixture().without("1/fancy/icon.png", "1/fancy/manifest.xml")
    assertEquals(setOf("1/quiet/quietImage.png"), admitted(quietOnlyResources).foreground)
    assertEquals(setOf("1/quiet/quietImage.png", "1/fancy/icon.png"), admitted(fixture()).foreground)
  }

  @Test fun fancyOnlyForegroundMayHaveAnOptionalQuietFallback() {
    val fixture = fixture(config = config(
      "layer0" to "0", "layer1" to "1", "layer_only_quiet0" to "true",
      "layer_only_fancy1" to "true",
    ))
    assertNotNull(fixture.classify())
    assertNotNull(fixture.without("1/quiet/quietImage.png").classify())
    assertNull(fixture.without("1/fancy/manifest.xml").classify())
  }

  @Test fun requiresAtLeastTwoDeclaredLayersAndActualForegroundContent() {
    val oneLayer = fixture(config = config("layer0" to "0", "layer_only_quiet0" to "true"))
    val threeLayers = fixture(config = config(
      "layer0" to "0", "layer1" to "1", "layer2" to "2", "layer_only_quiet0" to "true",
    ))
    assertNull(oneLayer.classify())
    assertNull(threeLayers.classify())
    assertNull(fixture().without("0/quiet/quietImage.png").classify())
    assertNull(fixture().without("1/fancy/icon.png", "1/quiet/quietImage.png").classify())
    assertNull(fixture().without("config.xml").classify())
  }

  @Test fun rootQuietCompositeHasASeparateRoleAndCannotBecomeABackground() {
    val group = admitted(fixture().with("quiet/quietImage.png", raster))
    assertEquals("quiet/quietImage.png", group.compositeQuiet)
    assertEquals(setOf("0/quiet/quietImage.png"), group.bgAssets)
    assertTrue(group.compositeQuiet !in group.foreground)
    assertEquals(group.bgAssets + group.foreground + "quiet/quietImage.png", group.nightPaths)
    assertNull(fixture().with("quiet/composite.png", raster).classify())
  }

  @Test fun calendarAllowsCompleteDateSeriesAndAThirdFancyOnlyTextLayer() {
    var calendar = fixture(images = (1..31).map { "num_$it.png" },
      body = "<Image src='num.png' srcid='#date'/>",
      config = config("layer0" to "0", "layer1" to "1", "layer2" to "2",
        "layer_only_quiet0" to "true", "layer_only_fancy1" to "true",
        "layer_only_fancy2" to "true"))
      .without("1/quiet/quietImage.png")
      .with("0/fancy/manifest.xml", manifest("<Image src='bg.png'/>"))
      .with("0/fancy/bg.png", raster)
      .with("2/fancy/manifest.xml", manifest("<Text color='#3482FF' textExp=\"formatDate('E',#time_sys)\"/>"))
      .with("quiet/quietImage.png", raster)
    val group = admitted(calendar)
    assertEquals(setOf("0/quiet/quietImage.png", "0/fancy/bg.png"), group.bgAssets)
    assertEquals(31, group.foreground.size)
    assertEquals("quiet/quietImage.png", group.compositeQuiet)

    calendar = calendar.with("config.xml", config("layer0" to "0", "layer1" to "1",
      "layer2" to "2", "layer3" to "3", "layer_only_quiet0" to "true",
      "layer_only_fancy1" to "true", "layer_only_fancy2" to "true",
      "layer_only_fancy3" to "true").toByteArray())
      .with("3/fancy/manifest.xml", manifest("<Text text='4' color='#ffffff'/>"))
    assertNotNull(calendar.classify())
    val five = calendar.with("config.xml", config("layer0" to "0", "layer1" to "1",
      "layer2" to "2", "layer3" to "3", "layer4" to "4", "layer_only_quiet0" to "true")
      .toByteArray()).with("4/fancy/manifest.xml", manifest("<Text text='5'/>"))
    assertNull(five.classify())
  }

  @Test fun existingBackgroundIsAPlainImageSceneWithOnlyMappedBackgroundRasters() {
    val fixture = fixture()
      .with("0/fancy/manifest.xml", manifest("<Image src='plate/bg.png'/>"))
      .with("0/fancy/plate/bg.png", raster)
    assertEquals(setOf("0/quiet/quietImage.png", "0/fancy/plate/bg.png"), admitted(fixture).bgAssets)
    assertNotNull(fixture.without("0/quiet/quietImage.png").classify())
    assertNull(fixture.with("0/fancy/other.png", raster).classify())
    for (body in listOf("<Image src='plate/bg.png'/><Image src='plate/bg.png'/>",
      "<Group><Image src='plate/bg.png'/></Group>",
      "<Image src='plate/bg.png' srcid='#date'/>",
      "<Image src='plate/bg.png'/><Function name='animate'/>")) {
      assertNull(body, fixture.with("0/fancy/manifest.xml", manifest(body)).classify())
    }
  }

  @Test fun clockFunctionsAndNativeDatetimeUpdateCommandsAreAllowedWithoutProviderIO() {
    val body = """
      <Image src='icon.png' rotation='#minute * 6'/>
      <Function name='end'><IfCommand ifCondition='#second_visible == 1'>
        <Consequent><ExternCommand command='__datatimeVarUpdate' strPara="'DateTime.Second'"/></Consequent>
        <Consequent><ExternCommand command='__datatimeVarUpdate' strPara="'DateTime.Minute'"/></Consequent>
      </IfCommand><FrameRateCommand rate='0'/></Function>
      <ExternalCommands><Trigger action='close_second'><FunctionCommand target='end'/></Trigger></ExternalCommands>
    """.trimIndent()
    assertNotNull(fixture(body = body).classify())
    assertNull(fixture(body = body.replace("__datatimeVarUpdate", "unknownCommand")).classify())
    assertNull(fixture(body = body.replace("'DateTime.Second'", "#arbitraryExpression")).classify())
  }

  @Test fun excludesBackgroundFancyResourcesEvenWhenQuietOnlyWouldNormallyHideThem() {
    for (path in listOf("plate/fancy/bg.png", "plate/fancy/manifest.xml")) {
      val bytes = if (path.endsWith(".xml")) manifest("<Image src='bg.png'/>") else raster
      assertNull(path, fixture(background = "plate", foreground = "art").with(path, bytes).classify())
    }
  }

  @Test fun excludesRastersOutsideTheMappedForegroundDirectory() {
    for (path in listOf("other/quiet/quietImage.png", "10/fancy/icon.png", "icon.png",
      "0/quiet/extra.png")) {
      assertNull(path, fixture().with(path, raster).classify())
    }
  }

  @Test fun excludesExplicitAuthorTintAndColorAttributesOnRootOrImage() {
    for (attribute in listOf("tint", "tintExp", "color", "colorExp", "colorFilter")) {
      assertNull("root $attribute",
        fixture(rootAttributes = "$attribute='#d6d6d6'").classify())
      assertNull("image $attribute",
        fixture(body = "<Image src='icon.png' $attribute='#d6d6d6'/>").classify())
    }
  }

  @Test fun excludesAuthorDarkExpressionsInVisualAndControlAttributesCaseInsensitively() {
    for (body in listOf(
      "<Image src='icon.png' alpha='ifelse(#__darkmode,128,255)'/>",
      "<Var name='night' expression='#__DARKMODE'/>",
      "<ExternalCommands><Trigger action='resume' condition='#__darkmode'>" +
        "<AnimationCommand target='icon' command='play'/></Trigger></ExternalCommands>",
    )) {
      assertNull(body, fixture(body = body).classify())
    }
  }

  @Test fun excludesAuthorDarkUpdaterEvenWithoutAnExplicitDarkExpression() {
    for (updaters in listOf("DarkMode", "DateTime.Second,DarkMode")) {
      assertNull(updaters, fixture(rootAttributes = "useVariableUpdater='$updaters'").classify())
    }
  }

  @Test fun excludesLottieProgramsUnknownTagsAndNonBitmapResourceFiles() {
    for (body in listOf("<Lottie src='motion.json'/>", "<ProceduralShape/>",
      "<Image src='icon.png'><UnknownAnimation/></Image>")) {
      assertNull(body, fixture(body = body).classify())
    }
    for (path in listOf("1/fancy/motion.json", "1/fancy/icon.svg",
      "1/fancy/font.ttf", "1/fancy/extra.xml")) {
      assertNull(path, fixture().with(path, "{}".toByteArray()).classify())
    }
  }

  @Test fun literalTextColorsArePreservedButExpressionsVectorShapesAndProviderIOAreExcluded() {
    assertNotNull(fixture(body = "<Text text='24' color='#3482FF'/>").classify())
    for (body in listOf(
      "<Text text='24' color='ifelse(#night,0,1)'/>",
      "<Text text='24' colorExp='#weekdayColor'/>",
      "<Rectangle w='4' h='30' fillColor='#ffffff'/>",
      "<Circle r='24'/>",
      "<VariableBinders><ContentProviderBinder uri='content://weather'/></VariableBinders>",
    )) {
      assertNull(body, fixture(body = body).classify())
    }
  }

  @Test fun excludesBgAndFgSceneRootsWithoutInventingAWrapper() {
    for (root in listOf("bg", "fg", "vector")) {
      val fixture = fixture().with("1/fancy/manifest.xml",
        "<$root><Image src='icon.png'/></$root>".toByteArray())
      assertNull(root, fixture.classify())
    }
  }

  @Test fun excludesMaskNodesAndDestructiveLiteralBlendModes() {
    assertNull(fixture(body = "<Image src='icon.png'><Mask src='mask.png'/></Image>")
      .with("1/fancy/mask.png", raster).classify())
    for (mode in listOf("src_in", "dst_in", "dst_out", "clear", "xor")) {
      assertNull(mode, fixture(body = "<Image src='icon.png' xfermode='$mode'/>").classify())
    }
  }

  @Test fun excludesExpressionDrivenBlendModesInsteadOfAssumingSourceOver() {
    assertNull(fixture(body = "<Image src='icon.png' xfermodeExp='#blend'/>").classify())
  }

  @Test fun excludesLayeredMaskCompositeEvenWhenItsLiteralBlendModeIsSrcAtop() {
    val fixture = fixture(body = """
      <Group layered="true">
        <Image src="mask.png"/>
        <Image src="icon.png" xfermode="src_atop"/>
      </Group>
    """.trimIndent()).with("1/fancy/mask.png", raster)
    assertNull(fixture.classify())
  }

  @Test fun permitsExplicitSourceOverOnAnOrdinaryBitmap() {
    assertNotNull(fixture(body = "<Image src='icon.png' xfermode='src_over'/>").classify())
  }

  @Test fun rasterCountIncludesEveryRoleAndAllowsAtMost128() {
    val images = (0 until 126).map { "frame_$it.png" }
    val atLimit = fixture(images = images)
    assertEquals(127, admitted(atLimit).foreground.size)

    assertNull(atLimit.with("1/fancy/frame_126.png", raster).classify())
  }

  @Test fun rejectsOver256FilesBeforeReadingConfigOrPrograms() {
    val fixture = fixture()
    val files = fixture.files.toMutableMap()
    repeat(257 - files.size) { index ->
      val path = "1/fancy/extra_$index/manifest.xml"
      files[path] = blob(path, 1)
    }
    assertEquals(257, files.size)
    assertRejectedWithoutRead(files)
  }

  @Test fun rejectsAggregateDeclaredBytesOver32MiBBeforeReadingConfigOrPrograms() {
    val fixture = fixture()
    val files = fixture.files.mapValues { (_, blob) -> blob.copy(size = 0) }.toMutableMap()
    files["0/quiet/quietImage.png"] = files.getValue("0/quiet/quietImage.png")
      .copy(size = 32L * 1024 * 1024)
    files["1/quiet/quietImage.png"] = files.getValue("1/quiet/quietImage.png").copy(size = 1)
    assertRejectedWithoutRead(files)
  }

  @Test fun propagatesCancellationBeforeAnyResourceRead() {
    val cancelled = CancellationException("cancel dynamic night classification")
    var reads = 0
    try {
      PerfectDynamicNight.group(fixture().files, { reads++; error("must not read") }) { throw cancelled }
      fail("Cancellation must propagate")
    } catch (failure: CancellationException) {
      assertSame(cancelled, failure)
    }
    assertEquals(0, reads)
  }

  @Test fun checksCancellationAgainAfterReadingManifest() {
    val fixture = fixture()
    val cancelled = CancellationException("cancel after manifest")
    var checks = 0
    var manifestRead = false
    try {
      PerfectDynamicNight.group(fixture.files, { blob ->
        fixture.read(blob).also { if (blob.sha.endsWith("/manifest.xml")) manifestRead = true }
      }) {
        checks++
        if (manifestRead) throw cancelled
      }
      fail("Cancellation after manifest must propagate")
    } catch (failure: CancellationException) {
      assertSame(cancelled, failure)
    }
    assertTrue(checks > 1)
    assertTrue(manifestRead)
  }

  // The classifier reads XML and blob metadata, not bitmap bytes; decoding belongs to the renderer.
  private val raster = byteArrayOf(1, 2, 3)

  private data class Fixture(
    val files: Map<String, PerfectIconBlob>,
    val bytes: Map<String, ByteArray>,
  ) {
    fun read(blob: PerfectIconBlob): ByteArray = bytes.getValue(blob.sha)

    fun classify(): PerfectDynamicNight.Group? = PerfectDynamicNight.group(files, ::read)

    fun with(path: String, content: ByteArray): Fixture = Fixture(
      files + (path to PerfectIconBlob(path, path, content.size.toLong())),
      bytes + (path to content),
    )

    fun without(vararg paths: String): Fixture = Fixture(files - paths.toSet(), bytes - paths.toSet())
  }

  private fun fixture(
    background: String = "0",
    foreground: String = "1",
    config: String = config("layer0" to background, "layer1" to foreground,
      "layer_only_quiet0" to "true", "layer_only_quiet1" to "false", "fancy_work" to "false"),
    images: List<String> = listOf("icon.png"),
    body: String = images.joinToString("") { "<Image src='$it'/>" },
    rootAttributes: String = "",
  ): Fixture {
    val bytes = linkedMapOf(
      "config.xml" to config.toByteArray(),
      "$background/quiet/quietImage.png" to raster,
      "$foreground/quiet/quietImage.png" to raster,
      "$foreground/fancy/manifest.xml" to manifest(body, rootAttributes),
    )
    for (image in images) bytes["$foreground/fancy/$image"] = raster
    return Fixture(bytes.mapValues { (path, content) -> blob(path, content.size.toLong()) }, bytes)
  }

  private fun config(vararg values: Pair<String, String>): String =
    "<Config><Variables>" + values.joinToString("") { (name, value) ->
      "<Variable name='$name' value='$value' type='string'/>"
    } + "</Variables></Config>"

  private fun manifest(body: String, attributes: String = ""): ByteArray =
    "<Icon version='1' width='351' height='351' $attributes>$body</Icon>".toByteArray()

  private fun blob(path: String, size: Long): PerfectIconBlob = PerfectIconBlob(path, path, size)

  private fun admitted(fixture: Fixture): PerfectDynamicNight.Group {
    val group = fixture.classify()
    assertNotNull("Expected bounded bitmap-only group to be eligible", group)
    return requireNotNull(group)
  }

  private fun assertRejectedWithoutRead(files: Map<String, PerfectIconBlob>) {
    var reads = 0
    assertNull(PerfectDynamicNight.group(files, { reads++; error("Oversized group must not be read") }))
    assertEquals(0, reads)
  }
}
