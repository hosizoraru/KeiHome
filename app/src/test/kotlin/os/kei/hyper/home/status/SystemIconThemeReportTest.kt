package os.kei.hyper.home.status

import org.junit.Assert.*
import org.junit.Test

class SystemIconThemeReportTest {
  @Test fun snapshotKeySurvivesTheRuntimeTransport() {
    val report = SystemIconThemeReport(SystemIconThemeReport.Source.THEME, "ColorOS", "/data/system/theme/icons",
      4, 2, true, "revision", "local", "hash", "a".repeat(64), "2.3:night | \u7248")
    assertEquals(11, report.encode().split(':').size)
    assertEquals(report, SystemIconThemeReport.decode(report.encode()))
  }
  @Test fun olderJavaTargetsRemainReadableDuringHotReload() {
    val report = SystemIconThemeReport(SystemIconThemeReport.Source.THEME, "Old theme", "/theme/icons",
      3, 1, false, "old", "local", "hash", "a".repeat(64), "2")
    for (count in listOf(7, 9, 10)) {
      val old = report.encode().split(':').take(count).joinToString(":")
      assertEquals(report.copy(
        localId = report.localId.takeIf { count >= 9 },
        nativeHash = report.nativeHash.takeIf { count >= 9 },
        previewKey = report.previewKey.takeIf { count >= 10 },
        version = null,
      ), SystemIconThemeReport.decode(old))
    }
  }

  @Test fun absentVersionAndInvalidFieldCountsStayUnambiguous() {
    val report = SystemIconThemeReport(SystemIconThemeReport.Source.DEFAULT, null, null, 0, 0, true, "old")
    assertEquals(report, SystemIconThemeReport.decode(report.encode()))
    val fields = report.encode().split(':')
    for (count in listOf(0, 6, 8)) {
      assertNull(SystemIconThemeReport.decode(fields.take(count).joinToString(":")))
    }
    assertNull(SystemIconThemeReport.decode(report.encode() + ":"))
    assertNull(SystemIconThemeReport.decode(fields.take(10).joinToString(":") + ":%%%"))
    assertNull(SystemIconThemeReport.decode(report.copy(resourceCount = 1, nightResourceCount = 2).encode()))
  }
}
