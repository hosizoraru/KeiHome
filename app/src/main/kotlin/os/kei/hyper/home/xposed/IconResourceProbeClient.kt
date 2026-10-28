package os.kei.hyper.home.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.LauncherActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class IconResourceProbeClient(private val context: Context) {
  fun packagesToRefresh(
    activities: List<LauncherActivityInfo>,
    isCurrent: () -> Boolean,
  ): Set<String> {
    val packages = activities.groupBy { it.componentName.packageName }
    if (packages.isEmpty() || packages.size > MAX_PACKAGES) return packages.keys

    val connectionReady = CountDownLatch(1)
    val resultsReady = CountDownLatch(packages.size)
    val results = ConcurrentHashMap<Int, Boolean>()
    val replyThread = HandlerThread("KeiHomeIconProbeReply").also { it.start() }
    val reply = Messenger(Handler(replyThread.looper) { message ->
      if (results.putIfAbsent(message.arg1, message.data.getBoolean(
          IconProbeProtocol.REQUIRES_REFRESH, true,
        )) == null
      ) resultsReady.countDown()
      true
    })
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
      val intent = Intent().setClassName(MODULE_PACKAGE, PROBE_SERVICE)
      bindAttempted = true
      val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      if (!bound || !connectionReady.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return packages.keys
      }
      val target = remote.get() ?: return packages.keys
      val config = Configuration(context.resources.configuration)
      val indexed = packages.entries.toList()
      for ((index, entry) in indexed.withIndex()) {
        if (!isCurrent()) return packages.keys
        try {
          target.send(Message.obtain().apply {
            arg1 = index
            replyTo = reply
            data.putParcelableArrayList(
              IconProbeProtocol.ACTIVITY_INFOS,
              ArrayList(entry.value.map { it.activityInfo }),
            )
            data.putParcelable(IconProbeProtocol.CONFIGURATION, config)
          })
        } catch (_: Throwable) {
          if (results.putIfAbsent(index, true) == null) resultsReady.countDown()
        }
      }
      val deadline = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
      while (isCurrent() && resultsReady.count > 0 && SystemClock.elapsedRealtime() < deadline) {
        resultsReady.await(POLL_MS, TimeUnit.MILLISECONDS)
      }
      return indexed.mapIndexedNotNull { index, entry ->
        if (results[index] != false) entry.key else null
      }.toSet()
    } catch (_: Throwable) {
      return packages.keys
    } finally {
      if (bindAttempted) {
        try {
          context.unbindService(connection)
        } catch (_: Throwable) {
        }
      }
      replyThread.quitSafely()
      try {
        replyThread.join(REPLY_THREAD_TIMEOUT_MS)
        if (replyThread.isAlive) Log.w(TAG, "Probe reply thread did not stop in time")
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  private companion object {
    const val TAG = "KeiHomeIconProbe"
    const val MODULE_PACKAGE = "os.kei.hyper.home"
    const val PROBE_SERVICE = "$MODULE_PACKAGE.xposed.IconResourceProbeService"
    const val MAX_PACKAGES = 512
    const val CONNECT_TIMEOUT_MS = 3_000L
    const val PROBE_TIMEOUT_MS = 5_000L
    const val POLL_MS = 100L
    const val REPLY_THREAD_TIMEOUT_MS = 1_000L
  }
}
