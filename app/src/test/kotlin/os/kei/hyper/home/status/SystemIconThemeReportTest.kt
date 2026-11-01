package os.kei.hyper.home.status

import org.junit.Assert.*
import org.junit.Test

class SystemIconThemeReportTest {
  @Test fun snapshotKeySurvivesTheRuntimeTransport() {
    val report = SystemIconThemeReport(SystemIconThemeReport.Source.THEME, "ColorOS", "/data/system/theme/icons",
      4, 2, true, "revision", "local", "hash", "a".repeat(64))
    assertEquals(report, SystemIconThemeReport.decode(report.encode()))
  }
  @Test fun olderJavaTargetsRemainReadableDuringHotReload() {
    val report = SystemIconThemeReport(SystemIconThemeReport.Source.DEFAULT, null, null, 0, 0, true, "old")
    val old = report.encode().split(':').take(9).joinToString(":")
    assertEquals(report, SystemIconThemeReport.decode(old))
    assertNull(SystemIconThemeReport.decode(old)?.previewKey)
  }
}
