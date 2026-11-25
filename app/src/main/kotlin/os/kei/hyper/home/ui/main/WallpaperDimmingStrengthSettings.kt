package os.kei.hyper.home.ui.main

import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.kei.hyper.home.data.WallpaperDimStrengths
import os.kei.hyper.home.data.WallpaperDimStyle
import os.kei.hyper.home.data.WallpaperDimmingPreferences
import os.kei.hyper.home.status.FrameworkConnection

internal class WallpaperDimmingStrengthSettings(
  scope: CoroutineScope,
  private val inScope: () -> Boolean,
) {
  data class State(
    val strengths: WallpaperDimStrengths? = null,
    val failed: Boolean = false,
  )

  private data class Save(
    val service: XposedService,
    val revision: Long,
    val strengths: WallpaperDimStrengths,
  )

  private val mutableState = MutableStateFlow(State())
  val state = mutableState.asStateFlow()
  private val mutations = Mutex()
  private val saves = Channel<Save>(Channel.CONFLATED)
  private var revision = 0L

  init {
    scope.launch {
      for (save in saves) {
        mutations.withLock {
          if (!current(save.service, save.revision)) return@withLock
          try {
            WallpaperDimmingPreferences.saveStrengths(save.service, save.strengths)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            if (current(save.service, save.revision) && mutableState.value.strengths == save.strengths) {
              mutableState.value = State(save.strengths, failed = true)
            }
          }
        }
        // Limit preference IPC during a drag. The newest value is retained even after dismissal.
        delay(90)
      }
    }
  }

  fun reset() {
    revision++
    mutableState.value = State()
  }

  suspend fun read() = mutations.withLock {
    if (mutableState.value.strengths != null) return@withLock
    val service = FrameworkConnection.service.value ?: return@withLock
    val reading = revision
    if (!current(service, reading)) return@withLock
    mutableState.value = State()
    try {
      val strengths = WallpaperDimmingPreferences.readStrengths(service)
      if (current(service, reading)) mutableState.value = State(strengths)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      if (current(service, reading)) mutableState.value = State(failed = true)
    }
  }

  fun set(lockScreen: Boolean, percent: Int, separate: Boolean) {
    val before = state.value.strengths ?: return
    if (before.style != WallpaperDimStyle.HYPER_OS) return
    val value = if (!separate) before.copy(home = percent, lock = percent)
      else if (lockScreen) before.copy(lock = percent) else before.copy(home = percent)
    if (value != before || state.value.failed) save(value)
  }

  fun setStyle(style: WallpaperDimStyle) {
    val before = state.value.strengths ?: return
    if (before.style != style || state.value.failed) save(before.copy(style = style))
  }

  fun retry() { state.value.strengths?.let(::save) }

  private fun save(value: WallpaperDimStrengths) {
    val service = FrameworkConnection.service.value ?: return
    if (!inScope()) return
    mutableState.value = State(value)
    saves.trySend(Save(service, revision, value))
  }

  private fun current(service: XposedService, reading: Long): Boolean =
    reading == revision && FrameworkConnection.service.value === service && inScope()
}
