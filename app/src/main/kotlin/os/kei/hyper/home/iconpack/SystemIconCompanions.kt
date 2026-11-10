package os.kei.hyper.home.iconpack

/** Split system UIs may be installed as data apps while their service stays a system app. */
internal object SystemIconCompanions {
  private val hosts = mapOf(
    "com.android.providers.downloads.ui" to "com.android.providers.downloads",
  )

  fun matches(packageName: String, isSystemHost: (String) -> Boolean,
    sameSigner: (String, String) -> Boolean): Boolean {
    val host = hosts[packageName] ?: return false
    return isSystemHost(host) && sameSigner(packageName, host)
  }
}
