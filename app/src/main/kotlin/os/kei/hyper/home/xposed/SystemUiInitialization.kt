package os.kei.hyper.home.xposed

internal fun runWithInitializationPriority(
  setPriority: () -> Unit,
  reportFailure: (Throwable) -> Unit,
  action: () -> Unit,
) {
  try { setPriority() } catch (failure: Throwable) {
    val cause = recoverableSystemUiFailure(failure)
    try { reportFailure(cause) }
    catch (report: Throwable) { recoverableSystemUiFailure(report) }
  }
  action()
}

/** Retries need another lifecycle signal; no waiting or timed background retries. */
internal class SystemUiInitialization(
  private val reportFailure: (feature: String, failures: Int, exhausted: Boolean, failure: Throwable) -> Unit,
) {
  private class Stage {
    var ready = false
    var failures = 0
    var owner: Any? = null
    var requested = false
  }
  private val lock = Any()
  private val stages = mutableMapOf<String, Stage>()

  fun canRun(feature: String): Boolean = synchronized(lock) {
    stages[feature]?.let { !it.ready && it.failures < MAX_FAILURES } ?: true
  }

  /** False means a prerequisite is not available yet, not an initialization error. */
  fun run(feature: String, action: () -> Boolean): Boolean {
    val owner = Any()
    val stage = synchronized(lock) {
      val current = stages.getOrPut(feature, ::Stage)
      if (current.ready) return true
      if (current.failures >= MAX_FAILURES) return false
      if (current.owner != null) {
        current.requested = true
        return false
      }
      current.owner = owner
      current
    }
    try {
      while (true) {
        var failure: Throwable? = null
        val ready = try { action() } catch (caught: Throwable) {
          failure = recoverableSystemUiFailure(caught)
          false
        }
        val failures = synchronized(lock) {
          if (ready) stage.ready = true
          if (failure != null) stage.failures++
          stage.failures
        }
        failure?.let {
          try { reportFailure(feature, failures, failures >= MAX_FAILURES, it) }
          catch (report: Throwable) { recoverableSystemUiFailure(report) }
        }
        val retry = synchronized(lock) {
          if (!ready && stage.requested && stage.failures < MAX_FAILURES) {
            stage.requested = false
            true
          } else {
            stage.requested = false
            stage.owner = null
            false
          }
        }
        if (!retry) return ready
      }
    } finally {
      synchronized(lock) {
        if (stage.owner === owner) {
          stage.owner = null
          stage.requested = false
        }
      }
    }
  }

  companion object {
    private const val MAX_FAILURES = 2
  }
}

/** One on-demand worker; repeated signals replace pending work for the same owner. */
internal class CoalescingInitializationWork(
  private val launch: (() -> Unit) -> Boolean,
  private val reportFailure: (Throwable) -> Unit,
) {
  private val lock = Any()
  private val pending = linkedMapOf<Any, () -> Unit>()
  private var owner: Any? = null
  private var closed = false

  fun submit(key: Any, action: () -> Unit): Boolean {
    val token = synchronized(lock) {
      if (closed) return false
      pending[key] = action
      if (owner != null) return true
      Any().also { owner = it }
    }
    var accepted = false
    try {
      accepted = launch { drain(token) }
      return accepted
    } finally {
      if (!accepted) synchronized(lock) {
        if (owner === token) {
          owner = null
        }
      }
    }
  }

  private fun drain(token: Any) {
    try {
      while (true) {
        val action = synchronized(lock) {
          val next = pending.entries.firstOrNull()
          if (next == null) {
            if (owner === token) owner = null
            null
          } else next.value.also { pending.remove(next.key) }
        } ?: return
        try { action() } catch (failure: Throwable) {
          val cause = recoverableSystemUiFailure(failure)
          try { reportFailure(cause) }
          catch (report: Throwable) { recoverableSystemUiFailure(report) }
        }
      }
    } finally {
      synchronized(lock) {
        if (owner === token) {
          owner = null
          pending.clear()
        }
      }
    }
  }

  fun close() = synchronized(lock) { closed = true; pending.clear() }
  fun reopen() = synchronized(lock) { closed = false }
}
