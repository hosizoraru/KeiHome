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
import os.kei.hyper.home.data.DockGlassPreferences
import os.kei.hyper.home.status.FrameworkConnection

@Immutable
internal data class DockGlassUiState(
  val saved: Boolean? = null,
  val pending: Boolean? = null,
  val saving: Boolean = false,
  val failed: Boolean = false,
  val unavailable: Boolean = false,
)

internal class DockGlassSettings(private val scope: CoroutineScope, private val inScope: () -> Boolean) {
  val state: StateFlow<DockGlassUiState>
    field = MutableStateFlow(DockGlassUiState())
  private val mutex = Mutex()
  fun reset() { state.value = DockGlassUiState() }

  suspend fun read() = mutex.withLock {
    val service = FrameworkConnection.service.value ?: return@withLock
    if (!inScope() || state.value.saving || state.value.pending != null) return@withLock
    try {
      val choice = DockGlassPreferences.read(service)
      if (FrameworkConnection.service.value === service) state.value = DockGlassUiState(saved = choice)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: UnsupportedOperationException) {
      if (FrameworkConnection.service.value === service) state.update { it.copy(unavailable = true) }
    } catch (_: Exception) {
      if (FrameworkConnection.service.value === service) state.update { it.copy(failed = true) }
    }
  }

  fun save(enabled: Boolean) {
    val service = FrameworkConnection.service.value ?: return
    if (!inScope() || state.value.saving || state.value.unavailable) return
    state.update { it.copy(pending = enabled, saving = true, failed = false) }
    scope.launch {
      mutex.withLock {
        try {
          if (FrameworkConnection.service.value !== service || !inScope()) return@withLock
          DockGlassPreferences.save(service, enabled)
          if (FrameworkConnection.service.value === service) state.value = DockGlassUiState(saved = enabled)
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
