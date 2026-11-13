package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the real Market alias on the HyperOS AVD; exports private fixtures without applying them. */
@RunWith(AndroidJUnit4::class)
class NativeDefaultAliasCompletionDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun png(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return try {
      ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    } finally { bitmap.recycle() }
  }

  private fun launcher(): IconPackComponent {
    val component = android.content.ComponentName("com.xiaomi.market", "com.xiaomi.market.ui.DefaultLauncherIcon")
    val pm = context.packageManager
    // Match the production query from the app UID, rather than front-door CATEGORY_INFO selection.
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
      .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(component.packageName)
    val matches = pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
      .mapNotNull { it.activityInfo }
    assertTrue("Market alias must be visible to targetContext MAIN/LAUNCHER query; got ${matches.map { it.name }}",
      matches.any { it.packageName == component.packageName && it.name == component.className })
    val info = pm.getActivityInfo(component, android.content.pm.PackageManager.ComponentInfoFlags.of(0))
    assertNotNull("Fixture must exercise the real Market launcher alias", info.targetActivity)
    assertEquals(component.packageName, info.packageName)
    assertEquals(component.packageName, info.applicationInfo.packageName)
    assertNotEquals(0, info.applicationInfo.icon)
    assertEquals(info.applicationInfo.icon, info.iconResource)
    return IconPackComponent(component.packageName, component.className)
  }

  private fun fixture(
    authoredAlias: Boolean = false, preserved: Boolean = false, target: IconPackComponent = launcher(),
    verify: (IconPackComponent, Map<String, ByteArray>, IconPackExportResult) -> Unit,
  ) {
    val pkg = target.packageName
    val day = png(Color.RED)
    val night = png(Color.BLUE)
    val original = linkedMapOf(
      "res/drawable-xxhdpi/os.test.unrelated.png" to day,
      "$pkg.png" to day, "nightmode/$pkg.png" to night,
      "res/drawable-xxhdpi/$pkg/0.png" to day,
      "res/drawable-xxhdpi/$pkg/1.png" to night,
      "nightmode/res/drawable-xxhdpi/$pkg/0.png" to night,
    ).apply {
      putAll(NativeDayNightIcon.entries(pkg, day, night))
      if (authoredAlias) put("res/drawable-xxhdpi/${nativeLauncherActivityName(target)}.png", png(Color.MAGENTA))
    }
    val folder = File(context.cacheDir, "native-alias-${System.nanoTime()}").apply { mkdirs() }
    val input = File(folder, "generated-perfect-fixture.zip")
    try {
      ZipOutputStream(input.outputStream()).use { zip ->
        original.forEach { (name, bytes) ->
          zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
      }
      val store = IconPackStore(File(folder, "store"))
      val record = input.inputStream().use { store.import(it, input.name).record }
      val index = IconPackArchive().inspect(record.originalFile, File(folder, "nested.zip"))
      val pack = IconPackDescriptor(record.id, "Generated source fixture", index.format,
        IconPackSource.Imported(record), index.entries)
      // This is the same MIUI native branch used by imported Perfect generated sources.
      val result = IconPackExporter(context, IconPackLimits()).export(pack, listOf(target), false, 24,
        installedOnly = true, preservedPackages = if (preserved) setOf(pkg) else emptySet()) {}
      try { verify(target, original, result) } finally { result.archive.delete() }
    } finally { folder.deleteRecursively() }
  }

  @Test fun realNativeExportCopiesDefaultLauncherDayNightAndFancyWithoutChangingSource() {
    fixture { target, original, result ->
      assertTrue(result.preservesNativeResources)
      val alias = requireNotNull(nativeLauncherActivityName(target))
      ZipFile(result.archive).use { zip ->
        for ((name, bytes) in original) {
          assertArrayEquals(name, bytes, zip.getInputStream(requireNotNull(zip.getEntry(name))).use { it.readBytes() })
          val copied = name.replace(target.packageName, alias)
          assertArrayEquals(copied, bytes, zip.getInputStream(requireNotNull(zip.getEntry(copied))).use { it.readBytes() })
        }
      }
    }
  }

  @Test fun exactAuthoredAliasDoesNotReceivePackageFancyOrNightArtwork() {
    fixture(authoredAlias = true) { target, original, result ->
      val alias = requireNotNull(nativeLauncherActivityName(target))
      ZipFile(result.archive).use { zip ->
        original.forEach { (name, bytes) ->
          assertArrayEquals(name, bytes, zip.getInputStream(requireNotNull(zip.getEntry(name))).use { it.readBytes() })
        }
        assertNull(zip.getEntry("fancy_icons/$alias/manifest.xml"))
        assertNull(zip.getEntry("nightmode/$alias.png"))
        assertNull(zip.getEntry("res/drawable-xxhdpi/$alias/1.png"))
      }
    }
  }

  @Test fun explicitEqualApplicationIconOnInstalledLauncherAliasAlsoCompletes() {
    val target = launcher()
    val info = context.packageManager.getActivityInfo(
      android.content.ComponentName(target.packageName, requireNotNull(target.className)),
      android.content.pm.PackageManager.ComponentInfoFlags.of(0))
    assertNotEquals("Market must retain its explicit nonzero alias icon", 0, info.icon)
    assertEquals(info.applicationInfo.icon, info.icon)
    fixture(target = target) { _, original, result ->
      val alias = requireNotNull(nativeLauncherActivityName(target))
      ZipFile(result.archive).use { zip ->
        original.forEach { (name, bytes) ->
          val copied = name.replace(target.packageName, alias)
          assertArrayEquals(copied, bytes, zip.getInputStream(requireNotNull(zip.getEntry(copied))).use { it.readBytes() })
        }
      }
    }
  }

  @Test fun preservedPackageDoesNotAcquireLauncherClosure() {
    fixture(preserved = true) { target, _, result ->
      ZipFile(result.archive).use { zip ->
        assertFalse(zip.entries().asSequence().any {
          it.name.contains(target.packageName)
        })
      }
    }
  }
}
