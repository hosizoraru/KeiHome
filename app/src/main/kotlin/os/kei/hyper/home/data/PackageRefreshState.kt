package os.kei.hyper.home.data

/** Call on the scanner's worker, never during composition or a main-thread state update. */
internal fun <T> List<T>.preserveUnchangedReference(previous: List<T>?): List<T> =
  if (previous != null && this == previous) previous else this

/** A null serial means the sender could not identify a profile; invalidate every matching profile. */
internal data class PackageRefreshTarget(val packageName: String, val userSerial: Long? = null) {
  fun matches(packageName: String, userSerial: Long): Boolean =
    this.packageName == packageName && (this.userSerial == null || this.userSerial == userSerial)
}

internal data class PackageRefreshBatch(val targets: Set<PackageRefreshTarget>, val global: Boolean)

internal data class PackageInventoryScope<C>(
  val configuration: C,
  val profiles: Set<Long>,
  val resourceRevision: Long,
)

internal fun <C> requiresFullInventory(
  previous: PackageInventoryScope<C>?,
  current: PackageInventoryScope<C>,
  request: PackageRefreshBatch,
  affectsOverlay: Boolean,
): Boolean = previous == null || previous != current || request.global || affectsOverlay ||
  current.profiles.any { it < 0 } ||
  request.targets.any { it.userSerial == null || it.userSerial !in current.profiles }

/** Missing replacements mean a confirmed absence, not a failed package query. */
internal fun <T : Any> mergePackageInventory(
  previous: List<T>,
  targets: Set<PackageRefreshTarget>,
  replacements: List<T>,
  key: (T) -> PackageRefreshTarget,
  comparator: Comparator<T>,
): List<T> {
  require(targets.all { it.userSerial != null })
  val updated = replacements.associateBy(key).toMutableMap()
  require(updated.keys.all { it in targets })
  val merged = previous.mapNotNull { old ->
    val identity = key(old)
    if (identity !in targets) old else updated.remove(identity)?.let { if (it == old) old else it }
  }.toMutableList()
  merged.addAll(updated.values)
  merged.sortWith(comparator)
  return if (merged.size == previous.size && merged.indices.all { merged[it] === previous[it] })
    previous else merged
}

internal fun <T : Any, C> refreshPackageInventory(
  previous: List<T>?,
  previousScope: PackageInventoryScope<C>?,
  scope: PackageInventoryScope<C>,
  request: PackageRefreshBatch,
  key: (T) -> PackageRefreshTarget,
  isOverlay: (T) -> Boolean,
  comparator: Comparator<T>,
  readTarget: (PackageRefreshTarget) -> T?,
  readFull: () -> List<T>,
): List<T> {
  val affectsOverlay = previous.orEmpty().any { old ->
    isOverlay(old) && request.targets.any { it.matches(key(old).packageName, key(old).userSerial ?: -1L) }
  }
  if (previous == null || requiresFullInventory(previousScope, scope, request, affectsOverlay)) return readFull()
  val incremental = try {
    val replacements = request.targets.mapNotNull(readTarget)
    if (replacements.any(isOverlay)) null
    else mergePackageInventory(previous, request.targets, replacements, key, comparator)
  } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
  } catch (_: Exception) { null }
  // A query failure or a new overlay needs one authoritative read, never a partially merged result.
  return incremental ?: readFull()
}

/** Main-thread queue. Overflow becomes one global refresh instead of retaining an unbounded set. */
internal class PendingPackageRefresh(private val capacity: Int = 1024) {
  init { require(capacity > 0) }
  private val targets = linkedSetOf<PackageRefreshTarget>()
  private var global = false
  var revision = 0L
    private set
  val isPending get() = global || targets.isNotEmpty()

  fun add(target: PackageRefreshTarget? = null) {
    revision++
    if (global) return
    if (target == null || targets.size >= capacity && target !in targets) {
      targets.clear()
      global = true
    } else targets.add(target)
  }

  fun drain(): PackageRefreshBatch {
    val batch = PackageRefreshBatch(targets.toSet(), global)
    targets.clear()
    global = false
    return batch
  }

  fun restore(batch: PackageRefreshBatch) {
    if (batch.global) add() else batch.targets.forEach { add(it) }
  }
}

/** Metadata only. Invalidation also prevents an older in-flight scan from repopulating the cache. */
internal class PackageAssessmentCache<K, V>(private val capacity: Int = 1024) {
  init { require(capacity > 0) }
  private val values = object : LinkedHashMap<K, V>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > capacity
  }
  var revision = 0L
    @Synchronized get
    private set

  fun resolve(key: K?, scanRevision: Long, cacheable: (V) -> Boolean = { true }, read: () -> V): V {
    if (key == null) return read()
    synchronized(this) {
      if (revision == scanRevision) values[key]?.let { return it }
    }
    val value = read()
    synchronized(this) { if (revision == scanRevision && cacheable(value)) values[key] = value }
    return value
  }

  @Synchronized
  fun invalidate(matches: (K) -> Boolean = { true }) {
    revision++
    values.keys.removeAll(matches)
  }
}
