package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class NativeLayerConfigTest {
  private fun config(body: String) = NativeLayerConfig.parse(
    "<Config><Variables>$body</Variables></Config>".toByteArray(), IconPackLimits())

  @Test fun legacySelectorsArePreservedWithoutInventingExclusiveLayerSemantics() {
    val c = config("""
      <Variable name="layer0" value="background"/>
      <Variable name="layer1" value="clock"/>
      <Variable name="use_fancy_when_static_layer1" value="true"/>
      <Variable name="use_quiet_when_anim_layer1" value="false"/>
      <Variable name="layer_only_quiet0" value="true"/>
    """.trimIndent())
    assertEquals(listOf("background", "clock"), c.layers)
    assertEquals(setOf("background"), c.quietOnly)
    assertTrue(c.fancyOnly.isEmpty())
  }

  @Test fun recoversAnOmittedExclusiveSlotOnlyFromItsRealResourceDirectory() {
    val c = NativeLayerConfig.parse(
      """
        <Config><Variables>
          <Variable name="layer0" value="background"/>
          <Variable name="layer1" value="foreground"/>
          <Variable name="layer_only_fancy2" value="true"/>
        </Variables></Config>
      """.trimIndent().toByteArray(),
      IconPackLimits(),
      availableLayers = setOf("0", "1", "2"),
    )
    assertEquals(listOf("background", "foreground", "2"), c.layers)
    assertEquals(setOf("2"), c.fancyOnly)
  }

  @Test fun anOmittedExclusiveSlotWithoutAResourceDirectoryIsRejected() {
    assertThrows(IconPackException::class.java) {
      NativeLayerConfig.parse(
        """
          <Config><Variables>
            <Variable name="layer0" value="background"/>
            <Variable name="layer_only_fancy2" value="true"/>
          </Variables></Config>
        """.trimIndent().toByteArray(),
        IconPackLimits(),
        availableLayers = setOf("0"),
      )
    }
  }

  @Test fun missingLayerAndNonBooleanLegacySelectorsAreRejected() {
    for (extra in listOf(
      "<Variable name='use_quiet_when_anim_layer1' value='true'/>",
      "<Variable name='use_fancy_when_static_layer0' value='1'/>",
    )) {
      assertThrows(IconPackException::class.java) {
        config("<Variable name='layer0' value='0'/>$extra")
      }
    }
  }
}
