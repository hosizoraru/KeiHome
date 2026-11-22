package os.kei.hyper.home.data

import android.content.Context

/** A batch keeps one immutable selection, including its light/dark and supplemental exports. */
internal data class RecommendedIconMappings(
  val telegramChannels: Boolean = true,
  val colorOsThemeStores: Boolean = true,
  val phoneCompanions: Boolean = true,
  val weiboLite: Boolean = true,
) {
  val bits: Int get() = (if (telegramChannels) 1 else 0) or
    (if (colorOsThemeStores) 2 else 0) or (if (phoneCompanions) 4 else 0) or (if (weiboLite) 8 else 0)

  companion object {
    const val TELEGRAM_KEY = "telegram_channel_mapping"
    const val THEME_STORE_KEY = "coloros_theme_store_mapping"
    const val PHONE_COMPANION_KEY = "phone_companion_mapping"
    const val WEIBO_LITE_KEY = "weibo_lite_mapping"
    val NONE = RecommendedIconMappings(false, false, false, false)
    val LEGACY = RecommendedIconMappings(true, true, false, false)
    val BEFORE_WEIBO = RecommendedIconMappings(weiboLite = false)

    fun fromBits(bits: Int): RecommendedIconMappings {
      require(bits in 0..15)
      return RecommendedIconMappings(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0)
    }

    fun snapshot(context: Context): RecommendedIconMappings = AppDisplayStorage.open(context).let {
      RecommendedIconMappings(it.getBoolean(TELEGRAM_KEY, true), it.getBoolean(THEME_STORE_KEY, true),
        it.getBoolean(PHONE_COMPANION_KEY, true), it.getBoolean(WEIBO_LITE_KEY, true))
    }
  }
}
