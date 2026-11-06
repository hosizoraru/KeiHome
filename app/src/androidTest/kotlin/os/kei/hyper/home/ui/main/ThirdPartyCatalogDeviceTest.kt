package os.kei.hyper.home.ui.main

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyCatalogDeviceTest {
  private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

  @Test fun resolvedDependenciesHaveExactlyOneAttribution() {
    val dependencies = assets.open("third-party-dependencies.tsv").bufferedReader().use {
      parseBundledDependencies(it.readText())
    }
    for (dependency in dependencies) {
      assertEquals(dependency.coordinate, 1, thirdPartyComponents.count { it.contains(dependency) })
    }
    assertTrue(thirdPartyComponents.first { it.key == "miuix" }.dependencies(dependencies)
      .any { it.module == "miuix-ui-android" && it.version.endsWith("-SNAPSHOT") })
  }

  @Test fun everyRouteAndOfflineLicenseIsAvailable() {
    assertEquals(thirdPartyComponents.size, thirdPartyComponents.distinctBy { it.key }.size)
    assertEquals(bundledLicenses.size, bundledLicenses.distinctBy { it.key }.size)
    for (component in thirdPartyComponents) {
      assertEquals(component, thirdPartyComponentForRoute("component/${component.key}"))
      assertTrue(component.source.startsWith("https://"))
      for (key in component.licenseKeys) {
        assertTrue(component.name, bundledLicenseForRoute("license/$key") != null)
      }
    }
    for (license in bundledLicenses) {
      val text = assets.open(license.asset).bufferedReader().use { it.readText() }
      assertTrue(license.asset, text.length > 100)
    }
    assertEquals(AttributionKind.HISTORY, thirdPartyComponents.first { it.key == "lsplt" }.kind)
  }

  @Test fun malformedAndDuplicateMetadataIsRejected() {
    for (text in listOf("", "only-one-field", "group\tname\t", "g\ta\t1\ng\ta\t2\n")) {
      assertTrue(text, runCatching { parseBundledDependencies(text) }.isFailure)
    }
    assertEquals(listOf(BundledDependency("g", "a", "1")), parseBundledDependencies("g\ta\t1\n"))
  }
}
