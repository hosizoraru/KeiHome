package os.kei.hyper.home.status

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

internal data class FrameworkBinding<T>(val service: T?, val waiting: Boolean)

/** Only the first bind may be pending; losing an established connection is a real disconnect. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T : Any> frameworkBindings(
  services: Flow<T?>,
  startupWaitMillis: Long = 4_000,
): Flow<FrameworkBinding<T>> = flow {
  var observedConnection = false
  emitAll(services.transformLatest { service ->
    if (service != null) observedConnection = true
    val waiting = service == null && !observedConnection
    emit(FrameworkBinding(service, waiting))
    if (waiting) {
      delay(startupWaitMillis)
      emit(FrameworkBinding(null, false))
    }
  })
}
