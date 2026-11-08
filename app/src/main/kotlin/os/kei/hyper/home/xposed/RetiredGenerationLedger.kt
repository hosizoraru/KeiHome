package os.kei.hyper.home.xposed

import java.lang.ref.WeakReference

/**
 * Process-local evidence of retired loaders. A cleared weak reference does not prove full unload.
 *
 * Wire format: Object[marker, version, lastGeneration, weakCleared, Object[records...]].
 * Each record is Object[generation, retiredAtNanos, label, WeakReference<ClassLoader>].
 * Only bootstrap objects cross the loader boundary; entries and the clock stay local.
 */
internal class RetiredGenerationLedger(
  private val nanoTime: () -> Long = System::nanoTime,
) {
  private class Entry(
    val generation: Long,
    val retiredAtNanos: Long,
    val label: String,
    val reference: WeakReference<*>,
  )

  private val entries = ArrayList<Entry>()
  private var provisional: Entry? = null
  private var lastGeneration = 0L
  private var weakCleared = 0L

  val pendingCount: Int
    @Synchronized get() {
      prune()
      return entries.size
    }

  /** Check before tearing down the owner. Generation exhaustion also refuses retirement. */
  @Synchronized
  fun pendingReferences(): List<WeakReference<*>> {
    prune()
    return entries.map { it.reference }
  }

  fun canRetire(): Boolean {
    prune()
    return entries.size < MAX_PENDING && lastGeneration < Long.MAX_VALUE
  }

  /**
   * Proposes this loader once in a detached neutral snapshot; only restore commits it.
   * Retrying a failed external export preserves the original ID, label and retirement time.
   * The weak provisional entry is excluded from local counts and retirement admission.
   */
  @Synchronized
  fun snapshotWith(loader: ClassLoader, label: String): Any {
    require(label.length <= MAX_LABEL_LENGTH) { "Retirement label exceeds $MAX_LABEL_LENGTH chars" }
    prune()
    val existing = entries.firstOrNull { it.reference.get() === loader }
    val addition = if (existing == null) {
      check(entries.size < MAX_PENDING) { "Retirement ledger is full" }
      check(lastGeneration < Long.MAX_VALUE) { "Retirement generation IDs exhausted" }
      provisional?.takeIf { it.reference.get() === loader }
        ?: Entry(lastGeneration + 1L, nanoTime(), label, WeakReference(loader))
    } else {
      null
    }
    val records = Array<Any>(entries.size + if (addition == null) 0 else 1) { index ->
      val entry = if (index < entries.size) entries[index] else checkNotNull(addition)
      arrayOf<Any>(entry.generation, entry.retiredAtNanos, entry.label, entry.reference)
    }
    val snapshot = arrayOf<Any>(
      MARKER, VERSION, addition?.generation ?: lastGeneration, weakCleared, records,
    )
    // External export can still fail. Cache only retry metadata, never commit the proposal here.
    if (addition != null) provisional = addition
    return snapshot
  }

  @Synchronized
  fun describe(): String {
    prune()
    val now = nanoTime()
    val oldestAgeMs = entries.maxOfOrNull {
      (now - it.retiredAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLI
    } ?: 0L
    return "pending=${entries.size} weakCleared=$weakCleared oldestAgeMs=$oldestAgeMs"
  }

  private fun prune() {
    val iterator = entries.iterator()
    while (iterator.hasNext()) {
      if (iterator.next().reference.get() == null) {
        iterator.remove()
        // Validated conservation (lastGeneration = weakCleared + entries) prevents overflow.
        weakCleared++
      }
    }
  }

  companion object {
    internal const val MAX_PENDING = 16
    internal const val MAX_LABEL_LENGTH = 96
    private const val MARKER = "KeiHome.RetiredGenerationLedger"
    private const val VERSION = 1
    private const val NANOS_PER_MILLI = 1_000_000L

    fun restoreRequired(state: Any?): RetiredGenerationLedger {
      require(state is Array<*> && state.firstOrNull() == MARKER) {
        "Retirement history is missing from a recognized handoff"
      }
      return restore(state)
    }

    /**
     * Unknown state is empty. Recognized but malformed state fails without dropping any records.
     * The injected clock uses the process-wide System.nanoTime time base, including signed values.
     */
    fun restore(
      state: Any?,
      nanoTime: () -> Long = System::nanoTime,
    ): RetiredGenerationLedger {
      val ledger = RetiredGenerationLedger(nanoTime)
      if (state !is Array<*> || MARKER != state.firstOrNull()) return ledger
      require(state.javaClass === Array<Any?>::class.java) { "Invalid retirement envelope array type" }
      require(state.size == 5) { "Invalid retirement ledger header" }
      require(state[1] is Int && state[1] == VERSION) { "Unsupported retirement ledger version" }
      val generation = state[2] as? Long
        ?: throw IllegalArgumentException("Invalid retirement generation counter")
      val cleared = state[3] as? Long
        ?: throw IllegalArgumentException("Invalid weak-cleared counter")
      val records = state[4] as? Array<*>
        ?: throw IllegalArgumentException("Invalid retirement records")
      require(records.javaClass === Array<Any?>::class.java) { "Invalid retirement records array type" }
      require(records.size <= MAX_PENDING) { "Too many retirement records" }
      require(generation >= 0L && cleared >= 0L && cleared <= generation) {
        "Invalid retirement counters"
      }
      require(generation - cleared == records.size.toLong()) { "Retirement records missing" }
      val now = nanoTime()
      var previousId = 0L
      for (record in records) {
        require(record is Array<*> && record.size == 4) { "Invalid retirement record" }
        require(record.javaClass === Array<Any?>::class.java) { "Invalid retirement record array type" }
        val id = record[0] as? Long
          ?: throw IllegalArgumentException("Invalid retirement generation ID")
        val retiredAt = record[1] as? Long
          ?: throw IllegalArgumentException("Invalid retirement timestamp")
        val label = record[2] as? String
          ?: throw IllegalArgumentException("Invalid retirement label")
        val reference = record[3]
        // Object.getClass equality rejects module-owned WeakReference subclasses without reflection.
        require(reference is WeakReference<*> && reference.javaClass === WeakReference::class.java) {
          "Invalid retirement weak reference"
        }
        require(id > previousId && id <= generation) { "Duplicate or invalid retirement ID" }
        require(now - retiredAt >= 0L) { "Retirement timestamp is outside the process clock range" }
        require(label.length <= MAX_LABEL_LENGTH) { "Retirement label exceeds bounds" }
        val referent = reference.get()
        require(referent == null || referent is ClassLoader) { "Weak reference is not a loader" }
        require(ledger.entries.none {
          it.reference === reference || (referent != null && it.reference.get() === referent)
        }) { "Duplicate retirement reference" }
        ledger.entries.add(Entry(id, retiredAt, label, reference))
        previousId = id
      }
      // Validate even cleared records before pruning; corruption must never disappear silently.
      ledger.lastGeneration = generation
      ledger.weakCleared = cleared
      ledger.prune()
      return ledger
    }
  }
}
