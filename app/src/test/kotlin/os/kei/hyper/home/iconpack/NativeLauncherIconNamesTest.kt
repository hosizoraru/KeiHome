package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class NativeLauncherIconNamesTest {
  @Test fun sameClassUnderDifferentApplicationIdsKeepsDistinctNativeOwners() {
    val name = "com.example.Main"
    assertEquals(name, nativeLauncherActivityName(IconPackComponent("com.example", name)))
    assertEquals("com.example.debug#$name",
      nativeLauncherActivityName(IconPackComponent("com.example.debug", name)))
    assertEquals("com.example.capture#$name",
      nativeLauncherActivityName(IconPackComponent("com.example.capture", name)))
  }

  @Test fun relativeAliasAndNativeRawPrefixRuleArePreserved() {
    assertEquals("com.example.Switcher",
      nativeLauncherActivityName(IconPackComponent("com.example", ".Switcher")))
    assertEquals("com.examples.Main",
      nativeLauncherActivityName(IconPackComponent("com.example", "com.examples.Main")))
    assertNull(nativeLauncherActivityName(IconPackComponent("com.example", null)))
  }

  @Test fun canonicalLookupPrecedesCompatibilityClassAndPackage() {
    assertEquals(listOf("com.example.debug#com.example.Main", "com.example.Main", "com.example.debug"),
      nativeLauncherIconNames(IconPackComponent("com.example.debug", "com.example.Main")))
    assertEquals(listOf("com.example.Main", "com.example"),
      nativeLauncherIconNames(IconPackComponent("com.example", "com.example.Main")))
  }

  @Test fun qualifiedFilenamesDoNotRelaxJavaNamesOrZipPathSafety() {
    assertTrue(validNativeIconName("com.example.debug#com.example.Main"))
    assertTrue(validNativeIconName("com.example.Main"))
    assertFalse(validDottedName("com.example.debug#com.example.Main"))
    listOf("#com.example.Main", "com.example#", "a.b#c.d#e.f",
      "a.b#../c.d", "a.b#/c.d", "/a.b#c.d", "a.b#c\\d",
      "a.b#c\u0000d").forEach { assertFalse(it, validNativeIconName(it)) }
  }
}
