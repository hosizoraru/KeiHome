package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class HyperOs4IconModuleTest {
  private fun configs(bytes: ByteArray): Map<String, String> {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(ByteArrayInputStream(bytes))
    assertEquals("IconTransform", document.documentElement.tagName)
    val nodes = document.getElementsByTagName("Config")
    return (0 until nodes.length).associate { index ->
      val attrs = nodes.item(index).attributes
      attrs.getNamedItem("name").nodeValue to attrs.getNamedItem("value").nodeValue
    }
  }

  @Test fun unavailableStockShapeLeavesNativeDefaultAndNoDevicePathInOutput() {
    assertEquals(mapOf("SupportLayerIcon" to "true", "UseDynamicIcon" to "false"),
      configs(HyperOs4IconModule.transform(false, null)))
  }

  @Test fun providedShapeRoundTripsWithoutCreatingAdditionalXmlFields() {
    val mask = "M 0 0 L 100 0 L 100 100 Z"
    val values = configs(HyperOs4IconModule.transform(true, mask))
    assertEquals(mask, values["ConfigIconMask"])
    assertEquals("true", values["UseDynamicIcon"])
    val quoted = """M 0 0 " /><Config name="injected" value="true" /> &"""
    assertEquals(quoted, configs(HyperOs4IconModule.transform(false, quoted))["ConfigIconMask"])
    assertEquals(3, configs(HyperOs4IconModule.transform(false, quoted)).size)
  }

  @Test fun stockShapeChangesInvalidateDestinationCacheIdentity() {
    val mask = "M 0 0 L 100 0 L 100 100 Z"
    assertNotEquals(HyperOs4IconModule.cacheIdentity(null), HyperOs4IconModule.cacheIdentity(mask))
    assertNotEquals(HyperOs4IconModule.cacheIdentity(mask), HyperOs4IconModule.cacheIdentity("$mask "))
    assertEquals(HyperOs4IconModule.cacheIdentity(mask), HyperOs4IconModule.cacheIdentity(mask))
  }

  @Test fun oversizedAndControlCharacterMasksAreRejected() {
    for (mask in listOf("", "x".repeat(4097), "M0\u00000")) {
      assertThrows(IllegalArgumentException::class.java) { HyperOs4IconModule.transform(false, mask) }
    }
  }
}
