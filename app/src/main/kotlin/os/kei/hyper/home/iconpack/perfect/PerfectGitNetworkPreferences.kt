package os.kei.hyper.home.iconpack.perfect

import android.content.Context

internal class PerfectGitNetworkPreferences(context: Context) {
  private val store = context.applicationContext.getSharedPreferences("perfect_git_connection", Context.MODE_PRIVATE)

  fun read(): PerfectGitNetworkOptions = runCatching {
    PerfectGitNetworkOptions(
      acceleration = store.getString("acceleration", "").orEmpty(),
      proxyMode = PerfectGitProxyMode.valueOf(store.getString("proxyMode", "SYSTEM").orEmpty()),
      proxyHost = store.getString("proxyHost", "").orEmpty(),
      proxyPort = store.getInt("proxyPort", 0),
    ).checked()
  }.getOrDefault(PerfectGitNetworkOptions())

  fun write(options: PerfectGitNetworkOptions) {
    val checked = options.checked()
    check(store.edit().putString("acceleration", checked.acceleration)
      .putString("proxyMode", checked.proxyMode.name).putString("proxyHost", checked.proxyHost)
      .putInt("proxyPort", checked.proxyPort).commit()) { "Cannot save Git connection settings" }
  }
}
