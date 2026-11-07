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
    "music" to music,
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
      return if (target.system) when (role(target)) {
        "store" -> store
        "settings" -> settings
        "music" -> macMusic
        "browser" -> macSafari
        "files" -> finder
        else -> null
      } else null
    }
    products[target.packageName]?.let { return it }
    return if (target.system) roles[role(target)] else null
  }

  private fun role(target: AppleAppTarget): String? {
    val name = AppleIconPolicy.normalize(target.label)
    val known = when (target.packageName) {
      "com.android.vending", "com.xiaomi.market", "com.xiaomi.mipicks", "com.oppo.market",
      "com.heytap.market", "com.bbk.appstore", "com.huawei.appmarket", "com.sec.android.app.samsungapps" -> "store"
      "com.miui.gallery", "com.android.gallery3d" -> "gallery"
      "com.android.deskclock" -> "clock"
      "com.android.camera" -> "camera"
      "com.android.settings" -> "settings"
      "com.android.providers.downloads.ui", "com.mi.android.globalFileexplorer" -> "files"
      else -> null
    }
    if (known != null) return known
    if (("商店" in name && ("应用" in name || "软件" in name)) ||
      name in setOf("应用市场", "软件市场", "appstore", "playstore", "googleplaystore")) return "store"
    return when (name) {
      "浏览器", "browser", "網頁瀏覽器", "ブラウザ" -> "browser"
      "邮件", "電子郵件", "email", "mail", "メール" -> "mail"
      "日历", "日曆", "calendar", "カレンダー" -> "calendar"
      "图库", "相册", "相簿", "gallery", "photos", "ギャラリー" -> "gallery"
      "相机", "相機", "camera", "カメラ" -> "camera"
      "时钟", "時鐘", "clock", "時計" -> "clock"
      "计算器", "計算機", "calculator", "電卓" -> "calculator"
      "文件管理", "文件管理器", "文件", "files", "filemanager", "ファイル" -> "files"
      "天气", "天氣", "weather", "天気" -> "weather"
      "笔记", "便签", "記事", "notes", "メモ" -> "notes"
      "录音机", "錄音機", "recorder", "voicerecorder", "ボイスレコーダー" -> "recorder"
      "联系人", "聯絡人", "contacts", "連絡先" -> "contacts"
      "音乐", "音樂", "music", "ミュージック" -> "music"
      "设置", "設定", "settings", "システム設定" -> "settings"
      else -> null
    }
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
      else -> emptyList()
    }
  }
}
