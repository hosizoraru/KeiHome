package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class NativeIconShapeConfigTest {
  @Test fun knownDynamicFalseTypoIsNormalizedWithoutEnablingTheAuthorsLegacyStyle() {
    val source = """<IconTransform><Config name="SupportLayerIcon" value="false"/>
      <Config name="UseDynamicIcon" value="fales"/><Config name="ConfigIconMask" value="M0 0H100V100H0Z"/></IconTransform>""".toByteArray()
    val changed = requireNotNull(NativeIconShapeConfig.complete(source, "M0 0H100V100H0Z"))
    assertFalse(changed.toString(Charsets.UTF_8).contains("fales"))
    assertTrue(changed.toString(Charsets.UTF_8).contains("name=\"SupportLayerIcon\" value=\"false\""))
    assertTrue(changed.toString(Charsets.UTF_8).contains("value=\"false\""))
    assertArrayEquals(changed, NativeIconShapeConfig.complete(changed, "M0 0H100V100H0Z"))
  }

  private val systemMask = "M0 40C0 10 10 0 40 0H60C90 0 100 10 100 40V60C100 90 90 100 60 100H40C10 100 0 90 0 60Z"

  @Test fun explicitCustomMaskReplacesOnlyTheSimpleMaskAndPreservesAuthoredFlags() {
    val source = """<IconTransform><Config name="SupportLayerIcon" value="false"/>
      <Config name="UseDynamicIcon" value="false"/><Config name="ConfigIconMask" value="M0 0H100V100H0Z"/></IconTransform>""".toByteArray()
    val result = requireNotNull(NativeIconShapeConfig.complete(source, systemMask, systemMask))
    assertTrue(result.toString(Charsets.UTF_8).contains("""value="false""""))
    assertTrue(result.toString(Charsets.UTF_8).contains(systemMask))
    assertNull(NativeIconShapeConfig.fullMask(result))
  }

  @Test fun defaultPreservesPackMaskWhileCustomChangesOnlySupportedSimpleContracts() {
    val original = NativeIconShapeConfig.generated("M0 0H100V100H0Z")
    assertArrayEquals(original, NativeIconShapeConfig.complete(original, systemMask))
    assertEquals("M0 0H100V100H0Z", NativeIconShapeConfig.fullMask(original))
    val changed = NativeIconShapeConfig.complete(original, systemMask, systemMask)
    assertEquals(systemMask, NativeIconShapeConfig.fullMask(changed))
    val legacy = """<IconTransform><Filter name="author"/><Config name="ConfigIconMask" value="M0 0H100V100H0Z"/></IconTransform>""".toByteArray()
    assertArrayEquals(legacy, NativeIconShapeConfig.complete(legacy, systemMask, systemMask))
  }

  @Test fun ambiguousOrNestedFullMaskIsNotPreviewedAsValidShape() {
    listOf(
      """<IconTransform><Config name="SupportLayerIcon" value="true"/><Config name="ConfigIconMask" value="M0 0Z"/><Config name="ConfigIconMask" value="M0 0Z"/></IconTransform>""",
      """<IconTransform><Config name="SupportLayerIcon" value="true"/><Group><Config name="ConfigIconMask" value="M0 0Z"/></Group></IconTransform>""",
      """<wrong><Config name="SupportLayerIcon" value="true"/><Config name="ConfigIconMask" value="M0 0Z"/></wrong>"""
    ).forEach { assertNull(NativeIconShapeConfig.fullMask(it.toByteArray())) }
  }

  @Test fun legacyGlobalStylesAreRecognizedWithoutMistakingPerAppMamlAssetsForGlobalMasks() {
    for (name in listOf("icon_mask.png", "nightmode/icon_pattern.png", "icon_background.png",
      "res/drawable-xxhdpi/icon_border.9.png", "nightmode/res/drawable-xhdpi/icon_mask.png")) {
      assertTrue(name, NativeIconShapeConfig.hasLegacyStyle(sequenceOf(name)))
    }
    for (name in listOf("fancy_icons/os.test.app/icon_mask.png", "nightmode/os.test.app/icon_border.png",
      "os.test.app.png", "transform_config.xml")) {
      assertFalse(name, NativeIconShapeConfig.hasLegacyStyle(sequenceOf(name)))
    }
  }

  @Test fun absentConfigurationGetsTheBoundedLayerAndShapeContract() {
    val result = requireNotNull(NativeIconShapeConfig.complete(null, systemMask))
    NativeIconShapeConfig.validateGenerated(result)
    assertTrue(result.decodeToString().contains(systemMask))
  }

  @Test fun completeAuthoredConfigurationRemainsByteIdenticalIncludingExplicitFalse() {
    val original = """<IconTransform>
      <Config name="SupportLayerIcon" value="false"/>
      <Config name="ConfigIconMask" value="M0 0H100V100H0Z"/>
      <Config name="UseDynamicIcon" value="false"/>
    </IconTransform>""".toByteArray()
    assertArrayEquals(original, NativeIconShapeConfig.complete(original, systemMask))
  }

  @Test fun partialConfigurationKeepsTheAuthorsMaskAndOtherSemantics() {
    val original = """<IconTransform><!-- keep this -->
      <Config name="ConfigIconMask" value="M50 0A50 50 0 1 0 50 100Z"/>
      <Config name="UseDynamicIcon" value="false"/>
    </IconTransform>""".toByteArray()
    val result = requireNotNull(NativeIconShapeConfig.complete(original, systemMask)).decodeToString()
    assertTrue(result.contains("M50 0A50 50 0 1 0 50 100Z"))
    assertTrue(result.contains("<!-- keep this -->"))
    assertTrue(result.contains("name=\"UseDynamicIcon\" value=\"false\""))
    assertTrue(result.contains("name=\"SupportLayerIcon\" value=\"true\""))
    assertFalse(result.contains(systemMask))
  }

  @Test fun authoredLegacyFiltersMatricesAndUnknownContractsAreNeverReroutedToFullMaskMode() {
    for (program in listOf(
      """<IconTransform><IconFilters><Filter name="Tint"/></IconFilters></IconTransform>""",
      """<IconTransform><Point x="0" y="0"/></IconTransform>""",
      """<IconTransform><Config name="ScaleX" value="0.8"/></IconTransform>""",
      """<IconTransform futureMode="true"/>""")) {
      val bytes = program.toByteArray()
      assertArrayEquals(program, bytes, NativeIconShapeConfig.complete(bytes, systemMask))
    }
  }

  @Test fun missingMaskDoesNotEnableAnExplicitlyDisabledLayerMode() {
    val result = requireNotNull(NativeIconShapeConfig.complete(
      """<IconTransform><Config name="SupportLayerIcon" value="false"/></IconTransform>""".toByteArray(),
      systemMask)).decodeToString()
    assertTrue(result.contains("name=\"SupportLayerIcon\" value=\"false\""))
    assertTrue(result.contains(systemMask))
  }

  @Test fun ambiguousUnsupportedMalformedAndOversizedProgramsStayUntouched() {
    listOf("<Other/>", "<IconTransform>", "<IconTransform>" + " ".repeat(20_000) + "</IconTransform>",
      """<IconTransform><Config name="ConfigIconMask" value="a"/><Config name="ConfigIconMask" value="b"/></IconTransform>""",
      """<!DOCTYPE IconTransform [<!ENTITY x "abc">]><IconTransform>&x;</IconTransform>""",
      """<IconTransform xmlns="urn:custom"/>""").forEach { text ->
      val original = text.toByteArray()
      assertArrayEquals(text, original, NativeIconShapeConfig.complete(original, systemMask))
    }
  }

  @Test fun convertedValidatorRejectsExtraFieldsInstructionsAndNoncanonicalPrograms() {
    val good = NativeIconShapeConfig.generated(systemMask)
    listOf(good.decodeToString().replace("value=\"true\"", "value=\"false\""),
      good.decodeToString().replace("</IconTransform>", """<Config name="UseDynamicIcon" value="false"/></IconTransform>"""),
      good.decodeToString().replace("<IconTransform>", "<IconTransform extra=\"1\">"),
      good.decodeToString() + "<!-- trailing -->").forEach {
      assertTrue(runCatching { NativeIconShapeConfig.validateGenerated(it.toByteArray()) }.isFailure)
    }
  }
}
