package os.kei.hyper.home.xposed

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActiveIconThemeTest {
  @get:Rule val temporary = TemporaryFolder()
  private val active get() = File(temporary.root, "icons")
  private val stock get() = File(temporary.root, "stock-icons")
  private val mod get() = File(temporary.root, "mod-icons")
  private fun reader(maxEntries: Int = 16_384) = ActiveIconTheme(active, stock, mod, maxEntries)

  @Test fun indexesStaticVariantsAndBothDynamicFormatsWithoutPrefixGuessing() {
    archive(active, mapOf(
      "res/drawable/com.sample.day.png" to png(),
      "res/drawable-night-xxhdpi/com.sample.night.png" to png(),
      "fancy_icons/com.sample.clock/manifest.xml" to descriptor(),
      "animating_icons/com.sample.motion/fancy/manifest.xml" to descriptor(),
      "res/drawable-xhdpi/com.sample.unknown.Activity.png" to png(),
      "res/drawable-xhdpi/com.sample.other.extra.png" to png(),
      "res/drawable-xhdpi/icon_mask.png" to png(),
    ))
    val requested = setOf("com.sample.day", "com.sample.night", "com.sample.clock", "com.sample.motion",
      "com.sample.unknown", "com.sample.other", "com.sample.missing")
    val result = reader().snapshot(requested)
    assertTrue(result.complete)
    assertEquals(setOf("com.sample.day", "com.sample.night", "com.sample.clock", "com.sample.motion"),
      result.coveredPackages)
  }

  @Test fun mapsOnlySuppliedComponentsToTheirOwningPackage() {
    archive(active, mapOf(
      "res/drawable-xhdpi/com.sample.app.Main.png" to png(),
      "fancy_icons/com.vendor.external.Clock/manifest.xml" to descriptor(),
    ))
    val index = reader()
    val packages = setOf("com.sample.app", "com.sample.clock", "com.sample")
    assertTrue(index.snapshot(packages).coveredPackages.isEmpty())
    assertEquals(setOf("com.sample.app", "com.sample.clock"), index.snapshot(packages, mapOf(
      "com.sample.app" to setOf(".Main"),
      "com.sample.clock" to setOf("com.vendor.external.Clock"),
    )).coveredPackages)
    assertTrue(index.snapshot(setOf("com.sample.unrelated")).coveredPackages.isEmpty())
  }

  @Test fun classicLayeredIconsRequireADeclaredLayerAndItsDescriptor() {
    val config = "<Config><Variables><Variable name='layer0' value='0' type='string'/></Variables></Config>"
      .toByteArray()
    archive(active, mapOf(
      "layer_animating_icons/com.sample.classic/config.xml" to config,
      "layer_animating_icons/com.sample.classic/0/fancy/manifest.xml" to descriptor(),
      "layer_animating_icons/com.sample.unlisted/config.xml" to config,
      "layer_animating_icons/com.sample.unlisted/1/fancy/manifest.xml" to descriptor(),
      "layer_animating_icons/com.sample.orphan/0/fancy/manifest.xml" to descriptor(),
      "layer_animating_icons/com.sample.empty/config.xml" to "<Config/>".toByteArray(),
      "layer_animating_icons/com.sample.empty/0/fancy/manifest.xml" to descriptor(),
      "layer_animating_icons/com.sample.quiet/config.xml" to config,
      "layer_animating_icons/com.sample.quiet/quiet/quietImage.png" to png(),
    ))
    val result = reader().snapshot(setOf("com.sample.classic", "com.sample.unlisted", "com.sample.orphan",
      "com.sample.empty", "com.sample.quiet"))
    assertTrue(result.complete)
    assertEquals(setOf("com.sample.classic"), result.coveredPackages)
  }

  @Test fun stockAndModDoNotOverrideNativeSourceGates() {
    archive(active, mapOf("res/drawable/com.sample.active.png" to png()))
    archive(stock, mapOf("res/drawable/com.sample.stock.png" to png()))
    assertTrue(mod.mkdir())
    File(mod, "com.sample.mod.png").writeBytes(png())
    val packages = setOf("com.sample.active", "com.sample.stock", "com.sample.mod")
    val index = reader()
    assertEquals(setOf("com.sample.active"), index.snapshot(packages).coveredPackages)
    assertEquals(packages, index.snapshot(packages, allowStockFallback = true, modEnabled = true).coveredPackages)
    assertTrue(active.delete())
    assertEquals(setOf("com.sample.stock"), index.snapshot(packages).coveredPackages)
  }

  @Test fun missingSourcesAreCompleteButCorruptActivePackCannotBorrowStockCoverage() {
    val index = reader()
    assertTrue(index.snapshot(setOf("com.sample.app")).complete)
    archive(stock, mapOf("res/drawable/com.sample.app.png" to png()))
    active.writeText("not a zip")
    val result = index.snapshot(setOf("com.sample.app"), allowStockFallback = true)
    assertFalse(result.complete)
    assertTrue(result.coveredPackages.isEmpty())
  }

  @Test fun ignoresNonResourcesInvalidDescriptorsAndEmptyOrUnrecognizedImages() {
    archive(active, mapOf(
      "../res/drawable/com.sample.bad.png" to png(),
      "res/drawable/com.sample.nested/unused.png" to png(),
      "previews/com.sample.preview.png" to png(),
      "res/drawable/com.sample.empty.png" to byteArrayOf(),
      "res/drawable/com.sample.notpng.png" to "not an image".toByteArray(),
      "fancy_icons/com.sample.emptyxml/manifest.xml" to "<Icon/>".toByteArray(),
      "fancy_icons/com.sample.broken/manifest.xml" to "<Icon><Image>".toByteArray(),
      "fancy_icons/com.sample.orphan/den480/bg.png" to png(),
      "animating_icons/com.sample.orphan/quiet/quietImage.png" to png(),
      "fancy_icons/com.sample.entity/manifest.xml" to
        "<!DOCTYPE Icon [<!ENTITY x SYSTEM 'file:///unread'>]><Icon><Image>&x;</Image></Icon>".toByteArray(),
    ))
    val result = reader().snapshot(setOf("com.sample.bad", "com.sample.nested", "com.sample.preview",
      "com.sample.empty", "com.sample.notpng", "com.sample.emptyxml", "com.sample.broken",
      "com.sample.orphan", "com.sample.entity"))
    assertTrue(result.complete)
    assertTrue(result.coveredPackages.isEmpty())
  }

  @Test fun entryAndDescriptorLimitsDiscardPartialCoverage() {
    archive(active, mapOf(
      "res/drawable/com.sample.app.png" to png(),
      "res/drawable/com.sample.other.png" to png(),
    ))
    assertFalse(reader(maxEntries = 1).snapshot(setOf("com.sample.app")).complete)
    archive(active, mapOf(
      "res/drawable/com.sample.app.png" to png(),
      "fancy_icons/com.sample.clock/manifest.xml" to ByteArray(65_537) { 32 },
    ))
    val result = reader().snapshot(setOf("com.sample.app"))
    assertFalse(result.complete)
    assertTrue(result.coveredPackages.isEmpty())
  }

  @Test fun sourceStampChangeAndRemovalReplaceOldCoverage() {
    archive(active, mapOf("res/drawable/com.sample.old.png" to png()))
    val index = reader()
    val packages = setOf("com.sample.old", "com.sample.new")
    val before = index.snapshot(packages)
    archive(active, mapOf("res/drawable/com.sample.new.png" to png()))
    Files.setLastModifiedTime(active.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 10_000))
    val after = index.snapshot(packages)
    assertNotEquals(before.signature, after.signature)
    assertEquals(setOf("com.sample.new"), after.coveredPackages)
    assertTrue(active.delete())
    assertTrue(index.snapshot(packages).coveredPackages.isEmpty())
  }

  @Test fun invalidateReindexesEvenWhenArchiveStampIsUnchanged() {
    archive(active, mapOf("res/drawable/com.sample.one.png" to png()))
    val index = reader()
    val packages = setOf("com.sample.one", "com.sample.two")
    val before = index.snapshot(packages)
    val modified = Files.getLastModifiedTime(active.toPath())
    val size = active.length()
    archive(active, mapOf("res/drawable/com.sample.two.png" to png()))
    assertEquals(size, active.length())
    Files.setLastModifiedTime(active.toPath(), modified)
    assertEquals(before, index.snapshot(packages))
    index.invalidate()
    val after = index.snapshot(packages)
    assertNotEquals(before.signature, after.signature)
    assertEquals(setOf("com.sample.two"), after.coveredPackages)
  }

  @Test fun atomicReplacementWithSameSizeAndTimeChangesFileIdentity() {
    archive(active, mapOf("res/drawable/com.sample.one.png" to png()))
    val index = reader()
    val packages = setOf("com.sample.one", "com.sample.two")
    val before = index.snapshot(packages)
    val replacement = temporary.newFile("replacement")
    archive(replacement, mapOf("res/drawable/com.sample.two.png" to png()))
    assertEquals(active.length(), replacement.length())
    Files.setLastModifiedTime(replacement.toPath(), Files.getLastModifiedTime(active.toPath()))
    Files.move(replacement.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING)
    val after = index.snapshot(packages)
    assertNotEquals(before.signature, after.signature)
    assertEquals(setOf("com.sample.two"), after.coveredPackages)
  }

  @Test fun modDirectoryIgnoresSymlinksAndHonorsExplicitNestedInvalidation() {
    assertTrue(mod.mkdir())
    val outside = temporary.newFile("outside.png").apply { writeBytes(png()) }
    Files.createSymbolicLink(File(mod, "com.sample.link.png").toPath(), outside.toPath())
    val nested = File(mod, "res/drawable-night").apply { mkdirs() }
    val icon = File(nested, "com.sample.app.png").apply { writeBytes(png()) }
    val index = reader()
    val packages = setOf("com.sample.app", "com.sample.link")
    assertEquals(setOf("com.sample.app"), index.snapshot(packages, modEnabled = true).coveredPackages)
    icon.writeText("invalid")
    index.invalidate()
    assertTrue(index.snapshot(packages, modEnabled = true).coveredPackages.isEmpty())
  }

  private fun archive(file: File, entries: Map<String, ByteArray>) {
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((name, bytes) in entries) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0 })
        zip.write(bytes)
        zip.closeEntry()
      }
    }
  }

  private fun descriptor() = "<Icon><Image src='bg.png'/></Icon>".toByteArray()

  // Only header recognition is in scope; pixel decoding remains the launcher's responsibility.
  private fun png(): ByteArray = byteArrayOf(
    137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
    0, 0, 0, 1, 0, 0, 0, 1,
  )
}
