package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class PerfectDynamicNightSwitchTest {
  @Test fun returnsCompleteBaseFilesWithOriginalQuietAndDaytimeTexturesUntouched() {
    val fixture = fixture()
    val output = converted(fixture)

    for ((path, bytes) in fixture.originals) {
      assertTrue(path, path in output)
      if (path != "config.xml" && !path.endsWith("/manifest.xml")) {
        assertSame(path, bytes, output.getValue(path))
      }
    }
    assertTrue(output.keys.none { it.startsWith("nightmode/") })
    assertArrayEquals(fixture.night.getValue("art/fancy/icon.png"),
      output.getValue("art/fancy/keihome_night/icon.png"))
    assertArrayEquals(fixture.originals.getValue(fixture.group.background),
      output.getValue("plate/fancy/day.png"))
    assertArrayEquals(fixture.night.getValue(fixture.group.background),
      output.getValue("plate/fancy/night.png"))
    assertTrue("art/quiet/keihome_night/quietImage.png" !in output)
  }

  @Test fun foregroundPreservesEverySourceNodeAttributeEventAndAnimationExceptSelectors() {
    val fixture = fixture()
    val output = converted(fixture)
    val before = parse(fixture.originals.getValue("art/fancy/manifest.xml"))
    val after = parse(output.getValue("art/fancy/manifest.xml"))
    val images = after.getElementsByTagName("Image")
    assertEquals("DateTime.Second,DarkMode", after.documentElement.getAttribute("useVariableUpdater"))
    assertEquals("icon", (images.item(0) as Element).getAttribute("name"))
    assertEquals("icon.png", (images.item(0) as Element).getAttribute("src"))
    assertEquals("ifelse(#__darkmode,'keihome_night/icon.png','icon.png')",
      (images.item(0) as Element).getAttribute("srcExp"))
    before.documentElement.removeAttribute("useVariableUpdater")
    after.documentElement.removeAttribute("useVariableUpdater")
    for (index in 0 until images.length) (images.item(index) as Element).removeAttribute("srcExp")
    assertEquals(signature(before), signature(after))
  }

  @Test fun mapsConfigSlotZeroFlagsToNamedDirectoriesAndPreservesAllOtherVariables() {
    val fixture = fixture()
    val original = variables(parse(fixture.originals.getValue("config.xml")))
    val document = parse(converted(fixture).getValue("config.xml"))
    val variables = variables(document)

    assertEquals("Config", document.documentElement.tagName)
    assertEquals(0, document.documentElement.attributes.length)
    assertEquals(original + mapOf("layer_only_quiet0" to "false", "layer_only_fancy0" to "true"), variables)
    assertEquals("plate", variables.getValue("layer0"))
    assertEquals("art", variables.getValue("layer1"))
    val flag = (0 until document.getElementsByTagName("Variable").length)
      .map { document.getElementsByTagName("Variable").item(it) as Element }
      .single { it.getAttribute("name") == "layer_only_fancy0" }
    assertEquals("string", flag.getAttribute("type"))
  }

  @Test fun updatesExistingFancyFlagWithoutAddingDuplicateVariables() {
    val fixture = fixture(extraConfig = "<Variable name='layer_only_fancy0' value='false' type='string'/>")
    val document = parse(converted(fixture).getValue("config.xml"))
    val entries = document.getElementsByTagName("Variable")
    assertEquals(1, (0 until entries.length).count {
      (entries.item(it) as Element).getAttribute("name") == "layer_only_fancy0"
    })
    assertEquals("true", variables(document).getValue("layer_only_fancy0"))
  }

  @Test fun backgroundUsesDerivedPngDimensionsZeroFramerateAndOnlyDarkModeUpdater() {
    val fixture = fixture(width = 43, height = 29)
    val document = parse(converted(fixture).getValue("plate/fancy/manifest.xml"))
    val root = document.documentElement
    assertEquals("43", root.getAttribute("width"))
    assertEquals("29", root.getAttribute("height"))
    assertEquals("0", root.getAttribute("frameRate"))
    assertEquals("DarkMode", root.getAttribute("useVariableUpdater"))
    val images = document.getElementsByTagName("Image")
    assertEquals(2, images.length)
    val day = images.item(0) as Element
    val night = images.item(1) as Element
    assertEquals("day.png", day.getAttribute("src"))
    assertEquals("not(#__darkmode)", day.getAttribute("visibility"))
    assertEquals("night.png", night.getAttribute("src"))
    assertEquals("#__darkmode", night.getAttribute("visibility"))
    for (image in listOf(day, night)) {
      assertEquals("43", image.getAttribute("w"))
      assertEquals("29", image.getAttribute("h"))
      assertEquals(0, image.childNodes.length)
    }
  }

  @Test fun nestedLiteralImagesKeepNamesAndGetSeparatePerFancyNamespacePaths() {
    val fixture = fixture(images = listOf("icon.png", "hands/hour.png"))
    val output = converted(fixture)
    val images = parse(output.getValue("art/fancy/manifest.xml")).getElementsByTagName("Image")
    val second = images.item(1) as Element
    assertEquals("icon1", second.getAttribute("name"))
    assertEquals("hands/hour.png", second.getAttribute("src"))
    assertEquals("ifelse(#__darkmode,'keihome_night/hands/hour.png','hands/hour.png')",
      second.getAttribute("srcExp"))
    assertArrayEquals(fixture.night.getValue("art/fancy/hands/hour.png"),
      output.getValue("art/fancy/keihome_night/hands/hour.png"))
  }

  @Test fun addsDarkModeToExplicitNoneUpdaterWithoutDuplicatingNone() {
    for (attributes in listOf("useVariableUpdater='none'")) {
      val fixture = fixture(updater = attributes)
      val root = parse(converted(fixture).getValue("art/fancy/manifest.xml")).documentElement
      assertEquals("DarkMode", root.getAttribute("useVariableUpdater"))
    }
  }

  @Test fun calendarPreservesThreeLayerProgramsBlueTextAndEveryDayFrame() {
    val fixture = calendar()
    val output = converted(fixture)
    for (path in listOf("plate/fancy/manifest.xml", "art/fancy/manifest.xml", "weekday/fancy/manifest.xml")) {
      val before = parse(fixture.originals.getValue(path))
      val after = parse(output.getValue(path))
      val updater = before.documentElement.getAttribute("useVariableUpdater")
      assertEquals(if (updater == "none") "DarkMode" else "$updater,DarkMode",
        after.documentElement.getAttribute("useVariableUpdater"))
      before.documentElement.removeAttribute("useVariableUpdater")
      after.documentElement.removeAttribute("useVariableUpdater")
      val images = after.getElementsByTagName("Image")
      for (index in 0 until images.length) (images.item(index) as Element).removeAttribute("srcExp")
      assertEquals(path, signature(before), signature(after))
    }
    val date = parse(output.getValue("art/fancy/manifest.xml"))
      .getElementsByTagName("Image").item(0) as Element
    assertEquals("#date", date.getAttribute("srcid"))
    assertEquals("num.png", date.getAttribute("src"))
    assertEquals("ifelse(#__darkmode,'keihome_night/num.png','num.png')", date.getAttribute("srcExp"))
    for (day in 1..31) {
      val path = "art/fancy/num_$day.png"
      assertArrayEquals(fixture.originals.getValue(path), output.getValue(path))
      assertArrayEquals(fixture.night.getValue(path), output.getValue("art/fancy/keihome_night/num_$day.png"))
    }
    assertFalse("art/fancy/num.png" in output)
    assertFalse("plate/fancy/day.png" in output)
    assertArrayEquals(fixture.night.getValue("plate/fancy/bg.png"),
      output.getValue("plate/fancy/keihome_night/bg.png"))
    val text = parse(output.getValue("weekday/fancy/manifest.xml"))
      .getElementsByTagName("Text").item(0) as Element
    assertEquals("#3482FF", text.getAttribute("color"))
    assertEquals("formatDate('E',#time_sys)", text.getAttribute("textExp"))
    assertArrayEquals(fixture.originals.getValue("quiet/quietImage.png"), output.getValue("quiet/quietImage.png"))
  }

  @Test fun dateSelectorRequiresAll31DayAndNightFramesAndRejectsUnboundedSourceIds() {
    val calendar = calendar()
    for (day in listOf(1, 15, 31)) {
      val missing = fixtureFrom(calendar.originals - "art/fancy/num_$day.png")
      assertNull(missing.convert())
      assertNull(PerfectDynamicNightSwitch.systemFiles(
        calendar.group, calendar.originals, calendar.night - "art/fancy/num_$day.png"))
    }
    for (attribute in listOf("srcid='#month'", "srcid='0'", "srcidExp='#date'")) {
      val invalid = fixtureFrom(calendar.originals + ("art/fancy/manifest.xml" to
        "<Icon><Image src='num.png' $attribute/></Icon>".toByteArray()))
      assertNull(invalid.convert())
    }
  }

  @Test fun neutralCalendarTextKeepsIdentityMetricsCommandsAndEveryOtherAttribute() {
    val original = calendar().originals.toMutableMap()
    original["weekday/fancy/manifest.xml"] = """
      <Icon version="1" frameRate="0" width="351" height="351" screenWidth="1080"
        useVariableUpdater="DateTime.Day">
        <ExternalCommands>
          <Trigger action="resume"><FrameRateCommand rate="30"/><FrameRateCommand rate="0" delay="100"/></Trigger>
          <Trigger action="back_home"><FrameRateCommand rate="30"/><FrameRateCommand rate="0" delay="100"/></Trigger>
        </ExternalCommands>
        <Text name="weekSize" pivotX="#weekSize.text_width/2" pivotY="#weekSize.text_height/2"
          scale="min(1,190/#weekSize.text_width)" x="351/2" y="351/2+31" align="center"
          size="43.83" fontFamily="mipro-demibold" color="#000000"
          textExp="strToUpperCase(ifelse(strContains(formatDate('EEEE',#time_sys),'&#x661f;&#x671f;'),formatDate('EEEE',#time_sys),formatDate('E',#time_sys)))"
          alpha="255*0.75"/>
      </Icon>
    """.trimIndent().toByteArray()
    val fixture = fixtureFrom(original)
    val saved = original.mapValues { it.value.copyOf() }
    val output = converted(fixture)
    val before = parse(original.getValue("weekday/fancy/manifest.xml"))
    val after = parse(output.getValue("weekday/fancy/manifest.xml"))
    val text = after.getElementsByTagName("Text").item(0) as Element
    assertEquals(1, after.getElementsByTagName("Text").length)
    assertEquals(PerfectDynamicIconValidator.nightTextColor("#000000"), text.getAttribute("color"))
    assertFalse(text.hasAttribute("colorExp"))
    assertEquals("DateTime.Day,DarkMode", after.documentElement.getAttribute("useVariableUpdater"))
    text.setAttribute("color", "#000000")
    after.documentElement.setAttribute("useVariableUpdater", "DateTime.Day")
    assertEquals(signature(withoutFormatting(before)), signature(withoutFormatting(after)))
    for ((path, bytes) in saved) assertArrayEquals(path, bytes, original.getValue(path))
  }

  @Test fun neutralTextOnlyLayerPreservesArgbAlphaWithoutInventingBitmapResources() {
    for (literal in listOf("#000000", "#00181818", "#80202020", "#FF1f1f1f")) {
      val fixture = fixtureFrom((fixture().originals -
        setOf("art/fancy/icon.png", "art/quiet/quietImage.png")) +
        ("art/fancy/manifest.xml" to
          "<Icon useVariableUpdater='none'><Text color='$literal' alpha='0.75' text='Mon'/></Icon>".toByteArray()))
      val output = converted(fixture)
      val root = parse(output.getValue("art/fancy/manifest.xml")).documentElement
      val text = root.getElementsByTagName("Text").item(0) as Element
      assertEquals("DarkMode", root.getAttribute("useVariableUpdater"))
      assertEquals("0.75", text.getAttribute("alpha"))
      assertEquals(PerfectDynamicIconValidator.nightTextColor(literal), text.getAttribute("color"))
      assertFalse(output.keys.any { it.startsWith("art/fancy/keihome_night/") })
    }
  }

  @Test fun coloredAndNonDarkTextRemainsAuthored() {
    for (literal in listOf("#3482FF", "#000001", "#010000", "#212121")) {
      val fixture = fixture(body = "<Image src='icon.png'/><Text color='$literal' text='Mon'/>")
      val root = parse(converted(fixture).getValue("art/fancy/manifest.xml")).documentElement
      assertEquals(literal, (root.getElementsByTagName("Text").item(0) as Element).getAttribute("color"))
    }
  }

  @Test fun sourceExpressionsAndFolmeControlledNeutralTextAreNotRewritten() {
    val expression = requireNotNull(PerfectDynamicIconValidator.nightTextColor("#000000"))
    for (body in listOf(
      "<Image src='icon.png'/><Text color='$expression' text='Mon'/>",
      "<Image src='icon.png'/><Text color='#000000' colorExp='1' text='Mon'/>",
      "<Image src='icon.png'/><Text color='#000000' folmeMode='true' text='Mon'/>",
    )) assertNull(fixture(body = body).convert())
  }

  @Test fun revalidationRejectsForgedCompositeQuietProofLayers() {
    val fixture = fixture()
    assertNotNull(fixture.convert())
    assertNull(PerfectDynamicNightSwitch.systemFiles(
      fixture.group.copy(compositeQuietProofLayers = listOf("art/quiet/quietImage.png")),
      fixture.originals, fixture.night))
  }

  @Test fun textOnlyForegroundNeedsNoInventedBitmapAndKeepsItsLiteralColor() {
    val fixture = fixtureFrom((fixture().originals - setOf("art/fancy/icon.png", "art/quiet/quietImage.png")) +
      ("art/fancy/manifest.xml" to
        "<Icon useVariableUpdater='DateTime.Day'><Text color='#3482FF' text='Monday'/></Icon>".toByteArray()))
    assertTrue(fixture.group.foreground.isEmpty())
    val output = converted(fixture)
    val root = parse(output.getValue("art/fancy/manifest.xml")).documentElement
    assertEquals("DateTime.Day,DarkMode", root.getAttribute("useVariableUpdater"))
    assertEquals("#3482FF", (root.getElementsByTagName("Text").item(0) as Element).getAttribute("color"))
    assertFalse(output.keys.any { it.startsWith("art/fancy/keihome_night/") })
  }

  @Test fun clockPreservesFunctionsBothConsequentsNativeTimeCommandsAndSecondVisibility() {
    val fixture = fixture(body = """
      <Function name="start"><FrameRateCommand rate="90"/></Function>
      <Function name="end"><IfCommand ifCondition="#second_visible == 1">
        <Consequent><ExternCommand command="__datatimeVarUpdate" strPara="'DateTime.Second'"/></Consequent>
        <Consequent><ExternCommand command="__datatimeVarUpdate" strPara="'DateTime.Minute'"/></Consequent>
      </IfCommand><FrameRateCommand rate="0"/></Function>
      <ExternalCommands>
        <Trigger action="open_second"><FunctionCommand target="start"/></Trigger>
        <Trigger action="close_second"><FunctionCommand target="end"/></Trigger>
      </ExternalCommands>
      <Image name="second" src="icon.png" rotation="#second * 6" alpha="0"/>
    """.trimIndent())
    val output = converted(fixture)
    val before = parse(fixture.originals.getValue("art/fancy/manifest.xml"))
    val after = parse(output.getValue("art/fancy/manifest.xml"))
    before.documentElement.removeAttribute("useVariableUpdater")
    after.documentElement.removeAttribute("useVariableUpdater")
    (after.getElementsByTagName("Image").item(0) as Element).removeAttribute("srcExp")
    assertEquals(signature(before), signature(after))
  }

  @Test fun canonicalWeatherPublishesEveryGlyphAndKeepsProviderControlsAndVisibilityParent() {
    val fixture = weather()
    val output = converted(fixture)
    val base = "art/fancy/"
    val originalResources = requireNotNull(NativeWeatherLayerProgram.resources(
      fixture.originals.getValue(base + "manifest.xml"), base, fixture.originals.keys))
    assertEquals(13, originalResources.size)
    val publishedResources = requireNotNull(NativeWeatherLayerProgram.resources(
      output.getValue(base + "manifest.xml"), base, output.keys))
    val namespaceResources = originalResources.mapTo(linkedSetOf()) {
      base + "keihome_night/" + it.removePrefix(base)
    }
    assertEquals(originalResources + namespaceResources, publishedResources)
    for (path in originalResources) {
      assertArrayEquals(fixture.originals.getValue(path), output.getValue(path))
      assertArrayEquals(fixture.night.getValue(path),
        output.getValue(base + "keihome_night/" + path.removePrefix(base)))
    }
    val before = parse(fixture.originals.getValue(base + "manifest.xml"))
    val after = parse(output.getValue(base + "manifest.xml"))
    assertEquals("DateTime.Minute,DarkMode", after.documentElement.getAttribute("useVariableUpdater"))
    for (tag in listOf("VariableBinders", "ExternalCommands"))
      assertEquals(signature(withoutFormatting(before.getElementsByTagName(tag).item(0))),
        signature(withoutFormatting(after.getElementsByTagName(tag).item(0))))
    val groups = after.getElementsByTagName("Group")
    assertEquals(3, groups.length)
    assertEquals("#hasweather", (groups.item(0) as Element).getAttribute("visibility"))
    val chars = after.getElementsByTagName("ImageChars")
    assertEquals(2, chars.length)
    assertEquals(setOf("num.png", "keihome_night/num.png"),
      (0 until chars.length).map { (chars.item(it) as Element).getAttribute("src") }.toSet())
    for (index in 0 until chars.length) {
      val image = chars.item(index) as Element
      assertEquals("#temperature + '\u00b0'", image.getAttribute("string"))
      assertEquals("Group", (image.parentNode as Element).tagName)
      assertEquals("#hasweather", (image.parentNode.parentNode as Element).getAttribute("visibility"))
    }
  }

  @Test fun canonicalWeatherRequiresEveryOriginalAndConvertedGlyphBeforePublication() {
    val fixture = weather()
    val resources = requireNotNull(NativeWeatherLayerProgram.resources(
      fixture.originals.getValue("art/fancy/manifest.xml"), "art/fancy/", fixture.originals.keys))
    for (path in resources) {
      assertNull(path, PerfectDynamicNightSwitch.systemFiles(
        fixture.group, fixture.originals, fixture.night - path))
      val missing = fixture.originals - path
      val blobs = missing.mapValues { (name, bytes) -> blob(name, bytes) }
      assertNull(path, PerfectDynamicNight.group(blobs, { missing.getValue(it.path) }))
    }
    assertNull(fixtureFrom(fixture.originals + ("art/fancy/unreferenced.png" to png(8, 6, 20))).convert())
  }

  @Test fun weatherSchemaChangesCannotBypassTheGenericProviderIOExclusion() {
    val fixture = weather()
    val source = fixture.originals.getValue("art/fancy/manifest.xml").toString(Charsets.UTF_8)
    for ((original, changed) in listOf(
      "content://weather/actualWeatherData/4" to "content://unrelated/private",
      "columns=\"temperature\"" to "columns=\"temperature,private\"",
      "600000" to "60000",
      "delay=\"550\"" to "delay=\"0\"",
      "command=\"refresh\"" to "command=\"unknown\"",
    )) {
      val bytes = fixture.originals + ("art/fancy/manifest.xml" to source.replace(original, changed).toByteArray())
      val blobs = bytes.mapValues { (path, value) -> blob(path, value) }
      assertNull(changed, PerfectDynamicNight.group(blobs, { bytes.getValue(it.path) }))
    }
  }

  @Test fun generatedWeatherIsRecognizedForAdmissionButNeverReprocessedAsAnOriginal() {
    val output = converted(weather())
    assertNotNull(NativeWeatherLayerProgram.resources(
      output.getValue("art/fancy/manifest.xml"), "art/fancy/", output.keys))
    val blobs = output.mapValues { (path, bytes) -> blob(path, bytes) }
    assertNull(PerfectDynamicNight.group(blobs, { output.getValue(it.path) }))
    assertNull(NativeWeatherLayerProgram.darkVariant(
      output.getValue("art/fancy/manifest.xml"), "art/fancy/", output.keys))
  }

  @Test fun preservesImplicitMinuteUpdaterWhenAddingDarkMode() {
    for (attributes in listOf("", "useVariableUpdater=''", "useVariableUpdater='  '")) {
      val root = parse(converted(fixture(updater = attributes))
        .getValue("art/fancy/manifest.xml")).documentElement
      assertEquals("DateTime.Minute,DarkMode", root.getAttribute("useVariableUpdater"))
    }
  }

  @Test fun rejectsExistingSelectorsAndSrcidProgramsWithoutMutatingInputs() {
    for (attribute in listOf("srcExp='some_expression'", "srcid='#frame'", "srcid='0'", "srcidExp='#frame'")) {
      val fixture = fixture(imageAttributes = attribute)
      val before = fixture.originals.mapValues { it.value.copyOf() }
      assertNull(attribute, fixture.convert())
      before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, fixture.originals.getValue(path)) }
    }
  }

  @Test fun rejectsExpressionPathsTraversalAndQuotesInsteadOfInterpolatingThem() {
    for (source in listOf("#icon", "../quiet/quietImage.png", "/icon.png",
      "ifelse(#x,a,b)", "icon&apos;s.png")) {
      val fixture = fixture(body = "<Image name='icon' src='$source'/>")
      assertNull(source, fixture.convert())
    }
  }

  @Test fun rejectsUnresolvedOrUnreferencedFancyPictures() {
    assertNull(fixture(body = "<Image src='missing.png'/>").convert())
    assertNull(fixture(images = listOf("icon.png", "unused.png"),
      body = "<Image src='icon.png'/>").convert())
    assertNull(fixture(body = "<Group/>").convert())
  }

  @Test fun rejectsAuthorDarkProgramsAndProceduralDrawingEvenWithAStaleGroup() {
    for (fixture in listOf(
      fixture(updater = "useVariableUpdater='DarkMode'"),
      fixture(body = "<Image src='icon.png' alpha='#__darkmode'/>"),
      fixture(body = "<Image src='icon.png'/><Rectangle w='3' h='4'/>"),
      fixture(body = "<Lottie src='motion.json'/>"),
    )) assertNull(fixture.convert())
  }

  @Test fun rejectsCommandsThatReplaceAnImageSource() {
    assertNull(fixture(body = """
      <Image name="icon" src="icon.png"/>
      <ExternalCommands><Trigger action="resume">
        <VariableCommand name="icon.src" expression="0"/>
      </Trigger></ExternalCommands>
    """.trimIndent()).convert())
    assertNull(fixture(body = "<FunctionCommand target='icon.src'/><Image src='icon.png'/>").convert())
  }

  @Test fun rejectsExistingReservedNamespaceAndBackgroundFancyCollisions() {
    val fixture = fixture()
    for (path in listOf("art/fancy/keihome_night/icon.png", "plate/fancy/day.png",
      "plate/fancy/night.png", "plate/fancy/manifest.xml")) {
      val originals = fixture.originals + (path to png(43, 29, 30))
      val files = fixture.group.files + (path to blob(path, originals.getValue(path)))
      assertNull(path, PerfectDynamicNightSwitch.systemFiles(fixture.group.copy(files = files),
        originals, fixture.night))
    }
  }

  @Test fun requiresExactCompleteOriginalAndRenderedRoleMaps() {
    val fixture = fixture()
    assertNull(PerfectDynamicNightSwitch.systemFiles(fixture.group,
      fixture.originals - "art/quiet/quietImage.png", fixture.night))
    assertNull(PerfectDynamicNightSwitch.systemFiles(fixture.group, fixture.originals,
      fixture.night - fixture.group.background))
    assertNull(PerfectDynamicNightSwitch.systemFiles(fixture.group, fixture.originals,
      fixture.night - "art/quiet/quietImage.png"))
    assertNull(PerfectDynamicNightSwitch.systemFiles(fixture.group, fixture.originals,
      fixture.night + ("unknown.png" to png(43, 29, 30))))
    assertNull(PerfectDynamicNightSwitch.systemFiles(fixture.group.copy(background = "wrong.png"),
      fixture.originals, fixture.night))
    val calendar = calendar()
    for (path in listOf("plate/fancy/bg.png", "quiet/quietImage.png")) {
      assertNull(path, PerfectDynamicNightSwitch.systemFiles(
        calendar.group, calendar.originals, calendar.night - path))
    }
  }

  @Test fun rejectsMalformedOrCrcInvalidDerivedPngsAtomically() {
    val fixture = fixture()
    val corrupt = fixture.night.getValue(fixture.group.background).copyOf().also {
      it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
    }
    for (bytes in listOf(byteArrayOf(1, 2, 3), corrupt)) {
      assertNull(PerfectDynamicNightSwitch.systemFiles(fixture.group, fixture.originals,
        fixture.night + (fixture.group.background to bytes)))
    }
    assertEquals(5, fixture.originals.size)
    assertTrue(fixture.originals.keys.none { "keihome_night" in it || "/plate/fancy/" in it })
  }

  @Test fun rejectsDtdExternalEntitiesMalformedXmlAndOversizedXml() {
    val fixture = fixture()
    for (xml in listOf(
      "<!DOCTYPE Icon [<!ENTITY xxe SYSTEM 'file:///unreadable-perfect-night-test'>]>" +
        "<Icon><Image src='icon.png' name='&xxe;'/></Icon>",
      "<!DOCTYPE Icon SYSTEM 'https://invalid.example/perfect-night.dtd'><Icon/>",
      "<Icon><Image></Icon>",
      " ".repeat(PerfectIconFormat.MAX_XML + 1),
    )) {
      assertNull(PerfectDynamicNightSwitch.systemFiles(fixture.group,
        fixture.originals + ("art/fancy/manifest.xml" to xml.toByteArray()), fixture.night))
    }
  }

  @Test fun isolatedCutoutRetainsItsOriginalTextureAndAnimationOwner() {
    val body = """
      <ExternalCommands><Trigger action="back_home_finish">
        <FolmeCommand target="cutout" states="'settled'" command="setTo"/>
      </Trigger></ExternalCommands>
      <FolmeState name="settled" x="140" y="136"/>
      <Group>
        <Group layered="true" w="324" h="324">
          <Image name="first" src="first.png" x="81" y="95" folmeMode="true"/>
          <Image name="cutout" src="cutout.png" x="140" y="136" folmeMode="true" xfermode="dst_out"/>
        </Group>
        <Image name="second" src="second.png" x="144" y="139" folmeMode="true"/>
      </Group>
    """.trimIndent()
    val fixture = fixtureFrom(fixture(
      width = 324, height = 324, images = listOf("first.png", "cutout.png", "second.png"), body = body,
    ).originals)
    val mask = "art/fancy/cutout.png"
    assertEquals(setOf(mask), fixture.group.alphaMasks)
    assertFalse(mask in fixture.group.nightPaths)
    val output = converted(fixture)
    assertArrayEquals(fixture.originals.getValue(mask), output.getValue(mask))
    assertFalse("art/fancy/keihome_night/cutout.png" in output)
    for (source in listOf("first.png", "second.png")) {
      assertArrayEquals(fixture.night.getValue("art/fancy/$source"),
        output.getValue("art/fancy/keihome_night/$source"))
    }
    val original = parse(fixture.originals.getValue("art/fancy/manifest.xml"))
    val rewritten = parse(output.getValue("art/fancy/manifest.xml"))
    rewritten.documentElement.setAttribute("useVariableUpdater", "DateTime.Second")
    val images = rewritten.getElementsByTagName("Image")
    for (index in 0 until images.length) {
      val image = images.item(index) as Element
      val source = image.getAttribute("src")
      if (source != "cutout.png") {
        assertEquals("ifelse(#__darkmode,'keihome_night/$source','$source')", image.getAttribute("srcExp"))
        image.removeAttribute("srcExp")
      }
    }
    assertTrue(withoutFormatting(original.documentElement).isEqualNode(withoutFormatting(rewritten.documentElement)))
  }

  @Test fun leavesAllInputBytesAndMapsUnchangedOnSuccessfulConversion() {
    val fixture = fixture()
    val originals = fixture.originals.mapValues { it.value.copyOf() }
    val night = fixture.night.mapValues { it.value.copyOf() }
    converted(fixture)
    assertEquals(originals.keys, fixture.originals.keys)
    assertEquals(night.keys, fixture.night.keys)
    originals.forEach { (path, bytes) -> assertArrayEquals(bytes, fixture.originals.getValue(path)) }
    night.forEach { (path, bytes) -> assertArrayEquals(bytes, fixture.night.getValue(path)) }
  }

  private data class Fixture(
    val group: PerfectDynamicNight.Group,
    val originals: Map<String, ByteArray>,
    val night: Map<String, ByteArray>,
  ) {
    fun convert(): Map<String, ByteArray>? = PerfectDynamicNightSwitch.systemFiles(group, originals, night)
  }

  private fun fixture(
    width: Int = 43,
    height: Int = 29,
    images: List<String> = listOf("icon.png"),
    imageAttributes: String = "",
    updater: String = "useVariableUpdater='DateTime.Second'",
    extraConfig: String = "",
    body: String = images.mapIndexed { index, image -> """
      <Group name="group$index" x="7" alpha="254">
        <!-- preserve image events and animation state -->
        <Image name="${if (index == 0) "icon" else "icon$index"}" src="$image"
          pivotX="21" pivotY="14" rotation="#time_sys%360" $imageAttributes>
          <PositionAnimation><Item time="0" x="0" y="0"/><Item time="600" x="2" y="3"/></PositionAnimation>
        </Image>
      </Group>
    """.trimIndent() }.joinToString("") + """
      <ExternalCommands><Trigger action="resume" condition="#time_sys&gt;0">
        <AnimationCommand target="icon" command="play"/>
      </Trigger></ExternalCommands>
    """.trimIndent(),
  ): Fixture {
    val config = """
      <Config><Variables>
        <Variable name="layer1" value="art" type="string"/>
        <Variable name="layer_only_quiet0" value="true" type="string"/>
        <Variable name="layer0" value="plate" type="string"/>
        <Variable name="fancy_work" value="false" type="string"/>
        <Variable name="use_quiet_when_anim_layer1" value="true" type="string"/>
        $extraConfig
      </Variables></Config>
    """.trimIndent().toByteArray()
    val originals = linkedMapOf(
      "config.xml" to config,
      "plate/quiet/quietImage.png" to png(width, height, 220),
      "art/quiet/quietImage.png" to png(width, height, 190),
      "art/fancy/manifest.xml" to
        "<Icon version='1' width='$width' height='$height' frameRate='30' $updater>$body</Icon>".toByteArray(),
    )
    for (image in images) originals["art/fancy/$image"] = png(width, height, 180)
    val foreground = originals.keys.filter { it.startsWith("art/") && it.endsWith(".png") }.toSet()
    val group = PerfectDynamicNight.Group(originals.mapValues { (path, bytes) -> blob(path, bytes) },
      "plate/quiet/quietImage.png", foreground)
    return Fixture(group, originals,
      (foreground + group.background).associateWith { png(width, height, 40) })
  }

  private fun converted(fixture: Fixture): Map<String, ByteArray> {
    val result = fixture.convert()
    assertNotNull("Expected supported literal bitmap program to convert", result)
    return requireNotNull(result)
  }

  private fun calendar(): Fixture {
    val bytes = fixture().originals.toMutableMap()
    bytes["config.xml"] = """
      <Config><Variables>
        <Variable name="layer0" value="plate"/><Variable name="layer_only_quiet0" value="true"/>
        <Variable name="layer1" value="art"/><Variable name="layer_only_fancy1" value="true"/>
        <Variable name="layer2" value="weekday"/><Variable name="layer_only_fancy2" value="true"/>
      </Variables></Config>
    """.trimIndent().toByteArray()
    bytes.remove("art/fancy/icon.png")
    bytes.remove("art/quiet/quietImage.png")
    bytes["quiet/quietImage.png"] = png(8, 6, 70)
    bytes["plate/fancy/bg.png"] = png(8, 6, 10)
    bytes["plate/fancy/manifest.xml"] = """
      <Icon width="351" height="351" frameRate="0" useVariableUpdater="none">
        <Image src="bg.png" x="351/2" y="351/2" align="center" alignV="center"/>
      </Icon>
    """.trimIndent().toByteArray()
    bytes["art/fancy/manifest.xml"] = """
      <Icon width="351" height="351" useVariableUpdater="DateTime.Day">
        <ExternalCommands>
          <Trigger action="resume"><FrameRateCommand rate="30"/><FrameRateCommand rate="0" delay="100"/></Trigger>
          <Trigger action="back_home"><FrameRateCommand rate="30"/><FrameRateCommand rate="0" delay="100"/></Trigger>
        </ExternalCommands>
        <Image src="num.png" srcid="#date" y="351/2+18" align="center"/>
      </Icon>
    """.trimIndent().toByteArray()
    for (day in 1..31) bytes["art/fancy/num_$day.png"] = png(8, 6, day)
    bytes["weekday/fancy/manifest.xml"] = """
      <Icon useVariableUpdater="DateTime.Day">
        <ExternalCommands><Trigger action="resume"><FrameRateCommand rate="0"/></Trigger></ExternalCommands>
        <Text color="#3482FF" textExp="formatDate('E',#time_sys)"/>
      </Icon>
    """.trimIndent().toByteArray()
    return fixtureFrom(bytes)
  }

  private fun weather(): Fixture {
    val bytes = fixture().originals.toMutableMap()
    bytes.remove("art/fancy/icon.png")
    bytes["quiet/quietImage.png"] = png(8, 6, 70)
    bytes["art/fancy/manifest.xml"] = """
      <Icon version="1" width="351" height="351" frameRate="0" useVariableUpdater="DateTime.Minute" screenWidth="1080">
        <VariableBinders>
          <ContentProviderBinder name="weather_provider" uri="content://weather/actualWeatherData/4" columns="temperature" countName="hasweather">
            <Variable name="temperature" type="int" column="temperature"/>
            <Trigger><VariableCommand name="success_time" type="number" expression="#time_sys"/></Trigger>
          </ContentProviderBinder>
        </VariableBinders>
        <ExternalCommands><Trigger action="init,resume">
          <VariableCommand name="update_weather_time" type="number" expression="#time_sys"/>
          <MultiCommand condition="(#time_sys - #success_time) } 600000">
            <BinderCommand name="weather_provider" command="refresh" delay="550" delayCondition="(#time_sys - #update_weather_time) } 500"/>
          </MultiCommand>
        </Trigger></ExternalCommands>
        <Image x="351 / 2" y="351 / 2" align="center" alignV="center" src="bg.png"/>
        <Group visibility="#hasweather"><ImageChars x="351 / 2 - 84" y="351 / 2 - 102" src="num.png" string="#temperature + '&#176;'"/></Group>
      </Icon>
    """.trimIndent().toByteArray()
    bytes["art/fancy/bg.png"] = png(8, 6, 90)
    for (suffix in (0..9).map(Int::toString) + listOf("-", "\u00b0"))
      bytes["art/fancy/num_$suffix.png"] = png(8, 6, 180)
    return fixtureFrom(bytes)
  }

  private fun fixtureFrom(originals: Map<String, ByteArray>): Fixture {
    val blobs = originals.mapValues { (name, bytes) -> blob(name, bytes) }
    val group = requireNotNull(PerfectDynamicNight.group(blobs, { originals.getValue(it.path) }))
    return Fixture(group, originals, group.nightPaths.associateWith { png(8, 6, 40) })
  }

  private fun withoutFormatting(original: Node): Node {
    val copy = original.cloneNode(true)
    fun strip(node: Node) {
      val children = (0 until node.childNodes.length).map(node.childNodes::item)
      for (child in children) {
        if (child.nodeType == Node.TEXT_NODE && child.nodeValue.isNullOrBlank()) node.removeChild(child)
        else strip(child)
      }
    }
    strip(copy)
    return copy
  }

  private fun blob(path: String, bytes: ByteArray) = PerfectIconBlob(path, path, bytes.size.toLong())

  private fun parse(bytes: ByteArray): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(bytes))

  private fun variables(document: Document): Map<String, String> {
    val entries = document.getElementsByTagName("Variable")
    return (0 until entries.length).associate {
      val entry = entries.item(it) as Element
      entry.getAttribute("name") to entry.getAttribute("value")
    }
  }

  private fun signature(node: Node): String {
    val attributes = node.attributes?.let { attrs ->
      (0 until attrs.length).map { attrs.item(it).nodeName to attrs.item(it).nodeValue }
        .sortedBy { it.first }
    }
    val children = (0 until node.childNodes.length).joinToString("") { signature(node.childNodes.item(it)) }
    return "${node.nodeType}:${node.nodeName}:${node.nodeValue}:$attributes[$children]"
  }

  private fun png(width: Int, height: Int, tone: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val stream = DataOutputStream(output)
    stream.writeLong(0x89504e470d0a1a0aUL.toLong())
    fun chunk(type: String, bytes: ByteArray) {
      val name = type.toByteArray(Charsets.US_ASCII)
      stream.writeInt(bytes.size)
      stream.write(name)
      stream.write(bytes)
      stream.writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
    }
    val header = ByteArrayOutputStream().also {
      DataOutputStream(it).apply {
        writeInt(width); writeInt(height); write(byteArrayOf(8, 6, 0, 0, 0))
      }
    }.toByteArray()
    chunk("IHDR", header)
    val pixels = ByteArrayOutputStream().also { compressed ->
      DeflaterOutputStream(compressed).use { deflater ->
        repeat(height) {
          deflater.write(0)
          repeat(width) { deflater.write(byteArrayOf(tone.toByte(), tone.toByte(), tone.toByte(), -1)) }
        }
      }
    }.toByteArray()
    chunk("IDAT", pixels)
    chunk("IEND", byteArrayOf())
    return output.toByteArray()
  }
}
