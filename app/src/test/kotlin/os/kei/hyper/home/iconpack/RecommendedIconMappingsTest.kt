package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.RecommendedIconMappings

class RecommendedIconMappingsTest {
  @Test fun defaultsEnableAllFourMappingsAndCopiesChangeEachSwitchIndependently() {
    val defaults = RecommendedIconMappings()
    assertTrue(defaults.telegramChannels)
    assertTrue(defaults.colorOsThemeStores)
    assertTrue(defaults.phoneCompanions)
    assertTrue(defaults.weiboLite)
    assertEquals(15, defaults.bits)
    val withoutTelegram = defaults.copy(telegramChannels = false)
    val withoutThemeStores = defaults.copy(colorOsThemeStores = false)
    val withoutPhone = defaults.copy(phoneCompanions = false)
    assertEquals(RecommendedIconMappings(false, true, true), withoutTelegram)
    assertEquals(14, withoutTelegram.bits)
    assertEquals(RecommendedIconMappings(true, false, true), withoutThemeStores)
    assertEquals(13, withoutThemeStores.bits)
    assertEquals(RecommendedIconMappings.LEGACY.copy(weiboLite = true), withoutPhone)
    assertEquals(11, withoutPhone.bits)
    assertEquals(RecommendedIconMappings(false, false, true),
      withoutTelegram.copy(colorOsThemeStores = false))
    assertEquals(RecommendedIconMappings.NONE.copy(weiboLite = true),
      withoutPhone.copy(telegramChannels = false, colorOsThemeStores = false))
    assertEquals(defaults, withoutTelegram.copy(telegramChannels = true))
    assertEquals(defaults, withoutThemeStores.copy(colorOsThemeStores = true))
    assertEquals(defaults, withoutPhone.copy(phoneCompanions = true))
    assertEquals(RecommendedIconMappings.BEFORE_WEIBO, defaults.copy(weiboLite = false))
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
    for (bits in 0..15) {
      val mappings = expected[bits and 7].copy(weiboLite = bits and 8 != 0)
      assertEquals(bits, mappings.bits)
      assertEquals(mappings, RecommendedIconMappings.fromBits(bits))
      assertEquals(bits, RecommendedIconMappings.fromBits(bits).bits)
    }
    assertEquals(expected[0].copy(weiboLite = false), RecommendedIconMappings.NONE)
    assertEquals(expected[3].copy(weiboLite = false), RecommendedIconMappings.LEGACY)
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
    for (bits in listOf(Int.MIN_VALUE, -4, -1, 16, 17, 255, Int.MAX_VALUE)) {
      assertThrows("bits=$bits", IllegalArgumentException::class.java) {
        RecommendedIconMappings.fromBits(bits)
      }
    }
  }
}
