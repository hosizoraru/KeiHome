package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class AppsBrowserUi(
  val query: String,
  val onQueryChange: (String) -> Unit,
  val origin: Int,
  val display: AppsBrowserDisplay,
) {
  val visibleRows get() = display.originRows[origin.coerceIn(0, 2)]
}

@Composable
internal fun rememberAppsBrowserState(model: HomeViewModel, enabled: Boolean): AppsBrowserUi {
  var started by remember { mutableStateOf(false) }
  LaunchedEffect(enabled) { if (enabled) started = true }
  val scan by model.apps.collectAsStateWithLifecycle()
  val sourceFlow = remember(model, started) {
    if (started) model.browserSource else kotlinx.coroutines.flow.flowOf<IconBrowserSource?>(null)
  }
  val source by sourceFlow.collectAsStateWithLifecycle(initialValue = null)
  val options by model.browserOptions.collectAsStateWithLifecycle()
  var query by rememberSaveable { mutableStateOf("") }
  val capabilities = scan.capabilities
  val currentSource = source
  val currentQuery = query
  val dataset = remember(SnapshotIdentity(capabilities), SnapshotIdentity(currentSource)) { Any() }
  // Build and sort once for all three views. Origin changes only select a partition.
  val projectionOptions = options.copy(origin = 0)
  val rowsRequest = remember(dataset, projectionOptions) { Any() }
  val rows by produceState<Pair<Any, List<IconBrowserRow>>?>(null, rowsRequest, started) {
    val input = capabilities?.takeIf { started } ?: return@produceState
    val projected = withContext(Dispatchers.Default) {
      browserRows(input, currentSource, projectionOptions, "")
    }
    currentCoroutineContext().ensureActive()
    value = rowsRequest to projected
  }
  val readyRows = rows?.takeIf { it.first === rowsRequest }?.second
  val request = remember(rowsRequest, currentQuery) { Any() }
  val result by produceState<AppsBrowserResult?>(null, request, SnapshotIdentity(readyRows)) {
    val input = readyRows ?: return@produceState
    val computed = withContext(Dispatchers.Default) {
      computeAppsBrowserMatches(request, input, currentQuery, rowsRequest, dataset)
    }
    currentCoroutineContext().ensureActive()
    value = computed
  }
  return AppsBrowserUi(query, { query = it }, options.origin,
    AppsBrowserDisplay(request, result, rowsRequest, dataset))
}
