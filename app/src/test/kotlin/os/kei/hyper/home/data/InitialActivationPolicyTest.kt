package os.kei.hyper.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialActivationPolicyTest {
  @Test fun realSystemLoadedProofCompletesOnlyWithConfirmedScope() {
    for (bootCount in listOf<Int?>(null, 12)) {
      val record = InitialActivationPolicy.observe(
        InitialActivationRecord(), bootCount, scopeGranted = true, systemLoaded = true,
      )

      assertTrue(record.completed)
      assertEquals(InitialActivationStatus.COMPLETE,
        InitialActivationPolicy.status(record, bootCount))
    }
  }

  @Test fun systemLoadedWithoutConfirmedScopeCannotCompleteEvenAnArmedRecord() {
    for (previous in listOf(
      InitialActivationRecord(),
      InitialActivationRecord(scopeGranted = true, bootCount = 12),
    )) {
      for (scopeGranted in listOf<Boolean?>(false, null)) {
        val record = InitialActivationPolicy.observe(
          previous, 12, scopeGranted, systemLoaded = true,
        )

        assertFalse(record.completed)
        assertFalse(InitialActivationPolicy.status(record, 12) ==
          InitialActivationStatus.COMPLETE)
      }
    }
  }

  @Test fun completedActivationSurvivesPersistenceAndEveryLaterObservation() {
    val restored = InitialActivationRecord.decode(
      InitialActivationRecord(completed = true, scopeGranted = true, bootCount = 12).encode(),
    )

    for (bootCount in listOf<Int?>(null, 12, 13)) {
      for (scopeGranted in listOf<Boolean?>(false, null, true)) {
        for (systemLoaded in listOf(false, true)) {
          val record = InitialActivationPolicy.observe(
            restored, bootCount, scopeGranted, systemLoaded,
          )

          assertTrue(record.completed)
          assertEquals(InitialActivationStatus.COMPLETE,
            InitialActivationPolicy.status(record, bootCount))
        }
      }
    }
  }

  @Test fun sameBootAndProcessDeathRestoreRemainPendingWithoutAnyGuideInput() {
    var record = InitialActivationPolicy.observe(
      InitialActivationRecord(), 12, scopeGranted = true, systemLoaded = false,
    )
    assertFalse(record.completed)
    assertTrue(record.scopeGranted)
    assertEquals(12, record.bootCount)
    assertEquals(InitialActivationStatus.REBOOT_REQUIRED,
      InitialActivationPolicy.status(record, 12))

    repeat(3) {
      val restored = InitialActivationRecord.decode(record.encode())
      assertEquals(record, restored)
      record = InitialActivationPolicy.observe(
        restored, 12, scopeGranted = true, systemLoaded = false,
      )
      assertEquals(restored, record)
      assertEquals(InitialActivationStatus.REBOOT_REQUIRED,
        InitialActivationPolicy.status(record, 12))
    }
  }

  @Test fun differentKnownBootCompletesAnArmedRecordEvenWithUnknownScopeSnapshot() {
    val armed = InitialActivationPolicy.observe(
      InitialActivationRecord(), 12, scopeGranted = true, systemLoaded = false,
    )

    for (bootCount in listOf(11, 13)) {
      for (scopeGranted in listOf<Boolean?>(true, null)) {
        val record = InitialActivationPolicy.observe(
          armed, bootCount, scopeGranted, systemLoaded = false,
        )

        assertTrue(record.completed)
        assertEquals(InitialActivationStatus.COMPLETE,
          InitialActivationPolicy.status(record, bootCount))
      }
    }
  }

  @Test fun bootChangesBeforeScopeGrantNeverCompleteAndGrantArmsCurrentBoot() {
    var record = InitialActivationRecord()
    for ((bootCount, scopeGranted) in listOf(
      10 to false, 11 to null, 12 to false, 13 to null,
    )) {
      record = InitialActivationPolicy.observe(
        record, bootCount, scopeGranted, systemLoaded = false,
      )
      assertFalse(record.completed)
      assertFalse(InitialActivationPolicy.status(record, bootCount) ==
        InitialActivationStatus.COMPLETE)
    }

    val armed = InitialActivationPolicy.observe(
      record, 13, scopeGranted = true, systemLoaded = false,
    )
    assertFalse(armed.completed)
    assertTrue(armed.scopeGranted)
    assertEquals(13, armed.bootCount)
    assertEquals(InitialActivationStatus.REBOOT_REQUIRED,
      InitialActivationPolicy.status(armed, 13))
  }

  @Test fun unknownScopeAndBootSnapshotsPreserveAnExistingArm() {
    val armed = InitialActivationRecord(scopeGranted = true, bootCount = 12)

    for (bootCount in listOf<Int?>(12, null)) {
      val record = InitialActivationPolicy.observe(
        armed, bootCount, scopeGranted = null, systemLoaded = false,
      )
      assertEquals(armed, record)

      val rebooted = InitialActivationPolicy.observe(
        record, 13, scopeGranted = null, systemLoaded = false,
      )
      assertTrue(rebooted.completed)
    }
  }

  @Test fun explicitScopeRevocationClearsArmAndRegrantRequiresAnotherReboot() {
    val armed = InitialActivationRecord(scopeGranted = true, bootCount = 12)
    val revoked = InitialActivationPolicy.observe(
      armed, 12, scopeGranted = false, systemLoaded = false,
    )
    assertFalse(revoked.completed)
    assertFalse(revoked.scopeGranted)

    val rebootedWithoutScope = InitialActivationPolicy.observe(
      InitialActivationRecord.decode(revoked.encode()),
      13, scopeGranted = null, systemLoaded = false,
    )
    assertFalse(rebootedWithoutScope.completed)
    assertFalse(InitialActivationPolicy.status(rebootedWithoutScope, 13) ==
      InitialActivationStatus.COMPLETE)

    val rearmed = InitialActivationPolicy.observe(
      rebootedWithoutScope, 13, scopeGranted = true, systemLoaded = false,
    )
    assertFalse(rearmed.completed)
    assertTrue(rearmed.scopeGranted)
    assertEquals(13, rearmed.bootCount)
    assertEquals(InitialActivationStatus.REBOOT_REQUIRED,
      InitialActivationPolicy.status(rearmed, 13))

    val rebooted = InitialActivationPolicy.observe(
      rearmed, 14, scopeGranted = true, systemLoaded = false,
    )
    assertTrue(rebooted.completed)
  }

  @Test fun revocationFirstObservedAfterRebootRequiresRegrantAndAnotherReboot() {
    for (systemLoaded in listOf(false, true)) {
      val revoked = InitialActivationPolicy.observe(
        InitialActivationRecord(scopeGranted = true, bootCount = 12),
        13, scopeGranted = false, systemLoaded = systemLoaded,
      )
      assertEquals(InitialActivationRecord(), revoked)

      val rearmed = InitialActivationPolicy.observe(
        InitialActivationRecord.decode(revoked.encode()),
        13, scopeGranted = true, systemLoaded = false,
      )
      assertFalse(rearmed.completed)
      assertEquals(13, rearmed.bootCount)
      assertEquals(InitialActivationStatus.REBOOT_REQUIRED,
        InitialActivationPolicy.status(rearmed, 13))
      assertTrue(InitialActivationPolicy.observe(
        rearmed, 14, scopeGranted = true, systemLoaded = false,
      ).completed)
    }
  }

  @Test fun missingBootCountWaitsForKnownCountThenRequiresItsNextChange() {
    val withoutBoot = InitialActivationPolicy.observe(
      InitialActivationRecord(), null, scopeGranted = true, systemLoaded = false,
    )
    assertFalse(withoutBoot.completed)
    assertTrue(withoutBoot.scopeGranted)
    assertEquals(null, withoutBoot.bootCount)

    val stillUnknown = InitialActivationPolicy.observe(
      InitialActivationRecord.decode(withoutBoot.encode()),
      null, scopeGranted = null, systemLoaded = false,
    )
    assertEquals(withoutBoot, stillUnknown)

    val armed = InitialActivationPolicy.observe(
      stillUnknown, 12, scopeGranted = null, systemLoaded = false,
    )
    assertFalse(armed.completed)
    assertEquals(12, armed.bootCount)
    assertEquals(InitialActivationStatus.REBOOT_REQUIRED,
      InitialActivationPolicy.status(armed, 12))

    val missingAgain = InitialActivationPolicy.observe(
      armed, null, scopeGranted = true, systemLoaded = false,
    )
    assertEquals(armed, missingAgain)
    val sameBoot = InitialActivationPolicy.observe(
      missingAgain, 12, scopeGranted = true, systemLoaded = false,
    )
    assertEquals(armed, sameBoot)

    val rebooted = InitialActivationPolicy.observe(
      sameBoot, 13, scopeGranted = null, systemLoaded = false,
    )
    assertTrue(rebooted.completed)
  }

  @Test fun missingOrCorruptPersistenceDecodesToDefaultPendingRecord() {
    val pending = InitialActivationRecord()
    for (raw in listOf<String?>(
      null, "", " ", "garbage", "true", "{", "{\"completed\":true",
    )) {
      val decoded = InitialActivationRecord.decode(raw)
      assertEquals("raw=$raw", pending, decoded)
      assertEquals(InitialActivationStatus.UNKNOWN,
        InitialActivationPolicy.status(decoded, null))
      assertEquals(InitialActivationStatus.UNKNOWN,
        InitialActivationPolicy.status(decoded, 12))
    }
  }

  @Test fun persistenceRoundTripsAllRecordFieldsIncludingCompletedActivation() {
    for (completed in listOf(false, true)) {
      for (scopeGranted in listOf(false, true)) {
        for (bootCount in listOf<Int?>(null, 0, 12)) {
          val record = InitialActivationRecord(completed, scopeGranted, bootCount)
          assertEquals(record, InitialActivationRecord.decode(record.encode()))
        }
      }
    }
  }
}
