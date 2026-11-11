package os.kei.hyper.home.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.data.LauncherGlassChoice
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.data.SearchGlassStyle

class RuntimeSearchGlassAckTest {
  private val ready = RuntimeStatusReport(
    requestId = "glass-pair", userId = 0, moduleVersion = 123, checkedAtElapsed = 100_500,
    nativeState = NativeRuntimeState.READY, launcherPid = 9700, launcherStartTicks = 3210,
    launcherGlassAvailable = true, launcherCapabilities = 80,
  )

  @Test fun schemaEightRoundTripsEveryPairAndAppendsOnlyTheEffectiveSearchAck() {
    for (dock in LauncherGlassStyle.entries) for (search in SearchGlassStyle.entries) {
      val choice = LauncherGlassChoice(dock, search)
      val report = ready.copy(launcherGlassStyle = dock.nativeValue,
        launcherSearchGlassStyle = choice.resolvedSearch.nativeValue)
      val fields = report.encode().split('|')
      assertEquals(21, fields.size)
      assertEquals("8", fields[0])
      assertEquals(ready.copy(launcherGlassStyle = dock.nativeValue).encode().split('|').subList(1, 20),
        fields.subList(1, 20))
      assertEquals(choice.resolvedSearch.nativeValue.toString(), fields[20])
      val decoded = requireNotNull(RuntimeStatusReport.decode(report.encode()))
      assertEquals(report, decoded)
      assertTrue(decoded.acknowledgesLauncherGlass(dock))
      assertTrue(decoded.acknowledgesSearchGlass(choice))
    }
  }

  @Test fun schemaSevenCanAcknowledgeDockButNeverSearchEvenForFollowDock() {
    for (caps in listOf(16, 63, 80, 127)) for (dock in LauncherGlassStyle.entries) {
      val original = ready.copy(launcherCapabilities = caps, launcherGlassStyle = dock.nativeValue,
        launcherSearchGlassStyle = dock.nativeValue)
      val legacy = original.encode().split('|').take(20).toMutableList()
        .apply { this[0] = "7" }.joinToString("|")
      val decoded = requireNotNull(RuntimeStatusReport.decode(legacy))
      assertEquals(original.copy(launcherSearchGlassStyle = null), decoded)
      assertTrue(decoded.acknowledgesLauncherGlass(dock))
      assertNull(decoded.launcherSearchGlassStyle)
      for (search in SearchGlassStyle.entries)
        assertFalse(decoded.acknowledgesSearchGlass(LauncherGlassChoice(dock, search)))
    }
  }

  @Test fun aForgedSearchAckWithoutBothCapabilitiesCannotDecodeOrAcknowledge() {
    for (caps in listOf(null, 0, 1, 16, 31, 63, 64, 65, 128, -2)) {
      val forged = ready.copy(launcherCapabilities = caps, launcherGlassStyle = 1, launcherSearchGlassStyle = 1)
      assertFalse("caps=$caps", forged.acknowledgesSearchGlass(LauncherGlassChoice(LauncherGlassStyle.LIGHT)))
      assertNull("caps=$caps", RuntimeStatusReport.decode(forged.encode()))
    }
  }

  @Test fun independentSearchAckCanMatchWhileDockDiffersButFollowDockRequiresBoth() {
    for (requestedDock in LauncherGlassStyle.entries) for (observedDock in LauncherGlassStyle.entries) {
      val report = ready.copy(launcherGlassStyle = observedDock.nativeValue,
        launcherSearchGlassStyle = requestedDock.nativeValue)
      val independentSearch = SearchGlassStyle.from(requestedDock.nativeValue)
      assertTrue(report.acknowledgesSearchGlass(LauncherGlassChoice(requestedDock, independentSearch)))
      assertEquals(requestedDock == observedDock,
        report.acknowledgesSearchGlass(LauncherGlassChoice(requestedDock, SearchGlassStyle.FOLLOW_DOCK)))
      assertEquals(requestedDock == observedDock, report.acknowledgesLauncherGlass(requestedDock))
    }
  }

  @Test fun effectiveSearchMustMatchEvenWhenDockMatches() {
    for (dock in LauncherGlassStyle.entries) for (search in SearchGlassStyle.entries) {
      val choice = LauncherGlassChoice(dock, search)
      val wrongSearch = (choice.resolvedSearch.nativeValue + 1) % 3
      val report = ready.copy(launcherGlassStyle = dock.nativeValue, launcherSearchGlassStyle = wrongSearch)
      assertTrue(report.acknowledgesLauncherGlass(dock))
      assertFalse(report.acknowledgesSearchGlass(choice))
    }
  }

  @Test fun searchAckRequiresAvailabilityActiveUserAndAnAuthenticatedProcess() {
    val report = ready.copy(launcherGlassStyle = 2, launcherSearchGlassStyle = 2)
    val choice = LauncherGlassChoice(LauncherGlassStyle.DARK)
    assertFalse(report.acknowledgesSearchGlass(null))
    for (invalid in listOf(
      report.copy(launcherGlassAvailable = false),
      report.copy(nativeState = NativeRuntimeState.INACTIVE_USER),
      report.copy(launcherPid = null), report.copy(launcherStartTicks = null),
      report.copy(launcherPid = 0), report.copy(launcherStartTicks = 0),
      report.copy(launcherPid = -1), report.copy(launcherStartTicks = -1),
    )) {
      assertFalse(invalid.acknowledgesSearchGlass(choice))
      assertNull(RuntimeStatusReport.decode(invalid.encode()))
    }
    val monetUnavailable = report.copy(nativeState = NativeRuntimeState.UNAVAILABLE,
      detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE)
    assertEquals(monetUnavailable, RuntimeStatusReport.decode(monetUnavailable.encode()))
    assertTrue(monetUnavailable.acknowledgesSearchGlass(choice))
  }

  @Test fun absentSearchAckIsReadableButNeverInferredFromCapabilityOrDockAck() {
    val report = ready.copy(launcherGlassStyle = 2)
    assertEquals("-1", report.encode().split('|')[20])
    assertEquals(report, RuntimeStatusReport.decode(report.encode()))
    assertTrue(report.acknowledgesLauncherGlass(LauncherGlassStyle.DARK))
    assertFalse(report.acknowledgesSearchGlass(LauncherGlassChoice(LauncherGlassStyle.DARK)))
  }

  @Test fun malformedEffectiveSearchValuesAndSchemaLengthsAreRejected() {
    for (invalid in listOf("-2", "3", "4", "2147483648", "", "true")) {
      val fields = ready.encode().split('|').toMutableList().apply { this[20] = invalid }
      assertNull("search=$invalid", RuntimeStatusReport.decode(fields.joinToString("|")))
    }
    val fields = ready.encode().split('|')
    assertNull(RuntimeStatusReport.decode(fields.dropLast(1).joinToString("|")))
    assertNull(RuntimeStatusReport.decode(fields.joinToString("|") + "|0"))
    assertNull(RuntimeStatusReport.decode(fields.toMutableList().apply { this[0] = "7" }.joinToString("|")))
  }
}
