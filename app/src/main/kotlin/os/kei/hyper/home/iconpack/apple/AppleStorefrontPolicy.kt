package os.kei.hyper.home.iconpack.apple

import java.util.Locale

/** Defaults only: saved options and mapping-specific storefronts remain authoritative. */
internal object AppleStorefrontPolicy {
  private val countries = Locale.getISOCountries().map { it.lowercase(Locale.ROOT) }.toSet()
  private val languageDefaults = mapOf(
    "en" to "us", "zh" to "cn", "ja" to "jp", "ko" to "kr", "fr" to "fr",
    "de" to "de", "es" to "es", "pt" to "pt", "it" to "it", "ar" to "sa",
    "hi" to "in", "id" to "id", "vi" to "vn", "th" to "th", "tr" to "tr",
    "ru" to "ru", "uk" to "ua", "ms" to "my",
  )

  fun defaultCountry(locales: List<Locale>): String {
    for (locale in locales) {
      val country = locale.country.lowercase(Locale.ROOT).let { if (it == "uk") "gb" else it }
      if (country in countries) return country
      if (locale.language == "zh" && locale.script == "Hant") return "tw"
      languageDefaults[locale.language]?.let { return it }
    }
    return "us"
  }

  fun searchCountry(label: String, fallback: String, explicit: String? = null): String =
    explicit ?: if (isHanName(label)) "cn" else fallback

  private fun isHanName(label: String): Boolean {
    var hasHan = false
    val points = label.codePoints().iterator()
    while (points.hasNext()) {
      val point = points.nextInt()
      if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) hasHan = true
      else if (!Character.isWhitespace(point) && Character.getType(point) !in punctuation) return false
    }
    return hasHan
  }

  private val punctuation = setOf(
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
  )
}
