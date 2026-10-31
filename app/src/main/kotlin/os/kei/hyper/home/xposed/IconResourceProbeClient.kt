package os.kei.hyper.home.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.LauncherActivityInfo
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class IconResourceProbeClient(private val context: Context) {
  fun packagesToRefresh(
    activities: List<LauncherActivityInfo>,
    isCurrent: () -> Boolean,
  ): Set<String> = probe(activities, false, isCurrent).packagesToRefresh

  fun probe(
    activities: List<LauncherActivityInfo>,
    inspectLauncherMask: Boolean,
    isCurrent: () -> Boolean,
  ): IconProbeBatch = probeGroups(
    activities.groupBy { it.componentName.packageName }.mapValues { (_, entries) ->
      entries.map { it.activityInfo }
    }, inspectLauncherMask, false, isCurrent,
  )

  fun applicationMasks(applications: List<ApplicationInfo>, isCurrent: () -> Boolean): IconProbeBatch =
    probeGroups(applications.groupBy { it.packageName }.mapValues { (_, entries) ->
      entries.map { app -> ActivityInfo().apply {
        packageName = app.packageName
        applicationInfo = app
        icon = app.icon
      } }
    }, true, true, isCurrent, MASK_BATCH_SIZE)

  private fun probeGroups(
    packages: Map<String, List<ActivityInfo>>,
    inspectLauncherMask: Boolean,
    maskOnly: Boolean,
    isCurrent: () -> Boolean,
    batchSize: Int = MAX_PACKAGES,
  ): IconProbeBatch = runCancellableIconProbe(isCurrent) {
    val unknown = IconProbeBatch(packages.keys, emptyMap())
    if (packages.isEmpty() || (!maskOnly && packages.size > MAX_PACKAGES)) {
      return@runCancellableIconProbe unknown
    }
    val indexed = packages.entries.toList()
    IconProbeSession(
      indexed.map { it.key },
      ProbeTransport(indexed, inspectLauncherMask, maskOnly, isCurrent),
      isCurrent,
    ).probe(batchSize)
  }

  private inner class ProbeTransport(
    private val packages: List<Map.Entry<String, List<ActivityInfo>>>,
    private val inspectLauncherMask: Boolean,
    private val maskOnly: Boolean,
    private val isCurrent: () -> Boolean,
  ) : IconProbeTransport {
    private val lock = Any()
    private val replyThread = HandlerThread("KeiHomeIconProbeReply")
    private var remote: Messenger? = null
    private var closed = false
    private var disconnected = false
    private var bindAttempted = false
    private lateinit var callbacks: IconProbeTransport.Callbacks
    private lateinit var reply: Messenger
    private lateinit var configuration: Configuration
    private val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val accepted = synchronized(lock) {
          if (closed || disconnected) false else {
            remote = Messenger(binder)
            true
          }
        }
        if (accepted) callbacks.connected()
      }

      override fun onServiceDisconnected(name: ComponentName) {
        synchronized(lock) {
          disconnected = true
          remote = null
        }
        callbacks.disconnected()
      }

      override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
      override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    override fun bind(callbacks: IconProbeTransport.Callbacks): Boolean {
      this.callbacks = callbacks
      replyThreads.start(replyThread)
      reply = Messenger(Handler(replyThread.looper) { message ->
        callbacks.result(
          message.arg1,
          message.data.getBoolean(IconProbeProtocol.REQUIRES_REFRESH, true),
          LauncherMaskStatus.fromWire(message.data.getInt(
            IconProbeProtocol.LAUNCHER_MASK, LauncherMaskStatus.UNKNOWN.ordinal,
          )),
        )
        true
      })
      configuration = Configuration(context.resources.configuration)
      val intent = Intent().setClassName(MODULE_PACKAGE, PROBE_SERVICE)
      checkIconProbeActive(isCurrent)
      bindAttempted = true
      // Connection callbacks only publish state; they must not queue behind system-server main.
      return context.bindService(intent, Context.BIND_AUTO_CREATE, Executor { it.run() }, connection)
    }

    override fun send(requestId: Int) {
      val target = synchronized(lock) { remote.takeUnless { closed || disconnected } }
        ?: throw IllegalStateException("Icon probe disconnected")
      target.send(Message.obtain().apply {
        arg1 = requestId
        replyTo = reply
        data.putParcelableArrayList(
          IconProbeProtocol.ACTIVITY_INFOS, ArrayList(packages[requestId].value),
        )
        data.putParcelable(IconProbeProtocol.CONFIGURATION, configuration)
        data.putBoolean(IconProbeProtocol.INSPECT_LAUNCHER_MASK, inspectLauncherMask)
        data.putBoolean(IconProbeProtocol.MASK_ONLY, maskOnly)
      })
    }

    override fun close() {
      synchronized(lock) {
        if (closed) return
        closed = true
        remote = null
      }
      if (bindAttempted) {
        try {
          context.unbindService(connection)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
        } catch (_: Throwable) {
        }
      }
      replyThread.quitSafely()
      try {
        replyThread.join(REPLY_THREAD_TIMEOUT_MS)
        if (replyThread.isAlive) Log.w(TAG, "Probe reply thread did not stop in time")
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      } finally {
        replyThreads.reapTerminated()
      }
    }
  }

  companion object {
    private val replyThreads = IconProbeThreadRegistry()

    /** Await this generation's reply threads after stopping probe admission; never scans the VM. */
    fun awaitOwnedReplyThreadsStopped(deadlineNanos: Long): Boolean =
      replyThreads.awaitStopped(deadlineNanos)

    private const val TAG = "KeiHomeIconProbe"
    private const val MODULE_PACKAGE = "os.kei.hyper.home"
    private const val PROBE_SERVICE = "$MODULE_PACKAGE.xposed.IconResourceProbeService"
    private const val MAX_PACKAGES = 512
    private const val MASK_BATCH_SIZE = 128
    private const val REPLY_THREAD_TIMEOUT_MS = 1_000L
  }
}

