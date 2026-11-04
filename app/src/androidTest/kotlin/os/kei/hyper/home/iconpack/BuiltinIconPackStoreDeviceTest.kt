package os.kei.hyper.home.iconpack

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real APK offsets/Android inflater and renderer; no production source or selected theme changes. */
class BuiltinIconPackStoreDeviceTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test fun everyUniqueObjectMatchesItsOriginalDigestWithoutExtractingAnyPack(): Unit = runBlocking(Dispatchers.IO) {
    val started = SystemClock.elapsedRealtime()
    val store = BuiltinIconPackStore(context.assets)
    val catalog = JSONObject(context.assets.open("builtin-icon-store/catalog.json").bufferedReader().use { it.readText() })
    val rows = catalog.getJSONArray("packs")
    val expected = (0 until rows.length()).associate { index ->
      val row = rows.getJSONObject(index)
      row.getString("key") to row.getJSONObject("paths")
    }
    val seen = hashSetOf<String>()
    var bytes = 0L
    val descriptorsBefore = File("/proc/self/fd").list()!!.size
    for (pack in store.list()) {
      val paths = expected.getValue(pack.key)
      for (path in pack.paths) {
        val digest = paths.getString(path)
        if (!seen.add(digest)) continue
        val data = pack.read(path, 96 * 1024 * 1024)
        assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)))
        bytes += data.size
      }
    }
    assertEquals(catalog.getJSONObject("objects").length(), seen.size)
    assertTrue("Read descriptors must close", File("/proc/self/fd").list()!!.size <= descriptorsBefore + 4)
    Log.i("KeiHomeBuiltinTest", "All ${seen.size} original objects / $bytes bytes read in ${SystemClock.elapsedRealtime() - started} ms")
  }

  @Test fun catalogAndConcurrentPreviewsNeedNoPackCopies(): Unit = runBlocking(Dispatchers.IO) {
    val repository = IconPackRepository(context)
    val start = SystemClock.elapsedRealtime()
    val simultaneous = coroutineScope { List(4) { async { repository.listBuiltins() } }.awaitAll() }
    val result = simultaneous.first()
    simultaneous.forEach { assertSame("Concurrent scans share the result", result, it) }
    val cold = SystemClock.elapsedRealtime() - start
    assertTrue(result.failures.toString(), result.failures.isEmpty())
    assertTrue(result.packs.isNotEmpty())
    assertTrue(result.packs.all { it.source is IconPackSource.Builtin && it.colorOsProfile != null })
    assertFalse(result.packs.any { it.builtinKey == "coloros-runtime-fallback" })
    assertNotNull(repository.runtimeFallback())
    val warmStart = SystemClock.elapsedRealtime()
    assertSame(result, repository.listBuiltins())
    val warm = SystemClock.elapsedRealtime() - warmStart
    coroutineScope {
      result.packs.map { pack -> async {
        val entry = pack.entries.first { it.light is IconPackArtwork.Archive &&
          pack.colorOsProfile?.source(requireNotNull(it.component))?.requiresLiveIcon != true }
        for (dark in listOf(false, true)) {
          IconPackRenderer(context).open(pack, dark).use { session ->
            val image = requireNotNull(session.render(entry, 64))
            assertEquals(64, image.width)
            image.recycle()
          }
        }
      } }.awaitAll()
    }
    assertFalse("No eager per-version ZIP copies", File(context.cacheDir, "builtin-icon-packs").exists())
    assertTrue(context.assets.list("builtin-icon-packs").orEmpty().isEmpty())
    Log.i("KeiHomeBuiltinTest", "Catalog ${result.packs.size} profiles: cold=${cold}ms warm=${warm}ms; builtin cache=0")
  }

  @Test fun sharedAndLegacyArchiveRenderingArePixelIdentical(): Unit = runBlocking(Dispatchers.IO) {
    val pack = IconPackRepository(context).listBuiltins().packs.single { it.builtinKey == "coloros-classic-art-on" }
    val builtin = (pack.source as IconPackSource.Builtin).record
    val directory = File(context.cacheDir, "builtin-render-comparison").apply { mkdirs() }
    try {
      val original = File(directory, "restored.zip")
      ZipOutputStream(original.outputStream()).use { output ->
        for (path in builtin.paths) {
          output.putNextEntry(ZipEntry(path))
          output.write(builtin.read(path, 96 * 1024 * 1024))
          output.closeEntry()
        }
      }
      val imported = original.inputStream().use { IconPackStore(File(directory, "legacy")).import(it, "legacy.zip").record }
      val index = IconPackArchive().inspect(imported.originalFile, File(directory, "scratch.zip"), Build.VERSION.SDK_INT)
      assertEquals(pack.entries, index.entries)
      val legacyProfile = requireNotNull(ColorOsPackProfile.load(imported) {})
      assertEquals(pack.colorOsProfile!!.icons, legacyProfile.icons)
      val legacy = pack.copy(source = IconPackSource.Imported(imported), colorOsProfile = legacyProfile)
      val probes = pack.entries.filter { it.light is IconPackArtwork.Archive }.filterIndexed { i, _ -> i % 45 == 0 }
      for (dark in listOf(false, true)) {
        IconPackRenderer(context).open(pack, dark).use { shared ->
          IconPackRenderer(context).open(legacy, dark).use { archive ->
            for (entry in probes) {
              val a = shared.render(entry, 96)
              val b = archive.render(entry, 96)
              try {
                assertEquals(a == null, b == null)
                if (a != null) assertTrue("Pixels: ${entry.key}, dark=$dark", a.sameAs(requireNotNull(b)))
              } finally { a?.recycle(); b?.recycle() }
            }
          }
        }
      }
      Log.i("KeiHomeBuiltinTest", "Legacy/shared ${probes.size * 2} day/night previews pixel-identical")
    } finally { directory.deleteRecursively() }
  }

  @Test fun cancelledAndOversizedReadsFailBeforeReturningData(): Unit = runBlocking(Dispatchers.IO) {
    val pack = BuiltinIconPackStore(context.assets).list().first()
    val path = pack.paths.first { it.endsWith(".png") }
    assertThrows(IconPackException::class.java) { pack.read(path, 1) }
    assertThrows(CancellationException::class.java) { pack.read(path, 1024 * 1024) { throw CancellationException() } }
    assertThrows(IllegalStateException::class.java) {
      pack.read(path, 1024 * 1024) { error("cancel during an existing request") }
    }
    assertTrue(pack.read(path, 1024 * 1024).isNotEmpty())
    assertThrows(IconPackException::class.java) { pack.read("../outside", 1024) }
    val retry = BuiltinIconPackStore(context.assets)
    assertThrows(CancellationException::class.java) { retry.list { throw CancellationException() } }
    assertTrue(retry.list().isNotEmpty())
  }
}
