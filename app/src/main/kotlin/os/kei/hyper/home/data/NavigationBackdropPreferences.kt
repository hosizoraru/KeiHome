package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class NavigationBackdropChoice(
  val enabled: Boolean = false,
  val packages: Set<String> = emptySet(),
  val userId: Int = 0,
) {
  fun fingerprint(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest("$enabled\n$userId\n${packages.sorted().joinToString("\n")}".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

  fun includes(packageName: String, uid: Int): Boolean =
    enabled && uid >= 0 && uid / 100_000 == userId &&
      uid % 100_000 >= 10_000 && packageName in packages
}

internal object NavigationBackdropPreferences {
  const val GROUP = "navigation_backdrop_v1"
  private const val ENABLED = "enabled"
  private const val PACKAGES = "packages"
  private const val USER = "user"
  const val MAX_PACKAGES = 64
  private val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")

  fun validPackage(value: String) = value.length <= 240 && packagePattern.matches(value)

  fun read(preferences: SharedPreferences?): NavigationBackdropChoice {
    if (preferences == null) return NavigationBackdropChoice()
    val packages = preferences.getStringSet(PACKAGES, emptySet()).orEmpty().toSet()
    require(packages.size <= MAX_PACKAGES && packages.all(::validPackage))
    val user = preferences.getInt(USER, 0)
    require(user in 0..999)
    return NavigationBackdropChoice(preferences.getBoolean(ENABLED, false), packages, user)
  }

  suspend fun read(service: XposedService) = withContext(Dispatchers.IO) { read(remote(service)) }

  suspend fun save(service: XposedService, choice: NavigationBackdropChoice) = withContext(Dispatchers.IO) {
    write(remote(service), choice)
  }

  fun write(preferences: SharedPreferences, choice: NavigationBackdropChoice) {
    require(choice.packages.size <= MAX_PACKAGES && choice.packages.all(::validPackage))
    require(choice.userId in 0..999)
    // API 102 serializes Set values verbatim into a process without Kotlin.
    check(preferences.edit().putBoolean(ENABLED, choice.enabled)
      .putStringSet(PACKAGES, java.util.HashSet(choice.packages)).putInt(USER, choice.userId).commit()) {
      "Navigation backdrop save is unconfirmed"
    }
  }

  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L)
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    return service.getRemotePreferences(GROUP)
  }
}
