package os.kei.hyper.home.xposed

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.status.IconPackImportRequest
import os.kei.hyper.home.status.NativeIconImportReport

class IconPackImportMonitorTest {
  private val request = IconPackImportRequest("00000000-0000-0000-0000-000000000000", 0, 100, "source")
  private val pending = NativeIconImportReport(request.token, request.userId, request.sourceId, "IMPORTING")
  private val imported = pending.copy(status = "IMPORTED", localId = "icon-local", convertedReady = true)
  private val proof = ConvertedIconArchive.Proof("CONVERTED_APK",
    ConvertedIconArchive.Digests("a".repeat(40), "b".repeat(64)),
    ConvertedIconArchive.Digests("c".repeat(40), "d".repeat(64)))

  @Test
  fun terminalWriteFailureKeepsOriginalForTheReplacementWorker() = withInput { input ->
    val receipt = IconImportReceipt()
    receipt.persist(pending, null) {}
    assertThrows(IOException::class.java) {
      receipt.persist(imported, null) { throw IOException("Receipt commit failed") }
    }
    receipt.releaseInput(input)
    assertTrue(input.isFile)

    val replacement = IconImportReceipt()
    replacement.restore(request, pending, null)
    replacement.releaseInput(input)
    assertTrue(input.isFile)
    replacement.persist(imported, null) {}
    replacement.releaseInput(input)
    assertFalse(input.exists())
  }

  @Test
  fun successfulReceiptRetainsItsSourceUntilStageCleanupIsCommitted() = withInput { input ->
    val stage = File(input.parentFile, "stage.mtz").apply { writeText("staging copy") }
    val receipt = IconImportReceipt()
    receipt.persist(imported, stage.path) {}
    receipt.releaseInput(input)
    assertTrue(input.isFile)

    val replacement = IconImportReceipt()
    replacement.restore(request, imported, stage.path)
    assertTrue(stage.delete())
    assertThrows(IOException::class.java) {
      replacement.persist(imported, null) { throw IOException("Cleanup receipt commit failed") }
    }
    replacement.releaseInput(input)
    assertTrue(input.isFile)
    replacement.persist(imported, null) {}
    replacement.releaseInput(input)
    assertFalse(input.exists())
  }

  @Test
  fun repeatedUncertainReceiptsKeepTheHandoffAcrossReloads() = withInput { input ->
    repeat(3) {
      val receipt = IconImportReceipt()
      receipt.restore(request, pending.copy(status = "UNCERTAIN"), "/owned/stage/icons.mtz")
      receipt.persist(pending.copy(status = "UNCERTAIN"), "/owned/stage/icons.mtz") {}
      receipt.releaseInput(input)
      assertTrue(input.isFile)
    }
  }

  @Test
  fun partialCopyCancelledBeforeJournalingIsReleased() = withInput { input ->
    IconImportReceipt().releaseInput(input)
    assertFalse(input.exists())
  }

  @Test
  fun cancellationBeforeNativeSubmissionReleasesOnlyAfterRemovingTheMarker() = withInput { input ->
    val journal = File(input.parentFile, "receipt.json").apply { writeText("IMPORTING") }
    val receipt = IconImportReceipt()
    receipt.persist(pending, null) {}
    assertThrows(IOException::class.java) {
      receipt.abandonUnsubmitted { throw IOException("Cannot remove marker") }
    }
    receipt.releaseInput(input)
    assertTrue(input.isFile)
    assertTrue(journal.isFile)

    receipt.abandonUnsubmitted { check(journal.delete()) }
    receipt.releaseInput(input)
    assertFalse(journal.exists())
    assertFalse(input.exists())
  }

  @Test
  fun foreignReceiptCannotReplaceOwnedPendingState() = withInput { input ->
    val receipt = IconImportReceipt()
    receipt.restore(request, pending, null)
    for (foreign in listOf(imported.copy(token = "other"), imported.copy(userId = 10),
      imported.copy(sourceId = "other"))) {
      assertThrows(IllegalStateException::class.java) { receipt.restore(request, foreign, null) }
      receipt.releaseInput(input)
      assertTrue(input.isFile)
    }
  }

  @Test
  fun savedSuccessReleasesTheInputEvenWhenReportDeliveryFails() = withInput { input ->
    val receipt = IconImportReceipt()
    receipt.persist(imported, null) {}
    try {
      assertThrows(IOException::class.java) { throw IOException("Client provider unavailable") }
    } finally {
      receipt.releaseInput(input)
    }
    assertFalse(input.exists())
  }

