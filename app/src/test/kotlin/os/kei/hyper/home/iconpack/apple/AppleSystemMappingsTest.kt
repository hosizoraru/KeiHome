package os.kei.hyper.home.iconpack.apple

import org.junit.Assert.*
import org.junit.Test

class AppleSystemMappingsTest {
  private data class Expected(
    val canonical: String,
    val id: String,
    val bundle: String,
    val aliases: List<String>,
  )

  private val expected = listOf(
    Expected("Notes", "1110145109", "com.apple.mobilenotes",
      listOf("笔记", "筆記", "便签", "備忘錄", "メモ", "Apple Notes")),
    Expected("Wallet", "1160481993", "com.apple.Passbook",
      listOf("钱包", "錢包", "ウォレット", "Apple Wallet")),
    Expected("Messages", "1146560473", "com.apple.MobileSMS",
      listOf("短信", "簡訊", "訊息", "メッセージ", "Apple Messages")),
    Expected("Passwords", "6473799789", "com.apple.Passwords",
      listOf("密码", "密码管理", "密碼", "密碼管理", "Password Manager", "パスワード")),
    Expected("Find My", "1514844621", "com.apple.findmy",
      listOf("查找", "查找设备", "查找設備", "尋找", "尋找裝置", "Find My Device", "探す")),
    Expected("Contacts", "1069512615", "com.apple.MobileAddressBook",
      listOf("联系人", "聯絡人", "通讯录", "通訊錄", "連絡先", "Apple Contacts")),
    Expected("Translate", "1514844618", "com.apple.Translate",
      listOf("翻译", "翻譯", "Translation", "翻訳", "Apple Translate")),
    Expected("Phone", "1146562108", "com.apple.mobilephone",
      listOf("电话", "電話", "Dialer", "ダイヤラー", "Apple Phone")),
    Expected("Calculator", "1069511488", "com.apple.calculator",
      listOf("计算器", "計算器", "計算機", "電卓", "Apple Calculator")),
    Expected("Voice Memos", "1069512134", "com.apple.VoiceMemos",
      listOf("录音机", "錄音機", "Recorder", "Voice Recorder", "ボイスレコーダー", "ボイスメモ")),
    Expected("Feedback", "system_feedback", "com.apple.appleseed.FeedbackAssistant",
      listOf("用户反馈", "服务与反馈", "用戶反饋", "使用者回饋", "服務與反饋", "服務與回饋",
        "Feedback Assistant", "Services & feedback", "フィードバック", "フィードバックアシスタント")),
    Expected("Mail", "1108187098", "com.apple.mobilemail",
      listOf("电子邮件", "電子郵件", "邮件", "郵件", "Email", "メール", "Apple Mail")),
    Expected("Health", "1242545199", "com.apple.Health",
      listOf("小米运动健康", "小米運動健康", "Mi Fitness", "Xiaomi Health", "Apple Health", "ヘルスケア")),
    Expected("Compass", "1067456176", "com.apple.compass",
      listOf("指南针", "指南針", "コンパス", "Apple Compass")),
    Expected("TV", "1174078549", "com.apple.tv",
      listOf("小米视频", "小米視頻", "小米影片", "Mi Video", "Apple TV")),
    Expected("Siri", "6758482875", "com.apple.SiriApp",
      listOf("超级小爱", "超級小愛", "小爱同学", "小愛同學", "XiaoAI", "Xiao AI")),
    Expected("Games", "6744464675", "com.apple.games",
      listOf("游戏中心", "遊戲中心", "Game Center", "Apple Games", "ゲーム", "ゲームセンター")),
  )

  private fun expectedForPlatform(app: Expected, platform: ApplePlatform): Expected =
    if (app.canonical == "Siri" && platform == ApplePlatform.MACOS) {
      app.copy(id = "system_siri", bundle = "com.apple.siri")
    } else app

  private fun assertMatch(expected: Expected, actual: AppleAppMatch?, context: String) {
    assertNotNull(context, actual)
    assertEquals(context, expected.id, actual?.id)
    assertEquals(context, expected.bundle, actual?.bundleId)
    assertEquals(context, "Apple", actual?.publisher)
  }

