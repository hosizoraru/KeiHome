package os.kei.hyper.home.status

import org.junit.Assert.*
import org.junit.Test

class FrameworkManagerProtocolTest {
  @Test fun recognizesFrameworkNamesWithoutGuessingUnknownForks() {
    assertEquals(0, FrameworkManagerProtocol.flavor("LSPosed"))
    assertEquals(1, FrameworkManagerProtocol.flavor("vector"))
    assertNull(FrameworkManagerProtocol.flavor(null))
    assertNull(FrameworkManagerProtocol.flavor("unknown"))
    assertNull(FrameworkManagerProtocol.flavor("LSPosed-extra"))
  }

  @Test fun acceptsOnlyKnownDestinationsAndFrameworkProtocols() {
    for (target in 0..1) for (flavor in 0..1)
      assertTrue(FrameworkManagerProtocol.valid(target, flavor, 2_500, 1_000))
    for (bad in listOf(-1, 2, Int.MIN_VALUE, Int.MAX_VALUE)) {
      assertFalse(FrameworkManagerProtocol.valid(bad, 0, 2_500, 1_000))
      assertFalse(FrameworkManagerProtocol.valid(0, bad, 2_500, 1_000))
    }
  }

  @Test fun rejectsExpiredAndUnboundedRequests() {
    for (deadline in listOf(0L, 999L, 1_000L, 2_501L, Long.MAX_VALUE))
      assertFalse(FrameworkManagerProtocol.valid(0, 0, deadline, 1_000))
    assertTrue(FrameworkManagerProtocol.valid(0, 0, 1_001, 1_000))
  }

  @Test fun managerCategoriesRemainFrameworkSpecific() {
    assertEquals("org.lsposed.manager", FrameworkManagerProtocol.managerPackage(0))
    assertEquals("org.matrix.vector.manager", FrameworkManagerProtocol.managerPackage(1))
    assertThrows(IllegalStateException::class.java) { FrameworkManagerProtocol.managerPackage(2) }
  }
}
