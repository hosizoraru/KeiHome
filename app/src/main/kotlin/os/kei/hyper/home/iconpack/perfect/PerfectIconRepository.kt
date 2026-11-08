package os.kei.hyper.home.iconpack.perfect

import android.content.Context
import com.tencent.mmkv.MMKV
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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

/** One explicit conditional codeload request per refresh; generation is entirely local. */
class PerfectIconRepository internal constructor(
  private val root: File,
  private val transport: PerfectIconTransport,
  private val settings: PerfectIconSettings,
  private val now: () -> Long = System::currentTimeMillis,
  private val limits: PerfectIconZipLimits = PerfectIconZipLimits(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  constructor(context: Context) : this(
    File(context.applicationContext.filesDir, "perfect-icons"),
    PerfectIconUrlConnection(),
    MmkvSettings(context.applicationContext),
  )

  private val mutex = locks.getOrPut(root.absolutePath) { Mutex() }
  private val snapshotFile get() = File(root, "catalog.json")
  private val sourceDir get() = File(root, "source")
  private val blobsDir get() = File(root, "blobs")
  private val outputDir get() = File(root, "generated")
  private val previewDir get() = File(root, "previews")

  fun options(): PerfectIconOptions = runCatching {
    val json = JSONObject(settings.read("options") ?: return PerfectIconOptions())
    PerfectIconOptions(json.getString("style"), json.getBoolean("installedOnly"), json.getBoolean("dynamic"))
      .also { PerfectIconFormat.style(it.style) }
  }.getOrDefault(PerfectIconOptions())

  fun setOptions(options: PerfectIconOptions) {
    PerfectIconFormat.style(options.style)
    settings.write("options", JSONObject().put("style", options.style)
      .put("installedOnly", options.installedOnly).put("dynamic", options.dynamic).toString())
  }

  // Old REST API cooldowns do not block the independent codeload source.
  fun retryAtMillis(): Long = if (settings.read("cooldownSource") == PerfectIconUrls.SOURCE)
    settings.read("retryAt")?.toLongOrNull()?.coerceAtLeast(0) ?: 0 else 0

  suspend fun catalog(
    refresh: Boolean = false,
    progress: (PerfectIconProgress) -> Unit = {},
  ): PerfectIconCatalog? = withContext(ioDispatcher) {
    mutex.withLock {
      val old = localSnapshot()
      if (refresh) refresh(old, progress).catalog else old?.catalog
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
      PerfectIconFormat.validate(plan) { blob -> coroutine.ensureActive(); blobFile(blob.sha).readBytes() }
      val file = publish(snapshot, options, plan)
      trimBlobs(unique.map { it.sha }.toSet())
      PerfectIconResult(file, (plan.staticIds + plan.dynamicIds).size, plan.staticIds.size,
        plan.dynamicIds.size, extracted, reused, snapshot.revision)
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
      val identity = "preview-v1\n${snapshot.sourceHash}\n$style\n${options.dynamic}\n$sampleLimit"
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
        PerfectIconFormat.validate(plan, ::read)
        writeArchive(destination, options, plan, maxFile = MAX_PREVIEW_FILE, read = ::read)
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
    (file.parentFile == blobsDir || file.toPath().startsWith(previewDir.toPath()) ||
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

  private suspend fun refresh(old: Snapshot?, progress: (PerfectIconProgress) -> Unit): Snapshot {
    val coroutine = currentCoroutineContext()
    sourceDir.mkdirs()
    val temporary = File(sourceDir, ".${UUID.randomUUID()}.part")
    var published: File? = null
    var committed = false
    try {
      val time = now()
      if (retryAtMillis() > time) throw PerfectIconCooldownException(retryAtMillis())
      // A 304 cannot repair disk corruption. Only offer a validator for verified local bytes.
      val reusable = old?.takeIf { snapshot ->
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

  private suspend fun publish(snapshot: Snapshot, options: PerfectIconOptions, plan: PerfectIconPlan): File {
    outputDir.mkdirs()
    val identity = buildString {
      append(snapshot.sourceHash).append('\n').append(options.style.ifEmpty { "default" })
      append('\n').append(options.dynamic).append('\n')
      plan.files.forEach { (path, blob) -> append(path).append('\u0000').append(blob.sha).append('\n') }
    }
    val key = PerfectIconFormat.sha(identity.toByteArray(Charsets.UTF_8)).take(24)
    val destination = File(outputDir, "perfect-${snapshot.sourceHash.take(12)}-$key.zip")
    writeArchive(destination, options, plan) { blob -> blobFile(blob.sha).readBytes() }
    // Imports own an independent copy. Keep the new publication and two prior usable outputs.
    outputDir.listFiles().orEmpty().filter { it != destination && it.name.startsWith("perfect-") && it.extension == "zip" }
      .sortedByDescending { it.lastModified() }.drop(2).forEach { it.delete() }
    return destination
  }

  private suspend fun writeArchive(
    destination: File,
    options: PerfectIconOptions,
    plan: PerfectIconPlan,
    maxFile: Long = Long.MAX_VALUE,
    read: (PerfectIconBlob) -> ByteArray,
  ) {
    val temporary = File(destination.parentFile, ".${UUID.randomUUID()}.part")
    val coroutine = currentCoroutineContext()
    val generated = mapOf(
      "transform_config.xml" to PerfectIconFormat.transform(options.dynamic),
    )
    try {
      FileOutputStream(temporary).use { output ->
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
          fun entry(path: String, bytes: ByteArray) {
            coroutine.ensureActive()
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
          plan.files.forEach { (path, blob) -> entry(path, read(blob)) }
          zip.finish()
          zip.flush()
          output.fd.sync()
        }
      }
      require(temporary.length() <= maxFile) { "Perfect Icons preview ZIP exceeds its size limit" }
      // Validate the archive that will be published, not just its inputs.
      ZipFile(temporary, Charsets.UTF_8).use { zip ->
        require(zip.size() == plan.files.size + generated.size)
        plan.files.forEach { (path, blob) ->
          coroutine.ensureActive()
          val entry = requireNotNull(zip.getEntry(path))
          require(entry.size == blob.size)
          require(PerfectIconFormat.sha(zip.getInputStream(entry).use { it.readBytes() }) == blob.sha)
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
    }
  }


  private suspend fun sourceBytes(
    zip: ZipFile, blob: PerfectIconBlob, source: PerfectIconSourceEntry,
  ): ByteArray {
    require(blob.size in 0..PerfectIconFormat.MAX_BLOB.toLong()) { "Selected source resource exceeds size limit" }
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
    override fun write(key: String, value: String) { check(store.encode(key, value)) }
  }

  companion object {
    private const val MAX_PREVIEW_BYTES = 32L * 1024 * 1024
    private const val MAX_PREVIEW_FILE = MAX_PREVIEW_BYTES + 1024 * 1024
    private const val MAX_META = 32 * 1024 * 1024
    private val locks = ConcurrentHashMap<String, Mutex>()
  }
}
