package os.kei.hyper.home.iconpack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreservedIconResourcesTest {
  private val resources = PreservedIconResources(setOf("org.telegram.messenger"), listOf(
    IconPackComponent("org.telegram.messenger", "org.telegram.messenger.DefaultIcon"),
    IconPackComponent("org.telegram.messenger", "org.telegram.ui.LaunchActivity"),
    IconPackComponent("org.telegram.messenger.web", "org.telegram.messenger.DefaultIcon")))

  @Test fun removesExactPackageAndOwnedAliasesAcrossAllNativeTrees() {
    for (name in listOf("org.telegram.messenger", "org.telegram.messenger.DefaultIcon",
        "org.telegram.messenger#org.telegram.ui.LaunchActivity")) {
      for (path in listOf("res/drawable-xxhdpi/$name.png", "res/drawable-xxhdpi/$name/1.png",
          "fancy_icons/$name/manifest.xml", "layer_animating_icons/$name/quiet/quietImage.png",
          "animating_icons/$name/fancy/bg.png")) {
        assertTrue(path, resources.containsPath(path))
        assertTrue(path, resources.containsPath("nightmode/$path"))
      }
    }
  }

  @Test fun keepsOtherChannelsAndGlobalProgramsEvenWhenTheirNamespaceStartsWithThePreservedPackage() {
    for (name in listOf("org.telegram.messenger.web", "org.telegram.messenger.beta",
        "org.telegram.messenger.web#org.telegram.messenger.DefaultIcon", "org.telegram.messenger2",
        "org.thunderdog.challegram")) {
      assertFalse(name, resources.containsName(name))
      assertFalse(name, resources.containsPath("fancy_icons/$name/manifest.xml"))
    }
    for (path in listOf("transform_config.xml", "Config", "keihome-icon-generation.txt",
        "assets/org.telegram.messenger.png", "fancy_icons/org.other/telegram.png")) {
      assertFalse(path, resources.containsPath(path))
    }
  }

  @Test fun customPackageAlsoBlocksDynamicAliasesDuringPairing() {
    val custom = PreservedIconResources(
      setOf("com.taobao.taobao"),
      listOf(IconPackComponent("com.taobao.taobao", "com.taobao.tao.welcome.Welcome")),
    )
    for (path in listOf(
      "fancy_icons/com.taobao.taobao/manifest.xml",
      "fancy_icons/com.taobao.tao.welcome.Welcome/manifest.xml",
      "res/drawable-xxhdpi/com.taobao.taobao.png",
      "nightmode/fancy_icons/com.taobao.tao.welcome.Welcome/day.png",
    )) {
      assertTrue(path, custom.containsPath(path))
    }
    assertFalse(custom.containsPath("fancy_icons/com.taobao.taobao.beta/manifest.xml"))
  }
}
