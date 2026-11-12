package os.kei.hyper.home.data

import android.content.Context

/** A batch keeps one immutable selection, including its light/dark and supplemental exports. */
internal data class RecommendedIconMappings(
  val telegramChannels: Boolean = true,
  val colorOsThemeStores: Boolean = true,
) {
  val bits: Int get() = (if (telegramChannels) 1 else 0) or (if (colorOsThemeStores) 2 else 0)

  companion object {
    const val TELEGRAM_KEY = "telegram_channel_mapping"
    const val THEME_STORE_KEY = "coloros_theme_store_mapping"
    val NONE = RecommendedIconMappings(false, false)

    fun fromBits(bits: Int): RecommendedIconMappings {
      require(bits in 0..3)
      return RecommendedIconMappings(bits and 1 != 0, bits and 2 != 0)
    }

    fun snapshot(context: Context): RecommendedIconMappings = AppDisplayStorage.open(context).let {
      RecommendedIconMappings(it.getBoolean(TELEGRAM_KEY, true), it.getBoolean(THEME_STORE_KEY, true))
    }
  }
}