internal interface IconProbeTransport : AutoCloseable {
  interface Callbacks {
    fun connected()
    fun disconnected()
    fun result(requestId: Int, refresh: Boolean, mask: LauncherMaskStatus)
  }

  fun bind(callbacks: Callbacks): Boolean
  fun send(requestId: Int)
}

/** One sweep owns one binding. Only the active chunk can accept replies, even after a timeout. */
internal class IconProbeSession(
  private val packages: List<String>,
  private val transport: IconProbeTransport,
  private val isCurrent: () -> Boolean,
  private val connectTimeoutMs: Long = 3_000L,
  private val resultTimeoutMs: Long = 5_000L,
) : IconProbeTransport.Callbacks {
  private data class Reply(val refresh: Boolean, val mask: LauncherMaskStatus)
  private val lock = Any()
  private val connectionReady = CountDownLatch(1)
  private val results = mutableMapOf<Int, Reply>()
  private var connected = false
  private var disconnected = false
  private var started = false
  private var closed = false
  private var activeRequests = IntRange.EMPTY
  private var remaining = 0
  private var batchReady: CountDownLatch? = null

  override fun connected() = synchronized(lock) {
    if (!closed && !disconnected) connected = true
    connectionReady.countDown()
  }

  override fun disconnected() = synchronized(lock) {
    disconnected = true
    connected = false
    connectionReady.countDown()
    batchReady?.countDown()
    Unit
  }

  override fun result(requestId: Int, refresh: Boolean, mask: LauncherMaskStatus) {
    synchronized(lock) {
      if (closed || disconnected || requestId !in activeRequests || requestId in results) return
      results[requestId] = Reply(refresh, mask)
      if (--remaining == 0) batchReady?.countDown()
    }
  }

  fun probe(batchSize: Int): IconProbeBatch = runCancellableIconProbe(isCurrent) {
    require(batchSize > 0)
    synchronized(lock) {
      check(!started) { "Icon probe session already used" }
      started = true
    }
    try {
      if (packages.isNotEmpty() && transport.bind(this) && await(connectionReady, connectTimeoutMs)) {
        for (start in packages.indices step batchSize) {
          checkIconProbeActive(isCurrent)
          val ready = CountDownLatch(1)
          val requests = start until minOf(start + batchSize, packages.size)
          val accepted = synchronized(lock) {
            if (!connected || disconnected) false else {
              activeRequests = requests
              remaining = requests.count()
              batchReady = ready
              true
            }
          }
          if (!accepted) break
          for (id in requests) {
            checkIconProbeActive(isCurrent)
            if (!isConnected()) break
            try {
              transport.send(id)
            } catch (cancelled: InterruptedException) {
              throw cancelled
            } catch (cancelled: IconProbeCancelledException) {
              throw cancelled
            } catch (_: Throwable) {
              result(id, true, LauncherMaskStatus.UNKNOWN)
            }
          }
          val complete = await(ready, resultTimeoutMs)
          synchronized(lock) {
            activeRequests = IntRange.EMPTY
            batchReady = null
          }
          // Do not add another chunk behind unfinished remote decoding or revive a dead binding.
          if (!complete || !isConnected()) break
        }
      }
    } catch (cancelled: InterruptedException) {
      Thread.currentThread().interrupt()
      throw cancelled
    } catch (cancelled: IconProbeCancelledException) {
      throw cancelled
    } catch (_: Throwable) {
      // Missing replies remain unknown; already completed results keep their conservative verdicts.
    } finally {
      synchronized(lock) {
        closed = true
        connected = false
        activeRequests = IntRange.EMPTY
        batchReady = null
      }
      transport.close()
    }
    synchronized(lock) {
      IconProbeBatch(
        packages.indices.filter { results[it]?.refresh != false }.map { packages[it] }.toSet(),
        packages.mapIndexed { id, name -> name to (results[id]?.mask ?: LauncherMaskStatus.UNKNOWN) }.toMap(),
      )
    }
  }

  private fun isConnected(): Boolean = synchronized(lock) { connected && !disconnected && !closed }

  private fun await(ready: CountDownLatch, timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (ready.count != 0L) {
      checkIconProbeActive(isCurrent)
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) return false
      ready.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
    }
    checkIconProbeActive(isCurrent)
    return true
  }
}

/** Cancellation must abort the caller's sweep, never become an unknown/all-packages result. */
internal class IconProbeCancelledException(cause: InterruptedException? = null) :
  CancellationException("Icon resource probe cancelled") {
  init { if (cause != null) initCause(cause) }
}

internal fun checkIconProbeActive(isCurrent: () -> Boolean) {
  if (Thread.currentThread().isInterrupted || !isCurrent()) throw IconProbeCancelledException()
}

internal fun <T> runCancellableIconProbe(isCurrent: () -> Boolean, operation: () -> T): T {
  checkIconProbeActive(isCurrent)
  val result = try {
    operation()
  } catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    throw IconProbeCancelledException(interrupted)
  }
  // Cleanup may itself be interrupted after a result has already been computed.
  checkIconProbeActive(isCurrent)
  return result
}

internal data class IconProbeBatch(
  val packagesToRefresh: Set<String>,
  val launcherMasks: Map<String, LauncherMaskStatus>,
)
