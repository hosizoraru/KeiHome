package os.kei.hyper.home.iconpack.perfect

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackExporter
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconPackStore
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackImportResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.RuntimeStatusProtocol
import os.kei.hyper.home.ui.main.HomeViewModel

/** Opt-in use of retained upstream inputs; no network or user theme changes. */
class PerfectDynamicNightSourceDeviceTest {
  companion object {
    private var preparedFile: File? = null
  }

  @Before fun prepareCurrentModule() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    if (preparedFile?.isFile == true) return@runBlocking
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val repository = PerfectIconRepository(context)
    assertNotNull("Acceptance requires the retained local snapshot; never download it", repository.catalog())
    val names = buildSet {
      context.getSystemService(android.content.pm.LauncherApps::class.java)
        .getActivityList(null, android.os.Process.myUserHandle()).forEach {
          add(it.componentName.packageName); add(it.componentName.className)
          add(it.componentName.flattenToString())
        }
      context.packageManager.getInstalledApplications(
        android.content.pm.PackageManager.ApplicationInfoFlags.of(0)).forEach { add(it.packageName) }
    }
    val options = PerfectIconOptions("HyperOS4", installedOnly = true, dynamic = true, staticNight = true)
    val first = repository.generate(options, names) {}
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val firstHash = digest.digest(first.file.readBytes())
    val repeated = repository.generate(options, names) {}
    assertEquals("Repeated generation reuses its publication", first.file, repeated.file)
    assertArrayEquals(firstHash, digest.digest(repeated.file.readBytes()))
    val root = File(context.cacheDir, "perfect-dynamic-night-probe").apply { mkdirs() }
    first.file.copyTo(File(root, "generated.zip"), overwrite = true)
    File(root, "publication.json").writeText(JSONObject().put("file", first.file.name)
      .put("sha256", firstHash.joinToString("") { "%02x".format(it.toInt() and 255) })
      .put("static", first.staticCount).put("dynamic", first.dynamicCount)
      .put("darkDynamic", first.derivedDynamicCount).put("reused", repeated.reused)
      .put("nightRevision", first.nightRevision).toString(2))
    preparedFile = first.file
  }

  @Test fun generatedModuleMeetsNativeAdmission() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    os.kei.hyper.home.xposed.LocalNativeIconValidator.validate(
      File(context.cacheDir, "perfect-dynamic-night-probe/generated.zip"))
  }

  @Test fun recordsRuntimeIdentityForOptInAcceptance() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    XposedServiceHelper.registerListener(FrameworkConnection)
    val service = withTimeoutOrNull(10_000) { FrameworkConnection.service.filterNotNull().first() }
    val report = service?.let { RuntimeStatusProtocol.query(it, includeIconTheme = true) }
    val identity = JSONObject()
      .put("installedVersion", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
      .put("runtimeVersion", report?.moduleVersion ?: -1)
      .put("scope", service?.scope?.toString() ?: "unavailable")
    File(context.cacheDir, "perfect-dynamic-night-probe/runtime-identity.json").writeText(identity.toString(2))
    assertEquals("Current runtime must answer before import acceptance",
      identity.getLong("installedVersion"), identity.getLong("runtimeVersion"))
    val store = ViewModelStore()
    try {
      val model = withContext(Dispatchers.Main) {
        ViewModelProvider(object : ViewModelStoreOwner { override val viewModelStore = store },
          ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as android.app.Application))
          .get(HomeViewModel::class.java).also { it.refresh(3) }
      }
      delay(12_000)
      val packs = model.iconPacks.value
      identity.put("framework", model.framework.value.toString())
        .put("runtime", model.runtime.value.toString())
        .put("preparing", packs.preparing).put("importing", packs.importing)
        .put("nativeImporting", packs.nativeImporting)
      File(context.cacheDir, "perfect-dynamic-night-probe/runtime-identity.json").writeText(identity.toString(2))
      if (InstrumentationRegistry.getArguments().getString("perfectDynamicApply") == "true") {
        val importedModule = IconPackRepository(context).importPerfectPublication(
          requireNotNull(preparedFile), "Perfect dynamic night QA.zip")
        var pack = requireNotNull((importedModule as? IconPackImportResult.Success)?.pack) {
          importedModule.toString()
        }
        if (InstrumentationRegistry.getArguments().getString("perfectDynamicProbe") == "true") {
          val probe = File(context.filesDir, "perfect-icons/generated/perfect-qa-music.zip")
          val prefix = "themes/HyperOS4/layer_animating_icons/com.miui.player/"
          ZipFile(File(context.cacheDir, "perfect-dynamic-night-probe/converted.zip")).use { converted ->
            ZipOutputStream(probe.outputStream()).use { output ->
              output.putNextEntry(ZipEntry("transform_config.xml"))
              output.write(HyperOs4IconModule.transform(true, null)); output.closeEntry()
              for (entry in converted.entries().asSequence().filter { !it.isDirectory }) {
                val night = entry.name.startsWith("nightmode/")
                val name = entry.name.removePrefix("nightmode/")
                if (!name.startsWith(prefix)) continue
                val bytes = converted.getInputStream(entry).use { it.readBytes() }
                for (target in listOf("com.android.settings", "com.android.settings.MainSettings")) {
                  val targetPath = (if (night) "nightmode/" else "") +
                    "layer_animating_icons/$target/" + name.removePrefix(prefix)
                  output.putNextEntry(ZipEntry(targetPath)); output.write(bytes); output.closeEntry()
                }
              }
            }
          }
          val imported = IconPackRepository(context).importPerfectPublication(probe, "KeiHome Music night QA.zip")
          pack = requireNotNull((imported as? IconPackImportResult.Success)?.pack) { imported.toString() }
        }
        val previous = model.iconPacks.value.nativeResult?.token
        withContext(Dispatchers.Main) { model.importNativeIconPack(pack) }
        val completed = withTimeout(180_000) {
          model.iconPacks.first { state ->
            state.nativeResult?.let { it.token != previous && !it.pending } == true
          }
        }.nativeResult!!
        identity.put("importResult", JSONObject(completed.encode()))
        File(context.cacheDir, "perfect-dynamic-night-probe/runtime-identity.json").writeText(identity.toString(2))
        assertTrue(completed.encode(), completed.imported && completed.convertedReady == true)
      }
    } finally { withContext(Dispatchers.Main) { store.clear() } }
  }

  @Test fun generatedLayeredNightExportsWithoutBeingFlattenedAsMonet() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "perfect-dynamic-night-probe")
    val source = File(root, "generated.zip")
    assertTrue(source.isFile)
    val work = File(root, "export-regression").apply { deleteRecursively(); mkdirs() }
    try {
      val record = source.inputStream().use { IconPackStore(File(work, "store")).import(it, "layered.zip").record }
      val index = IconPackArchive().inspect(record.originalFile, File(work, "inspect"))
      val pack = IconPackDescriptor(record.id, "Layered night", record.format, IconPackSource.Imported(record), index.entries)
      val targets = index.entries.mapNotNull { it.nativeName }.map { IconPackComponent(it) }
      val output = IconPackExporter(context, IconPackLimits()).export(pack, targets, true, 192) {}
      try {
        ZipFile(source).use { original ->
          ZipFile(output.archive).use { exported ->
            val groups = original.entries().asSequence().filter {
              it.name.startsWith("layer_animating_icons/") && "/keihome_night/" in it.name
            }.map { it.name.split('/')[1] }.toSet()
            assertTrue(groups.isNotEmpty())
            val resources = original.entries().asSequence().filter {
              !it.isDirectory && it.name.startsWith("layer_animating_icons/") &&
                it.name.split('/')[1] in groups
            }.toList()
            for (entry in resources) {
              val actual = requireNotNull(exported.getEntry(entry.name))
              assertArrayEquals(entry.name, original.getInputStream(entry).use { it.readBytes() },
                exported.getInputStream(actual).use { it.readBytes() })
            }
            assertTrue("Layered programs keep their animation owners",
              groups.none { it in output.fallbackPaletteNames })
          }
        }
      } finally { output.archive.delete() }
    } finally { work.deleteRecursively() }
  }

  @Test fun retainedBitmapProgramsProduceCompleteNightResourceClosures() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("perfectDynamicSource") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "perfect-dynamic-night-probe")
    val input = File(root, "source.zip")
    assertTrue("Stage the owned research fixture first", input.isFile)
    val results = JSONArray()
    ZipFile(input).use { zip ->
      val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
      val configs = names.filter { it.endsWith("/config.xml") }.sorted()
      assertTrue(configs.isNotEmpty())
      ZipOutputStream(File(root, "converted.zip").outputStream()).use { output ->
        for (config in configs) {
          val prefix = config.removeSuffix("config.xml")
          val bytes = names.filter { it.startsWith(prefix) }.associate {
            it.removePrefix(prefix) to zip.getInputStream(zip.getEntry(it)).use { stream -> stream.readBytes() }
          }
          val blobs = bytes.mapValues { (name, value) ->
            PerfectIconBlob(name, PerfectIconFormat.sha(value), value.size.toLong(), name)
          }
          val record = JSONObject().put("prefix", prefix)
          val group = PerfectDynamicNight.group(blobs, { bytes.getValue(it.path) })
          if (group == null) {
            record.put("status", "original-structure")
          } else {
            val converted = linkedMapOf<String, ByteArray>()
            for (name in listOf(group.background) + group.foreground) {
              val image = PerfectDynamicNightRenderer.render(bytes.getValue(name), name == group.background) {}
                ?: break
              converted[name] = image
            }
            if (converted.size != group.foreground.size + 1) record.put("status", "original-pixels")
            else {
              val live = PerfectDynamicNightSwitch.systemFiles(group, bytes, converted)
              if (live == null) {
                record.put("status", "original-selector")
                results.put(record)
                continue
              }
              record.put("status", "derived").put("textures", converted.size)
              for ((name, image) in live) {
                output.putNextEntry(ZipEntry(prefix + name))
                output.write(image)
                output.closeEntry()
              }
              for ((name, original) in bytes) {
                output.putNextEntry(ZipEntry("nightmode/$prefix$name"))
                output.write(converted[name] ?: original)
                output.closeEntry()
              }
            }
          }
          results.put(record)
        }
      }
    }
    File(root, "results.json").writeText(results.toString(2))
    assertTrue("No real-source dynamic group qualified: $results",
      (0 until results.length()).any { results.getJSONObject(it).getString("status") == "derived" })
    val music = (0 until results.length()).map { results.getJSONObject(it) }
      .single { it.getString("prefix") == "themes/HyperOS4/layer_animating_icons/com.miui.player/" }
    assertEquals("Unmodified upstream Music must support live selection", "derived", music.getString("status"))
  }
}
