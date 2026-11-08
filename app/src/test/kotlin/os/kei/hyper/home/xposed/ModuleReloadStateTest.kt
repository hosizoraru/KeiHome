package os.kei.hyper.home.xposed

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ModuleReloadStateTest {
  private fun loader() = object : ClassLoader(null) {}
  private fun state(
    loader: ClassLoader = loader(),
    clean: (ClassLoader) -> String = { "clean" },
    report: (String) -> Unit = {},
  ) = ModuleReloadState(loader, "test", clean, report)

  @Test fun legacyHostStateAndNullRemainCompatible() {
    val state = state()
    val host = loader()
    assertSame(host, state.restore(host))
    assertNull(state.restore(null))
  }

  @Test fun successorAdoptsWeakHistoryAndOriginalHostWithoutExportingModuleObjects() {
    val retiredLoader = loader()
    val host = loader()
    val logs = mutableListOf<String>()
    var wire: Any? = null
    assertTrue(state(retiredLoader, report = logs::add).retire({ wire = it }) { publish ->
      publish(host); true
    })
    assertSame(host, state(report = logs::add).restore(wire))
    assertTrue(logs.last().contains("pending=1"))
    val record = ((((wire as Array<*>)[2] as Array<*>)[4] as Array<*>).single() as Array<*>)
    assertSame(retiredLoader, (record[3] as WeakReference<*>).get())
    assertEquals(Array<Any?>::class.java, wire!!.javaClass)
  }

  @Test fun failedOwnerDrainDoesNotPublishOrTouchPlatformRoots() {
    var cleanups = 0
    assertFalse(state(clean = { cleanups++; "" }).retire({ error("Not drained") }) { false })
    assertEquals(0, cleanups)
  }

  @Test fun failedExternalExportCanRetryAndCleanupOnlyRunsInSuccessor() {
    var returned = false
    var cleanups = 0
    val state = state(clean = { assertTrue(returned); cleanups++; "" })
    assertFalse(state.retire({ error("export failed") }) { publish ->
      try { publish(null); true } catch (_: IllegalStateException) { false }
    })
    assertEquals(0, cleanups)
    var wire: Any? = null
    assertTrue(state.retire({ wire = it }) { publish ->
      publish(null)
      returned = true
      true
    })
    assertEquals(0, cleanups)
    assertTrue(state.retire({}) { error("Already retired") })
    val successor = state(clean = { assertTrue(returned); cleanups++; "" })
    successor.restore(wire)
    successor.startMaintenance()
    successor.startMaintenance()
    assertTrue(successor.awaitMaintenance(5_000))
    assertEquals(1, cleanups)
  }

  @Test fun capRefusesBeforeOwnerShutdownAndDoesNotLoseLiveHistory() {
    val loaders = List(RetiredGenerationLedger.MAX_PENDING + 1) { loader() }
    var wire: Any? = null
    for (loader in loaders.dropLast(1)) {
      val state = state(loader)
      state.restore(wire)
      assertTrue(state.retire({ wire = it }) { publish -> publish(null); true })
    }
    val last = state(loaders.last())
    last.restore(wire)
    assertFalse(last.retire({ error("At cap") }) { error("Do not stop owner") })
  }

  @Test fun exportRejectsOldModuleLoaderItsChildAndModuleRuntimeObjects() {
    val module = loader()
    for (invalid in listOf(module, object : ClassLoader(module) {}, Any())) {
      assertFalse(state(module).retire({ error("Unsafe state") }) { publish ->
        try { publish(invalid); true } catch (_: IllegalArgumentException) { false }
      })
    }
  }

  @Test fun optionalCacheFailureDoesNotUndoSuccessfulOwnerRetirement() {
    val logs = mutableListOf<String>()
    var wire: Any? = null
    assertTrue(state().retire({ wire = it }) { publish -> publish(null); true })
    val successor = state(clean = { throw SecurityException() }, report = logs::add)
    successor.restore(wire)
    successor.startMaintenance()
    assertTrue(successor.awaitMaintenance(5_000))
    assertTrue(logs.last().contains("cleanup unavailable"))
  }

  @Test fun blockedPlatformMaintenanceDefersBeforeStoppingCurrentOwner() {
    var wire: Any? = null
    assertTrue(state().retire({ wire = it }) { publish -> publish(null); true })
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val successor = state(clean = {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      "done"
    })
    successor.restore(wire)
    successor.startMaintenance()
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertFalse(successor.retire({ error("Still active") }) { error("Do not stop current owner") })
    } finally {
      release.countDown()
    }
    assertTrue(successor.awaitMaintenance(5_000))
    assertTrue(successor.retire({}) { publish -> publish(null); true })
  }

  @Test fun recognizedHandoffCannotSilentlyDropItsHistory() {
    var wire: Any? = null
    assertTrue(state().retire({ wire = it }) { publish -> publish(null); true })
    (wire as Array<Any?>)[2] = null
    assertThrows(IllegalArgumentException::class.java) { state().restore(wire) }
  }

  @Test fun concurrentRetirementRejectsPromptlyWithoutHoldingAPlatformLock() {
    val state = state()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try {
      val active = executor.submit<Boolean> {
        state.retire({}) { publish ->
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
          publish(null)
          true
        }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertFalse(state.retire({ error("Concurrent export") }) { error("Concurrent owner stop") })
      release.countDown()
      assertTrue(active.get(5, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }
}
