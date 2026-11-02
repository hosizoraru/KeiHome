package os.kei.hyper.home.status

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleGenerationTest {
  @Test fun aDowngradeAlsoRequiresReloadingTheSystemProcess() {
    assertTrue(needsJavaReload(1_793_624_474, 90_000_000))
    assertTrue(needsJavaReload(90_000_000, 90_000_001))
    assertFalse(needsJavaReload(90_000_001, 90_000_001))
  }
}
