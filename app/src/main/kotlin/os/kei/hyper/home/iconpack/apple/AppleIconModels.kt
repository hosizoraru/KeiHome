package os.kei.hyper.home.iconpack.apple

import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer

internal enum class ApplePlatform(val wire: String, val entity: String, val attribute: String) {
  IOS("iphone", "software", "ios"),
  IPADOS("ipad", "iPadSoftware", "ios"),
  MACOS("mac", "macSoftware", "osx"),
}

internal enum class AppleCorners { ROUNDED, ORIGINAL }

internal data class AppleIconOptions(
  val platform: ApplePlatform = ApplePlatform.IOS,
  val resolution: Int = 512,
  val corners: AppleCorners = AppleCorners.ROUNDED,
  val country: String = "us",
) {
  init {
    require(resolution in listOf(256, 512, 1024))
    require(country.matches(Regex("[a-z]{2}")))
  }
  val key: String get() = "${platform.name}:$resolution:${corners.name}:$country"

  fun forApp(app: AppleAppMatch): AppleIconOptions =
    app.country?.let { copy(country = it) } ?: this
}

internal data class AppleAppTarget(val packageName: String, val label: String, val system: Boolean)

internal data class AppleAppMatch(
  val id: String,
  val name: String,
  val publisher: String,
  val bundleId: String = "",
  val country: String? = null,
) {
  init {
    require(id.matches(Regex("[0-9]{6,12}|system_(appstore|settings|music|safari|finder)")))
    require(country == null || country.matches(Regex("[a-z]{2}")))
  }

  fun artworkId(options: AppleIconOptions): String = "${id}_${country ?: options.country}"
}

internal data class AppleArtwork(
  val app: AppleAppMatch,
  val template: String,
  val checkedAt: Long,
  val fallback: Boolean = false,
  val limited: Boolean = false,
)

internal object AppleIconPolicy {
  const val MAX_RESPONSE = 8 * 1024 * 1024
  const val MAX_CACHE_BYTES = 256L * 1024 * 1024
  const val META_TTL_MS = 7L * 24 * 60 * 60 * 1000
  const val SEARCH_TTL_MS = 24L * 60 * 60 * 1000

  fun key(text: String): String = digest(text.toByteArray())
  fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

  fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

  fun validateUrl(url: String, artwork: Boolean = false) {
    require(url.length in 1..4096)
    val uri = URI(url)
    require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443))
    val image = uri.host?.matches(Regex("is[0-9]+-ssl\\.mzstatic\\.com")) == true
    require(image || (!artwork && uri.host in listOf("apps.apple.com", "itunes.apple.com")))
    require(uri.fragment == null)
  }

  fun rendition(template: String, options: AppleIconOptions, dark: Boolean): String {
    require(template.length <= 4000)
    require(listOf("{w}", "{h}", "{c}", "{f}").all { it in template })
    val crop = when {
      options.corners == AppleCorners.ORIGINAL -> "bb"
      options.platform == ApplePlatform.MACOS -> "Icon.ApSmac01"
      else -> "Icon.ApSiOS01"
    }
    val url = template.replace("{w}", options.resolution.toString())
      .replace("{h}", options.resolution.toString()).replace("{c}", crop).replace("{f}", "png")
    validateUrl(url, artwork = true)
    val uri = URI(url)
    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() && !it.startsWith("appearance=") }
    return url.substringBefore('?') + "?" +
      (query + "appearance=${if (dark) "dark" else "light"}").joinToString("&")
  }
}
