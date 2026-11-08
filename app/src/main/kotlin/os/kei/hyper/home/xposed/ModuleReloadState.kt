package os.kei.hyper.home.xposed

import java.util.concurrent.atomic.AtomicBoolean

/** Only bootstrap containers, weak references and the live host loader cross generations. */
internal class ModuleReloadState(
  private val moduleLoader: ClassLoader,
  private val label: String,
  private val cleanup: (ClassLoader) -> String,
  private val report: (String) -> Unit,
) {
  private var ledger = RetiredGenerationLedger.restore(null)
  @Volatile private var acceptedState: Array<Any?>? = null
  private val transferring = AtomicBoolean(false)
  private val maintenance = ModuleRetirementMaintenance()
  private var maintenanceStarted = false

  fun restore(saved: Any?): Any? {
    if (saved !is Array<*> || saved.firstOrNull() != MAGIC) return saved
    require(saved.javaClass === arrayOfNulls<Any>(0).javaClass && saved.size == 3) {
      "Invalid module retirement state"
    }
    requireHostLoader(saved[1])
    ledger = RetiredGenerationLedger.restoreRequired(saved[2])
    report("adopted ${ledger.describe()}")
    return saved[1]
  }

  fun startMaintenance() {
    if (!transferring.compareAndSet(false, true)) return
    try {
      if (acceptedState == null) scheduleMaintenance()
    } finally {
      transferring.set(false)
    }
  }

  private fun scheduleMaintenance() {
    if (maintenanceStarted) return
    maintenanceStarted = true
    val references = ledger.pendingReferences()
    if (references.isEmpty()) return
    maintenance.start {
      for (reference in references) {
        val retired = reference.get() as? ClassLoader ?: continue
        val result = try {
          cleanup(retired)
        } catch (failure: Exception) {
          "cleanup unavailable (${failure.javaClass.simpleName})"
        }
        report("maintenance $result")
      }
    }
  }

  internal fun awaitMaintenance(timeoutMillis: Long): Boolean = maintenance.awaitStopped(timeoutMillis)

  fun retire(
    publish: (Any?) -> Unit,
    stopOwner: ((Any?) -> Unit) -> Boolean,
  ): Boolean {
    if (!transferring.compareAndSet(false, true)) return false
    return try {
      performRetirement(publish, stopOwner)
    } finally {
      transferring.set(false)
    }
  }

  private fun performRetirement(
    publish: (Any?) -> Unit,
    stopOwner: ((Any?) -> Unit) -> Boolean,
  ): Boolean {
    if (!maintenance.awaitStopped()) {
      report("deferred: previous-generation maintenance still active")
      return false
    }
    acceptedState?.let { publish(it); return true }
    if (!ledger.canRetire()) {
      report("deferred ${ledger.describe()}")
      return false
    }
    val envelope = arrayOf<Any?>(MAGIC, null, ledger.snapshotWith(moduleLoader, label))
    var published = false
    val accepted = stopOwner { host ->
      requireHostLoader(host)
      envelope[1] = host
      publish(envelope)
      published = true
    }
    // A failed export/owner drain must leave all platform caches and history untouched.
    if (!accepted) return false
    check(published) { "Retiring owner did not publish its handoff" }
    acceptedState = envelope
    // Cache hygiene runs in the successor, never on this framework/Binder control callback.
    report("retired ${RetiredGenerationLedger.restore(envelope[2]).describe()}")
    return true
  }

  private fun requireHostLoader(value: Any?) {
    require(value == null || value is ClassLoader) { "Only a host loader may be exported" }
    if (value is ClassLoader) {
      var current: ClassLoader? = value
      repeat(64) {
        require(current !== moduleLoader) { "Cannot export a module loader or its child" }
        current = current?.parent ?: return
      }
      error("Host loader hierarchy exceeds retirement bound")
    }
  }

  companion object {
    private const val MAGIC = "keihome.module-retirement.v1"
  }
}
