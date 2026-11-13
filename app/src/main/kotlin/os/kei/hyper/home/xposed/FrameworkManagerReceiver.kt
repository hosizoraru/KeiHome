package os.kei.hyper.home.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.status.FrameworkManagerProtocol

/** A fixed manager-launch operation, available only to this module's signed App. */
internal class FrameworkManagerReceiver(
  private val runActive: (() -> Unit) -> Unit,
  private val onFailure: (Throwable) -> Unit,
) {
  private data class Registration(val context: Context, val handler: Handler, val epoch: Long)

  private val epoch = AtomicLong()
  private val lifecycle = RestartableMonitor<Registration>(::createWorker, onFailure)

  @Synchronized fun start(context: Context, handler: Handler) {
    lifecycle.start(Registration(context, handler, epoch.get()))
  }

  @Synchronized fun stop() {
    // Reject delivery immediately, including registration still in flight during worker startup.
    epoch.incrementAndGet()
    lifecycle.stop()
  }

  fun awaitStopped(deadlineNanos: Long): Boolean = lifecycle.awaitStopped(deadlineNanos)

  private fun createWorker(registration: Registration): MonitorWorker {
    lateinit var worker: FrameworkManagerRegistrationWorker<BroadcastReceiver>
    fun accepting(): Boolean = epoch.get() == registration.epoch && worker.accepting
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (!accepting() || intent.action != FrameworkManagerProtocol.ACTION || !isOrderedBroadcast)
          return
        try {
          // Read the actual sender, shared through BroadcastOptions, never a caller-supplied UID.
          val appUid = context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
          if (sentFromUid != appUid || sentFromPackage != BuildConfig.APPLICATION_ID) return
          val target = intent.getIntExtra("target", -1)
          val flavor = intent.getIntExtra("flavor", -1)
          val deadline = intent.getLongExtra("deadline", 0)
          if (!FrameworkManagerProtocol.valid(target, flavor, deadline, SystemClock.elapsedRealtime()))
            return
          runActive {
            if (!accepting()) return@runActive
            val launch = FrameworkManagerProtocol.parasiticIntent(context, target, flavor)
            if (accepting() && deadline > SystemClock.elapsedRealtime()) {
              context.startActivity(launch)
              resultCode = FrameworkManagerProtocol.OPENED
            }
          }
        } catch (failure: Exception) { onFailure(failure) }
      }
    }
    worker = FrameworkManagerRegistrationWorker(
      token = receiver,
      register = {
        registration.context.registerReceiver(
          receiver, IntentFilter(FrameworkManagerProtocol.ACTION),
          FrameworkManagerProtocol.PERMISSION, registration.handler, Context.RECEIVER_EXPORTED,
        )
      },
      unregister = {
        try {
          registration.context.unregisterReceiver(it)
        } catch (failure: IllegalArgumentException) {
          if (failure.message?.startsWith("Receiver not registered:") != true) throw failure
        }
      },
      onFailure = onFailure,
    )
    return worker
  }
}

/**
 * Owns the token before registration: a failed reply stays unready but may have installed a callback.
 * Both successful and failed registration keep that token until cleanup has actually succeeded.
 */
internal class FrameworkManagerRegistrationWorker<T : Any>(
  private val token: T,
  private val register: (T) -> Unit,
  private val unregister: (T) -> Unit,
  private val onFailure: (Throwable) -> Unit,
) : MonitorWorker {
  private val lock = java.lang.Object()
  private var stopRevision = 0L
  @Volatile override var closed = false
    private set
  @Volatile override var cleanupComplete = false
    private set
  @Volatile private var registered = false
  val accepting: Boolean get() = registered && !closed

  override val thread = Thread(::run, "KeiHomeFrameworkManager").apply { isDaemon = true }

  override fun start() {
    thread.start()
  }

  override fun stop() {
    synchronized(lock) {
      closed = true
      stopRevision++
      lock.notifyAll()
    }
  }

  private fun run() {
    if (closed) {
      cleanupComplete = true
      return
    }
    try {
      register(token)
      registered = true
    } catch (failure: Throwable) {
      // A failed reply may follow installation. Keep the token unready and prove cleanup instead.
      stop()
      report(failure)
    }
    var attemptedRevision = 0L
    while (true) {
      synchronized(lock) {
        while (!closed || stopRevision == attemptedRevision) {
          try {
            lock.wait()
          } catch (_: InterruptedException) {
            closed = true
            stopRevision++
          }
        }
        attemptedRevision = stopRevision
      }
      registered = false
      try {
        unregister(token)
        cleanupComplete = true
        return
      } catch (failure: Throwable) {
        // Stop/rollback retries on this worker; a failed cleanup never discards its token.
        report(failure)
      }
    }
  }

  private fun report(failure: Throwable) {
    try {
      onFailure(failure)
    } catch (_: Throwable) {
      // Diagnostic callbacks must not abandon an outstanding registration.
    }
  }
}
