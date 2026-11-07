package os.kei.hyper.home.iconpack.apple

import org.junit.Assert.*
import org.junit.Test

class AppleAdditionalMappingsTest {
  private val mobile = listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)
  private val roles = listOf(
    Triple("364709193", "com.apple.iBooks", listOf("Apple Books", "图书", "閱讀", "ブック")),
    Triple("1110145103", "com.apple.Home", listOf("Home", "家庭", "ホーム")),
    Triple("375380948", "com.apple.store.Jolly", listOf("Apple Store", "小米商城", "苹果零售店")),
    Triple("1463884356", "com.apple.Research", listOf("Apple Research", "小米健康研究", "健康研究")),
    Triple("525463029", "com.apple.podcasts", listOf("Podcasts", "播客", "ポッドキャスト")),
    Triple("1108187841", "com.apple.reminders", listOf("Reminders", "提醒事项", "提醒事項", "リマインダー")),
    Triple("1069512882", "com.apple.stocks", listOf("Stocks", "股票", "株価")),
    Triple("915249334", "is.workflow.my.app", listOf("Shortcuts", "快捷指令", "自動化", "ショートカット")),
    Triple("1383426740", "com.apple.measure", listOf("Measure", "测距仪", "測量", "計測")),
    Triple("1563316278", "com.apple.Magnifier", listOf("Magnifier", "放大镜", "放大鏡", "拡大鏡")),
    Triple("1069509450", "com.apple.tips", listOf("Tips", "玩机技巧", "ヒント")),
    Triple("1130498044", "com.apple.supportapp", listOf("Apple Support", "客户支持", "サポート")),
    Triple("536344167", "com.apple.Preview", listOf("Preview", "AI扫描", "文档扫描", "預覽", "プレビュー")),
    Triple("6443742539", "com.apple.freeform", listOf("Freeform", "无边记", "無邊記", "フリーボード")),
    Triple("915056765", "com.apple.Maps", listOf("Apple Maps", "地图", "地圖", "地図")),
  )

  @Test fun additionalRolesResolveLocallyWithVerifiedMobileIdentities() {
    for (platform in mobile) for ((id, bundle, names) in roles) for (name in names) {
      val result = AppleSystemMappings.defaultFor(AppleAppTarget("system.test", name, true), platform)
      assertEquals("$name / $platform", id, result?.id)
      assertEquals(name, bundle, result?.bundleId)
      assertEquals(name, "Apple", result?.publisher)
      assertEquals(name, result, AppleSystemMappings.localSearch(name, platform).singleOrNull())
    }
  }

  @Test fun genericNamesRemainSystemOnlyAndMobileResourcesStayOffMac() {
    for ((_, _, names) in roles) for (name in names) {
      for (platform in ApplePlatform.entries) {
        assertNull("$name / $platform", AppleSystemMappings.defaultFor(
          AppleAppTarget("third.party.reader", name, false), platform))
      }
      assertNull(name, AppleSystemMappings.defaultFor(
        AppleAppTarget("system.test", name, true), ApplePlatform.MACOS))
      assertTrue(name, AppleSystemMappings.localSearch(name, ApplePlatform.MACOS).isEmpty())
    }
  }

  @Test fun observedOemProductsUseExactIdentityEvenAfterUserInstallation() {
    val expected = mapOf(
      "com.xiaomi.mico" to "1110145103",
      "com.duokan.reader" to "364709193", "com.xiaomi.shop" to "375380948",
      "com.mi.healthresearch" to "1463884356", "com.xiaomi.scanner" to "536344167",
      "com.google.android.apps.adm" to "1514844621", "com.xiaomi.station" to "1232058109",
      "com.xiaomi.tinygame" to "6744464675",
    )
    for ((pkg, id) in expected) for (system in listOf(false, true)) {
      for (platform in mobile) {
        assertEquals(pkg, id, AppleSystemMappings.defaultFor(AppleAppTarget(pkg, "Siri", system), platform)?.id)
        assertNull(pkg, AppleSystemMappings.defaultFor(AppleAppTarget("$pkg.clone", "Unknown", system), platform))
      }
      val mac = AppleSystemMappings.defaultFor(AppleAppTarget(pkg, "Unknown", system), ApplePlatform.MACOS)
      if (pkg == "com.xiaomi.station") assertEquals("system_finder", mac?.id) else assertNull(pkg, mac)
    }
  }

  @Test fun sameVendorProductsWinOverGenericRoleNames() {
    val expected = mapOf(
      "com.google.android.googlequicksearchbox" to "284815942",
      "com.google.android.apps.docs" to "507874739",
      "com.google.android.apps.photos" to "962194608",
      "com.google.android.apps.docs.editors.docs" to "842842640",
      "com.google.android.apps.docs.editors.sheets" to "842849113",
      "com.google.android.apps.docs.editors.slides" to "879478102",
      "com.google.android.apps.translate" to "414706506",
      "com.microsoft.office.outlook" to "951937596",
      "com.microsoft.skydrive" to "477537958",
      "com.microsoft.emmx" to "1288723196",
    )
    for ((pkg, id) in expected) for (system in listOf(false, true)) {
      for (platform in mobile) {
        val match = AppleSystemMappings.defaultFor(AppleAppTarget(pkg, "相册", system), platform)
        assertEquals(pkg, id, match?.id)
        assertNotEquals(pkg, "Apple", match?.publisher)
      }
      assertNull(pkg, AppleSystemMappings.defaultFor(AppleAppTarget(pkg, "Unknown", system), ApplePlatform.MACOS))
    }
  }

  @Test fun retailAppleStoreNeverBecomesTheSoftwareAppStore() {
    for (platform in mobile) {
      assertEquals("375380948", AppleSystemMappings.localSearch("Apple Store", platform).single().id)
      for (name in listOf("App Store", "应用商店", "软件商店")) {
        assertEquals("system_appstore", AppleSystemMappings.localSearch(name, platform).single().id)
      }
      assertEquals("system_appstore", AppleSystemMappings.defaultFor(
        AppleAppTarget("com.xiaomi.market", "小米商城", false), platform)?.id)
    }
  }

  @Test fun availableSourceHintOnlyAffectsAutomaticCnPresets() {
    for (pkg in listOf("com.mi.healthresearch", "com.google.android.googlequicksearchbox",
      "com.google.android.apps.translate", "com.spotify.music", "com.openai.chatgpt")) {
      val target = AppleAppTarget(pkg, "Unknown", false)
      assertEquals(pkg, "us", AppleSystemMappings.defaultFor(target, AppleIconOptions(country = "cn"))?.country)
      for (country in listOf("us", "jp", "sg")) {
        assertNull(pkg, AppleSystemMappings.defaultFor(target, AppleIconOptions(country = country))?.country)
      }
    }
    assertNull(AppleSystemMappings.defaultFor(AppleAppTarget("com.xiaomi.mico", "小米音箱", false),
      AppleIconOptions(country = "cn"))?.country)
  }

  @Test fun unrelatedSystemProductsAreNotForcedIntoSimilarLookingRoles() {
    for ((pkg, name) in mapOf(
      "com.miui.themestore" to "主题商店", "com.miui.securitymanager" to "手机管家",
      "com.xiaomi.vipaccount" to "小米社区", "com.miui.greenguard" to "家人守护",
      "com.miui.virtualsim" to "全球上网", "com.miui.huanji" to "小米换机",
      "com.duokan.phone.remotecontroller" to "万能遥控", "com.mi.earphone" to "小米耳机",
      "com.xiaomi.smarthome" to "米家",
    )) for (platform in ApplePlatform.entries) {
      assertNull(pkg, AppleSystemMappings.defaultFor(AppleAppTarget(pkg, name, true), platform))
    }
  }

  @Test fun miHomeNamesDoNotBecomeAppleHomeAndRemainAvailableForManualSearch() {
    for (name in listOf("米家", "Mi Home", "Xiaomi Home")) {
      for (system in listOf(false, true)) for (platform in ApplePlatform.entries) {
        assertNull("$name / $platform", AppleSystemMappings.defaultFor(
          AppleAppTarget("com.xiaomi.smarthome", name, system), platform))
      }
      assertTrue(AppleSystemMappings.localSearch(name, ApplePlatform.IOS).isEmpty())
    }
    assertEquals("1110145103", AppleSystemMappings.localSearch("Home", ApplePlatform.IOS).single().id)
  }
}
