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
import os.kei.hyper.home.data.SearchGlassStyle
import os.kei.hyper.home.status.FrameworkConnection

@Immutable
internal data class LauncherGlassUiState(
  val saved: LauncherGlassStyle? = null,
  val pending: LauncherGlassStyle? = null,
  val saving: Boolean = false,
  val failed: Boolean = false,
  val unavailable: Boolean = false,
  val savedSearch: SearchGlassStyle? = null,
  val pendingSearch: SearchGlassStyle? = null,
)

internal class LauncherGlassSettings(private val scope: CoroutineScope, private val inScope: () -> Boolean) {
  val state: StateFlow<LauncherGlassUiState>
    field = MutableStateFlow(LauncherGlassUiState())
  private val mutex = Mutex()

  fun reset() { state.value = LauncherGlassUiState() }

  suspend fun read() = mutex.withLock {
    val service = FrameworkConnection.service.value ?: return@withLock
    if (!inScope() || state.value.saving || state.value.pending != null ||
      state.value.pendingSearch != null) return@withLock
    fun publish(update: (LauncherGlassUiState) -> LauncherGlassUiState) {
      state.update { current ->
        if (FrameworkConnection.service.value === service && inScope() &&
          !current.saving && current.pending == null && current.pendingSearch == null) update(current)
        else current
      }
    }
    try {
      val choice = LauncherGlassPreferences.readChoice(service)
      publish { LauncherGlassUiState(saved = choice.dock, savedSearch = choice.search) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: UnsupportedOperationException) {
      publish { it.copy(unavailable = true) }
    } catch (_: Exception) {
      publish { it.copy(failed = true) }
    }
  }

  fun save(style: LauncherGlassStyle) = saveChange(style = style)

  fun saveSearch(search: SearchGlassStyle) = saveChange(search = search)

  private fun saveChange(style: LauncherGlassStyle? = null, search: SearchGlassStyle? = null) {
    val service = FrameworkConnection.service.value ?: return
    while (true) {
      val current = state.value
      if (!inScope() || current.saving || current.unavailable) return
      if (state.compareAndSet(current, current.copy(
        pending = style ?: current.pending,
        pendingSearch = search ?: current.pendingSearch,
        saving = true, failed = false,
      ))) break
    }
    scope.launch {
      mutex.withLock {
        try {
          if (FrameworkConnection.service.value !== service || !inScope()) return@withLock
          if (style != null) LauncherGlassPreferences.save(service, style)
          else LauncherGlassPreferences.saveSearch(service, requireNotNull(search))
          val choice = LauncherGlassPreferences.readChoice(service)
          if (FrameworkConnection.service.value === service && inScope())
            state.value = LauncherGlassUiState(saved = choice.dock, savedSearch = choice.search, saving = true)
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
