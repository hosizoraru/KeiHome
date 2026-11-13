package os.kei.hyper.home.iconpack.perfect

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

internal enum class PerfectGitProxyMode { SYSTEM, DIRECT, HTTP, SOCKS }

/** Only changes this client's route; it never changes the JVM or device proxy. */
internal data class PerfectGitNetworkOptions(
  val acceleration: String = "",
  val proxyMode: PerfectGitProxyMode = PerfectGitProxyMode.SYSTEM,
  val proxyHost: String = "",
  val proxyPort: Int = 0,
) {
  fun checked(): PerfectGitNetworkOptions {
    val normalized = copy(acceleration = acceleration.trim(), proxyHost = proxyHost.trim().removeSurrounding("[", "]"))
    require(normalized.acceleration.length <= 2048) { "Invalid acceleration address" }
    val target = URI.create(normalized.repositoryUrl())
    require(target.scheme.equals("https", true) && !target.host.isNullOrBlank() &&
      target.rawUserInfo == null && target.rawQuery == null && target.rawFragment == null &&
      (target.port == -1 || target.port in 1..65535)) { "An anonymous HTTPS Git endpoint is required" }
    if (normalized.proxyMode == PerfectGitProxyMode.HTTP || normalized.proxyMode == PerfectGitProxyMode.SOCKS) {
      require(normalized.proxyHost.isNotBlank() && normalized.proxyHost.length <= 253 &&
        normalized.proxyHost.none { it.isWhitespace() || it in "/\\@?#" }) { "Invalid proxy host" }
      val host = if (':' in normalized.proxyHost) "[${normalized.proxyHost}]" else normalized.proxyHost
      val authority = URI.create("https://$host:${normalized.proxyPort}/")
      require(authority.host != null && normalized.proxyPort in 1..65535) { "Invalid proxy port" }
    }
    return normalized
  }

  fun repositoryUrl(): String {
    val endpoint = acceleration.trim().trimEnd('/')
    return when {
      endpoint.isEmpty() -> UPSTREAM
      "{url}" in endpoint -> {
        require(endpoint.indexOf("{url}") == endpoint.lastIndexOf("{url}")) { "Use one URL placeholder" }
        endpoint.replace("{url}", UPSTREAM)
      }
      endpoint.endsWith(".git", ignoreCase = true) -> endpoint
      else -> endpoint.trimEnd('/') + "/" + UPSTREAM
    }
  }

  fun proxy(supplied: Proxy?): Proxy? = when (proxyMode) {
    // Let Android resolve its current per-URL/PAC policy instead of pinning a stale DIRECT result.
    PerfectGitProxyMode.SYSTEM -> supplied?.takeUnless { it.type() == Proxy.Type.DIRECT }
    PerfectGitProxyMode.DIRECT -> Proxy.NO_PROXY
    PerfectGitProxyMode.HTTP, PerfectGitProxyMode.SOCKS -> Proxy(
      if (proxyMode == PerfectGitProxyMode.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
      InetSocketAddress.createUnresolved(proxyHost, proxyPort),
    )
  }

  companion object {
    const val UPSTREAM = "https://github.com/pzcn/Perfect-Icons-Completion-Project.git"
  }
}
