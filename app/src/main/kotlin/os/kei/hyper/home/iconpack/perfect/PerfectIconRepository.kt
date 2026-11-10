package os.kei.hyper.home.iconpack.perfect

import android.content.Context
import com.tencent.mmkv.MMKV
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.GeneratedIconPng
import os.kei.hyper.home.iconpack.NativeDayNightIcon

/** Bounds compressed bytes too, including ZIP headers and the final central directory. */
internal class PerfectIconOutputLimit(
  output: OutputStream,
  private val limit: Long,
  private val check: () -> Unit = {},
) : FilterOutputStream(output) {
  private var count = 0L
  init { require(limit >= 0) }
  private fun reserve(size: Int) {
    check()
    if (size.toLong() > limit - count) throw IOException("Perfect Icons generated ZIP exceeds its size limit")
  }
  override fun write(value: Int) {
    reserve(1)
    out.write(value)
    count++
  }
  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
    reserve(length)
    out.write(bytes, offset, length)
    count += length
  }
}

/** One explicit conditional codeload request per refresh; generation is entirely local. */
class PerfectIconRepository internal constructor(
  private val root: File,
  private val transport: PerfectIconTransport,
  private val settings: PerfectIconSettings,
  private val now: () -> Long = System::currentTimeMillis,
  private val limits: PerfectIconZipLimits = PerfectIconZipLimits(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val nativeMask: () -> String? = { null },
  private val staticRenderer: ((List<ByteArray>, String?, () -> Unit) -> PerfectStaticPair)? = null,
  private val dynamicNightRenderer: ((ByteArray, Boolean, () -> Unit) -> ByteArray?)? = null,
) {
  constructor(context: Context) : this(
    File(context.applicationContext.filesDir, "perfect-icons"),
    PerfectIconUrlConnection(),
    MmkvSettings(context.applicationContext),
    nativeMask = { NativeAdaptiveIconMask.load()?.pathData },
    staticRenderer = { layers, mask, check -> PerfectStaticNightRenderer.render(layers, mask, check) },
    dynamicNightRenderer = { bytes, background, check ->
      PerfectDynamicNightRenderer.render(bytes, background, check)
    },
  )

  private val mutex = locks.getOrPut(root.absolutePath) { Mutex() }
  private val snapshotFile get() = File(root, "catalog.json")
  private val sourceDir get() = File(root, "source")
  private val blobsDir get() = File(root, "blobs")
  private val outputDir get() = File(root, "generated")
  private val previewDir get() = File(root, "previews")
  private val renderedDir get() = File(root, "rendered-static-night")
  // Cache metadata from the atomic source pointer, not decoded bitmaps or open ZIP handles.
  private var fallbackIndex: FallbackIndex? = null
  private val syncHistory = PerfectSyncHistory(settings::read, settings::write, now)
  private val localHealthProbe = PerfectLocalHealthProbe()

  private data class FallbackIndex(
    val stamp: String,
    val snapshot: Snapshot,
    val entries: Map<String, PerfectIconSourceEntry>,
    val plans: LinkedHashMap<String, PerfectIconPlan> = linkedMapOf(),
  )

  internal data class FallbackIcon(val layers: List<ByteArray>)

  /** Local, hash-checked static artwork only; never invokes the network or changes pack options. */
  internal suspend fun fallbackIcon(style: String, names: List<String>, expectedSourceHash: String? = null): FallbackIcon? = withContext(ioDispatcher) {
    mutex.withLock {
      val index = fallbackIndex(style)
      require(expectedSourceHash == null || expectedSourceHash == index.snapshot.sourceHash) {
        "Perfect Icons source changed during generation; retry with the current local resources"
      }
      val plan = index.plans.getOrPut(style) {
        fun branch(prefix: String) = index.snapshot.entries.filter { it.path.startsWith("$prefix/") }.map {
          PerfectIconBlob(it.path.removePrefix("$prefix/"), it.sha, it.size, it.path)
        }
        PerfectIconFormat.plan(branch("icons"), branch("themes/$style/icons"), emptyList(), emptyList(),
          PerfectIconOptions(style, installedOnly = false, dynamic = false), emptySet())
      }
      while (index.plans.size > 2) index.plans.remove(index.plans.keys.first())
      val blobs = names.asSequence().filter(PerfectIconFormat::id).mapNotNull { name ->
        plan.files["res/drawable-xxhdpi/$name.png"]?.let { return@mapNotNull listOf(it) }
        val layers = (0..1).mapNotNull { plan.files["res/drawable-xxhdpi/$name/$it.png"] }
        layers.takeIf { it.size == 2 }
      }.firstOrNull() ?: return@withLock null
      val coroutine = currentCoroutineContext()
      var zip: ZipFile? = null
      var extracted = false
      try {
        FallbackIcon(blobs.map { blob ->
          coroutine.ensureActive()
          val bytes = if (cachedBlob(blob)) blobFile(blob.sha).readBytes() else {
            val source = zip ?: ZipFile(archive(index.snapshot), Charsets.UTF_8).also { zip = it }
            sourceBytes(source, blob, requireNotNull(index.entries[blob.source])).also {
              atomic(blobFile(blob.sha), it) { coroutine.ensureActive() }
              extracted = true
            }
          }
          PerfectIconFormat.png(bytes, square = true)
          bytes
        }).also { if (extracted) trimBlobs(blobs.mapTo(hashSetOf()) { it.sha }) }
      } finally { zip?.close() }
    }
  }

  internal suspend fun fallbackSourceHash(style: String): String = withContext(ioDispatcher) {
    mutex.withLock { fallbackIndex(style).snapshot.sourceHash }
  }

  private fun fallbackIndex(style: String): FallbackIndex {
    PerfectIconFormat.style(style)
    val attributes = Files.readAttributes(snapshotFile.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
    val stamp = "${attributes.fileKey()}:${attributes.lastModifiedTime()}:${attributes.size()}"
    val index = fallbackIndex?.takeIf { it.stamp == stamp } ?: run {
      val snapshot = localSnapshot() ?: throw IOException("Perfect Icons local resources are unavailable")
      FallbackIndex(stamp, snapshot, snapshot.entries.associateBy { it.path }).also { fallbackIndex = it }
    }
    require(style in index.snapshot.styles) { "Perfect Icons style is unavailable: $style" }
    require(archive(index.snapshot).isFile && archive(index.snapshot).length() == index.snapshot.archiveBytes) {
      "Perfect Icons local source archive is unavailable"
    }
    return index
  }

  fun options(): PerfectIconOptions = runCatching {
    val json = JSONObject(settings.read("options") ?: return PerfectIconOptions())
    PerfectIconOptions(json.getString("style"), json.getBoolean("installedOnly"), json.getBoolean("dynamic"),
      json.optBoolean("staticNight", false))
      .also { PerfectIconFormat.style(it.style) }
  }.getOrDefault(PerfectIconOptions())

  fun setOptions(options: PerfectIconOptions) {
    PerfectIconFormat.style(options.style)
    settings.write("options", JSONObject().put("style", options.style)
      .put("installedOnly", options.installedOnly).put("dynamic", options.dynamic)
      .put("staticNight", options.staticNight).toString())
  }

  // Old REST API cooldowns do not block the independent codeload source.
  fun retryAtMillis(): Long = if (settings.read("cooldownSource") == PerfectIconUrls.SOURCE)
    settings.read("retryAt")?.toLongOrNull()?.coerceAtLeast(0) ?: 0 else 0

  internal suspend fun syncHistory(): PerfectSyncHistoryState = withContext(ioDispatcher) {
    mutex.withLock { syncHistory.current() }
  }

  /** Metadata and ZIP directory only. Full archive/payload validation remains on sync/read paths. */
  internal suspend fun localStatus(): PerfectLocalStatus = withContext(ioDispatcher) {
    mutex.withLock {
      val snapshot = localSnapshot()
      val knownHash = settings.read("knownSourceHash")
      val corruptHash = settings.read("corruptSourceHash")
      val sourceHash = snapshot?.sourceHash ?: knownHash.orEmpty()
      val source = File(sourceDir, "$sourceHash.zip")
      val stamp = PerfectLocalProbeStamp(snapshotFile.exists(), snapshotFile.length(), snapshotFile.lastModified(),
        sourceHash, source.isFile, source.length(), source.lastModified(), corruptHash,
        snapshot?.sourceHash ?: knownHash, sourceDir.lastModified())
      val referenced = snapshotFile.exists() || !knownHash.isNullOrEmpty() ||
        sourceDir.listFiles().orEmpty().any { it.isFile && it.extension == "zip" }
      val coroutine = currentCoroutineContext()
      val healthy = localHealthProbe.healthy(stamp) {
        snapshot != null && corruptHash != snapshot.sourceHash && try {
          ZipFile(archive(snapshot), Charsets.UTF_8).use { zip ->
            snapshot.entries.all { source ->
              coroutine.ensureActive()
              val entry = zip.getEntry(PerfectIconSourceZip.PREFIX + source.path)
              entry != null && entry.size == source.size && entry.crc == source.crc
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          false
        }
      }
      when {
        healthy -> {
          val checked = requireNotNull(snapshot)
          if (settings.read("knownSourceHash") != checked.sourceHash)
            settings.write("knownSourceHash", checked.sourceHash)
          if (settings.read("localCorruptAt") != "0") settings.write("localCorruptAt", "0")
          syncHistory.seedLastSuccess(checked.updatedAt)
          PerfectLocalStatus(PerfectLocalHealth.READY, checked.catalog)
        }
        referenced -> {
          val timestamp = settings.read("localCorruptAt")?.toLongOrNull()?.takeIf { it > 0 }
            ?: now().coerceAtLeast(1).also { settings.write("localCorruptAt", it.toString()) }
          PerfectLocalStatus(PerfectLocalHealth.CORRUPT, corruptAtMillis = timestamp)
        }
        else -> PerfectLocalStatus(PerfectLocalHealth.MISSING)
      }
    }
  }

  suspend fun catalog(
    refresh: Boolean = false,
    forceDownload: Boolean = false,
    progress: (PerfectIconProgress) -> Unit = {},
  ): PerfectIconCatalog? = withContext(ioDispatcher) {
    mutex.withLock {
      val old = localSnapshot()
      if (!refresh) return@withLock old?.catalog
      // A rejected local cooldown is not a new network attempt.
      if (retryAtMillis() > now()) throw PerfectIconCooldownException(retryAtMillis())
      syncHistory.started()
      try {
        val catalog = refresh(old, progress, forceDownload).catalog
        settings.write("knownSourceHash", catalog.sourceHash)
        settings.write("corruptSourceHash", "")
        settings.write("localCorruptAt", "0")
        syncHistory.succeeded()
        catalog
      } catch (cancelled: CancellationException) {
        syncHistory.interrupted()
        throw cancelled
      } catch (interrupted: InterruptedException) {
        syncHistory.interrupted()
        throw interrupted
      } catch (failure: Exception) {
        syncHistory.failed()
        throw failure
      } finally {
        localHealthProbe.invalidate()
      }
    }
  }

  suspend fun generate(
    options: PerfectIconOptions,
    installedNames: Set<String>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectIconResult = withContext(ioDispatcher) {
    PerfectIconFormat.style(options.style)
    require(installedNames.size <= PerfectIconFormat.MAX_FILES)
    val installed = installedNames.toSet()
    mutex.withLock {
      progress(PerfectIconProgress(0, 0, "Reading local repository snapshot"))
      val snapshot = localSnapshot() ?: throw IOException("Refresh the Perfect Icons repository before generating")
      val style = options.style.ifEmpty { "default" }
      require(style in snapshot.styles) { "Unknown Perfect Icons style: $style" }
      fun branch(prefix: String) = snapshot.entries.filter { it.path.startsWith("$prefix/") }.map {
        PerfectIconBlob(it.path.removePrefix("$prefix/"), it.sha, it.size, it.path)
      }
      val plan = PerfectIconFormat.plan(branch("icons"), branch("themes/$style/icons"),
        if (options.dynamic) branch("themes/common/layer_animating_icons") else emptyList(),
        if (options.dynamic) branch("themes/$style/layer_animating_icons") else emptyList(), options, installed)
      require(plan.files.isNotEmpty()) { "No Perfect Icons match the supplied installed IDs" }
      require(plan.files.values.groupBy { it.sha }.values.all { it.map { blob -> blob.size }.distinct().size == 1 })
      val unique = plan.files.values.distinctBy { it.sha }
      var extracted = 0
      var reused = 0
      val coroutine = currentCoroutineContext()
      val indexed = snapshot.entries.associateBy { it.path }
      ZipFile(archive(snapshot), Charsets.UTF_8).use { zip ->
        unique.forEachIndexed { index, blob ->
          coroutine.ensureActive()
          if (cachedBlob(blob)) {
            reused++
          } else {
            val bytes = sourceBytes(zip, blob, requireNotNull(indexed[blob.source]))
            atomic(blobFile(blob.sha), bytes) { coroutine.ensureActive() }
            extracted++
          }
          progress(PerfectIconProgress(index + 1, unique.size, blob.source))
        }
      }
      coroutine.ensureActive()
      val validated = PerfectIconFormat.validate(plan) { blob -> coroutine.ensureActive(); blobFile(blob.sha).readBytes() }
      require(validated.files.isNotEmpty()) { "No complete Perfect Icons match the supplied installed IDs" }
      val mask = nativeMask()
      val transform = HyperOs4IconModule.transform(usesLayerDirectory(validated) ||
        (options.staticNight && staticNightInputs(validated).isNotEmpty()), mask)
      val file = publish(snapshot, options, validated, transform, mask) { done, total ->
        progress(PerfectIconProgress(done, total, "source.static-night"))
      }
      trimBlobs(unique.map { it.sha }.toSet())
      val derivedDynamicCount = if (options.staticNight && options.dynamic) ZipFile(file).use { zip ->
        zip.entries().asSequence().count {
          it.name.startsWith("nightmode/layer_animating_icons/") && it.name.endsWith("/config.xml")
        }
      } else 0
      PerfectIconResult(file, (validated.staticIds + validated.dynamicIds).size, validated.staticIds.size,
        validated.dynamicIds.size, extracted, reused, snapshot.revision,
        rejectedDynamic = validated.rejectedDynamic.size,
        nightRevision = options.nightContract(),
        derivedDynamicCount = derivedDynamicCount)
    }
  }

  /** Catalog sample of the current style, independent of installed-app filtering or selection. */
  suspend fun previewArchive(options: PerfectIconOptions, limit: Int = 24): File = withContext(ioDispatcher) {
    PerfectIconFormat.style(options.style)
    val sampleLimit = limit.coerceIn(1, 60)
    mutex.withLock {
      val snapshot = localSnapshot() ?: throw IOException("Refresh the Perfect Icons repository before previewing")
      val style = options.style.ifEmpty { "default" }
      require(style in snapshot.styles) { "Unknown Perfect Icons style: $style" }
      val mask = nativeMask()
      val identity = "$LAYER_CONTRACT\n${HyperOs4IconModule.cacheIdentity(mask)}\npreview\n${snapshot.sourceHash}\n$style\n${options.dynamic}\n$sampleLimit" +
        if (options.staticNight) "\n${PerfectStaticNightRenderer.REVISION}:${PerfectDynamicNight.REVISION}" else ""
      val key = PerfectIconFormat.sha(identity.toByteArray(Charsets.UTF_8)).take(24)
      val destination = File(previewDir, "preview-${snapshot.sourceHash.take(12)}-$key.zip")
      val proof = File(previewDir, "${destination.name}.sha256")
      val coroutine = currentCoroutineContext()
      if (destination.isFile && destination.length() in 1..MAX_PREVIEW_FILE &&
        proof.isFile && proof.length() == 64L && previewHash(destination) == proof.readText(Charsets.US_ASCII)) {
        coroutine.ensureActive()
        return@withLock destination
      }
      fun branch(prefix: String) = snapshot.entries.filter { it.path.startsWith("$prefix/") }.map {
        PerfectIconBlob(it.path.removePrefix("$prefix/"), it.sha, it.size, it.path)
      }
      val base = branch("icons")
      val themed = branch("themes/$style/icons")
      val common = if (options.dynamic) branch("themes/common/layer_animating_icons") else emptyList()
      val dynamic = if (options.dynamic) branch("themes/$style/layer_animating_icons") else emptyList()
      val ids = sortedSetOf<String>().apply {
        addAll(PerfectIconFormat.staticGroups(base).keys)
        addAll(PerfectIconFormat.staticGroups(themed).keys)
        themed.filter { '/' !in it.path && it.path.endsWith(".png") }.forEach {
          val id = it.path.removeSuffix(".png")
          if (PerfectIconFormat.id(id)) add(id)
        }
        (common + dynamic).filter { '/' in it.path }.forEach {
          val id = it.path.substringBefore('/')
          if (PerfectIconFormat.id(id)) add(id)
        }
      }
      val preferred = listOf("com.miui.gallery", "com.android.settings", "com.android.contacts",
        "com.android.phone", "com.android.mms", "com.android.calendar", "com.android.deskclock", "com.xiaomi.calendar")
      val selected = (preferred.filter { it in ids } + (ids - preferred.toSet())).take(sampleLimit).toSet()
      val plan = PerfectIconFormat.plan(base, themed, common, dynamic,
        options.copy(installedOnly = true), selected)
      require(plan.files.isNotEmpty()) { "No Perfect Icons are available for this preview" }
      require(plan.files.size <= 2048 && plan.files.values.sumOf { it.size } <= MAX_PREVIEW_BYTES) {
        "Perfect Icons preview exceeds the 32 MiB / 2048-resource limit"
      }
      previewDir.mkdirs()
      val work = File(previewDir, ".${UUID.randomUUID()}.part")
      check(work.mkdir())
      try {
        val files = HashMap<String, File>()
        val indexed = snapshot.entries.associateBy { it.path }
        ZipFile(archive(snapshot), Charsets.UTF_8).use { zip ->
          plan.files.values.distinctBy { it.sha }.forEach { blob ->
            coroutine.ensureActive()
            if (cachedBlob(blob)) {
              files[blob.sha] = blobFile(blob.sha)
            } else {
              val bytes = sourceBytes(zip, blob, requireNotNull(indexed[blob.source]))
              val file = File(work, blob.sha)
              file.writeBytes(bytes)
              files[blob.sha] = file
            }
          }
        }
        fun read(blob: PerfectIconBlob): ByteArray {
          coroutine.ensureActive()
          return requireNotNull(files[blob.sha]).readBytes()
        }
        val validated = PerfectIconFormat.validate(plan, ::read)
        require(validated.files.isNotEmpty()) { "No complete Perfect Icons are available for this preview" }
        writeArchive(destination, validated, HyperOs4IconModule.transform(usesLayerDirectory(validated) ||
          (options.staticNight && staticNightInputs(validated).isNotEmpty()), mask),
          maxFile = MAX_PREVIEW_FILE, options = options, mask = mask, read = ::read)
        val hash = previewHash(destination)
        atomic(proof, hash.toByteArray(Charsets.US_ASCII)) { coroutine.ensureActive() }
        // Preview retention never touches generated publications or shared blob-cache entries.
        previewDir.listFiles().orEmpty().filter { it != destination && it.extension == "zip" }
          .sortedByDescending { it.lastModified() }.drop(3).forEach {
            it.delete()
            File(previewDir, "${it.name}.sha256").delete()
          }
        destination
      } finally {
        work.deleteRecursively()
      }
    }
  }

  /** Exactly the bytes clearDownloadCache can remove; usable repository/publications are excluded. */
  suspend fun cacheBytes(): Long = withContext(ioDispatcher) {
    mutex.withLock {
      val coroutine = currentCoroutineContext()
      root.walkTopDown().filter(::clearable).sumOf {
        coroutine.ensureActive()
        it.length()
      }
    }
  }

  /** Keep the usable source ZIP/catalog and completed outputs, so generation remains offline. */
  suspend fun clearDownloadCache(): Long = withContext(ioDispatcher) {
    mutex.withLock {
      val coroutine = currentCoroutineContext()
      var freed = 0L
      root.walkTopDown().filter(::clearable).forEach {
        coroutine.ensureActive()
        val size = it.length()
        if (it.delete()) freed += size
      }
      freed
    }
  }

  private fun clearable(file: File): Boolean = file.isFile &&
    (file.parentFile == blobsDir || file.parentFile == renderedDir || file.toPath().startsWith(previewDir.toPath()) ||
      file.toPath().startsWith(File(root, "trees").toPath()) || file.name.endsWith(".part"))

  private data class Snapshot(
    val revision: String,
    val sourceHash: String,
    val archiveBytes: Long,
    val entries: List<PerfectIconSourceEntry>,
    val styles: List<String>,
    val updatedAt: Long,
    val downloadedAt: Long,
    val count: Int,
    val etag: String,
    val upToDate: Boolean,
  ) {
    val catalog get() = PerfectIconCatalog(revision, styles, updatedAt, count, downloadedAt, sourceHash, upToDate, archiveBytes)
    fun json(): JSONObject = JSONObject().put("version", 2).put("revision", revision).put("sourceHash", sourceHash)
      .put("archiveBytes", archiveBytes).put("styles", JSONArray(styles)).put("updatedAt", updatedAt)
      .put("downloadedAt", downloadedAt).put("count", count).put("etag", etag).put("upToDate", upToDate)
      .put("entries", JSONArray(entries.map { JSONArray(listOf(it.path, it.sha, it.size, it.crc)) }))
  }

  private fun archive(snapshot: Snapshot) = File(sourceDir, "${snapshot.sourceHash}.zip")

  private fun localSnapshot(): Snapshot? = runCatching {
    if (!snapshotFile.isFile || snapshotFile.length() > MAX_META) return null
    val json = JSONObject(snapshotFile.readText(Charsets.UTF_8))
    require(json.getInt("version") == 2)
    val revision = json.getString("revision")
    require(revision.isEmpty() || revision.matches(Regex("[0-9a-f]{40}")))
    val sourceHash = json.getString("sourceHash")
    require(sourceHash.matches(Regex("[0-9a-f]{64}")))
    val styles = json.getJSONArray("styles")
    require(styles.length() in 1..256)
    val entries = json.getJSONArray("entries")
    require(entries.length() in 1..limits.entries)
    val paths = hashSetOf<String>()
    val snapshot = Snapshot(revision, sourceHash, json.getLong("archiveBytes"),
      (0 until entries.length()).map { index ->
        val entry = entries.getJSONArray(index)
        val path = entry.getString(0)
        PerfectIconFormat.safePath(path)
        require(paths.add(path))
        val sha = entry.getString(1)
        require(sha.matches(Regex("[0-9a-f]{40}")))
        val size = entry.getLong(2).also { require(it in 0..limits.expanded) }
        val crc = entry.getLong(3).also { require(it in 0..0xffffffffL) }
        PerfectIconSourceEntry(path, sha, size, crc)
      },
      (0 until styles.length()).map { styles.getString(it).also(PerfectIconFormat::style) }.sorted(),
      json.getLong("updatedAt").also { require(it >= 0) },
      json.getLong("downloadedAt").also { require(it >= 0) },
      json.getInt("count").also { require(it in 0..limits.entries) },
      json.optString("etag", ""), json.optBoolean("upToDate", false))
    require(snapshot.archiveBytes in 22..limits.compressed && archive(snapshot).isFile &&
      archive(snapshot).length() == snapshot.archiveBytes)
    snapshot
  }.getOrNull()

  private suspend fun refresh(old: Snapshot?, progress: (PerfectIconProgress) -> Unit,
    forceDownload: Boolean): Snapshot {
    val coroutine = currentCoroutineContext()
    sourceDir.mkdirs()
    val temporary = File(sourceDir, ".${UUID.randomUUID()}.part")
    var published: File? = null
    var committed = false
    try {
      val time = now()
      if (retryAtMillis() > time) throw PerfectIconCooldownException(retryAtMillis())
      // A 304 cannot repair disk corruption. Only offer a validator for verified local bytes.
      val reusable = old?.takeUnless { forceDownload }?.takeIf { snapshot ->
        progress(PerfectIconProgress(0, 0, "source.verify"))
        try {
          val digest = MessageDigest.getInstance("SHA-256")
          archive(snapshot).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              coroutine.ensureActive()
              val count = input.read(buffer)
              if (count < 0) break
              digest.update(buffer, 0, count)
            }
          }
          digest.digest().joinToString("") { "%02x".format(it) } == snapshot.sourceHash
        } catch (_: IOException) {
          false
        }
      }
      val headers = reusable?.etag?.takeIf { it.isNotEmpty() }?.let { mapOf("If-None-Match" to it) } ?: emptyMap()
      val response = try {
        progress(PerfectIconProgress(0, 0, "source.download"))
        transport.download(PerfectIconUrls.SOURCE, temporary, limits.compressed, headers, progress)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: IOException) {
        cooldown(time + 30_000)
        throw error
      }
      coroutine.ensureActive()
      val rateLimited = response.status == 429 || response.status == 403 &&
        (response.header("Retry-After") != null || response.header("X-RateLimit-Remaining") == "0")
      if (rateLimited) {
        val until = maxOf(retryAtMillis(), response.cooldown(time))
        cooldown(until)
        throw PerfectIconCooldownException(until)
      }
      val snapshot = if (response.status == 304) {
        requireNotNull(reusable) { "Unconditional source request returned 304" }.copy(updatedAt = now(), upToDate = true)
      } else {
        if (response.status != 200) {
          cooldown(time + 30_000)
          throw IOException("Perfect Icons source ZIP HTTP ${response.status}")
        }
        val index = PerfectIconSourceZip.inspect(temporary, limits, progress)
        coroutine.ensureActive()
        val etag = response.header("ETag").orEmpty().takeIf {
          it.length <= 256 && '\r' !in it && '\n' !in it
        }.orEmpty()
        val checkedAt = now()
        val result = Snapshot(index.revision, index.hash, temporary.length(), index.entries,
          index.styles, checkedAt, checkedAt, index.iconCount, etag, old?.sourceHash == index.hash)
        val destination = archive(result)
        move(temporary, destination)
        if (old?.sourceHash != index.hash) published = destination
        result
      }
      val bytes = snapshot.json().toString().toByteArray(Charsets.UTF_8)
      require(bytes.size <= MAX_META)
      atomic(snapshotFile, bytes) { coroutine.ensureActive() }
      committed = true
      // The pointer is durable before retiring an older source archive.
      sourceDir.listFiles().orEmpty().filter { it.extension == "zip" && it != archive(snapshot) }.forEach { it.delete() }
      return snapshot
    } finally {
      temporary.delete()
      if (!committed) published?.delete()
    }
  }

  private fun cooldown(until: Long) {
    settings.write("cooldownSource", PerfectIconUrls.SOURCE)
    settings.write("retryAt", until.toString())
  }

  private fun blobFile(sha: String): File {
    require(sha.matches(Regex("[0-9a-f]{40}")))
    return File(blobsDir, sha)
  }

  private fun cachedBlob(blob: PerfectIconBlob): Boolean {
    val file = blobFile(blob.sha)
    return file.isFile && file.length() == blob.size && PerfectIconFormat.sha(file) == blob.sha
  }

  private fun trimBlobs(active: Set<String>) {
    val files = blobsDir.listFiles().orEmpty().filter { it.isFile }
    var bytes = files.sumOf { it.length() }
    files.filter { it.name !in active }.sortedBy { it.lastModified() }.forEach {
      if (bytes > PerfectIconFormat.MAX_PACK) {
        val size = it.length()
        if (it.delete()) bytes -= size
      }
    }
  }

  // Directory admission also covers quiet-only layers; it does not imply a running MAML program.
  private fun usesLayerDirectory(plan: PerfectIconPlan): Boolean = plan.files.keys.any {
    it.startsWith("layer_animating_icons/") && it.endsWith("/config.xml")
  }

  private fun staticNightInputs(plan: PerfectIconPlan): Map<String, List<PerfectIconBlob>> =
    plan.staticIds.sorted().mapNotNull { name ->
      // Even quiet-only configured groups retain their original native mode selectors.
      if ("layer_animating_icons/$name/config.xml" in plan.files) return@mapNotNull null
      val prefix = "res/drawable-xxhdpi/$name"
      val flat = plan.files["$prefix.png"]
      val layers = listOfNotNull(plan.files["$prefix/0.png"], plan.files["$prefix/1.png"])
      when {
        flat != null -> name to listOf(flat)
        layers.size == 2 -> name to layers
        else -> null
      }
    }.toMap()

  private fun staticPair(
    inputs: List<PerfectIconBlob>, mask: String?, check: () -> Unit,
    read: (PerfectIconBlob) -> ByteArray,
  ): PerfectStaticPair {
    val identity = "${PerfectStaticNightRenderer.REVISION}\n${PerfectIconFormat.sha(mask.orEmpty().toByteArray())}\n" +
      inputs.joinToString("\n") { it.sha }
    val key = PerfectIconFormat.sha(identity.toByteArray())
    val file = File(renderedDir, "$key.zip")
    if (file.isFile && file.length() in 1..(9L * 1024 * 1024)) {
      try {
        ZipFile(file).use { zip ->
          require(zip.size() == 3)
          fun bytes(path: String, max: Int): ByteArray {
            val entry = requireNotNull(zip.getEntry(path))
            require(entry.size in 1..max.toLong())
            return zip.getInputStream(entry).use { it.readNBytes(max + 1) }.also {
              require(it.size.toLong() == entry.size)
            }
          }
          val proof = JSONObject(bytes("proof.json", 1024).toString(Charsets.UTF_8))
          val pair = PerfectStaticPair(bytes("day.png", 4 * 1024 * 1024), bytes("night.png", 4 * 1024 * 1024))
          require(proof.getString("key") == key &&
            proof.getString("day") == PerfectIconFormat.sha(pair.day) &&
            proof.getString("night") == PerfectIconFormat.sha(pair.night))
          GeneratedIconPng.validate(pair.day, 512)
          GeneratedIconPng.validate(pair.night, 512)
          check()
          file.setLastModified(now())
          return pair
        }
      } catch (failure: Exception) {
        if (failure is CancellationException || failure is InterruptedException) throw failure
      }
    }
    check()
    val source = inputs.map { blob ->
      check()
      read(blob).also { require(PerfectIconFormat.sha(it) == blob.sha) { "Cached source icon integrity mismatch" } }
    }
    val pair = requireNotNull(staticRenderer) { "Static night renderer unavailable" }(source, mask, check)
    GeneratedIconPng.validate(pair.day, 512)
    GeneratedIconPng.validate(pair.night, 512)
    renderedDir.mkdirs()
    val temporary = File(renderedDir, ".$key.${UUID.randomUUID()}.part")
    try {
      ZipOutputStream(temporary.outputStream()).use { zip ->
        val proof = JSONObject().put("key", key).put("day", PerfectIconFormat.sha(pair.day))
          .put("night", PerfectIconFormat.sha(pair.night)).toString().toByteArray()
        for ((path, bytes) in mapOf("day.png" to pair.day, "night.png" to pair.night, "proof.json" to proof)) {
          check()
          zip.putNextEntry(ZipEntry(path).apply {
            time = 0
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(bytes) }.value
          })
          zip.write(bytes)
          zip.closeEntry()
        }
      }
      check()
      move(temporary, file)
    } finally {
      temporary.delete()
    }
    return pair
  }

  private fun trimRendered() {
    val files = renderedDir.listFiles().orEmpty().filter { it.extension == "zip" }
    var bytes = files.sumOf { it.length() }
    for (file in files.sortedBy { it.lastModified() }) {
      if (bytes <= 256L * 1024 * 1024) break
      val size = file.length()
      if (file.delete()) bytes -= size
    }
  }

  private suspend fun publish(snapshot: Snapshot, options: PerfectIconOptions, plan: PerfectIconPlan,
    transform: ByteArray, mask: String?, renderProgress: (Int, Int) -> Unit): File {
    outputDir.mkdirs()
    val identity = buildString {
      append(LAYER_CONTRACT).append('\n').append(HyperOs4IconModule.REVISION).append('\n').append(PerfectIconFormat.sha(transform)).append('\n')
      append(snapshot.sourceHash).append('\n').append(options.style.ifEmpty { "default" })
      append('\n').append(options.dynamic).append('\n')
      if (options.staticNight) append(PerfectStaticNightRenderer.REVISION)
        .append(':').append(PerfectDynamicNight.REVISION).append('\n')
      plan.files.forEach { (path, blob) -> append(path).append('\u0000').append(blob.sha).append('\n') }
    }
    val key = PerfectIconFormat.sha(identity.toByteArray(Charsets.UTF_8)).take(24)
    val destination = File(outputDir, "perfect-${snapshot.sourceHash.take(12)}-$key.zip")
    writeArchive(destination, plan, transform, options = options, mask = mask,
      renderProgress = renderProgress) { blob -> blobFile(blob.sha).readBytes() }
    // Imports own an independent copy. Keep the new publication and two prior usable outputs.
    outputDir.listFiles().orEmpty().filter { it != destination && it.name.startsWith("perfect-") && it.extension == "zip" }
      .sortedByDescending { it.lastModified() }.drop(2).forEach { it.delete() }
    return destination
  }

  private suspend fun writeArchive(
    destination: File,
    plan: PerfectIconPlan,
    transform: ByteArray,
    maxFile: Long = 1024L * 1024 * 1024,
    options: PerfectIconOptions = PerfectIconOptions(),
    mask: String? = null,
    renderProgress: (Int, Int) -> Unit = { _, _ -> },
    read: (PerfectIconBlob) -> ByteArray,
  ) {
    val temporary = File(destination.parentFile, ".${UUID.randomUUID()}.part")
    val coroutine = currentCoroutineContext()
    val generated = mapOf(
      "transform_config.xml" to transform,
    )
    val nightInputs = if (options.staticNight) staticNightInputs(plan) else emptyMap()
    val dynamicNight = options.staticNight && dynamicNightRenderer != null
    val replaced = nightInputs.keys.mapTo(hashSetOf()) { "res/drawable-xxhdpi/$it.png" }
    val expected = linkedMapOf<String, Pair<Long, String>>()
    try {
      FileOutputStream(temporary).use { output ->
        ZipOutputStream(PerfectIconOutputLimit(output, maxFile) { coroutine.ensureActive() },
          Charsets.UTF_8).use { zip ->
          fun entry(path: String, bytes: ByteArray, sourceHash: String? = null) {
            coroutine.ensureActive()
            check(path !in expected) { "Duplicate generated icon entry" }
            val digest = PerfectIconFormat.sha(bytes)
            require(sourceHash == null || sourceHash == digest) { "Cached source icon integrity mismatch" }
            expected[path] = bytes.size.toLong() to digest
            zip.putNextEntry(ZipEntry(path).apply {
              time = 0
              if (path.endsWith(".png", true)) {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
              }
            })
            zip.write(bytes)
            zip.closeEntry()
          }
          generated.forEach { (path, bytes) -> entry(path, bytes) }
          plan.files.filterKeys {
            it !in replaced && !(dynamicNight && it.startsWith("layer_animating_icons/"))
          }.forEach { (path, blob) -> entry(path, read(blob), blob.sha) }
          nightInputs.entries.forEachIndexed { index, (name, inputs) ->
            coroutine.ensureActive()
            val pair = staticPair(inputs, mask, { coroutine.ensureActive() }, read)
            NativeDayNightIcon.entries(name, pair.day, pair.night).forEach { (path, bytes) -> entry(path, bytes) }
            entry("nightmode/res/drawable-xxhdpi/$name.png", pair.night)
            renderProgress(index + 1, nightInputs.size)
            if (index % 32 == 31) trimRendered()
          }
          if (dynamicNight) {
            val groups = plan.files.keys.filter { it.startsWith("layer_animating_icons/") }
              .map { it.removePrefix("layer_animating_icons/").substringBefore('/') }.toSortedSet()
            for (name in groups) {
              coroutine.ensureActive()
              val prefix = "layer_animating_icons/$name/"
              val files = plan.files.filterKeys { it.startsWith(prefix) }
                .mapKeys { (path, _) -> path.removePrefix(prefix) }
              fun original() = files.toSortedMap().forEach { (path, blob) ->
                entry("$prefix$path", read(blob), blob.sha)
              }
              val group = PerfectDynamicNight.group(files, read) { coroutine.ensureActive() }
              if (group == null) { original(); continue }
              val converted = linkedMapOf<String, ByteArray>()
              var renderedBytes = 0L
              for (path in listOf(group.background) + group.foreground) {
                coroutine.ensureActive()
                val bytes = dynamicNightTexture(group.files.getValue(path), path == group.background,
                  { coroutine.ensureActive() }, read) ?: break
                renderedBytes += bytes.size
                if (renderedBytes > 32L * 1024 * 1024) break
                converted[path] = bytes
              }
              // Publish the whole closure or none, never a mixture of night and missing day assets.
              if (converted.size != group.foreground.size + 1) { original(); continue }
              val originals = group.files.mapValues { (_, blob) ->
                read(blob).also { require(PerfectIconFormat.sha(it) == blob.sha) }
              }
              val night = originals + converted
              // Rust's layer loader does not reliably select the nightmode tree.
              // Keep native animation owners and select their textures inside the layers.
              val live = PerfectDynamicNightSwitch.systemFiles(group, originals, converted)
              if (live == null) { original(); continue }
              live.toSortedMap().forEach { (path, bytes) -> entry("$prefix$path", bytes) }
              night.toSortedMap().forEach { (path, bytes) -> entry("nightmode/$prefix$path", bytes) }
              trimRendered()
            }
          }
          zip.finish()
          zip.flush()
          output.fd.sync()
        }
      }
      require(temporary.length() <= maxFile) { "Perfect Icons generated ZIP exceeds its size limit" }
      // Validate the archive that will be published, not just its inputs.
      ZipFile(temporary, Charsets.UTF_8).use { zip ->
        require(zip.size() == expected.size)
        expected.forEach { (path, proof) ->
          coroutine.ensureActive()
          val entry = requireNotNull(zip.getEntry(path))
          require(entry.size == proof.first)
          require(PerfectIconFormat.sha(zip.getInputStream(entry).use { it.readBytes() }) == proof.second)
          if (path.endsWith(".png", true)) require(entry.method == ZipEntry.STORED)
        }
        generated.forEach { (path, bytes) ->
          require(zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }.contentEquals(bytes))
          require(zip.getEntry(path).method == ZipEntry.DEFLATED)
          PerfectIconFormat.xml(bytes)
        }
      }
      coroutine.ensureActive()
      move(temporary, destination)
    } finally {
      temporary.delete()
      trimRendered()
    }
  }

  private fun dynamicNightTexture(
    blob: PerfectIconBlob,
    background: Boolean,
    check: () -> Unit,
    read: (PerfectIconBlob) -> ByteArray,
  ): ByteArray? {
    val render = dynamicNightRenderer ?: return null
    val key = PerfectIconFormat.sha("${PerfectDynamicNight.REVISION}:${blob.sha}:$background".toByteArray())
    val output = File(renderedDir, "$key.zip")
    if (output.isFile && output.length() in 1..(PerfectIconFormat.MAX_BLOB.toLong() + 4096)) {
      try {
        ZipFile(output).use { zip ->
          val proofEntry = requireNotNull(zip.getEntry("proof.json"))
          require(proofEntry.size in 1..4096)
          val proofBytes = zip.getInputStream(proofEntry).use { it.readNBytes(4097) }
          require(proofBytes.size <= 4096)
          val proof = JSONObject(proofBytes.toString(Charsets.UTF_8))
          require(proof.getString("key") == key)
          if (proof.optBoolean("retained")) {
            require(zip.size() == 1)
            return null
          }
          val entry = requireNotNull(zip.getEntry("night.png"))
          require(zip.size() == 2 && entry.size in 1..PerfectIconFormat.MAX_BLOB.toLong())
          check()
          val bytes = zip.getInputStream(entry).use { it.readNBytes(PerfectIconFormat.MAX_BLOB + 1) }
          require(bytes.size <= PerfectIconFormat.MAX_BLOB)
          require(PerfectIconFormat.sha(bytes) == proof.getString("sha"))
          PerfectIconFormat.raster(bytes)
          return bytes
        }
      } catch (failure: Exception) {
        if (failure is CancellationException || failure is InterruptedException) throw failure
        output.delete()
      }
    }
    check()
    val bytes = render(read(blob), background, check)
    if (bytes != null) PerfectIconFormat.raster(bytes)
    val proof = JSONObject().put("key", key).apply {
      if (bytes == null) put("retained", true) else put("sha", PerfectIconFormat.sha(bytes))
    }.toString().toByteArray()
    val encoded = ByteArrayOutputStream().also { buffer ->
      ZipOutputStream(buffer).use { zip ->
        fun entry(name: String, content: ByteArray) {
          zip.putNextEntry(ZipEntry(name).apply {
            time = 0
            if (name.endsWith(".png")) {
              method = ZipEntry.STORED
              size = content.size.toLong()
              compressedSize = size
              crc = CRC32().apply { update(content) }.value
            }
          })
          zip.write(content)
          zip.closeEntry()
        }
        entry("proof.json", proof)
        if (bytes != null) entry("night.png", bytes)
      }
    }.toByteArray()
    atomic(output, encoded, check)
    return bytes
  }


  private suspend fun sourceBytes(
    zip: ZipFile, blob: PerfectIconBlob, source: PerfectIconSourceEntry,
  ): ByteArray {
    require(blob.size in 0..PerfectIconFormat.MAX_BLOB.toLong()) { "Selected source resource exceeds size limit" }
    try {
      val coroutine = currentCoroutineContext()
      val entry = requireNotNull(zip.getEntry(PerfectIconSourceZip.PREFIX + blob.source))
      require(entry.size == source.size && entry.crc == source.crc)
      val bytes = zip.getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var count = 0L
        while (true) {
          coroutine.ensureActive()
          val read = input.read(buffer)
          if (read < 0) break
          count += read
          require(count <= blob.size && count <= PerfectIconFormat.MAX_BLOB)
          output.write(buffer, 0, read)
        }
        output.toByteArray()
      }
      require(bytes.size.toLong() == blob.size && PerfectIconFormat.sha(bytes) == blob.sha &&
        CRC32().apply { update(bytes) }.value == source.crc) { "Local source resource integrity check failed" }
      return bytes
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (interrupted: InterruptedException) {
      throw interrupted
    } catch (failure: Exception) {
      localHealthProbe.invalidate()
      settings.write("corruptSourceHash", File(zip.name).nameWithoutExtension)
      throw failure
    }
  }

  private suspend fun previewHash(file: File): String {
    val coroutine = currentCoroutineContext()
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(32 * 1024)
      var count = 0L
      while (true) {
        coroutine.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        count += read
        require(count <= MAX_PREVIEW_FILE)
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun atomic(file: File, bytes: ByteArray, checkpoint: () -> Unit = {}) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, ".${UUID.randomUUID()}.part")
    try {
      FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
      checkpoint()
      move(temporary, file)
    } finally {
      temporary.delete()
    }
  }

  private fun move(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private class MmkvSettings(context: Context) : PerfectIconSettings {
    private val store = run {
      MMKV.initialize(context)
      requireNotNull(MMKV.mmkvWithID("perfect_icon_source", MMKV.MULTI_PROCESS_MODE))
    }
    override fun read(key: String): String? = store.decodeString(key)
    override fun write(key: String, value: String) {
      check(store.encode(key, value))
      store.sync()
    }
  }

  companion object {
    const val LAYER_CONTRACT = "keihome-perfect-layers-2"
    private const val MAX_PREVIEW_BYTES = 32L * 1024 * 1024
    private const val MAX_PREVIEW_FILE = MAX_PREVIEW_BYTES + 1024 * 1024
    private const val MAX_META = 32 * 1024 * 1024
    private val locks = ConcurrentHashMap<String, Mutex>()
  }
}
