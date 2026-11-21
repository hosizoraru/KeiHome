package os.kei.hyper.home.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStatusReportTest {
  @Test fun schemaEightAppendsSearchAckWithoutMovingOlderFields() {
    for (style in listOf(null) + os.kei.hyper.home.data.LauncherGlassStyle.entries.map { it.nativeValue }) {
      val report = ready.copy(launcherGlassAvailable = true, launcherGlassStyle = style)
      val fields = report.encode().split('|')
      assertEquals(21, fields.size)
      assertEquals("8", fields[0])
      assertEquals(ready.encode().split('|').subList(1, 17), fields.subList(1, 17))
      assertEquals("1", fields[17])
      assertEquals((style ?: -1).toString(), fields[18])
      assertEquals("-1", fields[19])
      assertEquals("-1", fields[20])
      assertEquals(report, RuntimeStatusReport.decode(report.encode()))
    }
    assertEquals(listOf("0", "-1", "-1", "-1"), ready.encode().split('|').takeLast(4))
    for ((schema, size) in listOf("1" to 11, "2" to 12, "3" to 13, "4" to 16, "5" to 17, "6" to 19, "7" to 20)) {
      val old = ready.encode().split('|').take(size).toMutableList().apply { this[0] = schema }.joinToString("|")
      assertEquals(ready, RuntimeStatusReport.decode(old))
      assertFalse(RuntimeStatusReport.decode(old)!!.launcherGlassAvailable)
      assertNull(RuntimeStatusReport.decode(old)!!.launcherGlassStyle)
      assertNull(RuntimeStatusReport.decode(old)!!.launcherCapabilities)
      assertNull(RuntimeStatusReport.decode(old)!!.launcherSearchGlassStyle)
      assertNull(RuntimeStatusReport.decode(old)!!.supportsLauncherCapability(LauncherRuntimeCapabilities.DOCK))
      assertNull(RuntimeStatusReport.decode(old + "|0"))
      assertNull(RuntimeStatusReport.decode(old.substringBeforeLast('|')))
    }
  }

  @Test fun capabilitiesAllowZeroAndRequireKnownBitsAndAResidentLauncherIdentity() {
    for (bits in 0..LauncherRuntimeCapabilities.KNOWN) {
      val report = ready.copy(launcherCapabilities = bits)
      if ((bits and 64 == 0 || bits and 16 != 0) && (bits and 128 == 0 || bits and 40 == 40))
        assertEquals(report, RuntimeStatusReport.decode(report.encode()))
      else assertNull("Extension capability needs its base capabilities: $bits", RuntimeStatusReport.decode(report.encode()))
    }
    for (bits in listOf(-2, Int.MIN_VALUE, 64, 65, 128, Int.MAX_VALUE))
      assertNull(RuntimeStatusReport.decode(ready.copy(launcherCapabilities = bits).encode()))
    for (unbound in listOf(
      ready.copy(launcherPid = null, launcherStartTicks = null),
      ready.copy(launcherPid = 0), ready.copy(launcherStartTicks = 0),
      ready.copy(launcherPid = -1), ready.copy(launcherStartTicks = -1),
    )) {
      for (bits in listOf(0, 8)) {
        assertNull(RuntimeStatusReport.decode(unbound.copy(launcherCapabilities = bits).encode()))
        assertEquals(null, unbound.copy(launcherCapabilities = bits).supportsLauncherCapability(8))
      }
    }
  }

  @Test fun diagnosticOnlyZeroCapabilitiesRemainDistinctFromAnUnknownGreeting() {
    val zero = ready.copy(nativeState = NativeRuntimeState.UNAVAILABLE,
      detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE, launcherCapabilities = 0)
    val decoded = requireNotNull(RuntimeStatusReport.decode(zero.encode()))
    assertEquals(zero, decoded)
    assertEquals(0, decoded.launcherCapabilities)
    assertTrue(decoded.hasMissingMonetCapability)
    for (bit in listOf(1, 2, 4, 8, 16, 32, 64))
      assertEquals(false, decoded.supportsLauncherCapability(bit))

    val unknown = zero.copy(launcherCapabilities = null)
    assertEquals(unknown, RuntimeStatusReport.decode(unknown.encode()))
    assertNull(RuntimeStatusReport.decode(unknown.encode())!!.launcherCapabilities)
    assertNull(unknown.supportsLauncherCapability(LauncherRuntimeCapabilities.DOCK))
    assertEquals("-1", unknown.encode().split('|')[19])
    assertEquals("0", zero.encode().split('|')[19])
    assertEquals("-1", zero.encode().split('|')[20])
    assertEquals(unknown, RuntimeStatusReport.decode(zero.encode().split('|').take(19).toMutableList()
      .apply { this[0] = "6" }.joinToString("|")))
  }

  @Test fun capabilitiesRemainIndependentOfMonetReadinessAndLegacyGlassBoolean() {
    val report = ready.copy(nativeState = NativeRuntimeState.UNAVAILABLE,
      launcherCapabilities = LauncherRuntimeCapabilities.DOCK)
    assertEquals(report, RuntimeStatusReport.decode(report.encode()))
    assertEquals(true, report.supportsLauncherCapability(LauncherRuntimeCapabilities.DOCK))
    assertEquals(false, report.supportsLauncherCapability(LauncherRuntimeCapabilities.GLASS))
    assertEquals(null, report.copy(nativeState = NativeRuntimeState.INACTIVE_USER)
      .supportsLauncherCapability(LauncherRuntimeCapabilities.DOCK))
  }

  @Test fun launcherGlassAvailabilityIsIndependentOfMonetAndNeverAnAckByItself() {
    val report = ready.copy(nativeState = NativeRuntimeState.UNAVAILABLE, launcherPid = null,
      launcherStartTicks = null, launcherGlassAvailable = true)
    assertEquals(report, RuntimeStatusReport.decode(report.encode()))
    assertFalse(report.acknowledgesLauncherGlass(os.kei.hyper.home.data.LauncherGlassStyle.SYSTEM))
    for (style in os.kei.hyper.home.data.LauncherGlassStyle.entries) {
      val accepted = report.copy(launcherGlassStyle = style.nativeValue)
      assertEquals(accepted, RuntimeStatusReport.decode(accepted.encode()))
      assertTrue(accepted.acknowledgesLauncherGlass(style))
      assertFalse(accepted.acknowledgesLauncherGlass(null))
      assertFalse(accepted.copy(launcherGlassAvailable = false).acknowledgesLauncherGlass(style))
      assertFalse(accepted.copy(nativeState = NativeRuntimeState.INACTIVE_USER).acknowledgesLauncherGlass(style))
      assertFalse(accepted.acknowledgesLauncherGlass(
        os.kei.hyper.home.data.LauncherGlassStyle.entries[(style.ordinal + 1) % 3]))
    }
  }

  @Test fun malformedOrContradictoryGlassFieldsCannotBecomeAnAcceptedStyle() {
    for (available in listOf("-1", "2", "true", "")) {
      assertNull(RuntimeStatusReport.decode(ready.encode().split('|').toMutableList()
        .apply { this[17] = available }.joinToString("|")))
    }
    for (style in listOf("-2", "3", "2147483648", "")) {
      assertNull(RuntimeStatusReport.decode(ready.copy(launcherGlassAvailable = true).encode().split('|')
        .toMutableList().apply { this[18] = style }.joinToString("|")))
    }
    for (style in 0..2)
      assertNull(RuntimeStatusReport.decode(ready.copy(launcherGlassStyle = style).encode()))
    assertNull(RuntimeStatusReport.decode(ready.encode().split('|').dropLast(1).joinToString("|")))
    assertNull(RuntimeStatusReport.decode(ready.encode() + "|0"))
  }

  @Test fun oldCapabilityFailuresRemainDistinctFromAnUnreachableLauncher() {
    val missing = ready.copy(nativeState = NativeRuntimeState.UNAVAILABLE, launcherPid = null,
      launcherStartTicks = null, detail = NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE)
    val legacy = missing.encode().split('|').take(11).toMutableList().apply { this[0] = "1" }.joinToString("|")
    assertTrue(RuntimeStatusReport.decode(legacy)!!.hasMissingMonetCapability)
    assertTrue(RuntimeStatusReport.decode(missing.encode())!!.hasMissingMonetCapability)
    assertFalse(missing.copy(detail = "endpoint: deadline exceeded").hasMissingMonetCapability)
    assertFalse(ready.copy(detail = missing.detail).hasMissingMonetCapability)
  }

  @Test fun dockAckHasItsOwnIdentityEvenWhenMonetIsUnavailable() {
    val report = ready.copy(nativeState = NativeRuntimeState.UNAVAILABLE, launcherPid = null,
      launcherStartTicks = null, dockGlassEnabled = true, dockGlassPid = 9701, dockGlassStartTicks = 3211, dockGlassCornerStyle = 1)
    assertEquals(report, RuntimeStatusReport.decode(report.encode()))
    assertEquals(report.copy(dockGlassEnabled = false),
      RuntimeStatusReport.decode(report.copy(dockGlassEnabled = false).encode()))
    assertNull(RuntimeStatusReport.decode(report.copy(dockGlassPid = null).encode()))
    assertNull(RuntimeStatusReport.decode(report.copy(dockGlassStartTicks = null).encode()))
    assertNull(RuntimeStatusReport.decode(report.copy(dockGlassEnabled = null).encode()))
    assertNull(RuntimeStatusReport.decode(report.encode().split('|').toMutableList()
      .apply { this[13] = "2" }.joinToString("|")))
    val v3 = ready.encode().split('|').take(13).toMutableList().apply { this[0] = "3" }.joinToString("|")
    assertEquals(ready, RuntimeStatusReport.decode(v3))
  }

  @Test fun dockStyleRequiresAnExactCompleteAckAndV4CanOnlyAcknowledgeRounded() {
    val choice = os.kei.hyper.home.data.DockGlassChoice(true)
    val report = ready.copy(dockGlassEnabled = true, dockGlassPid = 9701,
      dockGlassStartTicks = 3211, dockGlassCornerStyle = 1)
    for (style in 0..1) for (enabled in listOf(false, true)) {
      val observation = report.copy(dockGlassCornerStyle = style, dockGlassEnabled = enabled)
      assertEquals(observation, RuntimeStatusReport.decode(observation.encode()))
    }
    assertTrue(report.acknowledgesDockGlass(choice))
    assertFalse(report.copy(dockGlassCornerStyle = 0).acknowledgesDockGlass(choice))
    assertFalse(report.copy(dockGlassEnabled = false).acknowledgesDockGlass(choice))
    assertFalse(report.copy(dockGlassPid = null).acknowledgesDockGlass(choice))
    assertFalse(report.copy(dockGlassCornerStyle = null).acknowledgesDockGlass(choice))
    assertFalse(report.acknowledgesDockGlass(null))
    for (style in listOf(null, -1, 5)) {
      assertNull(RuntimeStatusReport.decode(report.copy(dockGlassCornerStyle = style).encode()))
    }
    assertNull(RuntimeStatusReport.decode(ready.copy(dockGlassCornerStyle = 1).encode()))
    val legacy = report.encode().split('|').take(16).toMutableList().apply { this[0] = "4" }.joinToString("|")
    val decoded = RuntimeStatusReport.decode(legacy)!!
    assertEquals(report.copy(dockGlassCornerStyle = 0), decoded)
    assertFalse(decoded.acknowledgesDockGlass(choice))
    assertTrue(decoded.acknowledgesDockGlass(choice.copy(cornerStyle = os.kei.hyper.home.data.DockGlassCornerStyle.ROUNDED)))
    val noDockV4 = ready.encode().split('|').take(16).toMutableList().apply { this[0] = "4" }.joinToString("|")
    assertEquals(ready, RuntimeStatusReport.decode(noDockV4))
  }

  @Test fun rawDockGeometryRoundTripsWithoutChangingTheSchemaAndAckChecksEveryActiveField() {
    val choice = os.kei.hyper.home.data.DockGlassChoice(true,
      os.kei.hyper.home.data.DockGlassCornerStyle.CUSTOM, 0, -48, 64, -16)
    val report = ready.copy(dockGlassEnabled = true, dockGlassPid = 9000,
      dockGlassStartTicks = 123L, dockGlassCornerStyle = choice.options)
    assertEquals(report, RuntimeStatusReport.decode(report.encode()))
    assertEquals("8", report.encode().substringBefore('|'))
    assertTrue(report.acknowledgesDockGlass(choice))
    for (different in listOf(choice.copy(verticalPaddingDp = 32),
      choice.copy(verticalOffsetDp = 96), choice.copy(customRadiusDp = 24), choice.copy(horizontalPaddingDeltaDp = 24),
      choice.copy(cornerStyle = os.kei.hyper.home.data.DockGlassCornerStyle.PAD))) {
      assertFalse(report.acknowledgesDockGlass(different))
    }
    for (invalid in listOf(5, 1 or (34 shl 3), 4 or (65 shl 9),
      1 or (24 shl 9), 1 or (97 shl 16), 25 shl 24, 1 shl 30)) {
      assertNull(RuntimeStatusReport.decode(report.copy(dockGlassCornerStyle = invalid).encode()))
    }
    val legacy = report.copy(dockGlassCornerStyle = 1)
    assertTrue(legacy.acknowledgesDockGlass(os.kei.hyper.home.data.DockGlassChoice(true)))
    assertFalse(legacy.acknowledgesDockGlass(os.kei.hyper.home.data.DockGlassChoice(true, verticalPaddingDp = 0)))
    assertFalse(legacy.acknowledgesDockGlass(os.kei.hyper.home.data.DockGlassChoice(true, verticalOffsetDp = 1)))
  }

  @Test fun labelAcknowledgementsRoundTripAndOldReportsRemainReadable() {
    for (mode in 0..2) {
      val report = ready.copy(launcherLabelMode = mode)
      assertEquals(report, RuntimeStatusReport.decode(report.encode()))
    }
    val legacy = ready.encode().split('|').take(12).toMutableList().apply { this[0] = "2" }.joinToString("|")
    assertEquals(ready, RuntimeStatusReport.decode(legacy))
    assertNull(RuntimeStatusReport.decode(ready.copy(launcherLabelMode = 3).encode()))
  }

  private val request = RuntimeStatusRequest("6ad7c033-914d-4adc-b7a9-794be04328ad", 0, 100_000)
  private val ready = RuntimeStatusReport(
    request.id, request.userId, 123, 100_500, NativeRuntimeState.READY,
    launcherPid = 9700, launcherStartTicks = 3210,
    refresh = IconRefreshReceipt(55, 1_793_373_931_000),
  )

  @Test
  fun readyObservationRoundTripsWithActualProcessAndCallbackReceipt() {
    assertEquals(ready, RuntimeStatusReport.decode(ready.encode()))
  }

  @Test
  fun iconThemeInventoryTravelsWithTheAuthenticatedObservation() {
    val theme = SystemIconThemeReport(SystemIconThemeReport.Source.THEME, "Day | Night : icons",
      "/data/system/theme/icons", 40, 12, true, "source-inode:3")
    val report = ready.copy(iconTheme = theme)
    assertEquals(report, RuntimeStatusReport.decode(report.encode()))
    val legacy = ready.encode().split('|').take(11).toMutableList().apply { this[0] = "1" }.joinToString("|")
    assertEquals(ready, RuntimeStatusReport.decode(legacy))
    assertNull(SystemIconThemeReport.decode(theme.copy(nightResourceCount = 41).encode()))
  }

  @Test
  fun failureKeepsItsReasonButDoesNotInheritAnOldSuccessfulIdentity() {
    val failed = ready.copy(nativeState = NativeRuntimeState.UNAVAILABLE,
      launcherPid = null, launcherStartTicks = null, refresh = null, detail = "peer | timeout\nreason")
    val decoded = RuntimeStatusReport.decode(failed.encode())!!
    assertEquals(failed, decoded)
    assertNull(decoded.launcherPid)
    assertNull(decoded.launcherStartTicks)
  }

  @Test
  fun readinessRequiresACompleteProcessIdentity() {
    assertNull(RuntimeStatusReport.decode(ready.copy(launcherPid = null).encode()))
    assertNull(RuntimeStatusReport.decode(ready.copy(launcherStartTicks = null).encode()))
  }

  @Test
  fun staleAndForeignRepliesCannotSatisfyTheCurrentRequest() {
    assertTrue(ready.matches(request, 101_000))
    assertFalse(ready.copy(requestId = "previous-request").matches(request, 101_000))
    assertFalse(ready.copy(userId = 10).matches(request, 101_000))
    assertFalse(ready.copy(checkedAtElapsed = 99_999).matches(request, 101_000))
    assertFalse(ready.matches(request, 100_499))
    assertFalse(ready.matches(request, request.startedAtElapsed + RuntimeStatusRequest.REQUEST_LIFETIME_MS + 1))
    assertFalse(ready.matches(request, ready.checkedAtElapsed + RuntimeStatusReport.FRESHNESS_MS + 1))
  }

  @Test
  fun cachedReadinessExpiresAndCannotSurviveAResetElapsedClock() {
    assertTrue(ready.isFresh(ready.checkedAtElapsed + RuntimeStatusReport.FRESHNESS_MS))
    assertFalse(ready.isFresh(ready.checkedAtElapsed + RuntimeStatusReport.FRESHNESS_MS + 1))
    assertFalse(ready.isFresh(100))
  }

  @Test
  fun requestsDoNotReplayAfterExpiryOrWithAFutureClock() {
    assertTrue(request.isPending(request.startedAtElapsed))
    assertTrue(request.isPending(request.startedAtElapsed + RuntimeStatusRequest.REQUEST_LIFETIME_MS))
    assertFalse(request.isPending(request.startedAtElapsed + RuntimeStatusRequest.REQUEST_LIFETIME_MS + 1))
    assertFalse(request.isPending(request.startedAtElapsed - 1))
    assertFalse(request.copy(startedAtElapsed = -1).isPending(0))
  }

  @Test
  fun unsupportedAndMalformedReceiptsDoNotBecomeReadiness() {
    assertNull(RuntimeStatusReport.decode(null))
    assertNull(RuntimeStatusReport.decode(""))
    assertNull(RuntimeStatusReport.decode("999|" + ready.encode().substringAfter('|')))
    assertNull(RuntimeStatusReport.decode(ready.encode().replace("READY", "RUNNING")))
    assertNull(RuntimeStatusReport.decode(ready.encode() + "|extra"))
    assertNull(RuntimeStatusReport.decode(ready.encode() + "not base64!"))
  }
}
