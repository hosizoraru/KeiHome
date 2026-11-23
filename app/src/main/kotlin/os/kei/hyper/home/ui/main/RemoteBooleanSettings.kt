package os.kei.hyper.home.ui.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.libxposed.service.XposedService
import os.kei.hyper.home.status.FrameworkConnection

internal class RemoteBooleanSettings(
  private val scope: CoroutineScope,
  private val readChoice: suspend (XposedService) -> Boolean,
  private val saveChoice: suspend (XposedService, Boolean) -> Unit,
  private val inScope: () -> Boolean,
) {
  val state: StateFlow<ThemeEffectsUiState>
    field = MutableStateFlow(ThemeEffectsUiState())
  private val mutex = Mutex()

  fun reset() { state.value = ThemeEffectsUiState() }

  suspend fun read() = mutex.withLock {
    val service = FrameworkConnection.service.value ?: return@withLock
    if (!inScope() || state.value.saving || state.value.saveFailed) return@withLock
    fun publish(update: (ThemeEffectsUiState) -> ThemeEffectsUiState) {
      state.update { if (FrameworkConnection.service.value === service && inScope() && !it.saving)
        update(it) else it }
    }
    try {
      val choice = readChoice(service)
      publish { ThemeEffectsUiState(savedChoice = choice) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: UnsupportedOperationException) { publish { it.copy(loading = false, unavailable = true) } }
    catch (_: Exception) { publish { it.copy(loading = false, readFailed = true) } }
  }

  fun save(enabled: Boolean) {
    val service = FrameworkConnection.service.value ?: return
    val before = state.value
    if (!inScope() || !before.canSaveThemeEffects(enabled) ||
      !state.compareAndSet(before, before.copy(saving = true))) return
    scope.launch {
      mutex.withLock {
        try {
          if (FrameworkConnection.service.value !== service || !inScope()) return@withLock
          saveChoice(service, enabled)
          val saved = readChoice(service)
          if (FrameworkConnection.service.value === service && inScope())
            state.value = ThemeEffectsUiState(savedChoice = saved, saving = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: UnsupportedOperationException) {
          if (FrameworkConnection.service.value === service) state.update { it.copy(unavailable = true) }
        } catch (_: Exception) {
          if (FrameworkConnection.service.value === service) state.update { it.copy(saveFailed = true) }
        } finally {
          if (FrameworkConnection.service.value === service) state.update { it.copy(saving = false) }
        }
      }
    }
  }
}
