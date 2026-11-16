package os.kei.hyper.home.ui.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal const val LOCAL_SEARCH_DEBOUNCE_MS = 140L
internal const val LOCAL_SEARCH_PROGRESS_MS = 450L

/** Scope and options are small identity tokens, never catalog or descriptor equality keys. */
internal class LocalSearchRequest(val scope: Any, val options: Any, val query: String)

internal class LocalSearchSnapshot<T>(
  val request: LocalSearchRequest,
  val value: T?,
  val error: Exception? = null,
)

internal suspend fun <T> runLocalSearch(
  request: LocalSearchRequest,
  previous: LocalSearchSnapshot<T>?,
  publish: (LocalSearchSnapshot<T>) -> Unit,
  compute: suspend () -> T,
) {
  // Initial loads, dataset/options changes and clearing the search do not wait for typing.
  if (previous != null && previous.request.scope === request.scope && previous.request.options === request.options &&
    previous.request.query != request.query && request.query.isNotEmpty()) {
    delay(LOCAL_SEARCH_DEBOUNCE_MS)
  }
  try {
    val result = compute()
    currentCoroutineContext().ensureActive()
    publish(LocalSearchSnapshot(request, result))
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    publish(LocalSearchSnapshot(request,
      previous?.takeIf { it.request.scope === request.scope }?.value, failure))
  }
}
