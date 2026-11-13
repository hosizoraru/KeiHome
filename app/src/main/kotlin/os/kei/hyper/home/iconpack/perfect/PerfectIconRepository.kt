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
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.data.DiskCacheUsage
import os.kei.hyper.home.data.measureDiskCache
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

/** Explicit anonymous Git synchronization; generation and already acquired styles stay offline. */
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
  private val git: PerfectIconGitBackend? = null,
  private val staticEquivalentRenderer:
    ((ByteArray, List<ByteArray>, String?, () -> Unit) -> PerfectStaticPair)? = null,
) {
  constructor(context: Context) : this(
    File(context.applicationContext.filesDir, "perfect-icons"),
    PerfectIconUrlConnection(),
    MmkvSettings(context.applicationContext),
    nativeMask = { os.kei.hyper.home.iconpack.IconMaskPolicy.snapshot(context).let {
      if (it.enabled) it.defaultPath else null
    } },
    staticRenderer = { layers, mask, check ->
      PerfectStaticNightRenderer.render(layers, mask, check = check)
    },
    staticEquivalentRenderer = { flat, layers, mask, check ->
      PerfectStaticNightRenderer.render(listOf(flat), mask, equivalentLayers = layers, check = check)
    },
    dynamicNightRenderer = { bytes, background, check ->
      PerfectDynamicNightRenderer.render(bytes, background, check)
    },
    git = PerfectIconGit(File(context.applicationContext.filesDir, "perfect-icons/git"),
      networkOptions = PerfectGitNetworkPreferences(context)::read),
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
  private var styleSizeCache: Pair<String, Map<String, PerfectIconStyleDetails>>? = null
  private val syncHistory = PerfectSyncHistory(settings::read, settings::write, now)
  private val localHealthProbe = PerfectLocalHealthProbe()

  private data class FallbackIndex(
    val stamp: LocalFileStamp,
    val snapshot: Snapshot,
    val entries: Map<String, PerfectIconSourceEntry>,
    val plans: LinkedHashMap<String, Map<String, List<PerfectIconBlob>>> = linkedMapOf(),
  )

  internal data class FallbackIcon(val layers: List<ByteArray>)

  internal interface FallbackLookupSession {
    val sourceHash: String
    val nativeNames: Set<String>
    suspend fun icon(names: List<String>): FallbackIcon?
    /** Recheck the published hash before accepting the completed preview/export. */
    suspend fun validate()
    suspend fun close()
  }

  private data class LocalFileStamp(
    val key: Any?,
    val modified: java.nio.file.attribute.FileTime,
    val created: java.nio.file.attribute.FileTime,
    val size: Long,
  )

  private fun localFileStamp(file: File, directory: Boolean = false): LocalFileStamp {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(if (directory) attributes.isDirectory else attributes.isRegularFile) {
      "Perfect Icons local resources are unavailable"
    }
    return LocalFileStamp(attributes.fileKey(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.size())
  }

  private data class FallbackSourceStamp(val file: LocalFileStamp, val tip: LocalFileStamp? = null)

  private fun fallbackSourceStamp(snapshot: Snapshot): FallbackSourceStamp {
    require(settings.read("corruptSourceHash") != snapshot.sourceHash) {
      "Perfect Icons local source is unavailable"
    }
    return if (snapshot.gitStore.isNotEmpty()) {
      // Packs live directly in this directory. No directory listing or Git/ref access on the lookup path.
      val directory = File(root, "git/${snapshot.gitStore}")
      FallbackSourceStamp(localFileStamp(directory, directory = true), localFileStamp(File(directory, "tip")))
    } else FallbackSourceStamp(localFileStamp(archive(snapshot)))
  }

  /** One immutable static ID index and one lazy reader; no network, generation, or decoded image cache. */
  internal suspend fun openFallbackSession(style: String): FallbackLookupSession = withContext(ioDispatcher) {
    mutex.withLock {
      val index = fallbackIndex(style)
      val sourceStamp = fallbackSourceStamp(index.snapshot)
      val gitStamp = index.snapshot.gitStore.takeIf { it.isNotEmpty() }?.let { requireNotNull(git).stamp(it) }
      val coroutine = currentCoroutineContext()
      val icons = index.plans.getOrPut(style) {
        fun branch(prefix: String) = index.snapshot.entries.mapNotNull {
          coroutine.ensureActive()
          if (it.path.startsWith("$prefix/"))
            PerfectIconBlob(it.path.removePrefix("$prefix/"), it.sha, it.size, it.path) else null
        }
        val plan = PerfectIconFormat.plan(branch("icons"), branch("themes/$style/icons"), emptyList(), emptyList(),
          PerfectIconOptions(style, installedOnly = false, dynamic = false), emptySet())
        Collections.unmodifiableMap(plan.staticIds.associateWith { name ->
          coroutine.ensureActive()
          val prefix = "res/drawable-xxhdpi/$name"
          Collections.unmodifiableList(plan.files["$prefix.png"]?.let(::listOf)
            ?: listOf(plan.files.getValue("$prefix/0.png"), plan.files.getValue("$prefix/1.png")))
        })
      }
      while (index.plans.size > 2) index.plans.remove(index.plans.keys.first())
      LocalFallbackSession(style, index, icons, sourceStamp, gitStamp).also { it.checkAvailable() }
    }
  }

  private inner class LocalFallbackSession(
    private val style: String,
    private val index: FallbackIndex,
    private val icons: Map<String, List<PerfectIconBlob>>,
    private val sourceStamp: FallbackSourceStamp,
    private val gitStamp: String?,
  ) : FallbackLookupSession {
    override val sourceHash = index.snapshot.sourceHash
    override val nativeNames: Set<String> = Collections.unmodifiableSet(icons.keys)
    private var invalidated = false
    private var extracted = false
    private var closed = false
    private var reader: PerfectGitReader? = null

    fun checkAvailable() {
      check(!closed) { "Perfect Icons lookup session is closed" }
      check(!invalidated) { "Perfect Icons lookup session is no longer valid; open a new session" }
      try {
        require(localFileStamp(snapshotFile) == index.stamp && fallbackSourceStamp(index.snapshot) == sourceStamp) {
          "Perfect Icons source changed during generation; retry with the current local resources"
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        invalidated = true
        throw failure
      }
    }

    override suspend fun icon(names: List<String>): FallbackIcon? = withContext(ioDispatcher) {
      mutex.withLock {
        try {
          val coroutine = currentCoroutineContext()
          coroutine.ensureActive()
          // Check even misses and cache hits: removed resources must never be revived by a pinned index.
          checkAvailable()
          val blobs = names.asSequence().mapNotNull { name ->
            coroutine.ensureActive()
            icons[name]
          }.firstOrNull() ?: return@withLock null
          FallbackIcon(blobs.map { blob ->
            coroutine.ensureActive()
            val file = blobFile(blob.sha)
            val bytes = fallbackCachedBlob(blob) ?: run {
              val source = reader ?: openSource(index.snapshot).also { reader = it }
              sourceBytes(source, blob, index.entries.getValue(blob.source)).also {
                atomic(file, it) { coroutine.ensureActive() }
                extracted = true
              }
            }
            coroutine.ensureActive()
            PerfectIconFormat.png(bytes, square = true)
            bytes
          }).also {
            checkAvailable()
          }
        } catch (failure: Throwable) {
          try { closeReader() } catch (closing: Exception) { failure.addSuppressed(closing) }
          throw failure
        }
      }
    }

    override suspend fun validate() = withContext(ioDispatcher) {
      mutex.withLock {
        try {
          currentCoroutineContext().ensureActive()
          checkAvailable()
          val current = localSnapshot() ?: throw IOException("Perfect Icons local resources are unavailable")
          if (!(current.sourceHash == sourceHash && style in current.downloadedStyles &&
            current.revision == index.snapshot.revision && current.entries == index.snapshot.entries &&
            current.gitStore == index.snapshot.gitStore &&
            (gitStamp == null || requireNotNull(git).stamp(current.gitStore) == gitStamp))) {
            invalidated = true
            throw IOException("Perfect Icons source changed during generation; retry with the current local resources")
          }
          currentCoroutineContext().ensureActive()
          checkAvailable()
          // Do directory maintenance once at batch completion, rather than scanning an expanding cache per icon.
          if (extracted) {
            trimBlobs(icons.values.flatten().mapTo(hashSetOf()) { it.sha })
            extracted = false
          }
        } catch (cancelled: CancellationException) {
          try { closeReader() } catch (closing: Exception) { cancelled.addSuppressed(closing) }
          throw cancelled
        } catch (failure: Exception) {
          invalidated = true
          try { closeReader() } catch (closing: Exception) { failure.addSuppressed(closing) }
          throw failure
        } finally { closeReader() }
      }
    }

    private fun closeReader() {
      val old = reader
      reader = null
      old?.close()
    }

    override suspend fun close() = withContext(ioDispatcher + NonCancellable) {
      mutex.withLock {
        closed = true
        closeReader()
      }
    }
  }

  private suspend fun fallbackCachedBlob(blob: PerfectIconBlob): ByteArray? {
    require(blob.size in 0..PerfectIconFormat.MAX_BLOB.toLong()) { "Selected source resource exceeds size limit" }
    val file = blobFile(blob.sha)
    if (!file.isFile || file.length() != blob.size) return null
    val coroutine = currentCoroutineContext()
    val bytes = try {
      file.inputStream().use { input ->
        val bytes = ByteArray(blob.size.toInt())
        var offset = 0
        while (offset < bytes.size) {
          coroutine.ensureActive()
          val count = input.read(bytes, offset, minOf(32 * 1024, bytes.size - offset))
          if (count < 0) return null
          offset += count
        }
        if (input.read() != -1) return null
        bytes
      }
    } catch (_: IOException) {
      return null
    }
    coroutine.ensureActive()
    return bytes.takeIf { PerfectIconFormat.sha(it) == blob.sha }
  }

  /** Compatibility for one-off callers; batch callers should retain one lookup session. */
  internal suspend fun fallbackIcon(style: String, names: List<String>, expectedSourceHash: String? = null): FallbackIcon? {
    val source = openFallbackSession(style)
    require(expectedSourceHash == null || expectedSourceHash == source.sourceHash) {
      "Perfect Icons source changed during generation; retry with the current local resources"
    }
    return try {
      source.icon(names).also { source.validate() }
    } finally { source.close() }
  }

  internal suspend fun fallbackSourceHash(style: String): String = withContext(ioDispatcher) {
    mutex.withLock {
      fallbackIndex(style).snapshot.also { fallbackSourceStamp(it) }.sourceHash
    }
  }

  private fun fallbackIndex(style: String): FallbackIndex {
    PerfectIconFormat.style(style)
    val stamp = localFileStamp(snapshotFile)
    val index = fallbackIndex?.takeIf { it.stamp == stamp } ?: run {
      val snapshot = localSnapshot() ?: throw IOException("Perfect Icons local resources are unavailable")
      FallbackIndex(stamp, snapshot, snapshot.entries.associateBy { it.path }).also { fallbackIndex = it }
    }
    require(style in index.snapshot.downloadedStyles) { "Download the Perfect Icons style first: $style" }
    require(sourceExists(index.snapshot)) { "Perfect Icons local source is unavailable" }
    return index
  }

  fun options(): PerfectIconOptions = runCatching {
    val json = JSONObject(settings.read("options") ?: return PerfectIconStyles.initialOptions)
    PerfectIconOptions(json.getString("style"), json.getBoolean("installedOnly"), json.getBoolean("dynamic"),
      json.optBoolean("staticNight", false))
      .also { PerfectIconFormat.style(it.style) }
  }.getOrDefault(PerfectIconStyles.initialOptions)

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

  /** A remote check never publishes source data or changes the last successful resource sync. */
  internal suspend fun checkUpdates(
    progress: (PerfectIconProgress) -> Unit = {},
  ): PerfectIconRemote = withContext(ioDispatcher) {
    mutex.withLock {
      val backend = git ?: throw IOException("Git synchronization is unavailable")
      if (retryAtMillis() > now()) throw PerfectIconCooldownException(retryAtMillis())
      val remote = try {
        backend.check(progress)
      } catch (failure: Exception) {
        if (failure !is CancellationException) cooldownCause(failure)?.let {
          cooldown(it.retryAt)
          throw it
        }
        throw failure
      }
      currentCoroutineContext().ensureActive()
      saveRemote(remote)
      remote
    }
  }

  internal suspend fun remoteStatus(): PerfectIconRemote? = withContext(ioDispatcher) {
    mutex.withLock { readRemote() }
  }

  private fun readRemote(): PerfectIconRemote? = runCatching {
    val value = settings.read("gitRemote") ?: return null
    require(value.length <= 16_384)
    val json = JSONObject(value)
    val styles = json.optJSONArray("styles")
    require(styles == null || styles.length() <= 256)
    PerfectIconRemote(json.getString("revision").also { require(it.matches(Regex("[0-9a-f]{40}"))) },
      json.getLong("committedAt").also { require(it >= 0) },
      json.getString("subject").take(1024),
      json.getLong("checkedAt").also { require(it >= 0) },
      styles?.let { values -> (0 until values.length()).map {
        values.getString(it).also(PerfectIconFormat::style)
      } }.orEmpty())
  }.getOrNull()

  private fun saveRemote(remote: PerfectIconRemote) {
    require(remote.revision.matches(Regex("[0-9a-f]{40}")) &&
      remote.committedAt >= 0 && remote.checkedAt >= 0)
    settings.write("gitRemote", JSONObject().put("revision", remote.revision)
      .put("committedAt", remote.committedAt).put("subject", remote.subject.take(1024))
      .put("checkedAt", remote.checkedAt).put("styles", JSONArray(remote.availableStyles)).toString())
  }

  /** Metadata/object headers only. Full payload hashes are checked on acquisition and reads. */
  internal suspend fun localStatus(): PerfectLocalStatus = withContext(ioDispatcher) {
    mutex.withLock {
      val snapshot = localSnapshot()
      val knownHash = settings.read("knownSourceHash")
      val corruptHash = settings.read("corruptSourceHash")
      val sourceHash = snapshot?.sourceHash ?: knownHash.orEmpty()
      val source = if (snapshot?.gitStore?.isNotEmpty() == true) File(root, "git/${snapshot.gitStore}")
        else File(sourceDir, "$sourceHash.zip")
      val gitStamp = if (snapshot?.gitStore?.isNotEmpty() == true)
        runCatching { git?.stamp(snapshot.gitStore) }.getOrNull().orEmpty() else ""
      val stamp = PerfectLocalProbeStamp(snapshotFile.exists(), snapshotFile.length(), snapshotFile.lastModified(),
        sourceHash, snapshot?.let(::sourceExists) ?: source.isFile, source.length(), source.lastModified(),
        corruptHash, snapshot?.sourceHash ?: knownHash,
        if (gitStamp.isNotEmpty()) gitStamp.hashCode().toLong() else sourceDir.lastModified())
      val referenced = snapshotFile.exists() || !knownHash.isNullOrEmpty() ||
        sourceDir.listFiles().orEmpty().any { it.isFile && it.extension == "zip" }
      val coroutine = currentCoroutineContext()
      val healthy = localHealthProbe.healthy(stamp) {
        snapshot != null && corruptHash != snapshot.sourceHash && try {
          openSource(snapshot).use { it.healthy(snapshot.entries) { coroutine.ensureActive() } }
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
    styles: Set<String> = emptySet(),
    progress: (PerfectIconProgress) -> Unit = {},
  ): PerfectIconCatalog? = withContext(ioDispatcher) {
    mutex.withLock {
      val old = localSnapshot()
      if (!refresh) return@withLock old?.catalog
      // A rejected local cooldown is not a new network attempt.
      if (retryAtMillis() > now()) throw PerfectIconCooldownException(retryAtMillis())
      syncHistory.started()
      try {
        val catalog = if (git != null) refreshGit(old, styles, progress, forceDownload).catalog
          else refresh(old, progress, forceDownload).catalog
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
        cooldownCause(failure)?.let {
          cooldown(it.retryAt)
          throw it
        }
        throw failure
      } finally {
        localHealthProbe.invalidate()
      }
    }
  }

  private fun cooldownCause(failure: Throwable): PerfectIconCooldownException? =
    generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(16)
      .filterIsInstance<PerfectIconCooldownException>().firstOrNull()

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
      require(style in snapshot.downloadedStyles) { "Download the Perfect Icons style first: $style" }
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
      openSource(snapshot).use { zip ->
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
      require(style in snapshot.downloadedStyles) { "Download the Perfect Icons style first: $style" }
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
        openSource(snapshot).use { zip ->
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

  /**
   * Full local footprint, including protected source/catalog/publications; no download lock or decoding.
   * Concurrent writes may change this snapshot; traversal failures are reported through complete.
   */
  internal suspend fun storageUsage(): DiskCacheUsage = withContext(ioDispatcher) {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    measureDiskCache(root) { coroutine.ensureActive() }
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

  /** Keep the usable source/catalog and completed outputs, so generation remains offline. */
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

  private fun resourceDirectories() = listOf(File(root, "git"), sourceDir, blobsDir, previewDir,
    renderedDir, File(root, "trees"))

  internal suspend fun resourceBytes(): Long = withContext(ioDispatcher) {
    mutex.withLock {
      val coroutine = currentCoroutineContext()
      resourceDirectories().sumOf { measureDiskCache(it) { coroutine.ensureActive() }.total } +
        snapshotFile.length()
    }
  }

  /** Confirmed source removal is serialized with all readers and never deletes generated exports. */
  internal suspend fun deleteResources(): Long = withContext(ioDispatcher) {
    mutex.withLock {
      currentCoroutineContext().ensureActive()
      deleteResourcesLocked()
    }
  }

  private suspend fun deleteResourcesLocked(): Long = withContext(NonCancellable) {
    val before = resourceDirectories().sumOf { measureDiskCache(it).total } + snapshotFile.length()
    require(!snapshotFile.exists() || snapshotFile.delete()) { "Cannot remove Perfect Icons source index" }
    fallbackIndex = null
    localHealthProbe.invalidate()
    settings.write("knownSourceHash", "")
    settings.write("corruptSourceHash", "")
    settings.write("localCorruptAt", "0")
    settings.write("retryAt", "0")
    settings.write("gitRemote", "")
    settings.write("syncHistoryV1", "{}")
    var complete = true
    resourceDirectories().forEach { if (it.exists() && !it.deleteRecursively()) complete = false }
    require(complete) { "Some Perfect Icons resources could not be removed; try again" }
    before
  }

  /** Rebuild only the remaining local graph; removal never needs the upstream repository. */
  internal suspend fun removeStyles(
    styles: Set<String>,
    progress: (PerfectIconProgress) -> Unit = {},
  ): Long = withContext(ioDispatcher) {
    mutex.withLock {
      require(styles.isNotEmpty())
      styles.forEach(PerfectIconFormat::style)
      val old = localSnapshot() ?: return@withLock 0L
      val removed = styles.intersect(old.downloadedStyles.toSet())
      if (removed.isEmpty()) return@withLock 0L
      val remaining = old.downloadedStyles.toSet() - removed
      if (remaining.isEmpty()) return@withLock deleteResourcesLocked()
      require(old.gitStore.isNotEmpty()) { "Refresh legacy source resources before removing individual styles" }
      val coroutine = currentCoroutineContext()
      val paths = PerfectIconSelection.paths(old.entries.map { it.path }, remaining)
      val entries = old.entries.filter { it.path in paths }
      require(entries.isNotEmpty())
      val before = resourceDirectories().sumOf { measureDiskCache(it) { coroutine.ensureActive() }.total } +
        snapshotFile.length()
      val retained = requireNotNull(git).retain(old.gitStore, entries, progress)
      var published = false
      try {
        coroutine.ensureActive()
        require(retained.store.matches(Regex("[a-zA-Z0-9-]{1,80}")) &&
          retained.store != old.gitStore && git.exists(retained.store) &&
          retained.storedBytes in 0..limits.compressed)
        val snapshot = old.copy(entries = entries, downloadedStyles = remaining.sorted(),
          sourceHash = graphHash(entries, remaining), gitStore = retained.store,
          archiveBytes = retained.storedBytes, count = iconCount(entries, remaining))
        val encoded = snapshot.json().toString().toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_META)
        atomic(snapshotFile, encoded) { coroutine.ensureActive() }
        published = true
        fallbackIndex = null
        localHealthProbe.invalidate()
        settings.write("knownSourceHash", snapshot.sourceHash)
        settings.write("corruptSourceHash", "")
        withContext(NonCancellable) {
          runCatching { git.published(snapshot.gitStore, snapshot.revision) }
          // These are rebuildable copies; remaining source objects are already durable.
          listOf(blobsDir, previewDir, renderedDir, File(root, "trees")).forEach { it.deleteRecursively() }
        }
        val after = resourceDirectories().sumOf { measureDiskCache(it).total } + snapshotFile.length()
        (before - after).coerceAtLeast(0)
      } finally {
        if (!published && retained.store != old.gitStore && retained.store.matches(Regex("[a-zA-Z0-9-]{1,80}")))
          File(root, "git/${retained.store}").deleteRecursively()
      }
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
    val gitStore: String = "",
    val downloadedStyles: List<String> = styles,
    val remoteCommittedAt: Long = 0,
    val remoteSubject: String = "",
    val transferredBytes: Long = 0,
  ) {
    fun json(): JSONObject = JSONObject().put("version", if (gitStore.isEmpty()) 2 else 3)
      .put("revision", revision).put("sourceHash", sourceHash)
      .put("archiveBytes", archiveBytes).put("styles", JSONArray(styles)).put("updatedAt", updatedAt)
      .put("downloadedAt", downloadedAt).put("count", count).put("etag", etag).put("upToDate", upToDate)
      .put("gitStore", gitStore).put("downloadedStyles", JSONArray(downloadedStyles))
      .put("remoteCommittedAt", remoteCommittedAt).put("remoteSubject", remoteSubject)
      .put("transferredBytes", transferredBytes)
      .put("entries", JSONArray(entries.map { JSONArray(listOf(it.path, it.sha, it.size, it.crc)) }))
  }

  private val Snapshot.catalog: PerfectIconCatalog
    get() {
      // Old snapshots already contain this index; no resource-tree walk or redownload is needed.
      val sizeKey = "$sourceHash\n${downloadedStyles.joinToString("\n")}"
      val sizes = styleSizeCache?.takeIf { it.first == sizeKey }?.second ?: run {
        val byPath = entries.associateBy { it.path }
        val paths = byPath.keys.toList()
        downloadedStyles.associateWith { style ->
          val objects = HashMap<String, Long>()
          val sharedObjects = HashSet<String>()
          val selectedStyle = style.ifEmpty { "default" }
          PerfectIconSelection.paths(paths, setOf(selectedStyle)).forEach { path ->
            val entry = byPath.getValue(path)
            val previous = objects.putIfAbsent(entry.sha, entry.size)
            require(previous == null || previous == entry.size) { "Inconsistent resource object size" }
            if (!path.startsWith("themes/$selectedStyle/")) sharedObjects += entry.sha
          }
          val bytes = objects.values.fold(0L, Math::addExact)
          val sharedBytes = sharedObjects.fold(0L) { sum, sha -> Math.addExact(sum, objects.getValue(sha)) }
          PerfectIconStyleDetails(bytes = bytes, ownBytes = bytes - sharedBytes, sharedBytes = sharedBytes)
        }.also { styleSizeCache = sizeKey to it }
      }
      val artwork = if (gitStore.isNotEmpty()) git?.styleIcons(revision).orEmpty() else emptyMap()
      val details = styles.mapNotNull { style ->
        if (style !in sizes && style !in artwork) null else
          style to (sizes[style] ?: PerfectIconStyleDetails()).copy(iconFile = artwork[style])
      }.toMap()
      return PerfectIconCatalog(revision, styles, updatedAt, count, downloadedAt, sourceHash,
        upToDate, archiveBytes, downloadedStyles, remoteCommittedAt, remoteSubject, transferredBytes,
        gitStore.isNotEmpty(), details)
    }

  private fun archive(snapshot: Snapshot) = File(sourceDir, "${snapshot.sourceHash}.zip")

  private fun localSnapshot(): Snapshot? = runCatching {
    if (!snapshotFile.isFile || snapshotFile.length() > MAX_META) return null
    val json = JSONObject(snapshotFile.readText(Charsets.UTF_8))
    val version = json.getInt("version")
    require(version in 2..3)
    val revision = json.getString("revision")
    require(revision.isEmpty() || revision.matches(Regex("[0-9a-f]{40}")))
    val sourceHash = json.getString("sourceHash")
    require(sourceHash.matches(Regex("[0-9a-f]{64}")))
    val styles = json.getJSONArray("styles")
    require(styles.length() in 1..256)
    val entries = json.getJSONArray("entries")
    require(entries.length() in 1..limits.entries)
    val paths = hashSetOf<String>()
    val available = (0 until styles.length()).map { styles.getString(it).also(PerfectIconFormat::style) }.sorted()
    require(available.distinct().size == available.size)
    val store = if (version == 3) json.getString("gitStore").also {
      require(it.matches(Regex("[a-zA-Z0-9-]{1,80}")))
    } else ""
    val downloaded = if (version == 3) json.getJSONArray("downloadedStyles").let { array ->
      require(array.length() in 1..256)
      (0 until array.length()).map { array.getString(it).also(PerfectIconFormat::style) }.sorted()
        .also { require(it.distinct().size == it.size && available.containsAll(it)) }
    } else available
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
      available,
      json.getLong("updatedAt").also { require(it >= 0) },
      json.getLong("downloadedAt").also { require(it >= 0) },
      json.getInt("count").also { require(it in 0..limits.entries) },
      json.optString("etag", ""), json.optBoolean("upToDate", false), store, downloaded,
      json.optLong("remoteCommittedAt", 0).also { require(it >= 0) },
      json.optString("remoteSubject", "").take(1024),
      json.optLong("transferredBytes", 0).also { require(it >= 0) })
    require(snapshot.entries.sumOf { it.size } <= limits.expanded)
    require(snapshot.archiveBytes in 0..limits.compressed && sourceExists(snapshot))
    snapshot
  }.getOrNull()

  private fun sourceExists(snapshot: Snapshot): Boolean =
    if (snapshot.gitStore.isNotEmpty()) git?.exists(snapshot.gitStore) == true
    else archive(snapshot).isFile && archive(snapshot).length() == snapshot.archiveBytes

  private fun openSource(snapshot: Snapshot): PerfectGitReader {
    val delegate = if (snapshot.gitStore.isNotEmpty()) requireNotNull(git).open(snapshot.gitStore)
      else ZipSourceReader(ZipFile(archive(snapshot), Charsets.UTF_8))
    return object : PerfectGitReader {
      override fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray = try {
        delegate.read(entry, check)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (interrupted: InterruptedException) {
        throw interrupted
      } catch (failure: Exception) {
        localHealthProbe.invalidate()
        settings.write("corruptSourceHash", snapshot.sourceHash)
        throw failure
      }
      override fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit): Boolean =
        delegate.healthy(entries, check)
      override fun close() = delegate.close()
    }
  }

  private class ZipSourceReader(private val zip: ZipFile) : PerfectGitReader {
    override fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray {
      val source = requireNotNull(zip.getEntry(PerfectIconSourceZip.PREFIX + entry.path))
      require(source.size == entry.size && source.crc == entry.crc)
      require(entry.size in 0..PerfectIconFormat.MAX_BLOB.toLong())
      val output = ByteArrayOutputStream()
      zip.getInputStream(source).use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
          check()
          val count = input.read(buffer)
          if (count < 0) break
          require(output.size().toLong() + count <= entry.size)
          output.write(buffer, 0, count)
        }
      }
      return output.toByteArray().also {
        require(it.size.toLong() == entry.size && PerfectIconFormat.sha(it) == entry.sha &&
          CRC32().apply { update(it) }.value == entry.crc) { "Local source resource integrity check failed" }
      }
    }

    override fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit): Boolean =
      entries.all { source ->
        check()
        val entry = zip.getEntry(PerfectIconSourceZip.PREFIX + source.path)
        entry != null && entry.size == source.size && entry.crc == source.crc
      }
    override fun close() = zip.close()
  }

  private suspend fun refreshGit(
    old: Snapshot?, requestedStyles: Set<String>, progress: (PerfectIconProgress) -> Unit, repair: Boolean,
  ): Snapshot {
    val backend = requireNotNull(git)
    val coroutine = currentCoroutineContext()
    val selected = requestedStyles.ifEmpty { setOf(options().style.ifEmpty { "default" }) }
    selected.forEach(PerfectIconFormat::style)
    require(selected.size <= 256)
    val retained = if (old?.gitStore?.isNotEmpty() == true) old.downloadedStyles.toSet() else emptySet()
    var fetched = backend.fetch(old?.gitStore?.takeIf { it.isNotEmpty() }, repair, retained + selected, progress)
    val pendingStores = mutableSetOf(fetched.store)
    var published = false
    try {
      coroutine.ensureActive()
      require(fetched.remote.revision.matches(Regex("[0-9a-f]{40}")))
      require(fetched.store.matches(Regex("[a-zA-Z0-9-]{1,80}")) && backend.exists(fetched.store))
      require(fetched.availableStyles.size in 1..256 && fetched.downloadedStyles.isNotEmpty() &&
        fetched.downloadedStyles.size <= 256 && fetched.availableStyles.containsAll(fetched.downloadedStyles) &&
        fetched.downloadedStyles.containsAll(selected))
      fetched.availableStyles.forEach(PerfectIconFormat::style)
      require(fetched.entries.size in 1..limits.entries && fetched.storedBytes in 0..limits.compressed &&
        fetched.transferredBytes >= 0 && fetched.remote.committedAt >= 0)
      val paths = hashSetOf<String>()
      var expanded = 0L
      fetched.entries.sortedBy { it.path }.forEach { entry ->
        coroutine.ensureActive()
        PerfectIconFormat.safePath(entry.path)
        require(paths.add(entry.path) && entry.sha.matches(Regex("[0-9a-f]{40}")))
        require(entry.path.startsWith("icons/") ||
          entry.path.startsWith("themes/common/layer_animating_icons/") ||
          fetched.downloadedStyles.any { entry.path.startsWith("themes/$it/") })
        require(entry.size in 0..PerfectIconFormat.MAX_BLOB.toLong() && entry.size <= limits.expanded - expanded)
        expanded += entry.size
      }
      if (old != null && old.revision != fetched.remote.revision &&
        backend.maintenanceNeeded(fetched.store, expanded)) {
        val compacted = backend.retain(fetched.store, fetched.entries, progress)
        pendingStores.add(compacted.store)
        fetched = fetched.copy(store = compacted.store, storedBytes = compacted.storedBytes)
      }
      val count = iconCount(fetched.entries, fetched.downloadedStyles.toSet())
      require(count > 0) { "Perfect Icons source lacks usable icon resources" }
      val hash = graphHash(fetched.entries, fetched.downloadedStyles.toSet())
      val checkedAt = now()
      val snapshot = Snapshot(fetched.remote.revision, hash, fetched.storedBytes, fetched.entries,
        fetched.availableStyles.sorted(), checkedAt,
        if (hash == old?.sourceHash) old.downloadedAt else checkedAt, count, "", hash == old?.sourceHash,
        fetched.store, fetched.downloadedStyles.sorted(), fetched.remote.committedAt,
        fetched.remote.subject.take(1024), fetched.transferredBytes)
      val encoded = snapshot.json().toString().toByteArray(Charsets.UTF_8)
      require(encoded.size <= MAX_META) { "Perfect Icons source index exceeds its size limit" }
      atomic(snapshotFile, encoded) { coroutine.ensureActive() }
      published = true
      fallbackIndex = null
      // The pointer is durable. Housekeeping must not turn this successful publication into a failed sync.
      runCatching { backend.published(snapshot.gitStore, snapshot.revision) }
      runCatching { saveRemote(fetched.remote) }
      sourceDir.listFiles().orEmpty().filter { it.isFile && it.extension == "zip" }.forEach { it.delete() }
      return snapshot
    } finally {
      if (!published) pendingStores.filter {
        it != old?.gitStore && it.matches(Regex("[a-zA-Z0-9-]{1,80}"))
      }.forEach { File(root, "git/$it").deleteRecursively() }
    }
  }

  private fun graphHash(entries: List<PerfectIconSourceEntry>, styles: Set<String>): String {
    val identity = MessageDigest.getInstance("SHA-256")
    entries.sortedBy { it.path }.forEach {
      identity.update("${it.path}\u0000${it.sha}\u0000${it.size}\n".toByteArray(Charsets.UTF_8))
    }
    styles.sorted().forEach { identity.update("style:$it\n".toByteArray(Charsets.UTF_8)) }
    return identity.digest().joinToString("") { "%02x".format(it) }
  }

  private fun iconCount(entries: List<PerfectIconSourceEntry>, styles: Set<String>): Int {
    val base = entries.filter { it.path.startsWith("icons/") }.map {
      PerfectIconBlob(it.path.removePrefix("icons/"), it.sha, it.size, it.path)
    }
    val common = entries.filter { it.path.startsWith("themes/common/layer_animating_icons/") }.map {
      PerfectIconBlob(it.path.removePrefix("themes/common/layer_animating_icons/"), it.sha, it.size, it.path)
    }
    return styles.maxOf { style ->
      val static = entries.filter { it.path.startsWith("themes/$style/icons/") }.map {
        PerfectIconBlob(it.path.removePrefix("themes/$style/icons/"), it.sha, it.size, it.path)
      }
      val dynamic = entries.filter { it.path.startsWith("themes/$style/layer_animating_icons/") }.map {
        PerfectIconBlob(it.path.removePrefix("themes/$style/layer_animating_icons/"), it.sha, it.size, it.path)
      }
      val plan = PerfectIconFormat.plan(base, static, common, dynamic,
        PerfectIconOptions(style = style, installedOnly = false, dynamic = true), emptySet())
      (plan.staticIds + plan.dynamicIds).size
    }
  }

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
        flat != null -> {
          val sourcePrefix = flat.source.removeSuffix(".png")
          val sameTheme = layers.size == 2 && layers[0].source == "$sourcePrefix/0.png" &&
            layers[1].source == "$sourcePrefix/1.png"
          name to if (sameTheme) listOf(flat) + layers else listOf(flat)
        }
        layers.size == 2 -> name to layers
        else -> null
      }
    }.toMap()

  private fun staticPair(
    inputs: List<PerfectIconBlob>, mask: String?, check: () -> Unit,
    read: (PerfectIconBlob) -> ByteArray,
  ): PerfectStaticPair {
    val identity = "${PerfectStaticNightRenderer.REVISION}\n" +
      "${PerfectStaticNightRenderer.EQUIVALENT_LAYER_POLICY}\n${inputs.size}\n" +
      "${PerfectIconFormat.sha(mask.orEmpty().toByteArray())}\n" +
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
    // Shape belongs to the final compositor. Baking it here prevents reversible mask comparisons.
    val pair = if (source.size == 3 && staticEquivalentRenderer != null) {
      staticEquivalentRenderer.invoke(source[0], source.drop(1), null, check)
    } else {
      val primary = if (source.size == 3) source.take(1) else source
      requireNotNull(staticRenderer) { "Static night renderer unavailable" }(primary, null, check)
    }
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
              if (group.compositeQuiet != null && staticRenderer == null) { original(); continue }
              val converted = linkedMapOf<String, ByteArray>()
              var renderedBytes = 0L
              for (path in group.bgAssets + group.foreground) {
                coroutine.ensureActive()
                val bytes = dynamicNightTexture(group.files.getValue(path), path in group.bgAssets,
                  { coroutine.ensureActive() }, read) ?: break
                renderedBytes += bytes.size
                if (renderedBytes > 32L * 1024 * 1024) break
                converted[path] = bytes
              }
              if (converted.size != group.bgAssets.size + group.foreground.size) { original(); continue }
              group.compositeQuiet?.let { path ->
                val bytes = staticPair(listOf(group.files.getValue(path)), null,
                  { coroutine.ensureActive() }, read).night
                renderedBytes += bytes.size
                converted[path] = bytes
              }
              // Publish the whole closure or none, never a mixture of night and missing day assets.
              if (converted.keys != group.nightPaths || renderedBytes > 32L * 1024 * 1024) {
                original(); continue
              }
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
    sourceReader: PerfectGitReader, blob: PerfectIconBlob, source: PerfectIconSourceEntry,
  ): ByteArray {
    require(blob.size in 0..PerfectIconFormat.MAX_BLOB.toLong()) { "Selected source resource exceeds size limit" }
    val coroutine = currentCoroutineContext()
    return sourceReader.read(source) { coroutine.ensureActive() }
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
