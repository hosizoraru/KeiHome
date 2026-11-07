package os.kei.hyper.home.iconpack.apple

/** Exact products are confirmed separately from the deliberately system-only role fallback. */
internal object AppleSystemMappings {
  private fun app(id: String, name: String, bundle: String) = AppleAppMatch(id, name, "Apple", bundle)
  private val music = app("1108187390", "Apple Music", "com.apple.Music")
  private val store = app("system_appstore", "App Store", "com.apple.AppStore")
  private val settings = app("system_settings", "Settings", "com.apple.Preferences")
  private val macMusic = app("system_music", "Apple Music", "com.apple.Music")
  private val macSafari = app("system_safari", "Safari", "com.apple.Safari")
  private val finder = app("system_finder", "Finder", "com.apple.finder")
  private val siri = app("6758482875", "Siri", "com.apple.SiriApp")
  private val macSiri by lazy { app("system_siri", "Siri", "com.apple.siri") }
  private val feedback by lazy { app("system_feedback", "Feedback Assistant", "com.apple.appleseed.FeedbackAssistant") }
  private val roles = mapOf(
    "store" to store,
    "settings" to settings,
    "browser" to app("1146562112", "Safari", "com.apple.mobilesafari"),
    "mail" to app("1108187098", "Mail", "com.apple.mobilemail"),
    "calendar" to app("1108185179", "Calendar", "com.apple.mobilecal"),
    "gallery" to app("1584215428", "Photos", "com.apple.mobileslideshow"),
    "camera" to app("1584216193", "Camera", "com.apple.camera"),
    "clock" to app("1584215688", "Clock", "com.apple.mobiletimer"),
    "calculator" to app("1069511488", "Calculator", "com.apple.calculator"),
    "files" to app("1232058109", "Files", "com.apple.DocumentsApp"),
    "weather" to app("1069513131", "Weather", "com.apple.weather"),
    "notes" to app("1110145109", "Notes", "com.apple.mobilenotes"),
    "recorder" to app("1069512134", "Voice Memos", "com.apple.VoiceMemos"),
    "contacts" to app("1069512615", "Contacts", "com.apple.MobileAddressBook"),
    "wallet" to app("1160481993", "Apple Wallet", "com.apple.Passbook"),
    "messages" to app("1146560473", "Messages", "com.apple.MobileSMS"),
    "passwords" to app("6473799789", "Passwords", "com.apple.Passwords"),
    "findmy" to app("1514844621", "Find My", "com.apple.findmy"),
    "translate" to app("1514844618", "Translate", "com.apple.Translate"),
    "phone" to app("1146562108", "Phone", "com.apple.mobilephone"),
    "health" to app("1242545199", "Apple Health", "com.apple.Health"),
    "compass" to app("1067456176", "Compass", "com.apple.compass"),
    "tv" to app("1174078549", "Apple TV", "com.apple.tv"),
    "games" to app("6744464675", "Apple Games", "com.apple.games"),
    "music" to music,
  )
  private val roleAliases = mapOf(
    "browser" to listOf("浏览器", "browser", "網頁瀏覽器", "ブラウザ", "Safari"),
    "mail" to listOf("邮件", "電子郵件", "电子邮件", "郵件", "email", "mail", "メール", "Apple Mail"),
    "calendar" to listOf("日历", "日曆", "calendar", "カレンダー"),
    "gallery" to listOf("图库", "相册", "相簿", "gallery", "photos", "ギャラリー"),
    "camera" to listOf("相机", "相機", "camera", "カメラ"),
    "clock" to listOf("时钟", "時鐘", "clock", "時計"),
    "calculator" to listOf("计算器", "計算器", "計算機", "calculator", "電卓", "Apple Calculator"),
    "files" to listOf("文件管理", "文件管理器", "文件", "files", "file manager", "ファイル"),
    "weather" to listOf("天气", "天氣", "weather", "天気"),
    "notes" to listOf("笔记", "筆記", "便签", "便箋", "备忘录", "備忘錄", "記事", "notes", "メモ", "Apple Notes"),
    "recorder" to listOf("录音机", "錄音機", "recorder", "voice recorder", "Voice Memos", "ボイスレコーダー", "ボイスメモ"),
    "contacts" to listOf("联系人", "聯絡人", "通讯录", "通訊錄", "contacts", "連絡先", "Apple Contacts"),
    "wallet" to listOf("钱包", "錢包", "wallet", "Apple Wallet", "ウォレット"),
    "messages" to listOf("短信", "简讯", "簡訊", "信息", "訊息", "messages", "messaging", "メッセージ", "Apple Messages"),
    "passwords" to listOf("密码", "密碼", "密码管理", "密碼管理", "passwords", "password manager", "パスワード", "Apple Passwords"),
    "findmy" to listOf("查找", "查找设备", "查找設備", "尋找", "尋找裝置", "Find My", "Find Device", "Find My Device", "探す", "デバイスを探す"),
    "translate" to listOf("翻译", "翻譯", "translate", "translation", "翻訳", "Apple Translate"),
    "phone" to listOf("电话", "電話", "phone", "dialer", "ダイヤラー", "Apple Phone"),
    "feedback" to listOf("用户反馈", "用戶反饋", "使用者回饋", "服务与反馈", "服務與反饋", "服務與回饋",
      "feedback", "Feedback Assistant", "Services & feedback", "フィードバック", "フィードバックアシスタント"),
    "health" to listOf("小米运动健康", "小米運動健康", "Mi Fitness", "Xiaomi Health", "health", "Apple Health", "ヘルスケア"),
    "compass" to listOf("指南针", "指南針", "compass", "コンパス", "Apple Compass"),
    "tv" to listOf("小米视频", "小米視頻", "小米影片", "Mi Video", "tv", "Apple TV"),
    "siri" to listOf("超级小爱", "超級小愛", "小爱同学", "小愛同學", "XiaoAI", "Xiao AI", "Siri"),
    "games" to listOf("游戏中心", "遊戲中心", "games", "game center", "Apple Games", "ゲーム", "ゲームセンター"),
    "music" to listOf("音乐", "音樂", "music", "ミュージック", "Apple Music"),
    "settings" to listOf("设置", "設定", "settings", "システム設定", "Apple Settings"),
  ).flatMap { (role, aliases) -> aliases.map { AppleIconPolicy.normalize(it) to role } }.toMap()
  // These exact products may be user-installed; generic labels still require a system app.
  private val exactRoles = mapOf(
    "com.mi.health" to "health",
    "com.xiaomi.wearable" to "health",
    "com.miui.voiceassist" to "siri",
    "com.miui.voiceassistProxy" to "siri",
    "com.android.vending" to "store",
    "com.xiaomi.market" to "store",
    "com.xiaomi.mipicks" to "store",
    "com.oppo.market" to "store",
    "com.heytap.market" to "store",
    "com.bbk.appstore" to "store",
    "com.huawei.appmarket" to "store",
    "com.sec.android.app.samsungapps" to "store",
    "com.android.browser" to "browser",
    "com.miui.gallery" to "gallery",
    "com.android.gallery3d" to "gallery",
    "com.android.calendar" to "calendar",
    "com.android.deskclock" to "clock",
    "com.android.camera" to "camera",
    "com.android.settings" to "settings",
    "com.android.providers.downloads.ui" to "files",
    "com.android.fileexplorer" to "files",
    "com.mi.android.globalFileexplorer" to "files",
    "com.miui.weather2" to "weather",
    "com.miui.player" to "music",
    "com.miui.notes" to "notes",
    "com.mipay.wallet" to "wallet",
    "com.android.mms" to "messages",
    "com.xiaomi.finddevice" to "findmy",
    "com.miui.translation" to "translate",
    "com.xiaomi.aiasst.vision" to "translate",
    "com.android.dialer" to "phone",
    "com.miui.calculator" to "calculator",
    "com.android.calculator2" to "calculator",
    "com.android.soundrecorder" to "recorder",
    "com.miui.bugreport" to "feedback",
    "com.miui.miservice" to "feedback",
    "com.android.email" to "mail",
    "com.miui.compass" to "compass",
    "com.miui.videoplayer" to "tv",
    "com.miui.video" to "tv",
    "com.xiaomi.gamecenter" to "games",
  )
  private val products = mapOf(
    "com.apple.android.music" to music,
    "com.apple.android.music.classical" to AppleAppMatch("1598433714", "Apple Music Classical", "Apple", "com.apple.music.classical"),
    "com.openai.chatgpt" to AppleAppMatch("6448311069", "ChatGPT", "OpenAI", "com.openai.chat"),
    "com.tencent.qqmusic" to AppleAppMatch("414603431", "QQ Music", "Tencent", "com.tencent.QQMusic"),
    "com.google.android.youtube" to AppleAppMatch("544007664", "YouTube", "Google", "com.google.ios.youtube"),
    "com.google.android.apps.maps" to AppleAppMatch("585027354", "Google Maps", "Google", "com.google.Maps"),
    "com.google.android.gm" to AppleAppMatch("422689480", "Gmail", "Google", "com.google.Gmail"),
    "com.android.chrome" to AppleAppMatch("535886823", "Chrome", "Google", "com.google.chrome.ios"),
    "org.telegram.messenger" to AppleAppMatch("686449807", "Telegram Messenger", "Telegram", "ph.telegra.Telegraph"),
    "org.telegram.messenger.web" to AppleAppMatch("686449807", "Telegram Messenger", "Telegram", "ph.telegra.Telegraph"),
    "com.whatsapp" to AppleAppMatch("310633997", "WhatsApp Messenger", "WhatsApp", "net.whatsapp.WhatsApp"),
    "com.spotify.music" to AppleAppMatch("324684580", "Spotify", "Spotify", "com.spotify.client"),
    "com.tencent.mm" to AppleAppMatch("414478124", "WeChat", "Tencent", "com.tencent.xin"),
  )

