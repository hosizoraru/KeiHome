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
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    }, true, true, isCurrent)

  private fun probeGroups(
    packages: Map<String, List<ActivityInfo>>,
    inspectLauncherMask: Boolean,
    maskOnly: Boolean,
    isCurrent: () -> Boolean,
  ): IconProbeBatch = runCancellableIconProbe(isCurrent) {
    val unknown = IconProbeBatch(packages.keys, emptyMap())
    if (packages.isEmpty() || packages.size > MAX_PACKAGES) return@runCancellableIconProbe unknown

    val connectionReady = CountDownLatch(1)
    val resultsReady = CountDownLatch(packages.size)
    val results = ConcurrentHashMap<Int, Boolean>()
    val masks = ConcurrentHashMap<Int, LauncherMaskStatus>()
    val replyThread = HandlerThread("KeiHomeIconProbeReply")
    val remote = AtomicReference<Messenger?>()
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        remote.set(Messenger(binder))
        connectionReady.countDown()
      }

      override fun onServiceDisconnected(name: ComponentName) {
        remote.set(null)
        connectionReady.countDown()
      }

      override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
      override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }
    var bindAttempted = false
    try {
      replyThreads.start(replyThread)
      val reply = Messenger(Handler(replyThread.looper) { message ->
        if (message.arg1 !in 0 until packages.size) return@Handler true
        masks[message.arg1] = LauncherMaskStatus.fromWire(message.data.getInt(
          IconProbeProtocol.LAUNCHER_MASK, LauncherMaskStatus.UNKNOWN.ordinal,
        ))
        if (results.putIfAbsent(message.arg1, message.data.getBoolean(
            IconProbeProtocol.REQUIRES_REFRESH, true,
          )) == null
        ) resultsReady.countDown()
        true
      })
      checkIconProbeActive(isCurrent)
      val intent = Intent().setClassName(MODULE_PACKAGE, PROBE_SERVICE)
      bindAttempted = true
      val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      if (!bound || !connectionReady.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return@runCancellableIconProbe unknown
      }
      checkIconProbeActive(isCurrent)
      val target = remote.get() ?: return@runCancellableIconProbe unknown
      val config = Configuration(context.resources.configuration)
      val indexed = packages.entries.toList()
      for ((index, entry) in indexed.withIndex()) {
        checkIconProbeActive(isCurrent)
        try {
          target.send(Message.obtain().apply {
            arg1 = index
            replyTo = reply
            data.putParcelableArrayList(
              IconProbeProtocol.ACTIVITY_INFOS,
              ArrayList(entry.value),
            )
            data.putParcelable(IconProbeProtocol.CONFIGURATION, config)
            data.putBoolean(IconProbeProtocol.INSPECT_LAUNCHER_MASK, inspectLauncherMask)
            data.putBoolean(IconProbeProtocol.MASK_ONLY, maskOnly)
          })
        } catch (cancelled: InterruptedException) {
          throw cancelled
        } catch (cancelled: IconProbeCancelledException) {
          throw cancelled
        } catch (_: Throwable) {
          if (results.putIfAbsent(index, true) == null) resultsReady.countDown()
        }
      }
      val deadline = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
      while (resultsReady.count > 0 && SystemClock.elapsedRealtime() < deadline) {
        checkIconProbeActive(isCurrent)
        resultsReady.await(POLL_MS, TimeUnit.MILLISECONDS)
      }
      checkIconProbeActive(isCurrent)
      val refresh = indexed.mapIndexedNotNull { index, entry ->
        if (results[index] != false) entry.key else null
      }.toSet()
      IconProbeBatch(refresh, indexed.mapIndexed { index, entry ->
        entry.key to (masks[index] ?: LauncherMaskStatus.UNKNOWN)
      }.toMap())
    } catch (cancelled: InterruptedException) {
      Thread.currentThread().interrupt()
      throw cancelled
    } catch (cancelled: IconProbeCancelledException) {
      throw cancelled
    } catch (_: Throwable) {
      unknown
    } finally {
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
    private const val CONNECT_TIMEOUT_MS = 3_000L
    private const val PROBE_TIMEOUT_MS = 5_000L
    private const val POLL_MS = 100L
    private const val REPLY_THREAD_TIMEOUT_MS = 1_000L
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
