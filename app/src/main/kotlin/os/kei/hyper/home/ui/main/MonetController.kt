package os.kei.hyper.home.ui.main

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.MonetActionStatus
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetPaletteReader
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetSchemeStyle
import os.kei.hyper.home.status.FrameworkConnection

internal fun resolveMonetInspiration(
  preference: Boolean?,
  snapshot: MonetPaletteSnapshot?,
  activeComponentMonet: Boolean?,
): Boolean = preference ?: (
  snapshot?.customization?.style == MonetSchemeStyle.COLOROS_INSPIRATION ||
    activeComponentMonet == true
)

/** Owns Monet palette observation, wallpaper seed refresh, and action delivery for the UI. */
internal class MonetController(
  private val application: Application,
  private val scope: CoroutineScope,
  private val preferences: AppPreferences,
  private val activeThemeLocalId: () -> String?,
  private val colorOsPaletteUnavailable: () -> Boolean,
) {
  val state: StateFlow<MonetUiState>
    field = MutableStateFlow(MonetUiState())

  private val paletteReader = MonetPaletteReader(application)
  private val paletteMutex = Mutex()
  private var applyJob: Job? = null

  suspend fun readPalette() = paletteMutex.withLock {
    try {
      val result = withContext(Dispatchers.IO) { paletteReader.read() }
      state.update { it.copy(snapshot = result, readFailed = false) }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      state.update { it.copy(readFailed = true) }
    }
  }

  fun cancelPendingApply() {
    val active = applyJob ?: return
    active.cancel()
    applyJob = null
    state.update {
      if (it.action == MonetActionStatus.APPLYING) it.copy(action = MonetActionStatus.UNAVAILABLE)
      else it
    }
  }

  fun apply(choice: MonetCustomization) {
    preferences.setMonetInspiration(choice.style == MonetSchemeStyle.COLOROS_INSPIRATION)
    submit(choice, preserveEnabled = true)
  }

  fun selectDesign(inspiration: Boolean) {
    state.update {
      if (it.action == MonetActionStatus.APPLIED) it.copy(action = MonetActionStatus.IDLE, submittedChoice = null)
      else it
    }
    preferences.setMonetInspiration(inspiration)
  }

  fun setEnabled(enabled: Boolean) {
    if (enabled && colorOsPaletteUnavailable()) return
    val choice = state.value.snapshot?.customization ?: MonetCustomization()
    val active = activeThemeLocalId()
    val inspiration = resolveMonetInspiration(
      preference = preferences.state.value.monetInspiration,
      snapshot = state.value.snapshot,
      activeComponentMonet = active?.let { preferences.state.value.colorOsComponents[it] },
    )
    submit(
      choice.copy(
        style = if (inspiration) MonetSchemeStyle.COLOROS_INSPIRATION
        else choice.style.takeUnless { it == MonetSchemeStyle.COLOROS_INSPIRATION }
          ?: MonetSchemeStyle.TONAL_SPOT,
      ),
      enabled = enabled,
    )
  }

  fun disableForNonMonetColorOs() {
    submit(state.value.snapshot?.customization ?: MonetCustomization(), enabled = false)
  }

  suspend fun refreshWallpaper(): Boolean {
    val service = FrameworkConnection.service.value ?: return false
    return try {
      val request = withContext(Dispatchers.IO) { MonetAction.requestPaletteRefresh(service) }
      val uri = Settings.Secure.getUriFor(MonetAction.PALETTE_REFRESHED)
      awaitSettingCondition(uri, timeoutMs = 4_000L, fallbackPollMs = 500L) {
        Settings.Secure.getString(application.contentResolver, MonetAction.PALETTE_REFRESHED)
          ?.toLongOrNull() == request.revision
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      false
    }
  }

  private fun submit(choice: MonetCustomization, enabled: Boolean = true, preserveEnabled: Boolean = false) {
    if (enabled && colorOsPaletteUnavailable()) return
    val service = FrameworkConnection.service.value ?: return
    applyJob?.cancel()
    val submitted = choice.copy(iconAppearance = preferences.state.value.iconPackAppearance)
    state.update { it.copy(action = MonetActionStatus.APPLYING, submittedChoice = submitted) }
    applyJob = scope.launch {
      try {
        val request = withContext(Dispatchers.IO) {
          MonetAction.submit(service, submitted, enabled, preserveEnabled = preserveEnabled)
        }
        val uri = Settings.Secure.getUriFor(MonetAction.Setting.APPLIED.key)
        val started = SystemClock.elapsedRealtime()
        var markedUnconfirmed = false
        val applied = awaitSettingCondition(
          uri = uri,
          timeoutMs = MonetAction.REQUEST_LIFETIME_MS,
          fallbackPollMs = 500L,
          shouldContinue = { FrameworkConnection.service.value === service },
          onTick = {
            if (!markedUnconfirmed && SystemClock.elapsedRealtime() - started >= 3_000L) {
              markedUnconfirmed = true
              state.update { it.copy(action = MonetActionStatus.UNCONFIRMED) }
            }
          },
        ) {
          MonetAction.isApplied(application.contentResolver, request)
        }
        if (applied) {
          readPalette()
          state.update { it.copy(action = MonetActionStatus.APPLIED) }
        } else {
          state.update { it.copy(action = MonetActionStatus.UNCONFIRMED) }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: UnsupportedOperationException) {
        state.update { it.copy(action = MonetActionStatus.UNAVAILABLE) }
      } catch (_: Exception) {
        state.update { it.copy(action = MonetActionStatus.FAILED) }
      }
    }
  }

  private suspend fun awaitSettingCondition(
    uri: Uri,
    timeoutMs: Long,
    fallbackPollMs: Long,
    shouldContinue: () -> Boolean = { true },
    onTick: () -> Unit = {},
    predicate: () -> Boolean,
  ): Boolean {
    val signals = Channel<Unit>(Channel.CONFLATED)
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        signals.trySend(Unit)
      }
    }
    val resolver = application.contentResolver
    resolver.registerContentObserver(uri, false, observer)
    try {
      val started = SystemClock.elapsedRealtime()
      while (shouldContinue()) {
        if (withContext(Dispatchers.IO) { predicate() }) return true
        val elapsed = SystemClock.elapsedRealtime() - started
        val remaining = timeoutMs - elapsed
        if (remaining <= 0L) return false
        onTick()
        withTimeoutOrNull(minOf(fallbackPollMs, remaining)) { signals.receive() }
      }
      return false
    } finally {
      resolver.unregisterContentObserver(observer)
      signals.close()
    }
  }
}
