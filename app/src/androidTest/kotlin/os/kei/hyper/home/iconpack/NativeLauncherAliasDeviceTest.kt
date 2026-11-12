package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.iconPackTargets
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NativeLauncherAliasDeviceTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test fun sharedClassAcrossChannelsPreservesDifferentArtworkAndNativeFilenameOwnership() = runBlocking {
    val folder = File(context.cacheDir, "native-alias-regression").apply { mkdirs() }
    try {
      val targets = listOf("os.kei", "os.kei.debug", "os.kei.capture")
        .map { IconPackComponent(it, "os.kei.LauncherAndroidDesigns") }
      val colors = listOf(Color.RED, Color.BLUE, Color.GREEN)
      val input = File(folder, "fixture.zip")
      ZipOutputStream(input.outputStream()).use { zip ->
        colors.forEachIndexed { index, color ->
          val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
          bitmap.eraseColor(color)
          val bytes = try { ByteArrayOutputStream().also {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
          }.toByteArray() } finally { bitmap.recycle() }
          zip.putNextEntry(ZipEntry("res/drawable/icon$index.png"))
          zip.write(bytes)
          zip.closeEntry()
        }
      }
      val entries = targets.mapIndexed { index, component ->
        IconPackEntry("icon$index", component = component,
          light = IconPackArtwork.Archive(listOf(IconArchiveVariant("res/drawable/icon$index.png",
            requireNotNull(IconResourceQualifiers.parse("drawable"))))))
      }
      val pack = IconPackDescriptor("alias-fixture", "Alias fixture", IconPackFormat.APK,
        IconPackSource.Generated(input, "a".repeat(64), 1), entries)
      for (complete in listOf(true, false)) {
        val result = IconPackExporter(context, IconPackLimits()).export(pack, targets, false, 32,
          maskPolicy = IconMaskPolicy(complete), check = {})
        try {
          assertEquals(targets.toSet(), result.exportedTargets.toSet())
          assertTrue(result.skippedTargets.isEmpty())
          ZipFile(result.archive).use { zip ->
            targets.forEachIndexed { index, component ->
              val path = "res/drawable-xxhdpi/${nativeLauncherActivityName(component)}.png"
              val bytes = zip.getInputStream(requireNotNull(zip.getEntry(path))).use { it.readBytes() }
              val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
              try {
                assertEquals(colors[index], bitmap.getPixel(16, 16))
                assertEquals(if (complete) 0 else 255, Color.alpha(bitmap.getPixel(0, 0)))
              } finally { bitmap.recycle() }
            }
            assertNull(zip.getEntry("res/drawable-xxhdpi/os.kei.debug_os.kei.LauncherAndroidDesigns.png"))
          }
        } finally { result.archive.delete() }
      }
    } finally { folder.deleteRecursively() }
  }

  @Test fun styleInventoryUsesCurrentLauncherEntriesWithoutDuplicatingNativeNames() {
    val targets = iconPackTargets(context)
    val names = targets.filter { it.packageName in setOf("os.kei", "os.kei.debug", "os.kei.capture") }
      .mapNotNull(::nativeLauncherActivityName)
    assertEquals(names.size, names.toSet().size)
    val visible = context.getSystemService(android.content.pm.LauncherApps::class.java)
      .getActivityList(null, android.os.Process.myUserHandle())
      .map { IconPackComponent(it.componentName.packageName, it.componentName.className) }.toSet()
    assertEquals(visible, targets.filter { it.className != null }.toSet())
  }
}
