package os.kei.hyper.home.ui.perfect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun ObservePerfectResources(controller: PerfectPackController, ensureCatalog: Boolean = false) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(controller, lifecycle, ensureCatalog) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      controller.onResourcesResumed(ensureCatalog)
      awaitCancellation()
    }
  }
}
