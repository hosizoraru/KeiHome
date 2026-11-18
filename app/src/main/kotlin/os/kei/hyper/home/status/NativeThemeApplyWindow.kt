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
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val THEME_APPLY_RESULT = "os.kei.hyper.home.extra.THEME_APPLY_RESULT"
// ActivityOptions' Android windowing-mode wire format, also used by HyperOS' dock.
private const val WINDOWING_MODE = "android.activity.windowingMode"
private const val FREEFORM = 5

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
  val intent = IconPackImportProtocol.nativeDetail(localId)
  if (!startThemeInFreeform(context, intent)) context.startActivity(intent)
}

private fun startThemeInFreeform(context: Context, intent: Intent, callback: ResultReceiver? = null): Boolean {
  if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
    !context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return false

  val options = ActivityOptions.makeBasic().toBundle().apply {
    putInt(WINDOWING_MODE, FREEFORM)
  }
  // A separate task keeps the current KeiHome window in place behind Themes.
  val launch = Intent(context, NativeThemeWindowActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    .putExtra(NativeThemeWindowActivity.THEME, intent)
    .putExtra(THEME_APPLY_RESULT, callback)
  return try {
    context.startActivity(launch, options)
    true
  } catch (failure: RuntimeException) {
    Log.w("KeiHomeAutoApply", "Cannot open Themes in freeform", failure)
    false
  }
}

/** Keeps the OEM activity result inside the separate, resizable Themes task. */
class NativeThemeWindowActivity : androidx.activity.ComponentActivity() {
  private val openTheme = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
  ) { result ->
    finishAndRemoveTask()
    intent.getParcelableExtra(THEME_APPLY_RESULT, ResultReceiver::class.java)?.send(result.resultCode, null)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
    finishAndRemoveTask()
    intent.getParcelableExtra(THEME_APPLY_RESULT, ResultReceiver::class.java)?.send(RESULT_CANCELED, null)
  }

  internal companion object {
    const val THEME = "os.kei.hyper.home.extra.THEME_DETAIL"
  }
}
