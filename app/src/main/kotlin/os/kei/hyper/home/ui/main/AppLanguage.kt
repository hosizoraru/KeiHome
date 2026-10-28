package os.kei.hyper.home.ui.main

import java.util.Locale

internal enum class AppLanguage(val tag: String) {
  SYSTEM(""),
  CHINESE("zh-CN"),
  ENGLISH("en"),
  JAPANESE("ja");

  companion object {
    fun fromTag(tags: String): AppLanguage? {
      if (tags.isEmpty()) return SYSTEM
      val locale = Locale.forLanguageTag(tags.substringBefore(','))
      return when (locale.language) {
        "zh" -> if (locale.script != "Hant" && locale.country !in setOf("TW", "HK", "MO")) {
          CHINESE
        } else {
          null
        }
        "en" -> ENGLISH
        "ja" -> JAPANESE
        else -> null
      }
    }
  }
}
