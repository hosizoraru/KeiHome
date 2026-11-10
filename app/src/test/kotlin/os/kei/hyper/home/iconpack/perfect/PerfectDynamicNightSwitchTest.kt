package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
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
      fixture(body = "<Image src='icon.png'/><Text text='42'/>"),
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
