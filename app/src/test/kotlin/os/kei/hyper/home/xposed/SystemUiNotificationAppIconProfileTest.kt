package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class SystemUiNotificationAppIconProfileTest {
  @Test fun acceptsOnlyMatchingNotificationAndApplicationUsers() {
    assertEquals("os.kei", notificationAppIconPackage("os.kei", 10123, 0, 0))
    assertEquals("os.kei", notificationAppIconPackage("os.kei", 110123, 1, 1))
    assertEquals("android", notificationAppIconPackage("android", 1000, 0, 0))
    assertNull(notificationAppIconPackage("os.kei", 110123, 0, 0))
    assertNull(notificationAppIconPackage("os.kei", 10123, 1, 0))
    assertNull(notificationAppIconPackage("os.kei", 10123, -1, 0))
    assertNull(notificationAppIconPackage("os.kei", 10123, 0, -1))
  }

  @Test fun rejectsMissingInvalidUidAndUnsafePackageIdentity() {
    for (uid in listOf(-1, 0, 999, 100001)) {
      assertNull(notificationAppIconPackage("os.kei", uid, 0, 0))
    }
    for (name in listOf(null, "", "../outside", ".os.kei", "os..kei", "os.kei.",
        "os.kei#foreign.Icon", "os.kei\$Icon", "os.kei/Other", "os. kei", "a".repeat(241))) {
      assertNull(notificationAppIconPackage(name, 10123, 0, 0))
    }
  }
}
