package os.kei.hyper.home.iconpack

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeiHomePackVersionTest {
  @Test fun formatsAppVersionWithTimestampAndOptionalRevision() {
    val epoch = Instant.parse("2026-11-27T02:45:00Z").toEpochMilli()
    assertEquals("1.17.0-261127.0245", KeiHomePackVersion.format(epoch, appVersion = "1.17.0"))
    assertEquals(
      "1.17.0-fb8b5c9.261127",
      KeiHomePackVersion.format(epoch, revision = "fb8b5c9abcdef", appVersion = "1.17.0"),
    )
  }

  @Test fun resolvesUpstreamOrInstalledVersionAndReplacesPlaceholders() {
    val epoch = Instant.parse("2026-11-27T02:45:00Z").toEpochMilli()
    assertTrue(KeiHomePackVersion.isPlaceholder(null))
    assertTrue(KeiHomePackVersion.isPlaceholder("1"))
    assertTrue(KeiHomePackVersion.isPlaceholder("1.0"))
    assertFalse(KeiHomePackVersion.isPlaceholder("15.0.231111"))

    assertEquals("15.0.231111", KeiHomePackVersion.resolveForExport("15.0.231111", "2.0", epoch))
    assertEquals("2.4.1", KeiHomePackVersion.resolveForExport("1.0", "2.4.1", epoch))
    assertTrue(KeiHomePackVersion.resolveForExport("1", null, epoch).endsWith("-261127.0245"))
  }
}
