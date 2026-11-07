package os.kei.hyper.home.iconpack.apple

import org.junit.Assert.*
import org.junit.Test

class AppleIconPolicyTest {
  private val template = "https://is1-ssl.mzstatic.com/image/thumb/art/Placeholder.mill/{w}x{h}{c}.{f}"

  @Test fun renditionSeparatesPlatformShapeSizeAndAppearance() {
    val ios = AppleIconPolicy.rendition(template, AppleIconOptions(), false)
    assertTrue(ios.endsWith("/512x512Icon.ApSiOS01.png?appearance=light"))
    assertTrue(AppleIconPolicy.rendition(template, AppleIconOptions(ApplePlatform.MACOS, 1024), true)
      .endsWith("/1024x1024Icon.ApSmac01.png?appearance=dark"))
    assertTrue(AppleIconPolicy.rendition(template,
      AppleIconOptions(ApplePlatform.IPADOS, 256, AppleCorners.ORIGINAL), true)
      .endsWith("/256x256bb.png?appearance=dark"))
  }

  @Test fun appearanceIsReplacedRatherThanDuplicated() {
    val url = AppleIconPolicy.rendition("$template?appearance=light&foo=bar", AppleIconOptions(), true)
    assertTrue(url.endsWith("?foo=bar&appearance=dark"))
    assertEquals(1, Regex("appearance=").findAll(url).count())
  }

  @Test fun untrustedAndCleartextSourcesAreRejected() {
    listOf("http://is1-ssl.mzstatic.com/a", "https://is1-ssl.mzstatic.com.evil.test/a",
      "https://is1-ssl.mzstatic.com@evil.test/a", "https://user@is1-ssl.mzstatic.com/a",
      "https://is1-ssl.mzstatic.com:444/a", "file:///tmp/icon.png",
      "https://is1-ssl.mzstatic.com/" + "a".repeat(4096)).forEach { url ->
      assertThrows(IllegalArgumentException::class.java) { AppleIconPolicy.validateUrl(url, true) }
    }
    assertThrows(IllegalArgumentException::class.java) {
      AppleIconPolicy.validateUrl("https://apps.apple.com/a", true)
    }
  }

  @Test fun unsupportedOptionsAndArbitrarySystemIdsAreRejected() {
    assertThrows(IllegalArgumentException::class.java) { AppleIconOptions(resolution = 4096) }
    assertThrows(IllegalArgumentException::class.java) { AppleIconOptions(country = "../") }
    assertThrows(IllegalArgumentException::class.java) { AppleAppMatch("../file", "", "") }
    assertThrows(IllegalArgumentException::class.java) { AppleAppMatch("system_phone", "", "") }
  }

  @Test fun systemStoresMapToAppStoreAndNeverAppleRetailStore() {
    listOf("应用商店", "软件商店", "应用市场", "App Store").forEach { label ->
      val matched = AppleSystemMappings.defaultFor(AppleAppTarget("test.store", label, true), ApplePlatform.IOS)
      assertEquals("system_appstore", matched?.id)
      assertNotEquals("375380948", matched?.id)
      assertNull(AppleSystemMappings.defaultFor(AppleAppTarget("test.store", label, false), ApplePlatform.IOS))
    }
  }

  @Test fun thirdPartyMusicAppsDoNotGetGenericRoleMapping() {
    assertNull(AppleSystemMappings.defaultFor(AppleAppTarget("third.party.music", "Music", false), ApplePlatform.IOS))
    assertEquals("1108187390", AppleSystemMappings.defaultFor(
      AppleAppTarget("com.apple.android.music", "Music", false), ApplePlatform.IOS)?.id)
  }

  @Test fun macDoesNotSilentlyBorrowAnIosProductId() {
    assertNull(AppleSystemMappings.defaultFor(
      AppleAppTarget("com.openai.chatgpt", "ChatGPT", false), ApplePlatform.MACOS))
    assertEquals("system_music", AppleSystemMappings.defaultFor(
      AppleAppTarget("com.apple.android.music", "Music", false), ApplePlatform.MACOS)?.id)
  }

  @Test fun offlineSystemSearchIsConservative() {
    assertEquals("system_appstore", AppleSystemMappings.localSearch("Apple App Store", ApplePlatform.IOS).single().id)
    assertTrue(AppleSystemMappings.localSearch("Apple Store", ApplePlatform.IOS).isEmpty())
    assertTrue(AppleSystemMappings.localSearch("Music downloader", ApplePlatform.MACOS).isEmpty())
  }

  @Test fun normalizationHandlesSpacesAndWidthWithoutGuessingIdentities() {
    assertEquals("applemusic", AppleIconPolicy.normalize("Ａｐｐｌｅ Music"))
    assertNotEquals(AppleIconOptions().key, AppleIconOptions(corners = AppleCorners.ORIGINAL).key)
    assertNotEquals(AppleIconOptions().key, AppleIconOptions(platform = ApplePlatform.IPADOS).key)
  }
}
