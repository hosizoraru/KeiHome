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
import os.kei.hyper.home.data.LauncherLabelMode
import os.kei.hyper.home.data.LauncherLabelPreferences
import os.kei.hyper.home.status.FrameworkConnection

@Immutable
internal data class LauncherLabelUiState(
  val saved: LauncherLabelMode? = null,
  val pending: LauncherLabelMode? = null,
  val saving: Boolean = false,
  val failed: Boolean = false,
  val unavailable: Boolean = false,
)

internal class LauncherLabelSettings(private val scope: CoroutineScope, private val inScope: () -> Boolean) {
  val state: StateFlow<LauncherLabelUiState>
    field = MutableStateFlow(LauncherLabelUiState())
  private val mutex = Mutex()
  fun reset() { state.value = LauncherLabelUiState() }

  suspend fun read() = mutex.withLock {
    val service = FrameworkConnection.service.value ?: return@withLock
    if (!inScope() || state.value.saving || state.value.pending != null) return@withLock
    try {
      val choice = LauncherLabelPreferences.read(service)
      if (FrameworkConnection.service.value === service) state.value = LauncherLabelUiState(saved = choice)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: UnsupportedOperationException) {
      if (FrameworkConnection.service.value === service) state.update { it.copy(unavailable = true) }
    } catch (_: Exception) {
      if (FrameworkConnection.service.value === service) state.update { it.copy(failed = true) }
    }
  }

  fun save(mode: LauncherLabelMode) {
    val service = FrameworkConnection.service.value ?: return
    if (!inScope() || state.value.saving || state.value.unavailable) return
    state.update { it.copy(pending = mode, saving = true, failed = false) }
    scope.launch {
      mutex.withLock {
        try {
          if (FrameworkConnection.service.value !== service || !inScope()) return@withLock
          LauncherLabelPreferences.save(service, mode)
          if (FrameworkConnection.service.value === service) state.value = LauncherLabelUiState(saved = mode)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
          if (FrameworkConnection.service.value === service) state.update { it.copy(failed = true) }
        } finally {
          if (FrameworkConnection.service.value === service) state.update { it.copy(saving = false) }
        }
      }
    }
  }
}
