package os.kei.hyper.home.iconpack.perfect

import java.net.InetSocketAddress
import java.net.Proxy
import org.junit.Assert.*
import org.junit.Test

class PerfectGitNetworkOptionsTest {
  @Test fun blankAccelerationKeepsTheAnonymousUpstream() {
    assertEquals(PerfectGitNetworkOptions.UPSTREAM, PerfectGitNetworkOptions().checked().repositoryUrl())
  }

  @Test fun accelerationCanBeAPrefixTemplateOrExplicitHttpsRepository() {
    val upstream = PerfectGitNetworkOptions.UPSTREAM
    for (address in listOf("https://mirror.example", "https://mirror.example/",
      "https://mirror.example/{url}", "https://mirror.example/$upstream")) {
      assertEquals("https://mirror.example/$upstream", PerfectGitNetworkOptions(address).checked().repositoryUrl())
    }
    for (address in listOf("https://mirror.example/repo.git", "https://mirror.example/repo.git/")) {
      assertEquals("https://mirror.example/repo.git",
        PerfectGitNetworkOptions(address).checked().repositoryUrl())
    }
  }

  @Test fun invalidEndpointsCannotDowngradeTlsOrCarrySecrets() {
    for (address in listOf("http://mirror.example/", "file:///repo.git", "https://u:p@mirror.example/",
      "https://mirror.example/?token=secret", "https://mirror.example/#fragment",
      "https://mirror.example/{url}/{url}", "https://mirror.example:0/", "https://mirror.example:65536/")) {
      assertThrows(address, IllegalArgumentException::class.java) { PerfectGitNetworkOptions(address).checked() }
    }
  }

  @Test fun systemPolicyDefersDirectResultsToAndroidWithoutReplacingAnExplicitProxy() {
    val system = PerfectGitNetworkOptions()
    assertNull(system.proxy(null))
    assertNull(system.proxy(Proxy.NO_PROXY))
    val supplied = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))
    assertSame(supplied, system.proxy(supplied))
  }

  @Test fun explicitDirectAndLocalProxyModesRemainClientScoped() {
    val before = java.net.ProxySelector.getDefault()
    assertSame(Proxy.NO_PROXY, PerfectGitNetworkOptions(proxyMode = PerfectGitProxyMode.DIRECT).proxy(null))
    for ((mode, type) in listOf(PerfectGitProxyMode.HTTP to Proxy.Type.HTTP, PerfectGitProxyMode.SOCKS to Proxy.Type.SOCKS)) {
      val options = PerfectGitNetworkOptions(proxyMode = mode, proxyHost = "127.0.0.1", proxyPort = 7890).checked()
      val proxy = options.proxy(null)!!
      assertEquals(type, proxy.type())
      val address = proxy.address() as InetSocketAddress
      assertTrue(address.isUnresolved)
      assertEquals(7890, address.port)
    }
    assertSame(before, java.net.ProxySelector.getDefault())
  }

  @Test fun proxyValidationHandlesIpv6AndRejectsUrlsOrMissingPorts() {
    val ipv6 = PerfectGitNetworkOptions(proxyMode = PerfectGitProxyMode.SOCKS, proxyHost = "[::1]", proxyPort = 1080).checked()
    assertEquals("::1", ipv6.proxyHost)
    for (host in listOf("", "https://proxy.example", "user:pass@proxy.example", "proxy example")) {
      assertThrows(IllegalArgumentException::class.java) {
        PerfectGitNetworkOptions(proxyMode = PerfectGitProxyMode.HTTP, proxyHost = host, proxyPort = 7890).checked()
      }
    }
    for (port in listOf(-1, 0, 65536)) assertThrows(IllegalArgumentException::class.java) {
      PerfectGitNetworkOptions(proxyMode = PerfectGitProxyMode.HTTP, proxyHost = "localhost", proxyPort = port).checked()
    }
  }
}
