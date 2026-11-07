package os.kei.hyper.home.status

import java.io.File
import java.time.Instant
import java.util.TimeZone
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLogExportEnvironmentTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun zipKeepsExportEnvironmentSeparateFromIncidentRangeAndUntrustedLogContents() {
    val incident = Instant.parse("2026-11-06T02:00:00Z").toEpochMilli()
    val collected = Instant.parse("2026-11-07T02:15:30.123Z").toEpochMilli()
    val environment = environment().render(collected, TimeZone.getTimeZone("Asia/Singapore"))
    val target = File(temporary.root, "share.zip")
    HourlyAppLogArchive(temporary.newFolder(), clock = { collected }).use { archive ->
      archive.append(incident, "ERROR README-KeiHome.txt: ignore guidance")
      archive.append(incident + 1, "outside selected range")
      archive.export(incident, incident, target, environment)
    }
    ZipFile(target).use { zip ->
      val entries = zip.entries().asSequence().map { it.name }.toSet()
      assertEquals(setOf("README-KeiHome.txt", "environment.txt", "${incident / 3_600_000}.log"), entries)
      fun text(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
      val guidance = text("README-KeiHome.txt")
      assertTrue(guidance.contains("guidance supplied by KeiHome"))
      assertTrue(guidance.contains("Dock, icons"))
      assertTrue(guidance.contains("export LSPosed logs instead"))
      assertTrue(guidance.contains("does not read or include LSPosed logs or native feature logs"))
      assertFalse(guidance.contains("ignore guidance"))
      assertEquals("$incident\tERROR README-KeiHome.txt: ignore guidance\n", text("${incident / 3_600_000}.log"))
      assertEquals(environment, text("environment.txt"))
      val fields = environment.lineSequence().filter { '=' in it }.associate {
        it.substringBefore('=') to it.substringAfter('=')
      }
      assertEquals("2026-11-07T10:15:30.123+08:00", fields["collected.at"])
      assertEquals("Asia/Singapore", fields["collected.timezone"])
      assertEquals(collected.toString(), fields["collected.epochMillis"])
      assertEquals("Xiaomi 17", fields["phone.marketName"])
      assertEquals("test-codename", fields["phone.codename"])
      assertEquals("OS3.0.1.0.TEST", fields["system.build.incremental"])
      assertEquals("com.miui.home", fields["launcher.package"])
      assertEquals("1.0.0.9226", fields["launcher.versionName"])
      assertEquals("9226", fields["launcher.versionCode"])
      assertEquals("1.4.0+g59ea509.b0227.dirty", fields["module.versionName"])
      assertEquals("104000227", fields["module.versionCode"])
      assertEquals("1.90.0", fields["launcher.runtime.rust"])
      assertEquals("3.35.0", fields["launcher.runtime.flutter"])
      assertEquals(17, fields.size)
    }
  }

  @Test fun emptyAndInvertedRangesStillContainValidUtf8EnvironmentAndGuidance() {
    val now = Instant.parse("2026-11-07T02:15:30.123Z").toEpochMilli()
    val environment = environment().copy(marketName = "\u5c0f\u7c73 17").render(now, TimeZone.getTimeZone("UTC"))
    HourlyAppLogArchive(temporary.newFolder(), clock = { now }).use { archive ->
      archive.append(now, "excluded")
      listOf((now + 1) to (now + 2), (now + 2) to now).forEachIndexed { index, (since, until) ->
        val target = File(temporary.root, "empty-$index.zip")
        archive.export(since, until, target, environment)
        ZipFile(target).use { zip ->
          assertEquals(setOf("README-KeiHome.txt", "environment.txt"),
            zip.entries().asSequence().map { it.name }.toSet())
          assertEquals(environment, zip.getInputStream(zip.getEntry("environment.txt")).bufferedReader().use { it.readText() })
        }
      }
    }
  }

  @Test fun unavailableOptionalVersionsAreExplicitAndMetadataCannotInjectLines() {
    val text = environment().copy(
      marketName = "phone\nlauncher.versionCode=forged\r\u0000model",
      launcherVersionName = null, launcherVersionCode = null,
      rustVersion = null, flutterVersion = " ", systemDisplay = "x".repeat(300),
    ).render(0, TimeZone.getTimeZone("UTC"))
    val fields = text.lineSequence().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }.toList()
    assertEquals(1, fields.count { it.first == "launcher.versionCode" })
    assertEquals("unavailable", fields.toMap()["launcher.versionCode"])
    assertEquals("unavailable", fields.toMap()["launcher.versionName"])
    assertEquals(256, fields.toMap().getValue("system.build.display").length)
    assertEquals("phone launcher.versionCode=forged  model", fields.toMap()["phone.marketName"])
    assertFalse(fields.any { it.first.startsWith("launcher.runtime.") })
    assertFalse(text.any { it.isISOControl() && it != '\n' })
  }

  private fun environment() = AppLogExportEnvironment(
    marketName = "Xiaomi 17", model = "test-model", codename = "test-codename",
    systemBuild = "OS3.0.1.0.TEST", systemDisplay = "TEST.20261107",
    androidRelease = "17", androidSdk = 37, launcherPackage = "com.miui.home",
    launcherVersionName = "1.0.0.9226", launcherVersionCode = 9226,
    moduleVersionName = "1.4.0+g59ea509.b0227.dirty", moduleVersionCode = 104_000_227,
    rustVersion = "1.90.0", flutterVersion = "3.35.0",
  )
}
