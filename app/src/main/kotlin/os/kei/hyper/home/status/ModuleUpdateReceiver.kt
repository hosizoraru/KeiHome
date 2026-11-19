package os.kei.hyper.home.status

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import os.kei.hyper.home.xposed.ownsReloadableJava

private const val TAG = "KeiHomeReload"
private val dispatchLock = Mutex()

/** Package replacement starts the manager even when its activity is closed. */
class ModuleUpdateReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val job = JobInfo.Builder(0x4b4801, ComponentName(context, ModuleUpdateRecoveryJob::class.java))
      .setMinimumLatency(30_000)
      .build()
    if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS)
      Log.w(TAG, "Could not schedule the post-update process check")
  }
}

/** Give the framework's automatic update first access to every live target. */
class ModuleUpdateRecoveryJob : JobService() {
  private var check: Job? = null

  override fun onStartJob(params: JobParameters): Boolean {
    check = CoroutineScope(Dispatchers.Main.immediate).launch {
      try {
        val completed = withTimeoutOrNull(60_000) {
          val service = FrameworkConnection.service.filterNotNull().first()
          withContext(Dispatchers.IO) { service.reloadStaleJavaTargets() }
          true
        }
        if (completed == null) Log.w(TAG, "Post-update process check timed out")
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (failure: Exception) {
        Log.w(TAG, "Post-update process check unavailable", failure)
      }
      jobFinished(params, false)
    }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    check?.cancel()
    return false
  }
}

internal fun shouldRecoverTarget(state: HookedTarget.State): Boolean =
  state == HookedTarget.State.FAILED || state == HookedTarget.State.STALE

internal suspend fun XposedService.reloadStaleJavaTargets() = dispatchLock.withLock {
  if (apiVersion < 102) return@withLock
  val user = android.os.Process.myUid() / 100_000
  val targets = runningTargets
  val recoverable = targets.filter {
    it.uid / 100_000 == user && ownsReloadableJava(it.processName, it.uid) &&
      shouldRecoverTarget(it.state)
  }
  Log.i(TAG, "Checked ${targets.size} running targets, ${recoverable.size} need Java recovery")
  val outcomes = recoverable.mapNotNull { target ->
    val label = "${target.processName}/${target.pid}"
    val done = CompletableDeferred<Unit>()
    try {
      hotReloadModule(target, null) { _, result ->
        val message = "$label ${result.status}${result.message?.let { " ($it)" }.orEmpty()}"
        if (result.status == HotReloadResult.Status.SUCCEEDED) Log.i(TAG, message)
        else Log.w(TAG, message)
        done.complete(Unit)
      }
      done
    } catch (failure: Exception) {
      Log.w(TAG, "Could not request reload of $label", failure)
      null
    }
  }
  if (withTimeoutOrNull(40_000) { outcomes.awaitAll() } == null) Log.w(TAG, "Missing reload callback")
}
