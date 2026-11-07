package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiringDisplayListenerExecutorTest {
  @Test
  fun activeDispatchRunsOnlyOnTheWorkerQueue() {
    val queue = ArrayDeque<Runnable>()
    val executor = RetiringDisplayListenerExecutor { queue.add(it) }
    var calls = 0
    executor.execute { calls++ }
    assertEquals(0, calls)
    queue.removeFirst().run()
    assertEquals(1, calls)
  }

  @Test
  fun retirementCancelsQueuedAndLateFrameworkDispatches() {
    val queue = ArrayDeque<Runnable>()
    val executor = RetiringDisplayListenerExecutor { queue.add(it) }
    var calls = 0
    executor.execute { calls++ }
    executor.close()
    executor.close()
    // Models a framework delegate retained by an iteration racing with unregistration.
    executor.execute { calls++ }
    assertEquals(1, queue.size)
    queue.removeFirst().run()
    assertEquals(0, calls)
  }

  @Test
  fun dispatchAlreadyInsidePostCanRaceWithRetirementAndQueueShutdown() {
    val posting = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val executor = RetiringDisplayListenerExecutor {
      posting.countDown()
      check(resume.await(2, TimeUnit.SECONDS))
      false // The handler rejected the post after its looper quit.
    }
    val dispatch = thread {
      try {
        executor.execute { error("Retired callback ran") }
      } catch (caught: Throwable) {
        failure.set(caught)
      }
    }
    try {
      assertTrue(posting.await(2, TimeUnit.SECONDS))
      executor.close()
    } finally {
      resume.countDown()
      dispatch.join(2_000)
    }
    assertTrue(!dispatch.isAlive)
    assertNull(failure.get())
  }

  @Test
  fun unexpectedQueueRejectionWhileActiveStillFails() {
    val executor = RetiringDisplayListenerExecutor { false }
    assertThrows(RejectedExecutionException::class.java) {
      executor.execute { error("Rejected callback ran") }
    }
  }

  @Test
  fun postAndCallbackFailuresAreNotSuppressed() {
    val failure = IllegalStateException("unrelated failure")
    val brokenPost = RetiringDisplayListenerExecutor { throw failure }
    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      brokenPost.execute {}
    })
    val queue = ArrayDeque<Runnable>()
    val executor = RetiringDisplayListenerExecutor { queue.add(it) }
    executor.execute { throw failure }
    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      queue.removeFirst().run()
    })
  }

  @Test
  fun replacementWorkerDoesNotReactivateRetiredCallbacks() {
    val queue = ArrayDeque<Runnable>()
    val retired = RetiringDisplayListenerExecutor { queue.add(it) }
    retired.execute { error("Old generation ran") }
    retired.close()
    val replacement = RetiringDisplayListenerExecutor { queue.add(it) }
    var calls = 0
    replacement.execute { calls++ }
    while (queue.isNotEmpty()) queue.removeFirst().run()
    assertEquals(1, calls)
  }
}
