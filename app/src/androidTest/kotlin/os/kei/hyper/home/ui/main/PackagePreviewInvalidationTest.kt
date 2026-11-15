package os.kei.hyper.home.ui.main

import android.os.Process
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.IconPreviewRepository
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.data.PackageRefreshTarget
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.StoredIconPack

@RunWith(AndroidJUnit4::class)
class PackagePreviewInvalidationTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun app() = InstalledIconCapability(
    label = "KeiHome", packageName = context.packageName,
    userId = context.getSystemService(UserManager::class.java)!!
      .getSerialNumberForUser(Process.myUserHandle()),
    personalProfile = true, nightMode = IconCapabilityStatus.UNKNOWN,
    monochrome = IconCapabilityStatus.UNKNOWN,
  )

  @Test fun originalInvalidationPreservesOtherPackagesAndProfiles() = runBlocking {
    val app = app()
    val repository = IconPreviewRepository(context)
    try {
      val before = repository.load(app, 64)
      repository.invalidatePackages(setOf(PackageRefreshTarget("example.unrelated")))
      assertSame(before, repository.load(app, 64))
      repository.invalidatePackages(setOf(PackageRefreshTarget(app.packageName, app.userId + 1)))
      assertSame(before, repository.load(app, 64))
      repository.invalidatePackages(setOf(PackageRefreshTarget(app.packageName, app.userId)))
      assertNotSame(before, repository.load(app, 64))
      assertFalse(before.light!!.isRecycled)
    } finally { repository.clear() }
  }

  @Test fun currentFallbackInvalidatesOnlyItsOwningPackage() = runBlocking {
    val app = app()
    val originals = IconPreviewRepository(context)
    val repository = CurrentIconPreviewRepository(context, { _, _ -> IconPackPreviews() })
    val archive = File.createTempFile("package-preview-", ".zip", context.cacheDir)
    try {
      ZipOutputStream(archive.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry(NativeIconShapeConfig.PATH))
        zip.write(NativeIconShapeConfig.complete(null, NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA)!!)
        zip.closeEntry()
      }
      val record = StoredIconPack("probe", "probe", 0, IconPackFormat.MIUI_ICONS,
        archive, archive, archive.length(), null)
      val pack = IconPackDescriptor("probe", "probe", IconPackFormat.MIUI_ICONS,
        IconPackSource.Imported(record), emptyList())
      val source = IconBrowserSource("probe", pack, emptyMap(), emptySet(), emptySet())
      val before = repository.loadOriginal(source, app, originals)
      repository.invalidatePackages(setOf(PackageRefreshTarget("example.unrelated")))
      assertSame(before, repository.loadOriginal(source, app, originals))
      repository.invalidatePackages(setOf(PackageRefreshTarget(app.packageName, app.userId + 1)))
      assertSame(before, repository.loadOriginal(source, app, originals))
      // Keep the underlying original bitmap so this specifically exercises the current cache's owner.
      repository.invalidatePackages(setOf(PackageRefreshTarget(app.packageName)))
      assertNotSame(before, repository.loadOriginal(source, app, originals))
      assertFalse(before.light!!.isRecycled)
    } finally {
      repository.clear()
      originals.clear()
      archive.delete()
    }
  }
}
