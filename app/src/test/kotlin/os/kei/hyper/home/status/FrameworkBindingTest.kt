package os.kei.hyper.home.status

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrameworkBindingTest {
  @Test fun initialAbsenceIsPendingButBecomesDisconnectedAtTheDeadline() = runTest {
    val services = MutableStateFlow<Any?>(null)
    val seen = mutableListOf<FrameworkBinding<Any>>()
    backgroundScope.launch { frameworkBindings(services).collect { seen += it } }
    runCurrent()
    assertEquals(listOf(FrameworkBinding<Any>(null, true)), seen)
    advanceTimeBy(3_999)
    runCurrent()
    assertTrue(seen.last().waiting)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(FrameworkBinding<Any>(null, false), seen.last())
  }

  @Test fun successfulBindingCancelsThePendingDisconnect() = runTest {
    val services = MutableStateFlow<Any?>(null)
    val seen = mutableListOf<FrameworkBinding<Any>>()
    backgroundScope.launch { frameworkBindings(services).collect { seen += it } }
    runCurrent()
    advanceTimeBy(250)
    val service = Any()
    services.value = service
    runCurrent()
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(listOf(FrameworkBinding(null, true), FrameworkBinding(service, false)), seen)
  }

  @Test fun losingAnEstablishedConnectionDoesNotStartAnotherGracePeriod() = runTest {
    val service = Any()
    val services = MutableStateFlow<Any?>(service)
    val seen = mutableListOf<FrameworkBinding<Any>>()
    backgroundScope.launch { frameworkBindings(services).collect { seen += it } }
    runCurrent()
    services.value = null
    runCurrent()
    assertEquals(FrameworkBinding<Any>(null, false), seen.last())
    assertFalse(seen.any { it.waiting })
  }

  @Test fun lateBindingRecoversAfterInitialTimeout() = runTest {
    val services = MutableStateFlow<Any?>(null)
    val seen = mutableListOf<FrameworkBinding<Any>>()
    backgroundScope.launch { frameworkBindings(services).collect { seen += it } }
    advanceTimeBy(4_001)
    runCurrent()
    assertFalse(seen.last().waiting)
    val service = Any()
    services.value = service
    runCurrent()
    assertSame(service, seen.last().service)
    assertFalse(seen.last().waiting)
  }
}
