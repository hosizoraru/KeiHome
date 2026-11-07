package os.kei.hyper.home.iconpack.apple

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleStorefrontPolicyTest {
  @Test fun explicitSystemRegionTakesPriorityOverLanguage() {
    mapOf("zh-CN" to "cn", "zh-HK" to "hk", "zh-TW" to "tw", "en-SG" to "sg",
      "en-GB" to "gb", "ja-JP" to "jp", "zh-US" to "us").forEach { (tag, expected) ->
      assertEquals(expected, AppleStorefrontPolicy.defaultCountry(listOf(Locale.forLanguageTag(tag))))
    }
  }

  @Test fun languageOnlyAndUnknownLocalesHaveDeterministicDefaults() {
    mapOf("zh" to "cn", "zh-Hant" to "tw", "ja" to "jp", "ko" to "kr", "en" to "us")
      .forEach { (tag, expected) ->
        assertEquals(expected, AppleStorefrontPolicy.defaultCountry(listOf(Locale.forLanguageTag(tag))))
      }
    assertEquals("jp", AppleStorefrontPolicy.defaultCountry(listOf(Locale.ROOT, Locale.JAPAN)))
    assertEquals("us", AppleStorefrontPolicy.defaultCountry(emptyList()))
    assertEquals("us", AppleStorefrontPolicy.defaultCountry(listOf(Locale.ROOT)))
  }

  @Test fun HanNamesPreferChinaWithoutRedirectingMixedNamesOrExplicitChoices() {
    listOf("小米运动健康", "短信", "中国银行（手机银行）", " 記事 ").forEach {
      assertEquals("cn", AppleStorefrontPolicy.searchCountry(it, "us"))
    }
    listOf("Apple Music", "小米 HD", "12306", "メモ", "", "…").forEach {
      assertEquals("jp", AppleStorefrontPolicy.searchCountry(it, "jp"))
    }
    assertEquals("hk", AppleStorefrontPolicy.searchCountry("中国银行", "us", explicit = "hk"))
    assertEquals("us", AppleStorefrontPolicy.searchCountry("中国银行", "cn", explicit = "us"))
  }
}
