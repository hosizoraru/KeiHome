package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.RecommendedIconMappings

class RecommendedIconMappingsTest {
  @Test fun defaultsEnableBothMappingsAndCopiesChangeEachSwitchIndependently() {
    val defaults = RecommendedIconMappings()
    assertTrue(defaults.telegramChannels)
    assertTrue(defaults.colorOsThemeStores)
    assertEquals(3, defaults.bits)
    val withoutTelegram = defaults.copy(telegramChannels = false)
    val withoutThemeStores = defaults.copy(colorOsThemeStores = false)
    assertEquals(RecommendedIconMappings(false, true), withoutTelegram)
    assertEquals(2, withoutTelegram.bits)
    assertEquals(RecommendedIconMappings(true, false), withoutThemeStores)
    assertEquals(1, withoutThemeStores.bits)
    assertEquals(RecommendedIconMappings.NONE, withoutTelegram.copy(colorOsThemeStores = false))
    assertEquals(RecommendedIconMappings.NONE, withoutThemeStores.copy(telegramChannels = false))
    assertEquals(defaults, withoutTelegram.copy(telegramChannels = true))
    assertEquals(defaults, withoutThemeStores.copy(colorOsThemeStores = true))
    assertEquals(3, defaults.bits)
  }

  @Test fun everyBitCombinationRoundTripsWithoutCouplingTheSwitches() {
    val expected = listOf(
      RecommendedIconMappings(false, false),
      RecommendedIconMappings(true, false),
      RecommendedIconMappings(false, true),
      RecommendedIconMappings(true, true),
    )
    expected.forEachIndexed { bits, mappings ->
      assertEquals(bits, mappings.bits)
      assertEquals(mappings, RecommendedIconMappings.fromBits(bits))
      assertEquals(bits, RecommendedIconMappings.fromBits(bits).bits)
    }
    assertEquals(expected[0], RecommendedIconMappings.NONE)
  }

  @Test fun negativeAndUnknownBitsAreRejectedInsteadOfMaskedOrTruncated() {
    for (bits in listOf(Int.MIN_VALUE, -4, -1, 4, 5, 7, 8, 255, Int.MAX_VALUE)) {
      assertThrows("bits=$bits", IllegalArgumentException::class.java) {
        RecommendedIconMappings.fromBits(bits)
      }
    }
  }
}
