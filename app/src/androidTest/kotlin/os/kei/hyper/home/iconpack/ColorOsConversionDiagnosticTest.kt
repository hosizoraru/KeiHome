package os.kei.hyper.home.iconpack

import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.xposed.ConvertedIconArchive

/** Explicit local-fixture diagnostic. It never applies a theme or changes system settings. */
@RunWith(AndroidJUnit4::class)
class ColorOsConversionDiagnosticTest {
  @Test
  fun inspectImportedStyleAndNativePair() = runBlocking {
    val args = InstrumentationRegistry.getArguments()
    assumeTrue(args.getString("class") ==
      "os.kei.hyper.home.iconpack.ColorOsConversionDiagnosticTest#inspectImportedStyleAndNativePair")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val fixtureName = requireNotNull(args.getString("fixture"))
    require(fixtureName.matches(Regex("[a-z0-9_-]+\\.zip")))
    val fixture = File(context.cacheDir, "coloros-qa/$fixtureName")
    val store = IconPackStore(File(context.noBackupFilesDir, "icon-packs"))
    val record = fixture.inputStream().use { store.import(it, fixtureName).record }
    val repository = IconPackRepository(context)
    val pack = repository.listImported().packs.single { it.id == record.id }
    assertEquals(IconPackFormat.CLASSIC_ZIP, pack.format)
    assertTrue(pack.displayName.startsWith("ColorOS"))
    assertEquals("OPPO / ColorOS", pack.description.author)
    assertNotNull("Classic fixture must include its original-icon treatment profile", pack.colorOsProfile)
    val targets = context.getSystemService(LauncherApps::class.java)
      .getActivityList(null, Process.myUserHandle()).map {
        IconPackComponent(it.componentName.packageName, it.componentName.className)
      }
    val previews = IconPackPreviewRepository(context)
    val exported = repository.exportNativeIcons(pack, targets, darkMode = false)
    try {
      val exact = pack.entries.mapNotNull { it.component }.toSet()
      val redraws = targets.count { it in exact || IconPackComponent(it.packageName) in exact }
      assertTrue("Original-icon treatment must cover more than ART+ redraw mappings",
        exported.exportedTargets.size > redraws)
      instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "Original-icon export: ${exported.exportedTargets.size}/${targets.size}, " +
          "Monet masks=${exported.monochrome.size}, skipped=${exported.skippedTargets}\n")
      })
    } finally { exported.archive.delete() }
    val settings = pack.entries.first { it.component?.packageName == "com.android.settings" }
    val pair = previews.load(pack, settings)
    assertNotNull(pair.light)
    assertNotNull(pair.dark)
    assertFalse("Classic source must retain different night artwork", pair.light!!.sameAs(pair.dark))
    val handoff = IconPackHandoffs.prepare(context, repository, previews, pack, targets, IconPackAppearance.SYSTEM)
    val export = File(context.cacheDir, "coloros-qa/last-export.mtz")
    handoff.file.copyTo(export, overwrite = true)
    try {
      ConvertedIconArchive.inspect(handoff.file, "CONVERTED_CLASSIC_ZIP", context.cacheDir)
      ZipFile(handoff.file).use { mtz ->
        val inner = File(context.cacheDir, "coloros-qa/last-icons.zip")
        mtz.getInputStream(mtz.getEntry("icons")).use { input -> inner.outputStream().use { input.copyTo(it) } }
        ZipFile(inner).use { icons ->
          val name = "com.android.settings.MainSettings"
          assertNotNull(icons.getEntry("res/drawable-xxhdpi/$name.png"))
          assertNotNull(icons.getEntry("nightmode/res/drawable-xxhdpi/$name.png"))
          val manifest = icons.getInputStream(icons.getEntry("fancy_icons/$name/manifest.xml"))
            .bufferedReader().use { it.readText() }
          assertTrue(manifest.contains("#__darkmode"))
        }
      }
      instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "ColorOS style=${pack.displayName} entries=${pack.entries.size} source=${pack.id} export=$export\n")
      })
    } finally { handoff.file.delete() }
  }
}
