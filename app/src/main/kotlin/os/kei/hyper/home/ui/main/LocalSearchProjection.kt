package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class LocalSearchStatus(
  val pending: Boolean = false,
  val showProgress: Boolean = false,
  val error: Exception? = null,
  val retry: () -> Unit = {},
)

internal class LocalSearchProjection<T>(
  val request: LocalSearchRequest,
  val value: T?,
  val status: LocalSearchStatus,
)

@Composable
internal fun <T> rememberLocalSearch(
  scope: Any,
  query: String,
  options: Any = Unit,
  enabled: Boolean = true,
  workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
  compute: suspend (LocalSearchRequest) -> T,
): LocalSearchProjection<T> {
  var retry by remember { mutableStateOf(0) }
  val request = remember(scope, options, query, retry) { LocalSearchRequest(scope, options, query) }
  // produceState retains its last publication while the old request is cancelled.
  val snapshot by produceState<LocalSearchSnapshot<T>?>(null, request, enabled) {
    if (enabled) runLocalSearch(request, value, { value = it }) {
      withContext(workerDispatcher) { compute(request) }
    }
  }
  val current = snapshot?.takeIf { it.request === request }
  val pending = current == null
  var progressRequest by remember { mutableStateOf<LocalSearchRequest?>(null) }
  LaunchedEffect(request, enabled, pending) {
    if (enabled && pending) {
      delay(LOCAL_SEARCH_PROGRESS_MS)
      progressRequest = request
    }
  }
  return LocalSearchProjection(request,
    snapshot?.takeIf { it.request.scope === scope }?.value,
    LocalSearchStatus(pending, pending && progressRequest === request, current?.error, { retry++ }))
}

@Composable
internal fun LocalSearchError(status: LocalSearchStatus) {
  if (status.error == null) return
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.local_search_failed), modifier = Modifier.weight(1f),
      style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
    TextButton(stringResource(R.string.local_search_retry), onClick = status.retry)
  }
}
