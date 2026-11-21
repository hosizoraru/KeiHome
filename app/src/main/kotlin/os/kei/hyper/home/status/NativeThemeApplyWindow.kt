package os.kei.hyper.home.status

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import os.kei.hyper.home.R

private const val THEME_APPLY_RESULT = "os.kei.hyper.home.extra.THEME_APPLY_RESULT"
// ActivityOptions' Android windowing-mode wire format, also used by HyperOS' dock.
private const val WINDOWING_MODE = "android.activity.windowingMode"
private const val FULLSCREEN = 1
private const val FREEFORM = 5
private const val DETAIL_LAUNCHED_AT = "detailLaunchedAt"
private const val EARLY_DETAIL_EXIT_MS = 1_500L

/** Owns the callback while the separate theme task is in front of KeiHome. */
internal class NativeThemeApplyWindow(private val scope: CoroutineScope) {
  enum class State { IDLE, APPLYING, COMPLETE }
  val state: StateFlow<State>
    field = MutableStateFlow(State.IDLE)

  fun launch(context: Context, intent: Intent, onApplied: () -> Unit): Boolean {
    val reply = CompletableDeferred<Int>()
    val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
      override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        reply.complete(resultCode)
      }
    }
    val parcel = Parcel.obtain()
    val callback = try {
      // Parcel the framework type so it can be restored without the anonymous subclass.
      receiver.writeToParcel(parcel, 0)
      parcel.setDataPosition(0)
      ResultReceiver.CREATOR.createFromParcel(parcel)
    } finally {
      parcel.recycle()
    }
    if (!startThemeInFreeform(context, intent, callback)) return false
    state.value = State.APPLYING
    scope.launch {
      try {
        if (withTimeoutOrNull(120_000) { reply.await() } == Activity.RESULT_OK) {
          state.value = State.COMPLETE
          onApplied()
          delay(850)
        }
      } finally {
        reply.cancel()
        state.value = State.IDLE
      }
    }
    return true
  }
}

internal fun openNativeThemeDetail(context: Context, localId: String) {
  try {
    startThemeTask(context, IconPackImportProtocol.nativeDetail(localId), FULLSCREEN)
  } catch (failure: RuntimeException) {
    Log.w("KeiHomePackImport", "Cannot launch manual Themes detail", failure)
    showThemeDetailRetry(context)
  }
}

private fun startThemeInFreeform(context: Context, intent: Intent, callback: ResultReceiver): Boolean {
  if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
    !context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return false

  return try {
    startThemeTask(context, intent, FREEFORM, callback)
    true
  } catch (failure: RuntimeException) {
    Log.w("KeiHomeAutoApply", "Cannot open Themes in freeform", failure)
    false
  }
}

private fun startThemeTask(context: Context, intent: Intent, windowingMode: Int, callback: ResultReceiver? = null) {
  val options = ActivityOptions.makeBasic().toBundle().apply {
    putInt(WINDOWING_MODE, windowingMode)
  }
  // A separate task keeps the current KeiHome window in place behind Themes.
  val launch = Intent(context, NativeThemeWindowActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    .putExtra(NativeThemeWindowActivity.THEME, intent)
    .putExtra(THEME_APPLY_RESULT, callback)
  context.startActivity(launch, options)
}

internal fun needsThemeDetailRetry(resultCode: Int, timeOpenMs: Long): Boolean =
  resultCode == Activity.RESULT_CANCELED && timeOpenMs in 0..EARLY_DETAIL_EXIT_MS

private fun showThemeDetailRetry(context: Context) {
  Toast.makeText(context, R.string.pack_open_themes_failed, Toast.LENGTH_LONG).show()
}

/** Keeps the OEM activity result inside the separate Themes task. */
class NativeThemeWindowActivity : androidx.activity.ComponentActivity() {
  private val applyCallback get() = intent.getParcelableExtra(THEME_APPLY_RESULT, ResultReceiver::class.java)
  private var launchedAt = 0L
  private val openTheme = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
  ) { result ->
    val callback = applyCallback
    val timeOpenMs = SystemClock.elapsedRealtime() - launchedAt
    if (callback == null && needsThemeDetailRetry(result.resultCode, timeOpenMs)) {
      Log.i("KeiHomePackImport", "Manual Themes detail closed immediately; showing reimport guidance")
      showThemeDetailRetry(this)
    }
    callback?.send(result.resultCode, null)
    finishAndRemoveTask()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    launchedAt = savedInstanceState?.getLong(DETAIL_LAUNCHED_AT) ?: SystemClock.elapsedRealtime()
    if (savedInstanceState != null) return
    val theme = intent.getParcelableExtra(THEME, Intent::class.java)
    if (theme != null) {
      try {
        openTheme.launch(theme)
        return
      } catch (failure: RuntimeException) {
        Log.w("KeiHomeAutoApply", "Cannot open Themes", failure)
      }
    }
    val callback = applyCallback
    if (callback == null) showThemeDetailRetry(this)
    callback?.send(RESULT_CANCELED, null)
    finishAndRemoveTask()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putLong(DETAIL_LAUNCHED_AT, launchedAt)
  }

  internal companion object {
    const val THEME = "os.kei.hyper.home.extra.THEME_DETAIL"
  }
}
