package os.kei.hyper.home.status

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import os.kei.hyper.home.data.ThemeEffectsPreferences

/** The App owns writes to API 102 preferences; injected processes have read-only access. */
internal object ThemeMaterialAutoEnable {
  const val METHOD = "enable_materials_for_non_default_icons"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val pending = AtomicBoolean()

  fun request(): Boolean {
    if (!pending.compareAndSet(false, true)) return true
    scope.launch {
      try {
        val service = withTimeoutOrNull(4_000) { FrameworkConnection.service.filterNotNull().first() }
          ?: return@launch
        if (ThemeEffectsPreferences.enableForNonDefaultOnce(service)) {
          Log.i("KeiHomeMaterials", "Enabled system materials for the first non-default icon pack")
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        Log.w("KeiHomeMaterials", "Automatic material preference could not be confirmed", failure)
      } finally { pending.set(false) }
    }
    // This acknowledges scheduling, not a completed preference write or renderer update.
    return true
  }
}