  fun defaultFor(target: AppleAppTarget, platform: ApplePlatform): AppleAppMatch? {
    if (platform == ApplePlatform.MACOS) {
      if (target.packageName == "com.apple.android.music") return macMusic
    } else {
      products[target.packageName]?.let { return it }
    }
    val role = exactRole(target) ?: if (target.system) role(target) else null
    if (role == "siri") return if (platform == ApplePlatform.MACOS) macSiri else siri
    if (role == "feedback") return feedback
    if (platform == ApplePlatform.MACOS) {
      return when (role) {
        "store" -> store
        "settings" -> settings
        "music" -> macMusic
        "browser" -> macSafari
        "files" -> finder
        else -> null
      }
    }
    return roles[role]
  }

  private fun exactRole(target: AppleAppTarget): String? {
    // HyperOS exposes either role from this APK, depending on the launcher label.
    if (target.packageName == "com.android.contacts") {
      return roleAliases[AppleIconPolicy.normalize(target.label)]
        ?.takeIf { it == "phone" || it == "contacts" } ?: "contacts"
    }
    return exactRoles[target.packageName]
  }

  private fun role(target: AppleAppTarget): String? {
    val name = AppleIconPolicy.normalize(target.label)
    if (("商店" in name && ("应用" in name || "软件" in name)) ||
      name in setOf("应用市场", "软件市场", "appstore", "playstore", "googleplaystore")) return "store"
    return roleAliases[name]
  }

  fun localSearch(term: String, platform: ApplePlatform): List<AppleAppMatch> {
    val name = AppleIconPolicy.normalize(term)
    return when {
      name in setOf("appstore", "苹果应用商店", "appleappstore", "应用商店", "软件商店", "苹果商店") -> listOf(store)
      name in setOf("settings", "设置", "設定", "applesettings", "システム設定") -> listOf(settings)
      platform == ApplePlatform.MACOS && name in setOf("applemusic", "音乐", "音樂", "ミュージック") -> listOf(macMusic)
      platform == ApplePlatform.MACOS && name == "safari" -> listOf(macSafari)
      platform == ApplePlatform.MACOS && name in setOf("finder", "访达", "訪達") -> listOf(finder)
      platform != ApplePlatform.MACOS && name == "applemusic" -> listOf(music)
      else -> when (val role = roleAliases[name]) {
        "siri" -> listOf(if (platform == ApplePlatform.MACOS) macSiri else siri)
        "feedback" -> listOf(feedback)
        "settings" -> listOf(settings)
        "music", "browser", "files" -> emptyList()
        else -> if (platform == ApplePlatform.MACOS) emptyList() else listOfNotNull(roles[role])
      }
    }
  }
}
