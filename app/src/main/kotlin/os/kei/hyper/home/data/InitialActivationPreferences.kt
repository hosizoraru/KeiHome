package os.kei.hyper.home.data

import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import java.io.File
import io.github.libxposed.service.HookedTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.kei.hyper.home.status.FrameworkSnapshot

internal class InitialActivationPreferences(
  context: Context,
  id: String = "initial_activation_v1",
  private val readBootCount: () -> Int? = {
    runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
      .getOrNull()?.takeIf { it >= 0 }
  },
) {
  // Device activation must not be restored along with backed-up user settings.
  private val storage = AtomicFile(File(context.noBackupFilesDir, id))
  private var record: InitialActivationRecord? = null
  private val mutex = Mutex()
  private val mutableState = MutableStateFlow<InitialActivationStatus?>(null)
  val state = mutableState.asStateFlow()

  suspend fun observe(snapshot: FrameworkSnapshot?) = withContext(Dispatchers.IO) {
    mutex.withLock {
      val previous = record ?: InitialActivationRecord.decode(runCatching {
        storage.readFully().toString(Charsets.UTF_8)
      }.getOrNull())
      if (previous.completed) {
        record = previous
        mutableState.value = InitialActivationStatus.COMPLETE
        return@withLock
      }
      val boot = readBootCount()
      // An older loaded generation still proves first injection; it does not prove an update is current.
      val systemLoaded = snapshot?.target?.let {
        it.loadedVersionCode > 0 && it.state != HookedTarget.State.FAILED
      } == true
      val next = InitialActivationPolicy.observe(previous, boot, snapshot?.systemInScope, systemLoaded)
      if (next != previous) {
        runCatching {
          val output = storage.startWrite()
          try {
            output.write(next.encode().toByteArray(Charsets.UTF_8))
            storage.finishWrite(output)
          } catch (failure: Exception) {
            storage.failWrite(output)
            throw failure
          }
        }.onFailure { android.util.Log.w("KeiHomeActivation", "Cannot persist initial activation", it) }
      }
      record = next
      mutableState.value = InitialActivationPolicy.status(next, boot)
    }
  }
}
