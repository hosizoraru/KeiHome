package os.kei.hyper.home.xposed

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import java.lang.reflect.Modifier
import java.util.UUID

internal object IconRefreshReportProtocol {
  const val ACTION = "os.kei.hyper.home.xposed.ICON_REFRESH_REPORT"
  const val MODULE_PACKAGE = "os.kei.hyper.home"
  const val RECEIVER = "$MODULE_PACKAGE.xposed.IconRefreshReportReceiver"
  const val BOOT = "boot"
  const val GENERATION = "generation"
  const val GENERATION_START = "generation_start"
  const val SEQUENCE = "sequence"
  const val ELAPSED = "elapsed"
  const val WALL_TIME = "wall_time"
  const val KIND = "kind"
  const val CALLBACKS = "callbacks"

  fun intent(report: IconRefreshReport): Intent =
    Intent(ACTION).setClassName(MODULE_PACKAGE, RECEIVER)
      .putExtra(BOOT, report.bootCount)
      .putExtra(GENERATION, report.generation)
      .putExtra(GENERATION_START, report.generationStartedNanos)
      .putExtra(SEQUENCE, report.sequence)
      .putExtra(ELAPSED, report.completedElapsedNanos)
      .putExtra(WALL_TIME, report.completedWallMillis)
      .putExtra(KIND, report.kind.name)
      .apply { report.callbackCount?.let { putExtra(CALLBACKS, it) } }

  fun read(intent: Intent): IconRefreshReport? = try {
    if (intent.action != ACTION) return null
    val kind = IconRefreshReportKind.valueOf(intent.getStringExtra(KIND) ?: return null)
    IconRefreshReport(
      bootCount = intent.getIntExtra(BOOT, -1),
      generation = intent.getStringExtra(GENERATION) ?: return null,
      generationStartedNanos = intent.getLongExtra(GENERATION_START, -1),
      sequence = intent.getLongExtra(SEQUENCE, -1),
      completedElapsedNanos = intent.getLongExtra(ELAPSED, -1),
      completedWallMillis = intent.getLongExtra(WALL_TIME, -1),
      kind = kind,
      callbackCount = if (intent.hasExtra(CALLBACKS)) intent.getIntExtra(CALLBACKS, -1) else null,
    )
  } catch (_: Throwable) {
    null
  }
}

internal class IconRefreshReporter {
  private val lock = Any()
  private val generation = UUID.randomUUID().toString()
  private val generationStartedNanos = SystemClock.elapsedRealtimeNanos()
  private var sequence = 0L
  private val dispatcher = IconRefreshReportDispatcher(::send)

  fun report(kind: IconRefreshReportKind, callbackCount: Int? = null) {
    try {
      synchronized(lock) {
        sequence += 1
        val report = IconRefreshReport(
          bootCount = -1,
          generation = generation,
          generationStartedNanos = generationStartedNanos,
          sequence = sequence,
          completedElapsedNanos = SystemClock.elapsedRealtimeNanos(),
          completedWallMillis = System.currentTimeMillis(),
          kind = kind,
          callbackCount = callbackCount,
        )
        if (!dispatcher.enqueue(report)) Log.w(TAG, "Runtime report queue unavailable")
      }
    } catch (failure: Throwable) {
      Log.w(TAG, "Runtime report could not be queued", failure)
    }
  }

  fun stopIfIdle(): Boolean = dispatcher.stopIfIdle()

  fun awaitStopped(timeoutMillis: Long): Boolean = dispatcher.awaitStopped(timeoutMillis)

  private fun send(report: IconRefreshReport): Boolean = try {
    if (Process.myUid() != Process.SYSTEM_UID) return false
    val activityThread = Class.forName("android.app.ActivityThread")
    if (activityThread.getDeclaredMethod("isSystem").invoke(null) != true) return false
    val thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null)
      ?: return false
    val context = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
      ?: return false
    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    if (boot < 0) return false
    val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
    val send = Context::class.java.getMethod(
      "sendBroadcastAsUser",
      Intent::class.java, UserHandle::class.java, String::class.java, Bundle::class.java,
    )
    require(send.returnType == Void.TYPE && !Modifier.isStatic(send.modifiers))
    send.invoke(
      context, IconRefreshReportProtocol.intent(report.copy(bootCount = boot)),
      Process.myUserHandle(), null, options,
    )
    true
  } catch (_: Throwable) {
    false
  }

  private companion object {
    const val TAG = "KeiHomeRuntimeReport"
  }
}
