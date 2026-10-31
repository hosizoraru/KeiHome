package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeIconImportGateTest {
  @Test fun activeNativeImportRejectsRetirementWithoutDisablingTheOldOwner() {
    val gate = ThemeIconImportGate()
    assertTrue(gate.enter())
    var saved = false
    assertFalse(gate.retire { saved = true })
    assertFalse(saved)
    assertTrue(gate.enter())
    gate.leave()
    gate.leave()
    assertTrue(gate.retire { saved = true })
    assertTrue(saved)
    assertFalse(gate.enter())
  }

  @Test fun failedStateTransferLeavesExistingBinderUsable() {
    val gate = ThemeIconImportGate()
    assertFalse(gate.retire { error("Framework rejected the saved state") })
    assertTrue(gate.enter())
    gate.leave()
    assertTrue(gate.retire {})
    assertFalse(gate.enter())
  }

  @Test fun retirementClosesReentrantAdmissionAndSavesOnlyOnce() {
    val gate = ThemeIconImportGate()
    var saves = 0
    assertTrue(gate.retire {
      saves++
      assertFalse(gate.enter())
      assertFalse(gate.retire { saves++ })
    })
    assertTrue(gate.retire { saves++ })
    assertEquals(1, saves)
    assertFalse(gate.enter())
  }
}