  @Test fun allSeventeenCanonicalNamesResolveOnMobileAndOffline() {
    assertEquals(17, expected.size)
    for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
      for (app in expected) {
        assertMatch(app, AppleSystemMappings.defaultFor(
          AppleAppTarget("system.test", app.canonical, true), platform), "${app.canonical} / $platform")
        assertMatch(app, AppleSystemMappings.localSearch(app.canonical, platform).singleOrNull(),
          "search ${app.canonical} / $platform")
      }
    }
  }

  @Test fun localizedSystemAliasesResolveOnMobileAndOffline() {
    for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
      for (app in expected) for (label in app.aliases) {
        assertMatch(app, AppleSystemMappings.defaultFor(
          AppleAppTarget("system.test", label, true), platform), "$label / $platform")
        assertMatch(app, AppleSystemMappings.localSearch(label, platform).singleOrNull(),
          "search $label / $platform")
      }
    }
  }

  @Test fun genericThirdPartyLabelsNeverTakeOverAnAppleRole() {
    for (platform in ApplePlatform.entries) {
      for (app in expected) for (label in app.aliases + app.canonical) {
        assertNull("$label / $platform", AppleSystemMappings.defaultFor(
          AppleAppTarget("third.party.app", label, false), platform))
      }
    }
  }

  @Test fun exactHealthProductsMayBeUserInstalledButRemainMobileOnly() {
    val health = expected.single { it.canonical == "Health" }
    for (pkg in listOf("com.mi.health", "com.xiaomi.wearable")) {
      for (system in listOf(false, true)) {
        for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
          assertMatch(health, AppleSystemMappings.defaultFor(
            AppleAppTarget(pkg, "Unknown localized product", system), platform), "$pkg / $system / $platform")
        }
        assertNull(AppleSystemMappings.defaultFor(
          AppleAppTarget(pkg, "小米运动健康", system), ApplePlatform.MACOS))
      }
    }
  }

  @Test fun bothExactVoiceAssistantsMapIncludingTheNonSystemProxyOnEveryPlatform() {
    val siri = expected.single { it.canonical == "Siri" }
    for (pkg in listOf("com.miui.voiceassist", "com.miui.voiceassistProxy")) {
      for (system in listOf(false, true)) for (platform in ApplePlatform.entries) {
        for (label in listOf("超级小爱", "Unknown localized product")) {
          assertMatch(expectedForPlatform(siri, platform),
            AppleSystemMappings.defaultFor(AppleAppTarget(pkg, label, system), platform),
            "$pkg / $label / $system / $platform")
        }
      }
    }
  }

  @Test fun exactOemPackagesResolveRegardlessOfSystemFlagOrLabelLanguage() {
    val packages = mapOf(
      "com.miui.notes" to "Notes",
      "com.mipay.wallet" to "Wallet",
      "com.android.mms" to "Messages",
      "com.xiaomi.finddevice" to "Find My",
      "com.miui.translation" to "Translate",
      "com.xiaomi.aiasst.vision" to "Translate",
      "com.android.dialer" to "Phone",
      "com.miui.calculator" to "Calculator",
      "com.android.calculator2" to "Calculator",
      "com.android.soundrecorder" to "Voice Memos",
      "com.miui.bugreport" to "Feedback",
      "com.miui.miservice" to "Feedback",
      "com.android.email" to "Mail",
      "com.miui.compass" to "Compass",
      "com.miui.videoplayer" to "TV",
      "com.miui.video" to "TV",
      "com.xiaomi.gamecenter" to "Games",
    )
    for ((pkg, canonical) in packages) {
      val app = expected.single { it.canonical == canonical }
      for (system in listOf(false, true)) for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
        assertMatch(app, AppleSystemMappings.defaultFor(
          AppleAppTarget(pkg, "Unknown localized label", system), platform), "$pkg / $system / $platform")
        assertMatch(app, AppleSystemMappings.defaultFor(
          AppleAppTarget(pkg, "Siri", system), platform), "$pkg exact identity wins over label / $platform")
      }
      for (system in listOf(false, true)) {
        val mac = AppleSystemMappings.defaultFor(
          AppleAppTarget(pkg, "Unknown localized label", system), ApplePlatform.MACOS)
        if (canonical == "Feedback") assertMatch(app, mac, "$pkg native Feedback / $system")
        else assertNull("$pkg mobile-only role / $system", mac)
      }
    }
  }

  @Test fun contactsApkHonorsPhoneVersusContactsLabels() {
    for (canonical in listOf("Phone", "Contacts")) {
      val app = expected.single { it.canonical == canonical }
      for (label in app.aliases + app.canonical) {
        for (system in listOf(false, true)) for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
          assertMatch(app, AppleSystemMappings.defaultFor(
            AppleAppTarget("com.android.contacts", label, system), platform), "contacts APK / $label / $system")
        }
        for (system in listOf(false, true)) {
          assertNull(AppleSystemMappings.defaultFor(
            AppleAppTarget("com.android.contacts", label, system), ApplePlatform.MACOS))
        }
      }
    }
  }

  @Test fun securityAndCloudServicePackagesDoNotGloballyBecomePasswordsOrFindMy() {
    for (system in listOf(false, true)) for (platform in ApplePlatform.entries) {
      for ((pkg, label) in mapOf(
        "com.miui.securitycenter" to "手机管家",
        "com.miui.securitymanager" to "手机管家",
        "com.miui.cloudservice" to "小米云服务",
        "com.miui.cloudservice.sysbase" to "Cloud service",
      )) {
        assertNull("$pkg / $system / $platform",
          AppleSystemMappings.defaultFor(AppleAppTarget(pkg, label, system), platform))
      }
    }
  }

  @Test fun observedUpdatableOemAppsAlsoRetainExistingAppleRoles() {
    val roles = mapOf(
      "com.android.calendar" to "1108185179",
      "com.android.deskclock" to "1584215688",
      "com.miui.gallery" to "1584215428",
      "com.miui.weather2" to "1069513131",
      "com.miui.player" to "1108187390",
      "com.android.providers.downloads.ui" to "1232058109",
      "com.android.fileexplorer" to "1232058109",
    )
    for ((pkg, id) in roles) {
      for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
        for (system in listOf(false, true)) {
          assertEquals("$pkg / $system / $platform", id, AppleSystemMappings.defaultFor(
            AppleAppTarget(pkg, "Unknown label", system), platform)?.id)
        }
      }
    }
    for ((pkg, id) in mapOf(
      "com.android.browser" to "system_safari",
      "com.android.fileexplorer" to "system_finder",
      "com.android.providers.downloads.ui" to "system_finder",
      "com.miui.player" to "system_music",
      "com.xiaomi.market" to "system_appstore",
      "com.android.settings" to "system_settings",
    )) {
      assertEquals(pkg, id, AppleSystemMappings.defaultFor(
        AppleAppTarget(pkg, "Unknown label", false), ApplePlatform.MACOS)?.id)
    }
  }

  @Test fun packageMatchingIsExactAndNeverUsesPrefixesOrSubstrings() {
    for (pkg in listOf("third.party.com.mi.health", "com.xiaomi.wearable.clone",
      "com.miui.voiceassistProxy.clone", "com.miui.voiceassistproxy", "com.miui.notes.clone",
      "com.xiaomi.gamecenter.sdk.service")) {
      for (system in listOf(false, true)) for (platform in ApplePlatform.entries) {
        assertNull("$pkg / $system / $platform", AppleSystemMappings.defaultFor(
          AppleAppTarget(pkg, "Unrecognized", system), platform))
      }
    }
  }

  @Test fun macRejectsMobilePublicIdsAndSeparatesSiri() {
    for (app in expected.filterNot { it.id.startsWith("system_") }) {
      for (label in app.aliases + app.canonical) {
        val default = AppleSystemMappings.defaultFor(
          AppleAppTarget("system.test", label, true), ApplePlatform.MACOS)
        val search = AppleSystemMappings.localSearch(label, ApplePlatform.MACOS)
        if (app.canonical == "Siri") {
          assertMatch(expectedForPlatform(app, ApplePlatform.MACOS), default, label)
          assertMatch(expectedForPlatform(app, ApplePlatform.MACOS), search.singleOrNull(), label)
        } else {
          assertNull(label, default)
          assertTrue(label, search.isEmpty())
        }
        assertNotEquals(label, app.id, default?.id)
        assertTrue(label, search.none { it.id == app.id })
      }
    }
    for (pkg in listOf("com.miui.notes", "com.mipay.wallet", "com.android.mms",
      "com.xiaomi.finddevice", "com.miui.translation", "com.android.dialer", "com.miui.calculator",
      "com.android.soundrecorder", "com.android.email", "com.mi.health", "com.xiaomi.wearable",
      "com.miui.compass", "com.miui.videoplayer", "com.xiaomi.gamecenter")) {
      assertNull(pkg, AppleSystemMappings.defaultFor(
        AppleAppTarget(pkg, "Unrecognized", true), ApplePlatform.MACOS))
    }
  }

  @Test fun nativeSiriAndFeedbackRemainAvailableOnMac() {
    for (mobile in expected.filter { it.canonical in setOf("Siri", "Feedback") }) {
      val app = expectedForPlatform(mobile, ApplePlatform.MACOS)
      for (label in app.aliases + app.canonical) {
        assertMatch(app, AppleSystemMappings.defaultFor(
          AppleAppTarget("system.test", label, true), ApplePlatform.MACOS), label)
        assertMatch(app, AppleSystemMappings.localSearch(label, ApplePlatform.MACOS).singleOrNull(), label)
      }
    }
    assertEquals("system_feedback", AppleSystemMappings.defaultFor(
      AppleAppTarget("com.miui.bugreport", "Unrecognized", true), ApplePlatform.MACOS)?.id)
  }

  @Test fun mobileSiriUsesThePublicAppStoreIdentityForEveryAlias() {
    val siri = expected.single { it.canonical == "Siri" }
    for (platform in listOf(ApplePlatform.IOS, ApplePlatform.IPADOS)) {
      for (label in siri.aliases + siri.canonical) {
        val default = AppleSystemMappings.defaultFor(AppleAppTarget("system.test", label, true), platform)
        val search = AppleSystemMappings.localSearch(label, platform).singleOrNull()
        assertMatch(siri, default, label)
        assertMatch(siri, search, label)
        assertNotEquals("system_siri", default?.id)
        assertNotEquals("com.apple.siri", search?.bundleId)
      }
    }
  }

  @Test fun normalizationDoesNotTurnPartialSearchTermsIntoMappings() {
    assertEquals("1069512134",
      AppleSystemMappings.localSearch(" Ｖｏｉｃｅ　Ｍｅｍｏｓ ", ApplePlatform.IOS).single().id)
    for (term in listOf("Password recovery", "Find My Phone Pro", "Wallet rewards", "TV remote",
      "Games downloader", "Super Siri", "Feedback Collector", "Apple Store deals")) {
      for (platform in ApplePlatform.entries) {
        assertTrue("$term / $platform", AppleSystemMappings.localSearch(term, platform).isEmpty())
        assertNull("$term / $platform", AppleSystemMappings.defaultFor(
          AppleAppTarget("system.test", term, true), platform))
      }
    }
  }

  @Test fun establishedExactProductsStillWinOverSystemLabelAliases() {
    for (label in listOf("Notes", "Siri", "Feedback")) {
      for (system in listOf(false, true)) {
        assertEquals("422689480", AppleSystemMappings.defaultFor(
          AppleAppTarget("com.google.android.gm", label, system), ApplePlatform.IOS)?.id)
        assertEquals("system_music", AppleSystemMappings.defaultFor(
          AppleAppTarget("com.apple.android.music", label, system), ApplePlatform.MACOS)?.id)
      }
    }
  }
}
