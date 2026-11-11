package os.kei.hyper.home.iconpack.perfect

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.errors.CorruptObjectException
import org.eclipse.jgit.internal.storage.dfs.*
import org.eclipse.jgit.internal.storage.pack.PackExt
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.FS
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PerfectIconRemote(
  val revision: String,
  val committedAt: Long = 0,
  val subject: String = "",
  val checkedAt: Long = 0,
  val availableStyles: List<String> = emptyList(),
)

internal data class PerfectGitSnapshot(
  val store: String,
  val remote: PerfectIconRemote,
  val entries: List<PerfectIconSourceEntry>,
  val storedBytes: Long,
  val transferredBytes: Long = 0,
  val availableStyles: List<String>,
  val downloadedStyles: List<String>,
)

internal data class PerfectGitRetained(val store: String, val storedBytes: Long)

internal interface PerfectGitReader : Closeable {
  fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray
  fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit): Boolean
}

internal interface PerfectIconGitBackend {
  suspend fun check(progress: (PerfectIconProgress) -> Unit): PerfectIconRemote
  suspend fun fetch(
    currentStore: String?,
    repair: Boolean,
    styles: Set<String>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitSnapshot
  suspend fun retain(
    store: String,
    entries: List<PerfectIconSourceEntry>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitRetained
  fun open(store: String): PerfectGitReader
  fun exists(store: String): Boolean
  fun stamp(store: String): String
  fun maintenanceNeeded(store: String, liveBytes: Long): Boolean = false
  /** Reserved for verified pack logos. Current source preview sheets are not pack icons. */
  fun styleIcons(revision: String): Map<String, File> = emptyMap()
  fun published(store: String, revision: String)
}

internal val PerfectGitWorkers = Executors.newCachedThreadPool { runnable ->
  Thread(runnable, "perfect-git").apply { isDaemon = true }
}

/**
 * Anonymous, bare, shallow object storage. Metadata and selected blobs are fetched
 * separately; no checkout, Git executable, user config, hooks, or ZIP repacking.
 *
 * The caller serializes JSON publication and readers. [published]
 * must be called AFTER the JSON pointer is durable. Until then active and tip
 * protect both revisions; this backend deliberately performs no pruning/GC.
 */
internal class PerfectIconGit(
  private val root: File,
  private val limits: PerfectIconZipLimits = PerfectIconZipLimits(),
  private val fixtureRemote: File? = null,
  private val metadataBytes: Long = minOf(32L * 1024 * 1024, limits.compressed),
  private val storedByteLimit: Long = limits.compressed,
) : PerfectIconGitBackend {
  private val lock = locks.computeIfAbsent(root.absolutePath) { ReentrantLock() }

  override suspend fun check(progress: (PerfectIconProgress) -> Unit): PerfectIconRemote = operation { op ->
    op.progress = progress
    prepareRoot()
    val directory = File(root, ".metadata-${UUID.randomUUID()}")
    try {
      DiskRepository(directory, metadataBytes, op::check).use { repo ->
        val advertised = transport(repo, op, metadata = true).use { transport ->
          transport.openFetch(listOf(RefSpec(MAIN))).use { connection ->
            connection.getRef(MAIN)?.objectId ?: throw IOException("Git main branch is absent")
          }
        }
        cachedRemote(advertised.name)?.let { return@operation it.copy(checkedAt = System.currentTimeMillis()) }
        // Never report metadata attached to a different ls-remote revision.
        val remote = fetchMetadata(repo, op)
        if (remote.revision != advertised.name) throw IOException("Git main changed while checking; retry")
        remote
      }
    } finally { directory.deleteRecursively() }
  }

  override suspend fun fetch(
    currentStore: String?,
    repair: Boolean,
    styles: Set<String>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitSnapshot = operation { op ->
    op.progress = progress
    styles.forEach { if (!safeStyle(it)) throw IOException("Unsafe Git style") }
    prepareRoot()
    val reuse = !repair && currentStore != null && exists(currentStore)
    val store = if (reuse) currentStore else UUID.randomUUID().toString()
    val directory = storeDirectory(store)
    if (!reuse) op.createdStore = directory
    val original = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
    val originalTip = File(directory, TIP).takeIf { it.isFile }?.readBytes()
    val metadata = File(root, ".metadata-${UUID.randomUUID()}")
    var success = false
    try {
      DiskRepository(metadata, metadataBytes, op::check).use { meta ->
        val remote = fetchMetadata(meta, op)
        val candidates = index(meta, ObjectId.fromString(remote.revision), op::check)
        val available = remote.availableStyles
        val selectedStyles = styles.intersect(available.toSet())
        if (selectedStyles.isEmpty()) throw IOException("No requested Git style remains available")
        val selected = select(candidates, selectedStyles, op::check)
        DiskRepository(directory, storedByteLimit, op::check).use { data ->
          // Persist only selected blobs. Commit/tree packs belong to the ephemeral
          // metadata repo; local revision markers must never negotiate commit haves.
          val missing = selected.map { it.id }.distinct().filterNot { data.objectDatabase.has(it) }
          missing.chunked(256).forEach { batch ->
            op.check()
            data.admission = batch.toSet()
            transport(data, op, metadata = false).use { transport ->
              transport.openFetch(listOf(RefSpec(MAIN))).use { connection ->
                if (connection !is BasePackFetchConnection) throw IOException("Git smart object fetch is required")
                connection.fetch(op, batch.mapIndexed { i, id ->
                  ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, "want/$i", id)
                }, emptySet())
              }
            }
          }
          var expanded = 0L
          val entries = selected.map { candidate ->
            op.check()
            val size = data.open(candidate.id, Constants.OBJ_BLOB).size
            if (size <= 0 || size > PerfectIconFormat.MAX_BLOB.toLong()) throw IOException("Invalid Git source object size")
            expanded += size
            if (expanded > limits.expanded) throw IOException("Git source exceeds expanded byte limit")
            val entry = PerfectIconSourceEntry(candidate.path, candidate.id.name, size, 0)
            verify(data, entry, op::check)
            entry
          }
          // Only successful, fully validated fetches move the remote-tip ref.
          data.writeRef(TIP, remote.revision)
          op.check()
          if (data.bytes() > storedByteLimit) throw IOException("Git store exceeds byte limit")
          val snapshot = PerfectGitSnapshot(store, remote, entries, data.bytes(), op.received,
            available, selectedStyles.sorted())
          success = true
          snapshot
        }
      }
    } finally {
      metadata.deleteRecursively()
      if (!success) {
        if (!reuse) directory.deleteRecursively()
        else {
          directory.listFiles()?.filter { it.name !in original }?.forEach { it.delete() }
          originalTip?.let { atomic(File(directory, TIP), it) }
        }
      }
    }
  }

  override suspend fun retain(
    store: String,
    entries: List<PerfectIconSourceEntry>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitRetained = operation { op ->
    if (!exists(store) || entries.isEmpty() || entries.size > limits.entries) {
      throw IOException("Invalid retained Git graph")
    }
    var expanded = 0L
    val paths = HashSet<String>()
    entries.forEach {
      op.check()
      validateEntry(it)
      if (!paths.add(it.path)) throw IOException("Duplicate retained Git path")
      expanded += it.size
      if (expanded > limits.expanded) throw IOException("Retained graph exceeds expanded byte limit")
    }
    val next = UUID.randomUUID().toString()
    val folder = storeDirectory(next)
    op.createdStore = folder
    var success = false
    try {
      DiskRepository(storeDirectory(store), storedByteLimit, op::check).use { source ->
        val revision = source.readRef(ACTIVE) ?: source.readRef(TIP)
          ?: throw IOException("Missing retained Git revision")
        DiskRepository(folder, storedByteLimit, op::check).use { destination ->
          destination.newObjectInserter().use { insert ->
            val unique = entries.distinctBy { it.sha }
            unique.forEachIndexed { index, entry ->
              op.check()
              val objectId = ObjectId.fromString(entry.sha)
              val blob = source.open(objectId, Constants.OBJ_BLOB)
              if (blob.size != entry.size) throw IOException("Retained Git blob size mismatch")
              blob.openStream().use { stream ->
                val checked = object : java.io.FilterInputStream(stream) {
                  override fun read(): Int { op.check(); return super.read() }
                  override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    op.check()
                    return super.read(bytes, off, minOf(len, 64 * 1024))
                  }
                }
                val inserted = insert.insert(Constants.OBJ_BLOB, entry.size, checked)
                if (inserted != objectId || checked.read() != -1) throw IOException("Retained Git blob hash mismatch")
              }
              progress(PerfectIconProgress(index + 1, unique.size, "source.verify"))
            }
            insert.flush()
          }
          // Recheck every path's declared size, including multiple paths sharing a blob.
          destination.newObjectReader().use { reader ->
            entries.forEach {
              op.check()
              if (reader.getObjectSize(ObjectId.fromString(it.sha), Constants.OBJ_BLOB) != it.size) {
                throw IOException("Conflicting retained Git blob size")
              }
            }
          }
          destination.writeRef(TIP, revision)
          val bytes = destination.bytes()
          if (bytes > storedByteLimit) throw IOException("Retained Git store exceeds byte limit")
          op.check()
          success = true
          PerfectGitRetained(next, bytes)
        }
      }
    } finally { if (!success) folder.deleteRecursively() }
  }

  override fun open(store: String): PerfectGitReader {
    val directory = storeDirectory(store)
    if (!exists(store)) throw IOException("Missing Git store")
    val repo = DiskRepository(directory, storedByteLimit) {}
    return object : PerfectGitReader {
      override fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray =
        verify(repo, entry, check, collect = true)!!
      override fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit): Boolean = try {
        if (entries.isEmpty() || entries.size > limits.entries) false else {
          var expanded = 0L
          repo.newObjectReader().use { reader ->
            entries.forEach {
              check()
              validateEntry(it)
              expanded += it.size
              if (expanded > limits.expanded) throw IOException("Invalid Git index")
              if (reader.getObjectSize(ObjectId.fromString(it.sha), Constants.OBJ_BLOB) != it.size) {
                throw IOException("Git source size mismatch")
              }
            }
          }
          true
        }
      } catch (error: IOException) { false }
      override fun close() { repo.close() }
    }
  }

  override fun exists(store: String): Boolean = try {
    val directory = storeDirectory(store)
    directory.isDirectory && File(directory, TIP).isFile && !Files.isSymbolicLink(File(directory, TIP).toPath())
  } catch (_: IOException) { false }

  override fun stamp(store: String): String {
    val directory = storeDirectory(store)
    if (!exists(store)) throw IOException("Missing Git store")
    val digest = MessageDigest.getInstance("SHA-256")
    directory.listFiles().orEmpty().sortedBy { it.name }.forEach {
      if (Files.isSymbolicLink(it.toPath())) throw IOException("Symlink in Git store")
      digest.update("${it.name}:${it.length()}:${it.lastModified()}\n".toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
  }

  override fun maintenanceNeeded(store: String, liveBytes: Long): Boolean = lock.withLock {
    val directory = storeDirectory(store)
    val files = directory.listFiles().orEmpty()
    val packs = files.count { it.extension == "pack" }
    packs > 96 || files.sumOf { it.length() } > maxOf(64L * 1024 * 1024, liveBytes + liveBytes / 2)
  }

  override fun published(store: String, revision: String) = lock.withLock {
    if (!ObjectId.isId(revision)) throw IOException("Invalid Git revision")
    DiskRepository(storeDirectory(store), storedByteLimit) {}.use { repo ->
      if (repo.readRef(TIP) != revision) throw IOException("Git publication is not the validated tip")
      repo.writeRef(ACTIVE, revision)
    }
    root.listFiles().orEmpty().filter {
      it.name != store && STORE.matches(it.name) && it.isDirectory && !Files.isSymbolicLink(it.toPath())
    }.forEach {
      if (!it.deleteRecursively()) throw IOException("Cannot remove superseded Git generation")
    }
  }

  private fun storeDirectory(store: String): File {
    if (!STORE.matches(store)) throw IOException("Unsafe Git store name")
    if (Files.isSymbolicLink(root.toPath())) throw IOException("Symlink Git root")
    val directory = File(root, store)
    if (Files.isSymbolicLink(directory.toPath())) throw IOException("Symlink Git store")
    return directory
  }

  private fun prepareRoot() {
    if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory && !root.mkdirs()) {
      throw IOException("Cannot create safe Git root")
    }
    root.listFiles().orEmpty().filter {
      it.name.startsWith(".metadata-") && STORE.matches(it.name.removePrefix(".metadata-")) &&
        it.isDirectory && !Files.isSymbolicLink(it.toPath())
    }.forEach {
      if (!it.deleteRecursively()) throw IOException("Cannot remove abandoned Git metadata")
    }
  }

  private fun fetchMetadata(repo: DiskRepository, op: Operation): PerfectIconRemote {
    repo.admission = emptySet()
    val tip = transport(repo, op, metadata = true).use { transport ->
      transport.openFetch(listOf(RefSpec(MAIN))).use { connection ->
        if (connection !is BasePackFetchConnection) throw IOException("Git smart filtered fetch is required")
        val ref = connection.getRef(MAIN) ?: throw IOException("Git main branch is absent")
        connection.fetch(op, listOf(ref), emptySet())
        ref.objectId
      }
    }
    val candidates = index(repo, tip, op::check)
    val styles = candidates.mapNotNull { pathStyle(it.path) }
      .filter { it != "common" }.distinct().sorted()
    if (styles.isEmpty() || styles.any { !safeStyle(it) }) throw IOException("Invalid Git source styles")
    select(candidates, styles.toSet(), op::check)
    return RevWalk(repo).use { walk ->
      val commit = walk.parseCommit(tip)
      PerfectIconRemote(tip.name, commit.commitTime.toLong() * 1000, commit.shortMessage.take(1024),
        System.currentTimeMillis(), styles)
    }.also(::cacheRemote)
  }

  private fun transport(repo: DiskRepository, op: Operation, metadata: Boolean): Transport {
    val uri = fixtureRemote?.let {
      if (!it.isDirectory || Files.isSymbolicLink(it.toPath())) throw IOException("Invalid JVM Git fixture")
      URIish(it.toURI().toString())
    } ?: URIish(REMOTE)
    val transport = Transport.open(repo, uri)
    transport.credentialsProvider = null
    transport.timeout = 20
    transport.tagOpt = TagOpt.NO_TAGS
    transport.isRemoveDeletedRefs = false
    transport.isCheckFetchedObjects = true
    transport.objectChecker = repo.checker()
    if (metadata) {
      transport.depth = 1
      transport.filterSpec = FilterSpec.fromFilterLine("blob:none")
    }
    if (fixtureRemote == null) {
      if (transport !is TransportHttp) { transport.close(); throw IOException("Expected Git HTTPS transport") }
      val factory = PerfectIconGitHttp(
        (if (metadata) metadataBytes else limits.compressed) - op.received,
        op::check,
      ) { count ->
        op.received = op.beforeRequest + count
        op.report()
      }
      op.beforeRequest = op.received
      op.http = factory
      transport.setHttpConnectionFactory(factory)
    }
    return transport
  }

  private data class Candidate(val path: String, val id: ObjectId)

  private fun cachedRemote(revision: String): PerfectIconRemote? {
    val file = File(root, ".remote")
    if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > 256 * 1024) return null
    return try {
      java.io.DataInputStream(file.inputStream()).use {
        if (it.readUTF() != revision) return null
        val committedAt = it.readLong()
        val subject = it.readUTF()
        val count = it.readInt()
        if (count !in 1..1024) return null
        val styles = List(count) { _ -> it.readUTF().also { style ->
          if (!safeStyle(style)) throw IOException("Invalid cached Git style")
        } }
        PerfectIconRemote(revision, committedAt, subject, availableStyles = styles)
      }
    } catch (_: IOException) { null }
  }

  private fun cacheRemote(remote: PerfectIconRemote) {
    val bytes = java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(bytes).use {
      it.writeUTF(remote.revision)
      it.writeLong(remote.committedAt)
      it.writeUTF(remote.subject)
      it.writeInt(remote.availableStyles.size)
      remote.availableStyles.forEach(it::writeUTF)
    }
    if (bytes.size() > 256 * 1024) throw IOException("Git metadata cache exceeds byte limit")
    atomic(File(root, ".remote"), bytes.toByteArray())
  }

  private fun select(candidates: List<Candidate>, styles: Set<String>, check: () -> Unit): List<Candidate> {
    if (styles.isEmpty()) throw IOException("Select at least one Git style")
    val paths = candidates.map { it.path }
    val needed = HashSet<String>()
    styles.forEach {
      check()
      needed += PerfectIconSelection.paths(paths, setOf(it))
    }
    check()
    val selected = candidates.filter { it.path in needed }
    if (selected.none {
      it.path.startsWith("icons/") || "/icons/" in it.path || "/layer_animating_icons/" in it.path
    }) throw IOException("Git style contains no usable icon graph")
    return selected
  }

  private fun index(repo: Repository, tip: ObjectId, check: () -> Unit): List<Candidate> {
    val result = ArrayList<Candidate>()
    RevWalk(repo).use { walk ->
      TreeWalk(repo).use { tree ->
        tree.addTree(walk.parseCommit(tip).tree)
        // Visit directories too, so malformed paths cannot hide under an ignored subtree.
        var visited = 0
        while (tree.next()) {
          check()
          if (++visited > limits.entries) throw IOException("Too many Git tree entries")
          val path = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(tree.rawPath)).toString()
          } catch (error: java.nio.charset.CharacterCodingException) {
            throw IOException("Invalid Git path encoding", error)
          }
          safePath(path)
          val mode = tree.getFileMode(0)
          if (mode == FileMode.SYMLINK || mode == FileMode.GITLINK) {
            throw IOException("Git symlink/submodule is not an icon source")
          }
          if (tree.isSubtree) { tree.enterSubtree(); continue }
          if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
            throw IOException("Unsupported Git source mode")
          }
          if (sourcePath(path)) {
            result += Candidate(path, tree.getObjectId(0))
            if (result.size > limits.entries) throw IOException("Too many Git source entries")
          }
        }
      }
    }
    if (result.isEmpty()) throw IOException("Empty Git icon source")
    return result
  }

  private fun verify(
    repo: Repository,
    entry: PerfectIconSourceEntry,
    check: () -> Unit,
    collect: Boolean = false,
  ): ByteArray? {
    validateEntry(entry)
    val loader = repo.open(ObjectId.fromString(entry.sha), Constants.OBJ_BLOB)
    if (loader.size != entry.size) throw IOException("Git source size mismatch")
    val hash = MessageDigest.getInstance("SHA-1")
    hash.update("blob ${entry.size}\u0000".toByteArray(Charsets.US_ASCII))
    val bytes = if (collect) ByteArray(entry.size.toInt()) else null
    loader.openStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      var total = 0
      while (true) {
        check()
        val count = input.read(buffer)
        if (count < 0) break
        if (total.toLong() + count > entry.size) throw IOException("Git source size mismatch")
        hash.update(buffer, 0, count)
        bytes?.let { buffer.copyInto(it, total, 0, count) }
        total += count
      }
      if (total.toLong() != entry.size) throw IOException("Truncated Git source")
    }
    if (ObjectId.fromRaw(hash.digest()).name != entry.sha) throw IOException("Git blob hash mismatch")
    check()
    return bytes
  }

  private fun validateEntry(entry: PerfectIconSourceEntry) {
    safePath(entry.path)
    if (!sourcePath(entry.path) || entry.size <= 0 ||
      entry.size > PerfectIconFormat.MAX_BLOB.toLong() || entry.size > Int.MAX_VALUE || !ObjectId.isId(entry.sha)) {
      throw IOException("Invalid Git source entry")
    }
  }

  private suspend fun <T> operation(block: (Operation) -> T): T {
    val op = Operation()
    val finished = CompletableDeferred<Unit>()
    try {
      val result = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { op.cancel() }
        PerfectGitWorkers.execute {
          var acquired = false
          try {
            while (!acquired) { op.check(); acquired = lock.tryLock(100, TimeUnit.MILLISECONDS) }
            op.check()
            val result = block(op)
            op.check()
            if (continuation.isActive) continuation.resume(result)
          } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
          } finally {
            op.http?.abort()
            if (acquired) lock.unlock()
            finished.complete(Unit)
          }
        }
      }
      withContext(NonCancellable) { finished.await() }
      currentCoroutineContext().ensureActive()
      return result
    } catch (error: Throwable) {
      op.cancel()
      // Keep the caller's repository mutex until all pack writers and rollback
      // have exited. Awaiting suspends; it never blocks the main/UI thread.
      withContext(NonCancellable) {
        finished.await()
        lock.withLock { op.createdStore?.deleteRecursively() }
      }
      throw error
    }
  }

  private class Operation : ProgressMonitor {
    private val cancelled = AtomicBoolean()
    @Volatile var http: PerfectIconGitHttp? = null
    var createdStore: File? = null
    var received = 0L
    var beforeRequest = 0L
    var progress: (PerfectIconProgress) -> Unit = {}
    private var reported = 0L
    fun cancel() { cancelled.set(true); http?.abort() }
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Git operation cancelled") }
    fun report() {
      check()
      val now = System.nanoTime()
      if (now - reported > 100_000_000) {
        reported = now
        progress(PerfectIconProgress(received.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), 0, "source.download"))
      }
    }
    override fun start(totalTasks: Int) { check() }
    override fun beginTask(title: String, totalWork: Int) { report() }
    override fun update(completed: Int) { report() }
    override fun endTask() { check() }
    override fun isCancelled(): Boolean = cancelled.get()
    override fun showDuration(enabled: Boolean) = Unit
  }

  /**
   * JGit DFS with file streams, not the in-memory repository implementation.
   * Config and refs are explicitly local; no SystemReader/FS singleton is replaced.
   * Pack+index files are exposed only after their small .ready marker is durable.
   */
  private inner class DiskRepository(
    val folder: File,
    val byteLimit: Long,
    val check: () -> Unit,
  ) : DfsRepository(DiskBuilder(folder)) {
    var admission: Set<ObjectId>? = null
    private val refs = object : RefDatabase() {
      override fun create() = Unit
      override fun close() = Unit
      override fun isNameConflicting(name: String): Boolean = false
      override fun newUpdate(name: String, detach: Boolean): RefUpdate = throw IOException("Read-only Git refs")
      override fun newRename(from: String, to: String): RefRename = throw IOException("Read-only Git refs")
      override fun exactRef(name: String): Ref? = null
      override fun getRefs(prefix: String): Map<String, Ref> = emptyMap()
      override fun getAdditionalRefs(): List<Ref> = emptyList()
      override fun peel(ref: Ref): Ref = ref
      override fun getReflogReader(ref: Ref): ReflogReader? = null
    }
    private val db = object : DfsObjDatabase(this, DfsReaderOptions().setStreamFileThreshold(1024 * 1024)) {
      override fun getApproximateObjectCount(): Long = -1
      private var shallow = emptySet<ObjectId>()
      override fun getShallowCommits(): Set<ObjectId> = shallow
      override fun setShallowCommits(ids: Set<ObjectId>) { shallow = ids.toSet() }
      override fun newPack(source: PackSource): DfsPackDescription =
        DfsPackDescription(this@DiskRepository.description, "${UUID.randomUUID()}.pack", source)
      override fun listPacks(): List<DfsPackDescription> = folder.listFiles().orEmpty()
        .filter { it.name.endsWith(".ready") }.map { marker ->
          check()
          val name = marker.name.removeSuffix(".ready")
          val description = DfsPackDescription(this@DiskRepository.description, "$name.pack", PackSource.RECEIVE)
          listOf(PackExt.PACK, PackExt.INDEX).forEach { ext ->
            val file = local(description.getFileName(ext))
            if (!file.isFile) throw IOException("Incomplete Git pack")
            description.addFileExt(ext)
            description.setFileSize(ext, file.length())
          }
          description
        }
      override fun commitPackImpl(desc: Collection<DfsPackDescription>, replace: Collection<DfsPackDescription>?) {
        desc.forEach { atomic(local("${it.packName}.ready"), byteArrayOf(1)) }
        // No automatic replacement/pruning: active and unpublished tips must survive.
      }
      override fun rollbackPack(desc: Collection<DfsPackDescription>) {
        desc.forEach { pack ->
          PackExt.values().forEach { local(pack.getFileName(it)).delete() }
          local("${pack.packName}.ready").delete()
        }
      }
      override fun openFile(desc: DfsPackDescription, ext: PackExt): ReadableChannel =
        channel(RandomAccessFile(local(desc.getFileName(ext)), "r"))
      override fun writeFile(desc: DfsPackDescription, ext: PackExt): DfsOutputStream {
        val file = RandomAccessFile(local(desc.getFileName(ext)), "rw")
        return object : DfsOutputStream() {
          override fun write(buf: ByteArray, off: Int, len: Int) {
            check()
            if (bytes() + len > byteLimit) throw IOException("Git store exceeds byte limit")
            file.write(buf, off, len)
          }
          override fun read(position: Long, buf: ByteBuffer): Int =
            file.channel.read(buf, position)
          override fun flush() { if (file.channel.isOpen) file.fd.sync() }
          override fun close() {
            if (file.channel.isOpen) try { flush() } finally { file.close() }
          }
        }
      }
    }
    init {
      if (Files.isSymbolicLink(folder.toPath())) throw IOException("Symlink Git repository")
      if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create Git repository")
      // All settings belong to this repository's in-memory DfsConfig.
      config.setString("protocol", null, "version", "2")
      config.setString("http", null, "followRedirects", "false")
      config.setBoolean("http", null, "saveCookies", false)
      config.setBoolean("core", null, "trustPackStat", false)
      config.setInt("core", null, "streamFileThreshold", 1024 * 1024)
      if (bytes() > byteLimit) throw IOException("Git store exceeds byte limit")
    }
    override fun getObjectDatabase(): DfsObjDatabase = db
    override fun getRefDatabase(): RefDatabase = refs
    override fun getReflogReader(refName: String): ReflogReader? = null
    override fun newObjectInserter(): ObjectInserter {
      val inserter = db.newInserter()
      return object : ObjectInserter.Filter() {
        override fun delegate(): ObjectInserter = inserter
        override fun newPackParser(input: InputStream): PackParser {
          val header = ByteArray(12)
          var read = 0
          while (read < header.size) {
            check()
            val count = input.read(header, read, header.size - read)
            if (count < 0) throw IOException("Truncated Git pack header")
            read += count
          }
          val fields = ByteBuffer.wrap(header)
          if (fields.int != 0x5041434b || fields.int !in 2..3 ||
            (fields.int.toLong() and 0xffffffffL) > limits.entries.toLong() * 2) {
            throw IOException("Invalid or oversized Git pack")
          }
          return inserter.newPackParser(java.io.SequenceInputStream(header.inputStream(), input)).also {
            it.setMaxObjectSizeLimit(if (admission?.isEmpty() == true) metadataBytes else PerfectIconFormat.MAX_BLOB.toLong())
            it.setObjectChecker(checker())
          }
        }
      }
    }
    fun checker(): ObjectChecker = object : ObjectChecker() {
      private var expanded = 0L
      private fun add(length: Int) {
        check()
        expanded += length
        val max = if (admission?.isEmpty() == true) metadataBytes else limits.expanded
        if (expanded > max) throw IOException("Git pack exceeds expanded byte limit")
      }
      override fun check(id: AnyObjectId?, type: Int, raw: ByteArray) {
        if (type == Constants.OBJ_BLOB) {
          if (admission?.contains(id) != true) throw CorruptObjectException("Unrequested Git blob")
        } else if (admission?.isEmpty() != true || type !in listOf(Constants.OBJ_TREE, Constants.OBJ_COMMIT)) {
          throw CorruptObjectException("Unexpected Git object type")
        }
        add(raw.size)
        super.check(id, type, raw)
      }
      override fun newBlobObjectChecker(): BlobObjectChecker {
        if (admission?.isEmpty() == true) throw CorruptObjectException("Server ignored Git blob:none filter")
        return object : BlobObjectChecker {
          override fun update(input: ByteArray, offset: Int, len: Int) { add(len) }
          override fun endBlob(id: AnyObjectId) {
            if (admission?.contains(id) != true) throw CorruptObjectException("Unrequested Git blob")
          }
        }
      }
    }
    fun readRef(name: String): String? {
      val file = local(name)
      if (!file.isFile) return null
      if (file.length() != 41L) throw IOException("Invalid local Git ref size")
      return file.readText(Charsets.US_ASCII).removeSuffix("\n").also {
        if (!ObjectId.isId(it)) throw IOException("Invalid local Git ref")
      }
    }
    fun writeRef(name: String, id: String) { atomic(local(name), "$id\n".toByteArray(Charsets.US_ASCII)) }
    fun bytes(): Long = folder.listFiles().orEmpty().sumOf {
      if (Files.isSymbolicLink(it.toPath())) throw IOException("Symlink in Git store")
      it.length()
    }
    private fun local(name: String): File {
      if (name.contains('/') || name.contains('\\') || name == "." || name == "..") throw IOException("Unsafe Git filename")
      return File(folder, name).also {
        if (Files.isSymbolicLink(it.toPath())) throw IOException("Symlink Git file")
      }
    }
  }

  companion object {
    private const val MAIN = "refs/heads/main"
    private const val REMOTE = "https://github.com/pzcn/Perfect-Icons-Completion-Project.git"
    private const val ACTIVE = "active"
    private const val TIP = "tip"
    private val STORE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private class DiskBuilder(folder: File) : DfsRepositoryBuilder<DiskBuilder, DfsRepository>() {
      init {
        setRepositoryDescription(DfsRepositoryDescription(folder.absolutePath))
        setFS(LocalFS(folder))
      }
      override fun build(): DfsRepository = throw UnsupportedOperationException()
    }
    private class LocalFS(private val folder: File) : FS() {
      override fun newInstance(): FS = LocalFS(folder)
      override fun supportsExecute(): Boolean = false
      override fun supportsSymlinks(): Boolean = false
      override fun isCaseSensitive(): Boolean = true
      override fun canExecute(file: File): Boolean = false
      override fun setExecute(file: File, canExec: Boolean): Boolean = false
      override fun retryFailedLockFileCommit(): Boolean = false
      override fun userHomeImpl(): File = folder
      override fun discoverGitExe(): File? = null
      override fun discoverGitSystemConfig(): File? = null
      override fun runInShell(cmd: String, args: Array<String>): ProcessBuilder =
        throw UnsupportedOperationException("Git subprocesses are disabled")
    }
    private fun sourcePath(path: String): Boolean = path.startsWith("icons/") ||
      path.startsWith("themes/") && path.count { it == '/' } >= 2
    private fun safeStyle(style: String): Boolean =
      style.isNotBlank() && style.length <= 128 && !style.startsWith('.') && style != "common" &&
        style.none { it == '/' || it == '\\' || it.code < 32 || it == '\u007f' }
    private fun pathStyle(path: String): String? =
      if (path.startsWith("themes/")) path.removePrefix("themes/").substringBefore('/') else null
    private fun safePath(path: String) {
      if (path.isBlank() || path.length > 1024 || path.count { it == '/' } >= 32 ||
        path.startsWith('/') || path.contains('\\') || path.contains(':') ||
        path.any { it.code < 32 || it == '\u007f' } ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) }) {
        throw IOException("Unsafe Git tree path")
      }
    }
    private fun atomic(destination: File, bytes: ByteArray) {
      val temporary = File(destination.parentFile, ".write-${UUID.randomUUID()}")
      try {
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), destination.toPath(),
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } finally { temporary.delete() }
    }
    private fun channel(file: RandomAccessFile): ReadableChannel = object : ReadableChannel {
      override fun read(dst: ByteBuffer): Int = file.channel.read(dst)
      override fun close() { file.close() }
      override fun isOpen(): Boolean = file.channel.isOpen
      override fun position(): Long = file.channel.position()
      override fun position(newPosition: Long) { file.channel.position(newPosition) }
      override fun size(): Long = file.length()
      override fun blockSize(): Int = 64 * 1024
      override fun setReadAheadBytes(bufferSize: Int) = Unit
    }
  }
}
