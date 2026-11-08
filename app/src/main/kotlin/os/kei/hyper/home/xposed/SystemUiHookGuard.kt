package os.kei.hyper.home.xposed

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/** The caller must keep chain.proceed() outside this optional-work boundary. */
internal class SystemUiHookGuard(private val onFailure: (Throwable) -> Unit) {
  private val disabled = AtomicBoolean()
  private val running = ThreadLocal<Boolean>()

  fun <T> run(fallback: T, action: () -> T): T {
    if (disabled.get() || running.get() == true) return fallback
    running.set(true)
    try {
      return action()
    } catch (failure: Throwable) {
      val cause = recoverableSystemUiFailure(failure)
      if (disabled.compareAndSet(false, true)) {
        try {
          onFailure(cause)
        } catch (reportFailure: Throwable) {
          recoverableSystemUiFailure(reportFailure)
        }
      }
      return fallback
    } finally {
      running.remove()
    }
  }
}

internal fun recoverableSystemUiFailure(failure: Throwable): Throwable {
  var cause = failure
  while (cause is InvocationTargetException && cause.targetException != null) {
    cause = cause.targetException
  }
  if (cause is VirtualMachineError || cause is ThreadDeath) throw cause
  if (cause is InterruptedException) Thread.currentThread().interrupt()
  return cause
}
