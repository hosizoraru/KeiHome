package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import os.kei.hyper.home.status.FrameworkConnection
import os.kei.hyper.home.status.NavigationBackdropInbox

@Composable
internal fun NavigationBackdropScreen(
  model: HomeViewModel,
  onBack: () -> Unit,
  page: NavigationBackdropPage = NavigationBackdropPage.OVERVIEW,
  onOpen: (NavigationBackdropPage) -> Unit = {},
) {
  val state by model.navigationBackdrop.state.collectAsStateWithLifecycle()
  val framework by model.framework.collectAsStateWithLifecycle()
  val service by FrameworkConnection.service.collectAsStateWithLifecycle()
  val backend by NavigationBackdropInbox.status.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val scope = rememberCoroutineScope()
  LaunchedEffect(model, lifecycle, service, framework.connected, framework.readFailed, framework.snapshot?.systemInScope) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.navigationBackdrop.read() }
  }
  NavigationBackdropContent(page, state, backend, onBack, onOpen,
    onEnabled = model.navigationBackdrop::enabled, onToggle = model.navigationBackdrop::toggle,
    onRefresh = { scope.launch { model.navigationBackdrop.read(refreshApps = page == NavigationBackdropPage.APPS) } })
}
