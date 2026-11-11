package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun ObservePerfectResources(controller: PerfectPackController) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(controller, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      controller.load()
      controller.refreshLocal()
      awaitCancellation()
    }
  }
}
