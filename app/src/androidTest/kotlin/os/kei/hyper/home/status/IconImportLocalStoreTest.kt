package os.kei.hyper.home.status

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.data.AppLocalStorage

class IconImportLocalStoreTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val groups = listOf("native_icon_associations", "native_icon_sources", "native_icon_operation", "native_icon_auto_navigation")
  private fun request(token: String = UUID.randomUUID().toString()) = IconPackImportRequest(token, 0, 1234,
    "coloros:经典:SYSTEM:source", convertedOrigin = "CONVERTED_CLASSIC_ZIP", autoApply = true)
  private fun report(request: IconPackImportRequest, id: String = "component") =
    NativeIconImportReport(request.token, 0, request.sourceId, "IMPORTED", id, autoApplyToken = request.token)

  private fun probe(block: (String, String) -> Unit) {
    val id = "import_probe_${UUID.randomUUID()}"
    val prefix = "$id."
    try { block(id, prefix)
    } finally {
      AppLocalStorage.open(context, id).close()
      MMKV.removeStorage(id)
      groups.forEach { context.deleteSharedPreferences(prefix + it) }
    }
  }

  @Test fun migratesWholeGroupAndReopenNeverReimportsStaleXml() = probe { id, prefix ->
    val request = request()
    val report = report(request)
    context.getSharedPreferences(prefix + groups[0], 0).edit().putString(request.sourceId, "old").commit()
    context.getSharedPreferences(prefix + groups[1], 0).edit().putString("older", request.sourceId).commit()
    context.getSharedPreferences(prefix + groups[2], 0).edit().putString("request_token", request.token)
      .putString("request_source", request.sourceId).putString("result", report.copy(status = "UNCERTAIN").encode()).commit()
    context.getSharedPreferences(prefix + groups[3], 0).edit().putString("token", "consumed-before-upgrade").commit()
    val store = IconImportLocalStore(context, id, prefix)
    val migrated = store.snapshot()
    assertEquals("old", migrated.associations[request.sourceId])
    assertEquals(request.sourceId, migrated.sources["old"])
    assertEquals(request.sourceId, migrated.sources["older"])
    assertEquals(request.token, migrated.pendingToken)
    assertNull("Migration cannot invent the legacy request's missing timestamp", migrated.request)
    assertEquals(report.copy(status = "UNCERTAIN"), migrated.result)
    assertTrue("consumed-before-upgrade" in migrated.navigated)
    store.accept(report)
    assertTrue(store.takeNavigation(report))
    AppLocalStorage.open(context, id).close()
    context.getSharedPreferences(prefix + groups[0], 0).edit().putString(request.sourceId, "stale").commit()
    context.getSharedPreferences(prefix + groups[3], 0).edit().putString("token", "stale").commit()
    val reopened = IconImportLocalStore(context, id, prefix)
    val state = reopened.snapshot()
    assertEquals("component", state.associations[request.sourceId])
    assertNull(state.pendingSource)
    assertEquals(report, state.result)
    assertFalse(reopened.takeNavigation(report))
    assertEquals(setOf("state"), AppLocalStorage.open(context, id).allKeys()!!.toSet())
  }

  @Test fun pendingRequestAndUnconfirmedPublicationSurviveReopenUnchanged() = probe { id, prefix ->
    val request = request().copy(preparedPalette = true)
    val store = IconImportLocalStore(context, id, prefix)
    store.begin(request)
    store.uncertain(request)
    AppLocalStorage.open(context, id).close()
    val reopened = IconImportLocalStore(context, id, prefix)
    assertEquals(request, reopened.snapshot().request)
    assertEquals("UNCERTAIN", reopened.snapshot().result!!.status)
    val mutation = reopened.accept(report(request))
    assertTrue(mutation.complete)
    assertNull(reopened.snapshot().request)
    assertEquals(true, reopened.snapshot().componentPalette["component"])
  }

  @Test fun failedEncodingLeavesCommittedReceiptAndNavigationUnconsumed() = probe { id, prefix ->
    var failWrites = false
    val store = IconImportLocalStore(context, id, prefix, encode = { mmkv, value -> if (failWrites) false else mmkv.encode("state", value) })
    val request = request()
    store.begin(request)
    failWrites = true
    assertThrows(IllegalStateException::class.java) { store.accept(report(request)) }
    assertEquals(request.token, store.snapshot().pendingToken)
    assertNull(store.snapshot().result)
    assertTrue(store.snapshot().associations.isEmpty())
    failWrites = false
    store.accept(report(request))
    failWrites = true
    assertThrows(IllegalStateException::class.java) { store.takeNavigation(report(request)) }
    assertTrue(store.snapshot().navigated.isEmpty())
    failWrites = false
    assertTrue(store.takeNavigation(report(request)))
  }

  @Test fun simultaneousDeliveryAndNavigationAreSerialized() = probe { id, prefix ->
    val store = IconImportLocalStore(context, id, prefix)
    val request = request()
    store.begin(request)
    val report = report(request)
    val executor = Executors.newFixedThreadPool(4)
    try {
      val deliveries = (0 until 12).map { executor.submit<Boolean> { store.accept(report).state.result == report } }
      assertTrue(deliveries.all { it.get(5, TimeUnit.SECONDS) })
      val navigations = (0 until 12).map { executor.submit<Boolean> { store.takeNavigation(report) } }
      assertEquals(1, navigations.count { it.get(5, TimeUnit.SECONDS) })
      assertEquals(1, store.snapshot().associations.size)
    } finally { executor.shutdownNow() }
  }

  @Test fun malformedLegacyReceiptDoesNotPublishAnEmptyMigration() = probe { id, prefix ->
    val legacy = context.getSharedPreferences(prefix + groups[2], 0)
    legacy.edit().putString("result", "not JSON").commit()
    val store = IconImportLocalStore(context, id, prefix)
    assertThrows(IllegalArgumentException::class.java) { store.snapshot() }
    assertFalse(AppLocalStorage.open(context, id).contains("state"))
    legacy.edit().clear().commit()
    assertEquals(IconImportLocalState(), store.snapshot())
  }

  @Test fun rejectedThrowingAndTimedOutPublicationRemainReconcilable(): Unit = runBlocking {
    probe { id, prefix -> runBlocking {
      val store = IconImportLocalStore(context, id, prefix)
      val request = request()
      val reports = MutableStateFlow<NativeIconImportReport?>(null)
      val rejected = submitNativeImport(store, request, reports) {
        assertEquals(request, store.snapshot().request)
        false
      }
      assertEquals("UNCERTAIN", rejected!!.status)
      assertEquals(request, store.snapshot().request)
      // A receipt can arrive even when Binder reports failure; preserve the confirmed result.
      val arrived = submitNativeImport(store, request, reports) {
        store.accept(report(request))
        throw java.io.IOException("reply lost after delivery")
      }
      assertEquals("IMPORTED", arrived!!.status)
      assertNull(store.snapshot().pendingToken)
      val next = request()
      val timeout = submitNativeImport(store, next, reports, timeoutMillis = 10) { true }
      assertEquals("UNCERTAIN", timeout!!.status)
      assertEquals(next, store.snapshot().request)
    } }
  }

  @Test fun callerCancellationRetainsItsPublishedRequestForRecovery(): Unit = runBlocking {
    probe { id, prefix -> runBlocking {
      val store = IconImportLocalStore(context, id, prefix)
      val request = request()
      try {
        withTimeout(50) { submitNativeImport(store, request, MutableStateFlow(null)) { true } }
        fail("Expected caller cancellation")
      } catch (_: TimeoutCancellationException) { /* This must not become FAILED or clean the request. */ }
      assertEquals(request, store.snapshot().request)
      assertNull(store.snapshot().result)
    } }
  }

  @Test fun lateSubmitReturnCannotRepublishUncertaintyOverCommittedSuccess(): Unit = runBlocking {
    probe { id, prefix -> runBlocking {
      val store = IconImportLocalStore(context, id, prefix)
      val request = request()
      val earlierReturn = submitNativeImport(store, request, store.states.map { it?.result }) { false }
      store.accept(report(request))
      assertEquals("UNCERTAIN", earlierReturn!!.status)
      assertEquals("IMPORTED", store.states.value!!.presentationResult(0)!!.status)
      assertTrue(store.takeNavigation(report(request)))
      assertFalse(store.takeNavigation(report(request)))
    } }
  }

  @Test fun failedUncertaintyWriteRetainsARecoveryPresentationAndRecheckCatchesStorageErrors(): Unit = runBlocking {
    probe { id, prefix -> runBlocking {
      var failWrites = false
      val store = IconImportLocalStore(context, id, prefix, encode = { mmkv, value ->
        if (failWrites) false else mmkv.encode("state", value) })
      val request = request()
      try {
        submitNativeImport(store, request, store.states.map { it?.result }) { failWrites = true; false }
        fail("Expected failed uncertainty persistence")
      } catch (_: IllegalStateException) { }
      assertEquals(request, store.snapshot().request)
      assertEquals("UNCERTAIN", store.states.value!!.presentationResult(0)!!.status)
      assertFalse(recheckNativeImport(store, Mutex()) { throw java.io.IOException("Binder unavailable") })
      failWrites = false
    } }
    probe { id, prefix -> runBlocking {
      context.getSharedPreferences(prefix + "native_icon_operation", 0).edit().putString("result", "broken").commit()
      assertFalse(recheckNativeImport(IconImportLocalStore(context, id, prefix), Mutex()) { true })
    } }
  }

  @Test fun recheckCannotOverwriteTheNextRemoteRequest(): Unit = runBlocking {
    probe { id, prefix -> runBlocking {
      val store = IconImportLocalStore(context, id, prefix)
      val a = request()
      val b = request()
      store.begin(a)
      val mutex = Mutex()
      val captured = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      var remoteToken: String? = null
      val recheck = launch {
        recheckNativeImport(store, mutex) { state ->
          captured.complete(Unit)
          release.await()
          remoteToken = state.pendingToken
          true
        }
      }
      captured.await()
      store.accept(report(a))
      val submit = launch {
        submitNativeImport(store, b, store.states.map { it?.result }, publicationMutex = mutex) {
          remoteToken = b.token
          store.accept(report(b))
          true
        }
      }
      release.complete(Unit)
      recheck.join(); submit.join()
      assertEquals(b.token, remoteToken)
      assertEquals(b.token, store.snapshot().result!!.token)
    } }
  }

  @Test fun existingUpgradeAssociationsAndReceiptRemainAvailable() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("mmkv.verifyImportUpgrade") == "true")
    val state = IconImportLocalStore(context).snapshot()
    for ((source, component) in context.getSharedPreferences(groups[0], Context.MODE_PRIVATE).all) {
      assertEquals(component, state.associations[source])
    }
    for ((component, source) in context.getSharedPreferences(groups[1], Context.MODE_PRIVATE).all) {
      assertEquals(source, state.sources[component])
    }
    val old = context.getSharedPreferences(groups[2], Context.MODE_PRIVATE).getString("result", null)
    val original = NativeIconImportReport.decode(old)
    // A live framework may replay this completed operation after migration with its
    // already-consumed one-shot navigation capability removed. Verify receipt identity
    // and all other fields, while requiring proof that the ticket was consumed.
    assertEquals(original?.copy(autoApplyToken = null), state.result?.copy(autoApplyToken = null))
    if (original?.autoApplyToken != state.result?.autoApplyToken)
      assertTrue(requireNotNull(original?.autoApplyToken) in state.navigated)
  }
}
