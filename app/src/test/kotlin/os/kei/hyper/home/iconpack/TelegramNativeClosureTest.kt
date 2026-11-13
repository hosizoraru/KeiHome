package os.kei.hyper.home.iconpack

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class TelegramNativeClosureTest {
  private val store = "org.telegram.messenger"
  private val web = "org.telegram.messenger.web"
  private val beta = "org.telegram.messenger.beta"
  private val target = IconPackComponent(beta, "$store.DefaultIcon")
  private val alias = "$beta#$store.DefaultIcon"

  private fun artwork(name: String, night: Boolean = false) = IconPackArtwork.Archive(listOf(
    IconArchiveVariant("${if (night) "nightmode/" else ""}res/drawable-xxhdpi/$name.png",
      requireNotNull(IconResourceQualifiers.parse("drawable-xxhdpi"))),
  ))

  private fun entry(name: String, day: Boolean = true, night: Boolean = true) =
    IconPackEntry("native:$name", nativeName = name,
      light = if (day) artwork(name) else null, dark = if (night) artwork(name, night = true) else null)

  private fun resources(name: String): Map<String, ByteArray> = linkedMapOf(
    "$name.png" to "root-day",
    "nightmode/$name.png" to "root-night",
    "res/drawable-xxhdpi/$name/0.png" to "day",
    "res/drawable-xxhdpi/$name/1.png" to "night",
    "fancy_icons/$name/manifest.xml" to "<Icon><Image srcExp=\"ifelse(#__darkmode,'night.png','day.png')\"/></Icon>",
    "fancy_icons/$name/day.png" to "fancy-day",
    "fancy_icons/$name/night.png" to "fancy-night",
    "animating_icons/$name/0.png" to "animating",
    "layer_animating_icons/$name/config.xml" to "<Config><Variables><Variable name=\"layer0\" value=\"0\"/><Variable name=\"layer1\" value=\"1\"/></Variables></Config>",
    "layer_animating_icons/$name/0/fancy/bg.png" to "bg-day",
    "layer_animating_icons/$name/0/fancy/keihome_night/bg.png" to "bg-night",
    "layer_animating_icons/$name/1/quiet/quietImage.png" to "365-quiet",
    "layer_animating_icons/$name/1/fancy/icon.png" to "382-foreground",
    "layer_animating_icons/$name/1/fancy/manifest.xml" to "<Icon><ExternalCommands><Trigger action=\"back_home_finish\"/></ExternalCommands><Image src=\"icon.png\"/></Icon>",
    "nightmode/layer_animating_icons/$name/config.xml" to "night-config",
    "nightmode/layer_animating_icons/$name/1/fancy/icon.png" to "night-foreground",
    "nightmode/layer_animating_icons/$name/1/fancy/manifest.xml" to "night-program",
  ).mapValues { it.value.toByteArray() }

  private fun archive(resources: Map<String, ByteArray>, block: (File) -> Unit) {
    val folder = Files.createTempDirectory("telegram-native-closure").toFile()
    try {
      val file = File(folder, "icons.zip")
      ZipOutputStream(file.outputStream()).use { zip ->
        resources.forEach { (name, bytes) ->
          zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
      }
      block(file)
    } finally { folder.deleteRecursively() }
  }

  @Test fun defaultAliasGetsBothCompleteNativeNamespacesWithoutBakingOrRewritingPrograms() {
    val source = resources(store)
    val family = TelegramChannelIcons(listOf(entry(store)))
    archive(source) { file ->
      val before = file.readBytes()
      val additions = family.nativeAdditions(file, listOf(target, IconPackComponent(beta), target),
        { null }, IconPackLimits()) {}
      assertEquals(setOf(beta, alias), additions.names)
      assertEquals(source.size * 2, additions.resources.size)
      for (name in additions.names) for ((path, bytes) in source) {
        assertArrayEquals(path, bytes, additions.resources[path.replace(store, name)])
      }
      assertArrayEquals(before, file.readBytes())
      assertFalse(additions.resources.containsKey("res/drawable-xxhdpi/$beta.png"))
    }
  }

  @Test fun sourceFamilyPriorityAndCompleteNightPreferenceRemainUnchanged() {
    val family = TelegramChannelIcons(listOf(entry(store, night = false), entry(web)))
    val source = resources(store) + resources(web)
    val copy = requireNotNull(family.nativeCopy(target, null, source.keys))
    assertTrue(copy.paths.values.all { it.contains(web) })
  }

  @Test fun differentDayAndNightDonorsRetainExistingModeSpecificFallback() {
    val family = TelegramChannelIcons(listOf(entry(store, day = false), entry(web)))
    assertEquals(web, family.donor(target, false, null)?.nativeName)
    assertEquals(store, family.donor(target, true, null)?.nativeName)
    assertNull(family.nativeCopy(target, null, (resources(store) + resources(web)).keys))
  }

  @Test fun authoredTargetAndCustomAliasNeverBorrowNativeTopology() {
    val family = TelegramChannelIcons(listOf(entry(store)))
    assertNull(family.nativeCopy(target, entry(beta, night = false), resources(store).keys))
    assertNull(family.nativeCopy(IconPackComponent(beta, "$store.BlueIcon"), null, resources(store).keys))
    assertNull(TelegramChannelIcons(listOf(entry(store)), false).nativeCopy(target, null, resources(store).keys))
  }

  @Test fun evenPartialAuthoredAliasTreesAreProtectedWithoutOverwritingTheirResources() {
    val source = resources(store)
    val family = TelegramChannelIcons(listOf(entry(store)))
    val authored = "nightmode/layer_animating_icons/$alias/1/fancy/manifest.xml"
    archive(source + (authored to "custom-night".toByteArray())) { file ->
      val result = family.nativeAdditions(file, listOf(target), { null }, IconPackLimits()) {}
      assertTrue(result.resources.isEmpty())
      assertTrue(alias in result.names)
    }
  }

  @Test fun dayOnlyAndNonNativeDonorsContinueThroughExistingStaticFallback() {
    assertNull(TelegramChannelIcons(listOf(entry(store, night = false)))
      .nativeCopy(target, null, resources(store).keys))
    val drawable = IconPackArtwork.Resource(IconDrawableName("drawable", "telegram"))
    val apk = IconPackEntry("apk", component = IconPackComponent(store), light = drawable, dark = drawable)
    assertNull(TelegramChannelIcons(listOf(apk)).nativeCopy(target, null, resources(store).keys))
  }

  @Test fun byteAndEntryBudgetsOmitWholeClosures() {
    val source = resources(store)
    val family = TelegramChannelIcons(listOf(entry(store)))
    archive(source) { file ->
      val bytes = family.nativeAdditions(file, listOf(target), { null },
        IconPackLimits(maxExpandedBytes = source.values.sumOf { it.size.toLong() })) {}
      assertTrue(bytes.resources.isEmpty())
      val entries = family.nativeAdditions(file, listOf(target), { null },
        IconPackLimits(maxEntries = source.size * 3 - 1)) {}
      assertTrue(entries.resources.isEmpty())
    }
  }

  @Test fun cancellationPropagatesAfterDonorLookupWithoutPublishingPartialResources() {
    val family = TelegramChannelIcons(listOf(entry(store)))
    archive(resources(store)) { file ->
      var queried = false
      assertThrows(CancellationException::class.java) {
        family.nativeAdditions(file, listOf(target), { queried = true; null }, IconPackLimits()) {
          if (queried) throw CancellationException("cancel native closure")
        }
      }
    }
  }
}
