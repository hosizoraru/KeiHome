package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.RecommendedIconMappings

class RecommendedIconMappingsTest {
  @Test fun defaultsEnableAllThreeMappingsAndCopiesChangeEachSwitchIndependently() {
    val defaults = RecommendedIconMappings()
    assertTrue(defaults.telegramChannels)
    assertTrue(defaults.colorOsThemeStores)
    assertTrue(defaults.phoneCompanions)
    assertEquals(7, defaults.bits)
    val withoutTelegram = defaults.copy(telegramChannels = false)
    val withoutThemeStores = defaults.copy(colorOsThemeStores = false)
    val withoutPhone = defaults.copy(phoneCompanions = false)
    assertEquals(RecommendedIconMappings(false, true, true), withoutTelegram)
    assertEquals(6, withoutTelegram.bits)
    assertEquals(RecommendedIconMappings(true, false, true), withoutThemeStores)
    assertEquals(5, withoutThemeStores.bits)
    assertEquals(RecommendedIconMappings.LEGACY, withoutPhone)
    assertEquals(3, withoutPhone.bits)
    assertEquals(RecommendedIconMappings(false, false, true),
      withoutTelegram.copy(colorOsThemeStores = false))
    assertEquals(RecommendedIconMappings.NONE,
      withoutPhone.copy(telegramChannels = false, colorOsThemeStores = false))
    assertEquals(defaults, withoutTelegram.copy(telegramChannels = true))
    assertEquals(defaults, withoutThemeStores.copy(colorOsThemeStores = true))
    assertEquals(defaults, withoutPhone.copy(phoneCompanions = true))
    assertEquals(7, defaults.bits)
  }

  @Test fun everyBitCombinationRoundTripsWithoutCouplingTheSwitches() {
    val expected = listOf(
      RecommendedIconMappings(false, false, false),
      RecommendedIconMappings(true, false, false),
      RecommendedIconMappings(false, true, false),
      RecommendedIconMappings(true, true, false),
      RecommendedIconMappings(false, false, true),
      RecommendedIconMappings(true, false, true),
      RecommendedIconMappings(false, true, true),
      RecommendedIconMappings(true, true, true),
    )
    expected.forEachIndexed { bits, mappings ->
      assertEquals(bits, mappings.bits)
      assertEquals(mappings, RecommendedIconMappings.fromBits(bits))
      assertEquals(bits, RecommendedIconMappings.fromBits(bits).bits)
    }
    assertEquals(expected[0], RecommendedIconMappings.NONE)
    assertEquals(expected[3], RecommendedIconMappings.LEGACY)
    assertEquals(expected[7], RecommendedIconMappings())
  }

  @Test fun phoneSwitchCanBeChangedWithoutChangingEitherLegacyChoice() {
    for (bits in 0..3) {
      val legacySelection = RecommendedIconMappings.fromBits(bits)
      assertFalse(legacySelection.phoneCompanions)
      val withPhone = legacySelection.copy(phoneCompanions = true)
      assertEquals(bits + 4, withPhone.bits)
      assertEquals(legacySelection.telegramChannels, withPhone.telegramChannels)
      assertEquals(legacySelection.colorOsThemeStores, withPhone.colorOsThemeStores)
      assertEquals(legacySelection, withPhone.copy(phoneCompanions = false))
    }
  }

  @Test fun negativeAndUnknownBitsAreRejectedInsteadOfMaskedOrTruncated() {
    for (bits in listOf(Int.MIN_VALUE, -4, -1, 8, 9, 15, 255, Int.MAX_VALUE)) {
      assertThrows("bits=$bits", IllegalArgumentException::class.java) {
        RecommendedIconMappings.fromBits(bits)
      }
    }
  }
}
