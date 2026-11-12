package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class NativeIconMaskImportTest {
  private val rectangle = "M0 0H100V100H0Z"
  private fun xml(path: String = rectangle, extra: String = "") =
    """<IconTransform>$extra<Config name="ConfigIconMask" value="$path"/></IconTransform>"""
  private fun parse(text: String) = NativeIconMaskImport.pathData(text.toByteArray())
  private fun reject(text: String) {
    assertThrows(text.take(100), Exception::class.java) { parse(text) }
  }

  @Test fun extractsOnlyMaskAndNeverTreatsAuthoredFieldsAsInstructions() {
    val authored = """<Author name="Artist">Example</Author>
      <Config name="SupportLayerIcon" value="false"/>
      <Config name="ScaleX" value="0.8"/>
      <IconFilters><Filter name="Tint" value="red"/></IconFilters>"""
    assertEquals("M 0 0 H 100 V 100 H 0 Z", parse(xml(extra = authored)))
    assertEquals(parse(xml()), parse(xml().replace("<IconTransform>", """<IconTransform author="Artist">""")))
  }

  @Test fun canonicalizesWhitespaceCommasNumbersAndExponentWithoutChangingCommandCase() {
    assertEquals(parse(xml("M0,0 L100,0 L100,100 Z")),
      parse(xml(" M+0.0 00e0   L1e2 0.000 L100.00 1E2 Z ")))
    assertEquals("m 0 0 h 100 v 100 h -100 z", parse(xml("m0 0h100v100h-100z")))
  }

  @Test fun acceptsCurvesArcsAndImplicitCoordinateGroups() {
    for (path in listOf("M0 0 10 10L20 20 30 30Z",
      "M0 50Q0 0 50 0T100 50Z",
      "M0 50C0 0 50 0 50 50S100 100 100 50Z",
      "M50 0A50 50 0 1 0 50 100a50 50 0 1 0 0-100Z")) {
      assertTrue(parse(xml(path)).startsWith("M "))
    }
  }

  @Test fun requiresExactlyOneDirectMaskAndTheExactRoot() {
    listOf("<IconTransform/>", "<Other/>", xml().replace("IconTransform", "Other"),
      xml(extra = """<Config name="ConfigIconMask" value="$rectangle"/>"""),
      """<IconTransform><Group><Config name="ConfigIconMask" value="$rectangle"/></Group></IconTransform>""",
      """<IconTransform><Other name="ConfigIconMask" value="$rectangle"/></IconTransform>""",
      """<IconTransform><Config name="ConfigIconMask"/></IconTransform>""",
      xml(""), xml("   "), xml().replace("/>", "><Child/></Config>"),
      xml().replace("/>", ">injected text</Config>"),
      xml() + xml()).forEach(::reject)
  }

  @Test fun rejectsNamespacesDtdExternalAndUndeclaredEntities() {
    listOf(xml().replace("<IconTransform>", """<IconTransform xmlns="urn:unknown">"""),
      """<!DOCTYPE IconTransform [<!ENTITY mask "$rectangle">]>""" + xml("&mask;"),
      """<!DOCTYPE IconTransform SYSTEM "file:///should-not-be-opened">""" + xml(),
      """<!DOCTYPE IconTransform [<!ENTITY mask SYSTEM "https://invalid.test/mask">]>""" + xml("&mask;"),
      xml("&unknown;")).forEach(::reject)
  }

  @Test fun rejectsUnsupportedOrIncompletePathSyntaxBeforeAndroidGeometryValidation() {
    listOf("L0 0L100 100Z", "M0", "M0 0L", "M0 0Q1 2 3", "M0 0Z2 2",
      "M0 0R100 100", "M0 0HNaN", "M0 0HInfinity", "M0 0H1e99999999",
      "M0 0H1e-99999999", "M,0,0", "M0,,0", "M0 0,", "M0 0A-1 5 0 0 1 10 10",
      "M0 0A5 5 0 2 1 10 10", "M0 0A5 5 0 0 .5 10 10").forEach { reject(xml(it)) }
  }

  @Test fun boundsWholeDocumentAndPathAndAcceptsTheExactXmlByteLimit() {
    val valid = xml()
    val atLimit = valid.replace("</IconTransform>",
      " ".repeat(NativeIconMaskImport.MAX_XML_BYTES - valid.toByteArray().size) + "</IconTransform>")
    assertEquals(NativeIconMaskImport.MAX_XML_BYTES, atLimit.toByteArray().size)
    assertEquals(parse(valid), parse(atLimit))
    reject(atLimit + " ")
    reject(xml("M0 0" + " H0".repeat(1400)))
    assertThrows(Exception::class.java) { NativeIconMaskImport.pathData(byteArrayOf()) }
    assertThrows(Exception::class.java) { NativeIconMaskImport.pathData(byteArrayOf(0xc3.toByte(), 0x28)) }
  }

  @Test fun handlesUtf16BomAndRejectsEncodedDtdAndEmbeddedNul() {
    val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + xml().toByteArray(Charsets.UTF_16LE)
    assertEquals(parse(xml()), NativeIconMaskImport.pathData(bytes))
    val dtd = """<!DOCTYPE IconTransform [<!ENTITY x "bad">]>""" + xml()
    assertThrows(Exception::class.java) {
      NativeIconMaskImport.pathData(byteArrayOf(0xfe.toByte(), 0xff.toByte()) + dtd.toByteArray(Charsets.UTF_16BE))
    }
    reject(xml().replace("IconTransform>", "IconTransform>\u0000"))
  }
}
