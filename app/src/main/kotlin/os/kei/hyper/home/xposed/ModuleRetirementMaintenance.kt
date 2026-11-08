package os.kei.hyper.home.xposed

/** One finite task per adoption. Platform cache locks must never block the reload callback. */
internal class ModuleRetirementMaintenance {
  @Volatile private var worker: Thread? = null

  @Synchronized fun start(work: () -> Unit) {
    check(worker == null) { "Retirement maintenance already started" }
    val thread = Thread(work, "KeiHomeRetirement").apply {
      isDaemon = true
      contextClassLoader = ClassLoader.getSystemClassLoader()
    }
    worker = thread
    thread.start()
  }

  fun awaitStopped(timeoutMillis: Long = 300): Boolean {
    val thread = synchronized(this) { worker } ?: return true
    if (thread === Thread.currentThread()) return false
    if (!thread.isAlive) return true
    require(timeoutMillis > 0)
    return try {
      thread.join(timeoutMillis)
      !thread.isAlive
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }
  }
}
