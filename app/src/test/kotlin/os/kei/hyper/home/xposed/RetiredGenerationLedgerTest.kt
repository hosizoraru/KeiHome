package os.kei.hyper.home.xposed

import java.lang.ref.WeakReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredGenerationLedgerTest {
  private class Loader : ClassLoader(null)

  private class ModuleOwnedReference(loader: ClassLoader) : WeakReference<ClassLoader>(loader) {
    val moduleOwnedField = Any()
  }

  private class Clock(var now: Long = 1_000_000_000L) {
    fun read(): Long = now
  }

  @Test
  fun transfersAcrossGenerationsAndKeepsOriginalAge() {
    val clock = Clock()
    val first = Loader()
    val second = Loader()
    val third = Loader()
    val initial = RetiredGenerationLedger(clock::read).snapshotWith(first, "first")
    clock.now += 25_000_000L
    val next = RetiredGenerationLedger.restore(initial, clock::read)
    val transferred = next.snapshotWith(second, "second")
    clock.now += 10_000_000L
    val latest = RetiredGenerationLedger.restore(transferred, clock::read)
    assertEquals("pending=2 weakCleared=0 oldestAgeMs=35", latest.describe())
    val finalState = header(latest.snapshotWith(third, "third"))
    assertEquals(3L, finalState[2])
    assertEquals(listOf(1L, 2L, 3L), rows(finalState).map { row(it)[0] })
    assertSame(first, reference(rows(finalState)[0]).get())
    assertSame(second, reference(rows(finalState)[1]).get())
    assertSame(third, reference(rows(finalState)[2]).get())
  }

  @Test
  fun nullAndUnknownFormatsAreEmpty() {
    for (state in listOf(null, "unknown", 7, emptyArray<Any>(), arrayOf<Any>("other", 1))) {
      val ledger = RetiredGenerationLedger.restore(state)
      assertEquals(0, ledger.pendingCount)
      assertTrue(ledger.canRetire())
      assertEquals("pending=0 weakCleared=0 oldestAgeMs=0", ledger.describe())
    }
  }

  @Test
  fun collectionCadenceSurvivesHandoffAndSkipsClearedLoaders() {
    val live = List(8) { Loader() }
    var ledger = RetiredGenerationLedger.restore(null)
    for ((index, loader) in live.withIndex()) {
      ledger = RetiredGenerationLedger.restore(ledger.snapshotWith(loader, "round-$index"))
      assertEquals(index == 3 || index == 7, ledger.needsCollection())
    }
    val references = ledger.pendingReferences()
    references.drop(1).forEach { it.clear() }
    assertFalse(ledger.needsCollection())
    assertEquals(1, ledger.pendingCount)
    assertSame(live.first(), references.first().get())
  }

  @Test
  fun capRefusesAnotherLoaderButAllowsRetryOfLastExport() {
    val clock = Clock()
    val loaders = List(17) { Loader() }
    var ledger = RetiredGenerationLedger(clock::read)
    var state: Any? = null
    for (index in 0 until 16) {
      assertTrue(ledger.canRetire())
      state = ledger.snapshotWith(loaders[index], "loader-$index")
      ledger = RetiredGenerationLedger.restore(state, clock::read)
    }
    assertEquals(16, ledger.pendingCount)
    assertFalse(ledger.canRetire())
    assertThrows(IllegalStateException::class.java) {
      ledger.snapshotWith(loaders[16], "overflow")
    }
    val retry = ledger.snapshotWith(loaders[15], "retry")
    assertEquals(16L, header(retry)[2])
    assertEquals(16, rows(retry).size)
    assertSame(reference(rows(state!!)[15]), reference(rows(retry)[15]))
  }

  @Test
  fun explicitlyClearedReferencesArePrunedAndCountedOnce() {
    val clock = Clock()
    val loaders = List(17) { Loader() }
    var ledger = RetiredGenerationLedger(clock::read)
    var state: Any = ledger.snapshotWith(loaders[0], "first")
    ledger = RetiredGenerationLedger.restore(state, clock::read)
    for (index in 1 until 16) {
      state = ledger.snapshotWith(loaders[index], "next")
      ledger = RetiredGenerationLedger.restore(state, clock::read)
    }
    // Reference.clear simulates weak clearing only; this test makes no GC/unload claim.
    reference(rows(state)[0]).clear()
    assertTrue(ledger.canRetire())
    assertEquals(15, ledger.pendingCount)
    assertEquals("pending=15 weakCleared=1 oldestAgeMs=0", ledger.describe())
    val restored = RetiredGenerationLedger.restore(state, clock::read)
    assertEquals(ledger.describe(), restored.describe())
    val replacement = restored.snapshotWith(loaders[16], "replacement")
    assertEquals(17L, header(replacement)[2])
    assertEquals(1L, header(replacement)[3])
    assertEquals(16, rows(replacement).size)
    assertEquals(2L, row(rows(replacement)[0])[0])
    assertEquals(16, RetiredGenerationLedger.restore(replacement, clock::read).pendingCount)
  }

  @Test
  fun allClearedEntriesHaveZeroOldestAgeAndKeepCumulativeCount() {
    val clock = Clock()
    val first = Loader()
    val second = Loader()
    val ledger = RetiredGenerationLedger(clock::read)
    val state = ledger.snapshotWith(first, "first")
    reference(rows(state)[0]).clear()
    clock.now += 50_000_000L
    val restored = RetiredGenerationLedger.restore(state, clock::read)
    assertEquals("pending=0 weakCleared=1 oldestAgeMs=0", restored.describe())
    val next = restored.snapshotWith(second, "second")
    reference(rows(next)[0]).clear()
    val receiving = RetiredGenerationLedger.restore(next, clock::read)
    assertEquals("pending=0 weakCleared=2 oldestAgeMs=0", receiving.describe())
    assertEquals(receiving.describe(), receiving.describe())
    assertEquals("pending=0 weakCleared=1 oldestAgeMs=0", restored.describe())
  }

  @Test
  fun failedExternalExportDoesNotConsumeRecordsAndRetriesAreIdempotent() {
    val clock = Clock()
    val loader = Loader()
    val ledger = RetiredGenerationLedger(clock::read)
    lateinit var failedSnapshot: Array<*>
    assertThrows(IllegalStateException::class.java) {
      failedSnapshot = header(ledger.snapshotWith(loader, "original"))
      error("External owner rejected state")
    }
    clock.now += 7_000_000L
    val retry = header(ledger.snapshotWith(loader, "retry"))
    assertNotSame(failedSnapshot, retry)
    assertNotSame(rows(failedSnapshot), rows(retry))
    assertEquals(failedSnapshot.toList().take(4), retry.toList().take(4))
    assertEquals(row(rows(failedSnapshot)[0]).toList(), row(rows(retry)[0]).toList())
    assertEquals("pending=0 weakCleared=0 oldestAgeMs=0", ledger.describe())
    assertEquals(0, ledger.pendingCount)
    assertTrue(ledger.canRetire())
    val restored = RetiredGenerationLedger.restore(retry, clock::read)
    assertEquals("pending=1 weakCleared=0 oldestAgeMs=7", restored.describe())
    assertEquals(1, rows(restored.snapshotWith(loader, "again")).size)
    assertEquals(1L, header(restored.snapshotWith(loader, "again"))[2])
  }

  @Test
  fun failedExportAtFifteenPendingKeepsAdmissionAndRetryUntilReceivingRestore() {
    val clock = Clock()
    val loaders = List(17) { Loader() }
    var ledger = RetiredGenerationLedger(clock::read)
    for (index in 0 until 15) {
      ledger = RetiredGenerationLedger.restore(
        ledger.snapshotWith(loaders[index], "loader-$index"), clock::read,
      )
    }
    lateinit var failedSnapshot: Array<*>
    assertThrows(IllegalStateException::class.java) {
      failedSnapshot = header(ledger.snapshotWith(loaders[15], "retiring"))
      error("External publish failed before owner retirement")
    }
    assertEquals(15, ledger.pendingCount)
    assertTrue(ledger.canRetire())
    assertEquals("pending=15 weakCleared=0 oldestAgeMs=0", ledger.describe())
    clock.now += 9_000_000L
    val retry = ledger.snapshotWith(loaders[15], "retry")
    assertEquals(16L, header(retry)[2])
    assertEquals(row(rows(failedSnapshot)[15]).toList(), row(rows(retry)[15]).toList())
    assertEquals(16, rows(retry).size)
    assertEquals(15, ledger.pendingCount)
    assertTrue(ledger.canRetire())
    val receiving = RetiredGenerationLedger.restore(retry, clock::read)
    assertEquals(16, receiving.pendingCount)
    assertEquals("pending=16 weakCleared=0 oldestAgeMs=9", receiving.describe())
    assertFalse(receiving.canRetire())
    assertThrows(IllegalStateException::class.java) {
      receiving.snapshotWith(loaders[16], "overflow")
    }
  }

  @Test
  fun clearedProvisionalDoesNotCountAsRetiredOrConsumeGenerationId() {
    val clock = Clock()
    val first = Loader()
    val second = Loader()
    val ledger = RetiredGenerationLedger(clock::read)
    val abandoned = ledger.snapshotWith(first, "abandoned")
    reference(rows(abandoned)[0]).clear()
    assertEquals("pending=0 weakCleared=0 oldestAgeMs=0", ledger.describe())
    assertTrue(ledger.canRetire())
    clock.now += 4_000_000L
    val replacement = ledger.snapshotWith(second, "replacement")
    assertEquals(1L, header(replacement)[2])
    assertEquals(0L, header(replacement)[3])
    assertEquals(clock.now, row(rows(replacement)[0])[1])
    assertSame(second, reference(rows(replacement)[0]).get())
    assertEquals(1, RetiredGenerationLedger.restore(replacement, clock::read).pendingCount)
  }

  @Test
  fun snapshotContainsOnlyNeutralObjectsAndWeakLoaderEdges() {
    val loader = Loader()
    val state = RetiredGenerationLedger().snapshotWith(loader, "neutral")
    var references = 0
    fun inspect(value: Any?) {
      when (value) {
        is Array<*> -> {
          assertEquals(arrayOfNulls<Any>(0).javaClass, value.javaClass)
          value.forEach(::inspect)
        }
        is String, is Int, is Long -> Unit
        is WeakReference<*> -> {
          assertEquals(WeakReference::class.java, value.javaClass)
          assertSame(loader, value.get())
          references++
        }
        else -> error("Non-neutral or strong loader edge: ${value?.javaClass}")
      }
    }
    inspect(state)
    assertEquals(1, references)
  }

  @Test
  fun snapshotArraysAreDetachedFromLedgerAndRestoreDoesNotRetainInputArrays() {
    val clock = Clock()
    val first = Loader()
    val second = Loader()
    val ledger = RetiredGenerationLedger(clock::read)
    val state = mutableHeader(ledger.snapshotWith(first, "first"))
    val restored = RetiredGenerationLedger.restore(state, clock::read)
    mutableRow(rows(state)[0])[2] = "changed"
    state[2] = -1L
    val fromOriginal = ledger.snapshotWith(first, "retry")
    val fromRestore = restored.snapshotWith(second, "second")
    assertEquals("first", row(rows(fromOriginal)[0])[2])
    assertEquals("first", row(rows(fromRestore)[0])[2])
    assertEquals(2L, header(fromRestore)[2])
  }

  @Test
  fun rejectsMalformedRecognizedHeadersAndCounters() {
    val loader = Loader()
    val clock = Clock()
    fun valid() = mutableHeader(RetiredGenerationLedger(clock::read).snapshotWith(loader, "valid"))
    val cases = listOf<(Array<Any>) -> Any>(
      { arrayOf(it[0] as String) },
      { it.copyOf(4) },
      { it.copyOf(6) },
      { it.apply { this[1] = 2 } },
      { it.apply { this[1] = 1L } },
      { it.apply { this[2] = -1L } },
      { it.apply { this[2] = 1 } },
      { it.apply { this[2] = java.util.concurrent.atomic.AtomicLong(1L) } },
      { it.apply { this[3] = -1L } },
      { it.apply { this[3] = 0 } },
      { it.apply { this[3] = 2L } },
      { it.apply { this[2] = 2L } },
      { it.apply { this[4] = "records" } },
      { it.apply { this[4] = arrayOf(mutableRow(rows(it)[0])) } },
      { it.apply { this[4] = Array<Any>(17) { index -> index } } },
    )
    for (corrupt in cases) {
      assertThrows(IllegalArgumentException::class.java) {
        RetiredGenerationLedger.restore(corrupt(valid()), clock::read)
      }
    }
  }

  @Test
  fun rejectsEveryInvalidRecordEvenWhenItsWeakReferenceWasCleared() {
    val clock = Clock()
    val loader = Loader()
    val changes = listOf<(Array<Any>) -> Any>(
      { "record" },
      { arrayOf("1", "time", "label", "reference") },
      { it.copyOf(3) },
      { it.apply { this[0] = 0L } },
      { it.apply { this[0] = 2L } },
      { it.apply { this[0] = 1 } },
      { it.apply { this[0] = java.util.concurrent.atomic.AtomicLong(1L) } },
      { it.apply { this[1] = "time" } },
      { it.apply { this[1] = clock.now + 1L } },
      { it.apply { this[2] = 3 } },
      { it.apply { this[2] = "x".repeat(97) } },
      { it.apply { this[3] = loader } },
      { it.apply { this[3] = WeakReference("not a loader") } },
      { it.apply { this[3] = ModuleOwnedReference(loader) } },
    )
    for (cleared in listOf(false, true)) {
      for (change in changes) {
        val state = mutableHeader(RetiredGenerationLedger(clock::read).snapshotWith(loader, "valid"))
        val record = mutableRow(rows(state)[0])
        if (cleared) reference(record).clear()
        state[4] = arrayOf(change(record))
        assertThrows(IllegalArgumentException::class.java) {
          RetiredGenerationLedger.restore(state, clock::read)
        }
      }
    }
  }

  @Test
  fun rejectsDuplicateIdsWeakReferencesAndLiveLoaders() {
    val clock = Clock()
    val first = Loader()
    val second = Loader()
    for (kind in 0..2) {
      val initial = RetiredGenerationLedger(clock::read).snapshotWith(first, "first")
      val ledger = RetiredGenerationLedger.restore(initial, clock::read)
      val state = ledger.snapshotWith(second, "second")
      val a = mutableRow(rows(state)[0])
      val b = mutableRow(rows(state)[1])
      when (kind) {
        0 -> b[0] = a[0]
        1 -> b[3] = a[3]
        2 -> b[3] = WeakReference(first)
      }
      assertThrows(IllegalArgumentException::class.java) {
        RetiredGenerationLedger.restore(state, clock::read)
      }
    }
  }

  @Test
  fun labelsAreBoundedWithoutConsumingAnIdOnFailure() {
    val clock = Clock()
    val loader = Loader()
    val ledger = RetiredGenerationLedger(clock::read)
    assertThrows(IllegalArgumentException::class.java) {
      ledger.snapshotWith(loader, "x".repeat(97))
    }
    assertEquals(0, ledger.pendingCount)
    val state = ledger.snapshotWith(loader, "x".repeat(96))
    assertEquals(1L, header(state)[2])
    assertEquals(96, (row(rows(state)[0])[2] as String).length)
    assertEquals(1, RetiredGenerationLedger.restore(state, clock::read).pendingCount)
  }

  @Test
  fun countersNeverWrapAndClearingAtMaximumStillCountsExactly() {
    val clock = Clock()
    val first = Loader()
    val second = Loader()
    val seed = mutableHeader(RetiredGenerationLedger(clock::read).snapshotWith(first, "last"))
    seed[2] = Long.MAX_VALUE
    seed[3] = Long.MAX_VALUE - 1L
    mutableRow(rows(seed)[0])[0] = Long.MAX_VALUE
    val ledger = RetiredGenerationLedger.restore(seed, clock::read)
    assertFalse(ledger.canRetire())
    assertThrows(IllegalStateException::class.java) { ledger.snapshotWith(second, "overflow") }
    assertEquals(Long.MAX_VALUE, header(ledger.snapshotWith(first, "retry"))[2])
    reference(rows(seed)[0]).clear()
    assertEquals("pending=0 weakCleared=${Long.MAX_VALUE} oldestAgeMs=0", ledger.describe())
    assertFalse(ledger.canRetire())
    assertEquals(0, ledger.pendingCount)
  }

  @Test
  fun signedNanoTimeAndWraparoundPreserveElapsedAge() {
    val clock = Clock(Long.MAX_VALUE - 2_000_000L)
    val loader = Loader()
    val state = RetiredGenerationLedger(clock::read).snapshotWith(loader, "wrap")
    clock.now = Long.MIN_VALUE + 2_999_999L
    assertEquals(
      "pending=1 weakCleared=0 oldestAgeMs=5",
      RetiredGenerationLedger.restore(state, clock::read).describe(),
    )
  }

  private fun header(state: Any): Array<*> = state as Array<*>
  private fun rows(state: Any): Array<*> = header(state)[4] as Array<*>
  private fun row(value: Any?): Array<*> = value as Array<*>
  private fun reference(value: Any?): WeakReference<*> = row(value)[3] as WeakReference<*>

  @Suppress("UNCHECKED_CAST")
  private fun mutableHeader(state: Any): Array<Any> = state as Array<Any>

  @Suppress("UNCHECKED_CAST")
  private fun mutableRow(value: Any?): Array<Any> = value as Array<Any>
}
