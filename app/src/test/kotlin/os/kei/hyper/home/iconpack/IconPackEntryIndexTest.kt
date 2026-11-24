package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class IconPackEntryIndexTest {
  private val target = IconPackComponent("com.example.app", "org.shared.Main")
  private fun entry(key: String, component: IconPackComponent? = null, name: String? = null) =
    IconPackEntry(key, component = component, nativeName = name)

  @Test fun exactComponentPrecedesPackageAndNativeAliases() {
    val exact = entry("exact", target)
    val packageEntry = entry("package", IconPackComponent(target.packageName))
    val native = entry("native", name = nativeLauncherActivityName(target))
    assertSame(exact, IconPackEntryIndex(listOf(packageEntry, native, exact)).find(target))
    assertSame(packageEntry, IconPackEntryIndex(listOf(packageEntry, native)).find(target))
  }

  @Test fun qualifiedNativeAliasPrecedesLegacyAndPackageStems() {
    val qualified = entry("qualified", name = nativeLauncherActivityName(target))
    val legacy = entry("legacy", name = target.className)
    val fallback = entry("fallback", name = target.packageName)
    assertSame(qualified, IconPackEntryIndex(listOf(fallback, legacy, qualified)).find(target))
    assertSame(legacy, IconPackEntryIndex(listOf(fallback, legacy)).find(target))
    assertSame(fallback, IconPackEntryIndex(listOf(fallback)).find(target))
  }

  @Test fun neighboringPackagesCannotAcquireAnUnrelatedNativeIcon() {
    assertNull(IconPackEntryIndex(listOf(entry("other", name = "${target.packageName}.beta"))).find(target))
  }

  @Test fun telegramNamesRequireOfficialOrExplicitPackageOwnershipAcrossPacks() {
    for (className in listOf("org.telegram.messenger.DefaultIcon", "org.telegram.ui.LaunchActivity",
      "org.telegram.messenger.PremiumIcon")) {
      val legacy = entry("official", name = className)
      val official = IconPackComponent("org.telegram.messenger", className)
      assertSame(legacy, IconPackEntryIndex(listOf(legacy)).find(official))
      for (packageName in listOf("xyz.nextalone.nnngram", "org.telegram.messenger.fork", "example.client")) {
        val fork = IconPackComponent(packageName, className)
        val owned = entry("own", name = packageName)
        val qualified = entry("qualified", name = "$packageName#$className")
        assertEquals(listOf("$packageName#$className", packageName), nativeLauncherIconNames(fork))
        assertNull(IconPackEntryIndex(listOf(legacy)).find(fork))
        assertSame(owned, IconPackEntryIndex(listOf(legacy, owned)).find(fork))
        assertSame(qualified, IconPackEntryIndex(listOf(legacy, owned, qualified)).find(fork))
      }
    }
  }
}