  @Test
  fun failedReceiptCommitCannotNotifyTheRuntimeOrReportImportSuccess() = withInput { input ->
    val receipt = IconImportReceipt()
    receipt.persist(pending, null) {}
    val unexpectedGrant = { _: NativeIconImportReport, _: ConvertedIconArchive.Proof ->
      error("Uncommitted receipt cannot register")
    }
    assertThrows(IOException::class.java) {
      receipt.persist(imported, null, proof) {
        assertEquals("UNCERTAIN", receipt.forDelivery(imported, unexpectedGrant, ::unexpectedFailure).status)
        throw IOException("Receipt commit failed")
      }
    }
    val delivered = receipt.forDelivery(imported, unexpectedGrant, ::unexpectedFailure)
    assertFalse(delivered.imported)
    assertEquals(false, delivered.convertedReady)
    assertEquals(imported.localId, delivered.localId)
    receipt.releaseInput(input)
    assertTrue(input.exists())
  }

  @Test
  fun grantFailureKeepsNativeSuccessAndReplaysTheCommittedProofAfterSourceCleanup() = withInput { input ->
    val receipt = IconImportReceipt()
    var savedProof: String? = null
    receipt.persist(imported, null, proof) { savedProof = ConvertedImportProofReceipt.encode(proof) }
    var failures = 0
    val delivered = receipt.forDelivery(imported, { _, _ -> throw IOException("Runtime grant failed") },
      { failures += 1 })
    assertTrue(delivered.imported)
    assertEquals(imported.localId, delivered.localId)
    assertEquals(false, delivered.convertedReady)
    assertEquals(1, failures)
    receipt.releaseInput(input)
    assertFalse(input.exists())

    val replacement = IconImportReceipt()
    replacement.restore(request, imported, null, ConvertedImportProofReceipt.decode(savedProof))
    var registrations = 0
    val replayed = replacement.forDelivery(imported, { report, restored ->
      assertEquals(imported, report)
      assertEquals(proof, restored)
      registrations += 1
    }, ::unexpectedFailure)
    assertEquals(imported, replayed)
    assertEquals(1, registrations)
  }

  @Test
  fun successfulCleanupPreservesProofAndDeliveryRetryDoesNotRegisterTwice() = withInput { input ->
    val receipt = IconImportReceipt()
    receipt.restore(request, imported, "/owned/stage/icons.mtz", proof)
    receipt.persist(imported, null, proof) {}
    receipt.releaseInput(input)
    assertFalse(input.exists())
    var registrations = 0
    repeat(2) {
      assertEquals(imported, receipt.forDelivery(imported, { report, suppliedProof ->
        assertEquals(imported, report)
        assertEquals(proof, suppliedProof)
        registrations += 1
      }, ::unexpectedFailure))
    }
    assertEquals(1, registrations)
  }

  @Test
  fun legacyOrMalformedProofCannotReportConvertedReadiness() {
    for (savedProof in listOf(null, proof.copy(origin = "NATIVE_MTZ"),
      proof.copy(icons = proof.icons.copy(sha256 = "invalid")))) {
      val receipt = IconImportReceipt()
      receipt.restore(request, imported, null, savedProof)
      var failures = 0
      var registrations = 0
      val delivered = receipt.forDelivery(imported, { _, _ -> registrations += 1 },
        { failures += 1 })
      assertTrue(delivered.imported)
      assertEquals(false, delivered.convertedReady)
      assertEquals(0, registrations)
      assertEquals(1, failures)
    }
  }

  @Test
  fun proofCannotGrantAnotherOutcomeOrAConversionRejectedByThemes() {
    for (report in listOf(pending, imported.copy(convertedReady = null), imported.copy(convertedReady = false))) {
      val receipt = IconImportReceipt()
      receipt.persist(report, null, proof) {}
      assertEquals(report, receipt.forDelivery(report, { _, _ -> error("Not an approved conversion") },
        ::unexpectedFailure))
    }
    val receipt = IconImportReceipt()
    receipt.persist(imported, null, proof) {}
    val differentIcon = imported.copy(localId = "different-local-id")
    assertEquals("UNCERTAIN", receipt.forDelivery(differentIcon, { _, _ -> error("Wrong native Icon") },
      ::unexpectedFailure).status)
  }

  private fun unexpectedFailure(failure: Throwable): Unit = throw AssertionError(failure)

  private fun withInput(action: (File) -> Unit) {
    val directory = Files.createTempDirectory("keihome-import-receipt-").toFile()
    try {
      action(File(directory, "input.mtz").apply { writeText("system-owned handoff") })
    } finally {
      directory.deleteRecursively()
    }
  }
}
