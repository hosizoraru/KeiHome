package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class NativeIconAutoApplyTest {
  @Test fun authorizationIsBoundToBothOperationAndComponent() {
    assertTrue(validAutoApplyTicket("token", "component", "token", "component", 100, 200, false))
    assertFalse(validAutoApplyTicket("other", "component", "token", "component", 100, 200, false))
    assertFalse(validAutoApplyTicket("token", "other", "token", "component", 100, 200, false))
  }
  @Test fun consumptionExpiryAndClockResetCannotReplayAnOperation() {
    assertFalse(validAutoApplyTicket("t", "c", "t", "c", 100, 200, true))
    assertFalse(validAutoApplyTicket("t", "c", "t", "c", 100, 600101, false))
    assertFalse(validAutoApplyTicket("t", "c", "t", "c", 100, 50, false))
    assertFalse(validAutoApplyTicket("t", "c", null, null, -1, 50, false))
  }
}
