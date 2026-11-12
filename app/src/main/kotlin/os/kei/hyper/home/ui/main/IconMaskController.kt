package os.kei.hyper.home.ui.main

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.kei.hyper.home.iconpack.CustomIconMask
import os.kei.hyper.home.iconpack.CustomIconMaskStore
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask

internal enum class IconMaskOperation { LOAD, IMPORT, RESET }

internal data class IconMaskState(
  val custom: CustomIconMask? = null,
  val defaultPathData: String = NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA,
  val loaded: Boolean = false,
  val busy: Boolean = false,
  val operation: IconMaskOperation? = null,
  val error: IconMaskOperation? = null,
  val lastChange: IconMaskOperation? = null,
)

/** A single owner for mask edits across document-picker results and page navigation. */
internal class IconMaskController(
  context: Context,
  private val scope: CoroutineScope,
  private val onChanged: () -> Unit,
) {
  private val store = CustomIconMaskStore(context.applicationContext ?: context)
  private val mutable = MutableStateFlow(IconMaskState())
  val state = mutable.asStateFlow()

  fun load() {
    if (mutable.value.loaded) return
    execute(IconMaskOperation.LOAD) {
      val custom = store.read()
      custom to NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA
    }
  }

  fun importMask(uri: Uri) {
    execute(IconMaskOperation.IMPORT) {
      store.`import`(uri) to mutable.value.defaultPathData
    }
  }

  fun reset() {
    execute(IconMaskOperation.RESET) {
      store.reset()
      null to mutable.value.defaultPathData
    }
  }

  private fun execute(
    operation: IconMaskOperation,
    work: suspend () -> Pair<CustomIconMask?, String>,
  ) {
    val before = mutable.value
    if (before.busy || operation == IconMaskOperation.LOAD && before.loaded || !mutable.compareAndSet(before,
        before.copy(busy = true, operation = operation, error = null))) return
    scope.launch {
      var changed = false
      try {
        val (custom, defaultPath) = withContext(Dispatchers.IO) { work() }
        mutable.update {
          it.copy(custom = custom, defaultPathData = defaultPath, loaded = true,
            lastChange = operation.takeUnless { value -> value == IconMaskOperation.LOAD } ?: it.lastChange)
        }
        changed = operation != IconMaskOperation.LOAD
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Validation and IO failures must not replace the last known valid mask.
        mutable.update { it.copy(error = operation) }
      } finally {
        mutable.update { it.copy(busy = false, operation = null) }
      }
      if (changed) onChanged()
    }
  }
}
