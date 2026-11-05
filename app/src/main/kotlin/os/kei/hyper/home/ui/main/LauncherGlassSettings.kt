package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.kei.hyper.home.data.LauncherGlassPreferences
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.RuntimeStatusUiState

@Immutable
internal data class LauncherGlassUiState(
  val saved: LauncherGlassStyle? = null,
  val pending: LauncherGlassStyle? = null,
  val saving: Boolean = false,
  val failed: Boolean = false,
  val unavailable: Boolean = false,
)

internal enum class LauncherGlassRuntimeStatus { PENDING, UNAVAILABLE, ACCEPTED }

internal fun launcherGlassRuntimeStatus(style: LauncherGlassStyle?, runtime: RuntimeStatusUiState,
  now: Long, moduleVersion: Long): LauncherGlassRuntimeStatus {
  val report = runtime.report?.takeIf { it.isFresh(now) && it.moduleVersion == moduleVersion }
  return when {
    runtime.readFailed || runtime.timedOut || report == null -> LauncherGlassRuntimeStatus.PENDING
    report.nativeState == os.kei.hyper.home.status.NativeRuntimeState.INACTIVE_USER -> LauncherGlassRuntimeStatus.PENDING
    !report.launcherGlassAvailable -> LauncherGlassRuntimeStatus.UNAVAILABLE
    !report.acknowledgesLauncherGlass(style) -> LauncherGlassRuntimeStatus.PENDING
    else -> LauncherGlassRuntimeStatus.ACCEPTED
  }
}

internal class LauncherGlassSettings(private val scope: CoroutineScope, private val inScope: () -> Boolean) {
  val state: StateFlow<LauncherGlassUiState>
    field = MutableStateFlow(LauncherGlassUiState())
  private val mutex = Mutex()

  fun reset() { state.value = LauncherGlassUiState() }

  suspend fun read() = mutex.withLock {
    val service = FrameworkConnection.service.value ?: return@withLock
    if (!inScope() || state.value.saving || state.value.pending != null) return@withLock
    val mayPublish = {
      FrameworkConnection.service.value === service && inScope() &&
        !state.value.saving && state.value.pending == null
    }
    try {
      val style = LauncherGlassPreferences.read(service)
      if (mayPublish()) state.value = LauncherGlassUiState(saved = style)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: UnsupportedOperationException) {
      if (mayPublish()) state.update { it.copy(unavailable = true) }
    } catch (_: Exception) {
      if (mayPublish()) state.update { it.copy(failed = true) }
    }
  }

  fun save(style: LauncherGlassStyle) {
    val service = FrameworkConnection.service.value ?: return
    if (!inScope() || state.value.saving || state.value.unavailable) return
    state.update { it.copy(pending = style, saving = true, failed = false) }
    scope.launch {
      mutex.withLock {
        try {
          if (FrameworkConnection.service.value !== service || !inScope()) return@withLock
          LauncherGlassPreferences.save(service, style)
          if (FrameworkConnection.service.value === service) state.value = LauncherGlassUiState(saved = style)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: UnsupportedOperationException) {
          if (FrameworkConnection.service.value === service) state.update { it.copy(unavailable = true) }
        } catch (_: Exception) {
          if (FrameworkConnection.service.value === service) state.update { it.copy(failed = true) }
        } finally {
          if (FrameworkConnection.service.value === service) state.update { it.copy(saving = false) }
        }
      }
    }
  }
}
