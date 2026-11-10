package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/** Drives the real popup handlers without sending a system key to the test Activity. */
internal class Os4NavigationTestHost {
  private lateinit var input: DirectNavigationEventInput

  @Composable
  fun Provide(content: @Composable () -> Unit) {
    val dispatcher = remember {
      NavigationEventDispatcher(onBackCompletedFallback = { error("No popup handled Back") })
    }
    val owner = remember(dispatcher) {
      object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = dispatcher
      }
    }
    val eventInput = remember { DirectNavigationEventInput() }
    DisposableEffect(dispatcher, eventInput) {
      input = eventInput
      dispatcher.addInput(eventInput)
      onDispose { dispatcher.dispose() }
    }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner, content = content)
  }

  fun completeBack() { input.backCompleted() }
}
