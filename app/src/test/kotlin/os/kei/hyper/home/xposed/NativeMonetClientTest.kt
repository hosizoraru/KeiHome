package os.kei.hyper.home.xposed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.data.LauncherGlassStyle

class NativeMonetClientTest {
  @Test fun independentGlassEncodesAllNinePoliciesAndRequiresBothCapabilitiesBeforeWriting() {
    for (dock in 0..2) for (search in 0..2) {
      val policy = dock or (search shl 2)
      assertTrue(NativeMonetProtocol.validGlassPolicy(policy))
      val command = NativeMonetProtocol.launcherGlassPairRequest(policy)
      assertEquals(24, command.size)
      assertArrayEquals(words(magic, 1, 10, policy, 0, 0), command)
      for (capabilities in 0..127) {
        val output = ByteArrayOutputStream()
        val input = ByteArrayInputStream(greeting(capabilities) + ack())
        var writes = 0
        val accepted = NativeMonetProtocol.exchange(input, output, targetUid, targetUid, command,
          onWriteAttempt = { writes++ })
        val expected = capabilities and 80 == 80
        assertEquals("dock=$dock search=$search caps=$capabilities", expected, accepted)
        assertEquals(if (expected) 1 else 0, writes)
        assertArrayEquals(if (expected) command else byteArrayOf(), output.toByteArray())
        assertEquals(if (expected) 0 else 12, input.available())
      }
    }
  }

  @Test fun independentGlassRejectsNegativeReservedAndHighBitPoliciesBeforeWriting() {
    val invalid = listOf(-1, Int.MIN_VALUE, 3, 7, 11, 12, 13, 14, 15, 16, 64, 256, Int.MAX_VALUE)
    for (policy in invalid) {
      assertFalse("policy=$policy", NativeMonetProtocol.validGlassPolicy(policy))
      assertThrows(IllegalArgumentException::class.java) { NativeMonetProtocol.launcherGlassPairRequest(policy) }
      val output = ByteArrayOutputStream()
      var writes = 0
      assertFalse(NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(127) + ack()),
        output, targetUid, targetUid, words(magic, 1, 10, policy, 0, 0), onWriteAttempt = { writes++ }))
      assertEquals(0, writes)
      assertEquals(0, output.size())
    }
    for (reserved in listOf(words(magic, 1, 10, 10, 1, 0), words(magic, 1, 10, 10, 0, 1),
      words(magic, 1, 10, 10, -1, 0), words(magic, 1, 10, 10, 0, Int.MIN_VALUE))) {
      val output = ByteArrayOutputStream()
      var writes = 0
      assertFalse(NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(127) + ack()),
        output, targetUid, targetUid, reserved, onWriteAttempt = { writes++ }))
      assertEquals(0, writes)
      assertEquals(0, output.size())
    }
  }

  @Test fun independentGlassRejectsTruncatedAndTrailingPayloadBeforeWriting() {
    val command = NativeMonetProtocol.launcherGlassPairRequest(10)
    for (malformed in (0 until command.size).map(command::copyOf) +
      listOf(command + byteArrayOf(0), command + words(0))) {
      val output = ByteArrayOutputStream()
      var writes = 0
      assertFalse("request length=${malformed.size}", NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting(80) + ack()), output, targetUid, targetUid, malformed,
        onWriteAttempt = { writes++ }))
      assertEquals(0, writes)
      assertEquals(0, output.size())
    }
  }

  @Test fun legacyGlassEndpointRejectsPairWithoutWritingAndStillAcceptsOpcodeNine() {
    for (caps in listOf(16, 17, 31, 63)) {
      val output = ByteArrayOutputStream()
      var writes = 0
      var failure: String? = null
      assertFalse(NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(caps) + ack()),
        output, targetUid, targetUid, NativeMonetProtocol.launcherGlassPairRequest(6),
        onWriteAttempt = { writes++ }, onFailure = { failure = it }))
      assertEquals("native independent glass capability unavailable", failure)
      assertEquals(0, output.size())
      assertEquals(0, writes)
      for (style in 0..2) {
        val legacy = NativeMonetProtocol.launcherGlassRequest(style)
        assertArrayEquals(words(magic, 1, 9, style, 0, 0), legacy)
        val legacyOutput = ByteArrayOutputStream()
        assertTrue(exchange(ByteArrayInputStream(greeting(caps) + ack()), legacyOutput, request = legacy))
        assertArrayEquals(legacy, legacyOutput.toByteArray())
      }
    }
  }

  @Test fun pairOnlyGreetingIsInvalidAndCannotAdvertiseOrAuthorizeAnyGlassCommand() {
    for (caps in (64..127).filter { it and 16 == 0 }) {
      for (command in listOf(null, NativeMonetProtocol.launcherGlassRequest(1),
        NativeMonetProtocol.launcherGlassPairRequest(9))) {
        val output = ByteArrayOutputStream()
        var observed: Int? = null
        var writes = 0
        var failure: String? = null
        assertFalse(NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(caps) + ack()),
          output, targetUid, targetUid, command, onGreeting = { observed = it },
          onWriteAttempt = { writes++ }, onFailure = { failure = it }))
        assertEquals("native greeting capabilities invalid", failure)
        assertNull(observed)
        assertEquals(0, writes)
        assertEquals(0, output.size())
      }
    }
  }

  @Test fun partialGlassSupportSendsOnlyDockStyleOnTheAuthenticatedConnection() {
    val command = NativeMonetProtocol.launcherGlassPairRequest(9)
    for ((capabilities, expected) in listOf(
      16 to NativeMonetProtocol.launcherGlassRequest(1),
      80 to command,
      0 to byteArrayOf(),
    )) {
      val output = ByteArrayOutputStream()
      var observed: Int? = null
      val accepted = NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting(capabilities) + ack()), output, targetUid, targetUid,
        request = command, onGreeting = { observed = it }, allowDockOnlyGlass = true,
      )
      assertEquals(capabilities != 0, accepted)
      assertEquals(capabilities, observed)
      assertArrayEquals(expected, output.toByteArray())
    }
  }

  @Test fun independentGlassNeedsACompleteSuccessfulAndCurrentAck() {
    val command = NativeMonetProtocol.launcherGlassPairRequest(6)
    for (length in 0 until 12) assertThrows(EOFException::class.java) {
      exchange(ByteArrayInputStream(greeting(80) + ack().copyOf(length)), ByteArrayOutputStream(), request = command)
    }
    for (reply in listOf(words(magic, 1, 1), words(magic, 2, 0), words(0, 1, 0))) {
      val output = ByteArrayOutputStream()
      assertFalse(exchange(ByteArrayInputStream(greeting(80) + reply), output, request = command))
      assertArrayEquals(command, output.toByteArray())
    }
    var checks = 0
    val output = ByteArrayOutputStream()
    assertFalse(NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(80) + ack()),
      output, targetUid, targetUid, command, isCurrentTarget = { ++checks == 1 }))
    assertArrayEquals(command, output.toByteArray())
  }

  @Test fun failedPairWriteMarksTheAttemptBeforeAnyPartialByteCanEscape() {
    var writes = 0
    val output = object : OutputStream() {
      override fun write(value: Int) {
        assertEquals(1, writes)
        throw InterruptedIOException("partial pair write")
      }
    }
    assertThrows(InterruptedIOException::class.java) {
      NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(80)), output, targetUid, targetUid,
        NativeMonetProtocol.launcherGlassPairRequest(9), onWriteAttempt = { writes++ })
    }
    assertEquals(1, writes)
  }

  @Test fun launcherGlassHasItsOwnCapabilityAndExact24ByteWallpaperMaterialCommand() {
    for (material in LauncherGlassStyle.entries) {
      val style = material.nativeValue
      val command = NativeMonetProtocol.launcherGlassRequest(style)
      assertEquals(24, command.size)
      assertArrayEquals(words(magic, 1, 9, style, 0, 0), command)
      for (capabilities in 1..31) {
        val output = ByteArrayOutputStream()
        var writes = 0
        val accepted = NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(capabilities) + ack()),
          output, targetUid, targetUid, command, onWriteAttempt = { writes++ })
        assertEquals(capabilities and 16 != 0, accepted)
        assertEquals(if (accepted) 1 else 0, writes)
        assertArrayEquals(if (accepted) command else byteArrayOf(), output.toByteArray())
      }
    }
    for (style in listOf(-1, 3, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
      NativeMonetProtocol.launcherGlassRequest(style)
    }
  }

  @Test fun launcherGlassCapabilityIsObservableWithoutPretendingMonetOrStyleWasAcknowledged() {
    for (capabilities in listOf(1, 16, 17, 31, 32, 40, 63)) {
      var observed: Int? = null
      val output = ByteArrayOutputStream()
      assertEquals(capabilities and 1 != 0, NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting(capabilities)), output, targetUid, targetUid, request = null,
        onGreeting = { observed = it }))
      assertEquals(capabilities, observed)
      assertEquals(0, output.size())
      assertEquals(capabilities and 16 != 0, NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting(capabilities)), output, targetUid, targetUid, request = null,
        readinessCapability = NativeMonetProtocol.LAUNCHER_GLASS_CAPABILITY))
    }
    for (current in listOf(false, true)) {
      var observed = false
      assertFalse(NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting(if (current) 64 else 16)), ByteArrayOutputStream(),
        targetUid, targetUid, request = null, isCurrentTarget = { current },
        onGreeting = { observed = true }))
      assertFalse(observed)
    }
  }

  @Test fun launcherGlassRequiresACompleteCurrentAckAndDoesNotPoisonOtherCommands() {
    val command = NativeMonetProtocol.launcherGlassRequest(2)
    for (length in 0 until 12) assertThrows(EOFException::class.java) {
      exchange(ByteArrayInputStream(greeting(16) + ack().copyOf(length)), ByteArrayOutputStream(), request = command)
    }
    var checks = 0
    assertFalse(NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(16) + ack()),
      ByteArrayOutputStream(), targetUid, targetUid, command, isCurrentTarget = { ++checks == 1 }))
    assertFalse(exchange(ByteArrayInputStream(greeting(31) + words(magic, 1, 1)),
      ByteArrayOutputStream(), request = command))
    for (ordinary in listOf(request(), NativeMonetProtocol.themeMaterialsRequest(true),
      NativeMonetProtocol.launcherLabelsRequest(1), NativeMonetProtocol.dockGlassRequest(true))) {
      assertTrue(exchange(ByteArrayInputStream(greeting(31) + ack()), ByteArrayOutputStream(), request = ordinary))
    }
  }

  @Test fun dockGlassUsesIndependentCapabilityAndExactHeader() {
    for (enabled in listOf(false, true)) {
      val command = NativeMonetProtocol.dockGlassRequest(enabled)
      assertArrayEquals(words(magic, 1, 8, if (enabled) 1 else 0, 0, 1), command)
      for (capabilities in 1..31) {
        val output = ByteArrayOutputStream()
        val accepted = NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(capabilities) + ack()),
          output, targetUid, targetUid, command)
        assertEquals(capabilities and 8 != 0, accepted)
        assertArrayEquals(if (accepted) command else byteArrayOf(), output.toByteArray())
      }
    }
    // A Dock-only endpoint cannot claim Monet readiness, and vice versa.
    for (capabilities in listOf(1, 8, 9)) {
      assertEquals(capabilities and 8 != 0, NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting(capabilities)), ByteArrayOutputStream(), targetUid, targetUid,
        request = null, readinessCapability = NativeMonetProtocol.DOCK_GLASS_CAPABILITY))
      assertEquals(capabilities and 1 != 0, exchange(ByteArrayInputStream(greeting(capabilities)),
        ByteArrayOutputStream(), request = null))
    }
  }

  @Test fun dockGlassCarriesBoundedLogicalScreenRadiusWithoutChangingPacketSize() {
    for (corner in listOf(0, 10_240, 65_536)) {
      assertArrayEquals(words(magic, 1, 8, 1, corner, 1), NativeMonetProtocol.dockGlassRequest(true, corner))
    }
    assertThrows(IllegalArgumentException::class.java) { NativeMonetProtocol.dockGlassRequest(true, -1) }
    assertThrows(IllegalArgumentException::class.java) { NativeMonetProtocol.dockGlassRequest(true, 65_537) }
    assertEquals(10_240, DockScreenCorners.quantize(120, 3f))
    assertEquals(0, DockScreenCorners.quantize(120, Float.NaN))
    assertEquals(0, DockScreenCorners.quantize(-1, 3f))
    assertEquals(0, DockScreenCorners.quantize(120, 0f))
  }

  @Test fun bothDockStylesUseTheSameExact24ByteCommandAndTheirOwnAck() {
    for (enabled in listOf(false, true)) for (style in 0..1) {
      val command = NativeMonetProtocol.dockGlassRequest(enabled, 10_240, style)
      assertEquals(24, command.size)
      assertArrayEquals(words(magic, 1, 8, if (enabled) 1 else 0, 10_240, style), command)
      val output = ByteArrayOutputStream()
      assertTrue(exchange(ByteArrayInputStream(greeting(8) + ack()), output, request = command))
      assertArrayEquals(command, output.toByteArray())
      assertFalse(exchange(ByteArrayInputStream(greeting(8) + words(magic, 1, 1)),
        ByteArrayOutputStream(), request = command))
    }
    for (style in listOf(-1, 5, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
      NativeMonetProtocol.dockGlassRequest(true, options = style)
    }
  }

  @Test fun extendedDockOptionsUseOneWordAndRequireBothCapabilitiesBeforeWriting() {
    val base = os.kei.hyper.home.data.DockGlassChoice()
    val options = listOf(
      base.copy(cornerStyle = os.kei.hyper.home.data.DockGlassCornerStyle.PAD).options,
      base.copy(cornerStyle = os.kei.hyper.home.data.DockGlassCornerStyle.CONCENTRIC).options,
      base.copy(cornerStyle = os.kei.hyper.home.data.DockGlassCornerStyle.CUSTOM, customRadiusDp = 64).options,
      base.copy(verticalPaddingDp = 0).options, base.copy(verticalOffsetDp = -48).options,
      base.copy(verticalOffsetDp = 96).options)
    for (enabled in listOf(false, true)) for (value in options) {
      val command = NativeMonetProtocol.dockGlassRequest(enabled, 10_240, value)
      assertArrayEquals(words(magic, 1, 8, if (enabled) 1 else 0, 10_240, value), command)
      for (caps in listOf(8, 31, 32, 40, 63)) {
        val output = ByteArrayOutputStream()
        val accepted = (caps and 40) == 40
        assertEquals(accepted, exchange(ByteArrayInputStream(greeting(caps) + ack()), output, request = command))
        assertArrayEquals(if (accepted) command else byteArrayOf(), output.toByteArray())
      }
      for (badAck in listOf(words(magic, 1, 7), words(magic, 2, 8), words(magic, 1, 8))) {
        assertFalse(exchange(ByteArrayInputStream(greeting(40) + badAck), ByteArrayOutputStream(), request = command))
      }
    }
    val invalid = words(magic, 1, 8, 1, 0, 5)
    val output = ByteArrayOutputStream()
    assertFalse(exchange(ByteArrayInputStream(greeting(40) + ack()), output, request = invalid))
    assertEquals(0, output.size())
  }

  @Test fun dockGlassNeedsCurrentCompleteAckAndRejectionDoesNotPoisonMonet() {
    val command = NativeMonetProtocol.dockGlassRequest(true)
    for (length in 0 until 12) assertThrows(EOFException::class.java) {
      exchange(ByteArrayInputStream(greeting(8) + ack().copyOf(length)), ByteArrayOutputStream(), request = command)
    }
    var checks = 0
    assertFalse(NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(8) + ack()),
      ByteArrayOutputStream(), targetUid, targetUid, command, isCurrentTarget = { ++checks == 1 }))
    assertFalse(exchange(ByteArrayInputStream(greeting(9) + words(magic, 1, 1)),
      ByteArrayOutputStream(), request = command))
    assertTrue(exchange(ByteArrayInputStream(greeting(9) + ack()), ByteArrayOutputStream(), request = request()))
  }

  @Test fun labelCommandsRequireTheirOwnCapabilityAndKeepModesDistinct() {
    for (mode in 0..2) {
      val command = NativeMonetProtocol.launcherLabelsRequest(mode)
      assertArrayEquals(words(magic, 1, 3, mode, 0, 0), command)
      for (capabilities in 1..31) {
        val output = ByteArrayOutputStream()
        val accepted = NativeMonetProtocol.exchange(ByteArrayInputStream(greeting(capabilities) + ack()),
          output, targetUid, targetUid, command)
        assertEquals(capabilities and 4 != 0, accepted)
        assertEquals(if (accepted) command.size else 0, output.size())
      }
    }
    assertThrows(IllegalArgumentException::class.java) { NativeMonetProtocol.launcherLabelsRequest(3) }
  }

  @Test
  fun publishUsesLittleEndianArgbAndVariableLengthAsciiWithoutPadding() {
    val request = NativeMonetProtocol.publishRequest(
      foreground, background, linkedSetOf("com.long", "a"),
    )
    assertArrayEquals(
      hex(
        "4b484d33 01000000 01000000 5d0021ff ffddeaff 02000000 " +
          "01000000 61 08000000 636f6d2e6c6f6e67",
      ),
      request,
    )
  }

  @Test
  fun disableUsesFlagsZeroAndNoColorsOrEntries() {
    assertArrayEquals(
      hex("4b484d33 01000000 00000000 00000000 00000000 00000000"),
      NativeMonetProtocol.disableRequest(),
    )
  }

  @Test
  fun themeMaterialsUsesSeparateOpcodeAndExactLegacySizedHeader() {
    assertArrayEquals(
      hex("4b484d33 01000000 02000000 01000000 00000000 00000000"),
      NativeMonetProtocol.themeMaterialsRequest(true),
    )
    assertArrayEquals(
      hex("4b484d33 01000000 02000000 00000000 00000000 00000000"),
      NativeMonetProtocol.themeMaterialsRequest(false),
    )
    for (enabled in listOf(false, true)) {
      val output = ByteArrayOutputStream()
      val command = NativeMonetProtocol.themeMaterialsRequest(enabled)
      assertTrue(exchange(ByteArrayInputStream(greeting(3) + ack()), output, request = command))
      assertArrayEquals(command, output.toByteArray())
    }
  }

  @Test
  fun legacyGreetingSkipsMaterialCommandButKeepsNormalMonetPublicationWorking() {
    val material = NativeMonetProtocol.themeMaterialsRequest(true)
    val rejectedOutput = ByteArrayOutputStream()
    var writes = 0
    assertFalse(exchange(
      ByteArrayInputStream(greeting() + words(magic, 1, 1)), rejectedOutput, request = material,
      onWriteAttempt = { writes++ },
    ))
    assertEquals(0, rejectedOutput.size())
    assertEquals(0, writes)
    for (ordinary in listOf(request(), NativeMonetProtocol.disableRequest())) {
      val output = ByteArrayOutputStream()
      assertTrue(exchange(ByteArrayInputStream(greeting() + ack()), output, request = ordinary))
      assertArrayEquals(ordinary, output.toByteArray())
    }
  }

  @Test
  fun capabilityBitsAuthorizeOnlyTheirOwnCommandsAndReadinessRequiresMonet() {
    val requests = listOf(
      null to 1,
      request() to 1,
      NativeMonetProtocol.disableRequest() to 1,
      NativeMonetProtocol.themeMaterialsRequest(false) to 2,
      NativeMonetProtocol.themeMaterialsRequest(true) to 2,
      NativeMonetProtocol.launcherLabelsRequest(1) to 4,
      NativeMonetProtocol.dockGlassRequest(false) to 8,
      NativeMonetProtocol.dockGlassRequest(true) to 8,
      NativeMonetProtocol.launcherGlassRequest(0) to 16,
      NativeMonetProtocol.launcherGlassRequest(1) to 16,
      NativeMonetProtocol.launcherGlassRequest(2) to 16,
    )
    for (capabilities in 1..31) {
      for ((request, required) in requests) {
        val output = ByteArrayOutputStream()
        val input = ByteArrayInputStream(greeting(capabilities) + ack())
        val accepted = capabilities and required != 0
        assertEquals("capabilities=$capabilities required=$required request=${request != null}",
          accepted, exchange(input, output, request = request))
        assertArrayEquals(if (accepted && request != null) request else byteArrayOf(), output.toByteArray())
        assertEquals(if (accepted && request != null) 0 else 12, input.available())
      }
    }
  }

  @Test
  fun emptyAndUnknownCapabilityBitsRejectEveryCommandBeforeWriting() {
    val requests = listOf(null, request(), NativeMonetProtocol.disableRequest(),
      NativeMonetProtocol.themeMaterialsRequest(false), NativeMonetProtocol.themeMaterialsRequest(true),
      NativeMonetProtocol.launcherLabelsRequest(0), NativeMonetProtocol.launcherLabelsRequest(1),
      NativeMonetProtocol.dockGlassRequest(false), NativeMonetProtocol.dockGlassRequest(true),
      NativeMonetProtocol.launcherGlassRequest(0), NativeMonetProtocol.launcherGlassRequest(2))
    for (capabilities in listOf(64, 65, 128, Int.MIN_VALUE, -1)) {
      for (request in requests) {
        val output = ByteArrayOutputStream()
        var failure: String? = null
        assertFalse(NativeMonetProtocol.exchange(
          ByteArrayInputStream(greeting(capabilities) + ack()), output,
          targetUid, targetUid, request, onFailure = { failure = it },
        ))
        assertEquals(0, output.size())
        assertEquals("native greeting capabilities invalid", failure)
      }
    }
  }

  @Test fun authenticatedZeroCapabilitiesAreReportedWithoutSendingAnyCommand() {
    val requests = listOf(null, request(), NativeMonetProtocol.disableRequest(),
      NativeMonetProtocol.themeMaterialsRequest(false), NativeMonetProtocol.launcherLabelsRequest(1),
      NativeMonetProtocol.dockGlassRequest(true), NativeMonetProtocol.launcherGlassRequest(2))
    for (request in requests) {
      val output = ByteArrayOutputStream()
      var reported: Int? = null
      var writes = 0
      var failure: String? = null
      assertFalse(NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting(0) + ack()), output, targetUid, targetUid, request,
        onGreeting = { reported = it }, onWriteAttempt = { writes++ }, onFailure = { failure = it },
      ))
      assertEquals(0, reported)
      assertEquals(0, output.size())
      assertEquals(0, writes)
      assertTrue(failure.orEmpty().contains("capability unavailable"))
    }
    var reported = false
    assertFalse(NativeMonetProtocol.exchange(
      ByteArrayInputStream(greeting(0)), ByteArrayOutputStream(), targetUid + 1, targetUid, null,
      onGreeting = { reported = true },
    ))
    assertFalse("A zero mask must still authenticate the peer", reported)
  }

  @Test
  fun capablePeerRejectingMaterialCommandDoesNotPoisonSubsequentMonetExchanges() {
    val material = NativeMonetProtocol.themeMaterialsRequest(true)
    val rejectedOutput = ByteArrayOutputStream()
    assertFalse(exchange(
      ByteArrayInputStream(greeting(3) + words(magic, 1, 1)), rejectedOutput, request = material,
    ))
    assertArrayEquals(material, rejectedOutput.toByteArray())
    for (ordinary in listOf(request(), NativeMonetProtocol.disableRequest())) {
      assertTrue(exchange(ByteArrayInputStream(greeting(3) + ack()),
        ByteArrayOutputStream(), request = ordinary))
    }
  }

  @Test
  fun malformedRequestCannotBorrowAnotherCommandsCapability() {
    val unknown = NativeMonetProtocol.themeMaterialsRequest(true).also {
      ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 3)
    }
    for (request in listOf(byteArrayOf(), unknown.copyOf(12), unknown)) {
      val output = ByteArrayOutputStream()
      assertFalse(exchange(ByteArrayInputStream(greeting(3) + ack()), output, request = request))
      assertEquals(0, output.size())
    }
  }

  @Test
  fun materialCommandRequiresCompleteAckAndCurrentLauncherJustLikePolicy() {
    val material = NativeMonetProtocol.themeMaterialsRequest(true)
    for (length in 0 until 12) {
      var writes = 0
      assertThrows(EOFException::class.java) {
        exchange(ByteArrayInputStream(greeting(2) + ack().copyOf(length)),
          ByteArrayOutputStream(), request = material, onWriteAttempt = { writes++ })
      }
      assertEquals(1, writes)
    }
    var checks = 0
    var writes = 0
    assertFalse(NativeMonetProtocol.exchange(
      ByteArrayInputStream(greeting(2) + ack()), ByteArrayOutputStream(),
      targetUid, targetUid, material, isCurrentTarget = { ++checks == 1 },
      onWriteAttempt = { writes++ },
    ))
    assertEquals(1, writes)
  }

  @Test
  fun failedRequestWriteIsMarkedBeforeItCanPartiallyReachNative() {
    var writes = 0
    val output = object : OutputStream() {
      override fun write(value: Int) {
        assertEquals(1, writes)
        throw InterruptedIOException("Partial request write")
      }
    }
    assertThrows(InterruptedIOException::class.java) {
      NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting(2)), output, targetUid, targetUid,
        NativeMonetProtocol.themeMaterialsRequest(true), onWriteAttempt = { writes++ },
      )
    }
    assertEquals(1, writes)
  }

  @Test
  fun materialLeaseReconcilesLostAckButNotPreflightRejection() {
    val process = NativeMonetProcessIdentity(42, 100L)
    val lease = ThemeMaterialLease()
    val output = ByteArrayOutputStream()
    fun sync(enabled: Boolean, capabilities: Int = 2, response: ByteArray = ack()): Boolean =
      lease.synchronize(enabled, 0, { 0L }, { true }) { onWriteAttempted ->
        try {
          if (NativeMonetProtocol.exchange(
              ByteArrayInputStream(greeting(capabilities) + response), output,
              targetUid, targetUid, NativeMonetProtocol.themeMaterialsRequest(enabled),
              onWriteAttempt = onWriteAttempted,
            )) process else null
        } catch (_: EOFException) {
          null
        }
      }
    assertFalse(sync(false))
    output.reset()
    assertFalse(sync(true, capabilities = 1))
    assertEquals(0, output.size())
    assertFalse(sync(false))
    output.reset()
    assertFalse(sync(true, response = byteArrayOf()))
    assertArrayEquals(NativeMonetProtocol.themeMaterialsRequest(true), output.toByteArray())
    assertTrue(sync(false))
    repeat(10) { assertFalse(sync(false)) }
  }

  @Test
  fun publishRejectsInvalidColorsAndNeverTruncatesOversizedOrNonAsciiDenySets() {
    assertNull(NativeMonetProtocol.publishRequest(0x0021005d, background, emptySet()))
    assertNull(NativeMonetProtocol.publishRequest(foreground, 0x80eaddff.toInt(), emptySet()))
    assertNull(NativeMonetProtocol.publishRequest(background, background, emptySet()))
    val maximum = (1..512).map { "com.example.p$it" }.toSet()
    assertNotNull(NativeMonetProtocol.publishRequest(foreground, background, maximum))
    assertNull(NativeMonetProtocol.publishRequest(foreground, background, maximum + "extra"))
    assertNotNull(NativeMonetProtocol.publishRequest(foreground, background, setOf("a".repeat(255))))
    for (invalid in listOf("", "a".repeat(256), "com.中文", "com.é", "com.\u0000app",
      "com.\napp", "com. app", "com.\u007fapp", "com..app", "com.", ".com", "1com.app",
      "com.1app", "com._app", "com.app/name")) {
      assertNull(invalid, NativeMonetProtocol.publishRequest(foreground, background, setOf(invalid)))
    }
  }

  @Test
  fun readinessAuthenticatesGreetingAndClosesWithoutRequestOrAck() {
    val output = ByteArrayOutputStream()
    val input = ByteArrayInputStream(greeting())
    var writes = 0
    assertTrue(exchange(input, output, request = null, onWriteAttempt = { writes++ }))
    assertEquals(0, output.size())
    assertEquals(0, writes)
    assertEquals(0, input.available())
  }

  @Test
  fun wrongPeerUidIsRejectedBeforeReadingOrWriting() {
    val input = ByteArrayInputStream(greeting())
    val output = ByteArrayOutputStream()
    assertFalse(exchange(input, output, peerUid = targetUid + 100_000))
    assertEquals(16, input.available())
    assertEquals(0, output.size())
  }

  @Test
  fun greetingMustMatchMagicVersionFullUidAndCapabilityBeforeAnyRequest() {
    val invalidGreetings = listOf(
      words(0x334d484a, 1, targetUid, 1),
      words(magic, 2, targetUid, 1),
      words(magic, 1, targetUid + 100_000, 1),
      words(magic, 1, targetUid, 0),
      words(magic, 1, targetUid, 2),
      words(magic, 1, -1, 1),
      ByteBuffer.allocate(16).putInt(magic).putInt(1).putInt(targetUid).putInt(1).array(),
    )
    for (greeting in invalidGreetings) {
      val output = ByteArrayOutputStream()
      assertFalse(exchange(ByteArrayInputStream(greeting + ack()), output))
      assertEquals(0, output.size())
    }
  }

  @Test
  fun userSwitchBetweenConnectionAndGreetingPreventsRequest() {
    val output = ByteArrayOutputStream()
    var writes = 0
    assertFalse(NativeMonetProtocol.exchange(
      ByteArrayInputStream(greeting()), output, targetUid, targetUid, request(),
      isCurrentTarget = { false }, onWriteAttempt = { writes++ },
    ))
    assertEquals(0, output.size())
    assertEquals(0, writes)
  }

  @Test
  fun fragmentedGreetingAndAckAreFullyReadAndRequestIsWrittenExactlyOnce() {
    val output = ByteArrayOutputStream()
    val response = greeting() + ack()
    val input = object : ByteArrayInputStream(response) {
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (pos < 16) assertEquals(0, output.size())
        else assertArrayEquals(request(), output.toByteArray())
        return super.read(bytes, offset, minOf(1, length))
      }
    }
    var ioChecks = 0
    assertTrue(NativeMonetProtocol.exchange(
      input, output, targetUid, targetUid, request(), beforeIo = { ioChecks++ },
    ))
    assertArrayEquals(request(), output.toByteArray())
    // One deadline check per partial byte read, plus write and flush.
    assertEquals(16 + 12 + 2, ioChecks)
    assertEquals(0, input.available())
  }

  @Test
  fun aRequestIsSuccessfulOnlyAfterExactSuccessAckIncludingDisable() {
    for (invalidAck in listOf(
      words(magic, 1, 1), words(magic, 1, -1), words(magic, 2, 0), words(0, 1, 0),
    )) {
      val output = ByteArrayOutputStream()
      assertFalse(exchange(ByteArrayInputStream(greeting() + invalidAck), output))
      assertArrayEquals(request(), output.toByteArray())
    }
    val output = ByteArrayOutputStream()
    assertTrue(exchange(
      ByteArrayInputStream(greeting() + ack()), output, request = NativeMonetProtocol.disableRequest(),
    ))
    assertArrayEquals(NativeMonetProtocol.disableRequest(), output.toByteArray())
  }

  @Test
  fun rejectionDiagnosticsIdentifyTheFailedGreetingFieldWithoutPublishing() {
    for ((frame, expected) in listOf(
      words(0, 1, targetUid, 1) to "magic",
      words(magic, 2, targetUid, 1) to "protocol",
      words(magic, 1, targetUid + 100_000, 1) to "UID",
      words(magic, 1, targetUid, 2) to "Monet capability",
    )) {
      val output = ByteArrayOutputStream()
      val failures = mutableListOf<String>()
      assertFalse(NativeMonetProtocol.exchange(
        ByteArrayInputStream(frame + ack()), output, targetUid, targetUid, request(),
        onFailure = failures::add,
      ))
      assertEquals(0, output.size())
      assertEquals(1, failures.size)
      assertTrue(failures.single(), failures.single().contains(expected))
    }
  }

  @Test
  fun successfulAckDoesNotAuthorizeAWriteAfterLauncherRetirement() {
    val output = ByteArrayOutputStream()
    var checks = 0
    var failure: String? = null
    assertFalse(NativeMonetProtocol.exchange(
      ByteArrayInputStream(greeting() + ack()), output, targetUid, targetUid, request(),
      isCurrentTarget = { ++checks == 1 }, onFailure = { failure = it },
    ))
    assertArrayEquals(request(), output.toByteArray())
    assertEquals(2, checks)
    assertEquals("launcher changed after policy ACK", failure)
  }

  @Test
  fun nativePolicyDenialIsNotConfusedWithInvalidAckOrSuccessfulPublication() {
    for ((reply, expected) in listOf(
      words(magic, 1, 1) to "native policy rejected: status=1",
      words(magic, 1, -1) to "native policy rejected: status=-1",
      words(magic, 2, 0) to "native policy ACK identity mismatch",
    )) {
      var failure: String? = null
      val output = ByteArrayOutputStream()
      assertFalse(NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting() + reply), output, targetUid, targetUid, request(),
        onFailure = { failure = it },
      ))
      assertEquals(expected, failure)
      assertArrayEquals(request(), output.toByteArray())
    }
  }

  @Test
  fun failureDiagnosticsUnwrapHiddenApiExceptionsWithoutLeakingCredentialsOrNewlines() {
    val cause = SecurityException("Permission denied\nAuthorization: Bearer do-not-print; token=private-value")
    val message = nativeMonetFailure("broker identity", ExecutionException(InvocationTargetException(cause)))
    assertTrue(message.startsWith("broker identity: SecurityException: Permission denied"))
    assertFalse(message.contains("do-not-print"))
    assertFalse(message.contains("private-value"))
    assertFalse(message.contains('\n'))
    assertTrue(message.contains("[redacted]"))
  }

  @Test
  fun truncatedGreetingNeverPublishesAndTruncatedAckNeverSucceeds() {
    for (length in 0 until 16) {
      val output = ByteArrayOutputStream()
      assertThrows(EOFException::class.java) {
        exchange(ByteArrayInputStream(greeting().copyOf(length)), output)
      }
      assertEquals(0, output.size())
    }
    for (length in 0 until 12) {
      val output = ByteArrayOutputStream()
      assertThrows(EOFException::class.java) {
        exchange(ByteArrayInputStream(greeting() + ack().copyOf(length)), output)
      }
      assertArrayEquals(request(), output.toByteArray())
    }
  }

  @Test
  fun readTimeoutPropagatesWithoutRetryOrRequest() {
    var reads = 0
    val input = object : InputStream() {
      override fun read(): Int {
        reads++
        throw SocketTimeoutException()
      }
    }
    val output = ByteArrayOutputStream()
    assertThrows(SocketTimeoutException::class.java) { exchange(input, output) }
    assertEquals(1, reads)
    assertEquals(0, output.size())
  }

  @Test
  fun cancellationAfterGreetingPreventsRequest() {
    var checks = 0
    var writes = 0
    val output = ByteArrayOutputStream()
    assertThrows(InterruptedIOException::class.java) {
      NativeMonetProtocol.exchange(
        ByteArrayInputStream(greeting()), output, targetUid, targetUid, request(),
        beforeIo = { if (++checks == 2) throw InterruptedIOException() },
        onWriteAttempt = { writes++ },
      )
    }
    assertEquals(0, output.size())
    assertEquals(0, writes)
  }

  @Test
  fun stopBeforeFirstExchangeIsIdempotentAndExplicitStartReopens() {
    val worker = NativeMonetWorker()
    assertFalse(worker.awaitStopped(System.nanoTime()))
    worker.stop()
    worker.stop()
    assertTrue(worker.awaitStopped(System.nanoTime()))
    assertFalse(worker.submit(FutureTask { true }) {})
    assertTrue(worker.start())
    val task = FutureTask { true }
    try {
      assertTrue(worker.submit(task) {})
      assertTrue(task.get(1, TimeUnit.SECONDS))
      worker.release(task)
    } finally {
      worker.stop()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  @Test
  fun stopClosesInFlightAndQueuedExchangesAndJoinsTheirWorker() {
    val worker = NativeMonetWorker()
    val entered = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val workerThread = AtomicReference<Thread>()
    val queuedRuns = AtomicInteger()
    val queuedCloses = AtomicInteger()
    val running = FutureTask {
      workerThread.set(Thread.currentThread())
      entered.countDown()
      awaitUninterruptibly(closed)
      true
    }
    val queued = FutureTask { queuedRuns.incrementAndGet(); true }
    try {
      assertTrue(worker.submit(running) { closed.countDown() })
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      assertTrue(worker.submit(queued) { queuedCloses.incrementAndGet() })
      worker.stop()
      assertEquals(0L, closed.count)
      assertEquals(1, queuedCloses.get())
      assertTrue(worker.awaitStopped(deadline()))
      assertFalse(workerThread.get().isAlive)
      assertTrue(queued.isCancelled)
      assertEquals(0, queuedRuns.get())
    } finally {
      closed.countDown()
      worker.stop()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  @Test
  fun cancelledFutureDoesNotPermitRestartWhileOldWorkerIgnoresInterruption() {
    val worker = NativeMonetWorker()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val oldThread = AtomicReference<Thread>()
    val closes = AtomicInteger()
    val blocked = FutureTask {
      oldThread.set(Thread.currentThread())
      entered.countDown()
      awaitUninterruptibly(release)
      true
    }
    try {
      assertTrue(worker.submit(blocked) { closes.incrementAndGet() })
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      worker.stop()
      assertTrue(blocked.isCancelled)
      assertEquals(1, closes.get())
      assertFalse(worker.awaitStopped(deadline(20)))
      assertFalse(worker.start())
      assertFalse(worker.submit(FutureTask { true }) {})

      release.countDown()
      assertTrue(worker.awaitStopped(deadline()))
      assertFalse(oldThread.get().isAlive)
      assertTrue(worker.start())
      val next = FutureTask { true }
      assertTrue(worker.submit(next) {})
      assertTrue(next.get(1, TimeUnit.SECONDS))
      worker.release(next)
    } finally {
      release.countDown()
      worker.stop()
      assertTrue(worker.awaitStopped(deadline()))
    }
  }

  @Test
  fun workersAreOwnedByEachClientLifecycle() {
    val retired = NativeMonetWorker()
    val active = NativeMonetWorker()
    retired.stop()
    assertTrue(retired.awaitStopped(System.nanoTime()))
    val task = FutureTask { true }
    try {
      assertTrue(active.submit(task) {})
      assertTrue(task.get(1, TimeUnit.SECONDS))
      active.release(task)
    } finally {
      active.stop()
      assertTrue(active.awaitStopped(deadline()))
    }
  }

  private fun exchange(
    input: InputStream,
    output: ByteArrayOutputStream,
    peerUid: Int = targetUid,
    request: ByteArray? = request(),
    onWriteAttempt: () -> Unit = {},
  ): Boolean = NativeMonetProtocol.exchange(
    input, output, targetUid, peerUid, request, onWriteAttempt = onWriteAttempt,
  )

  private fun request(): ByteArray =
    requireNotNull(NativeMonetProtocol.publishRequest(foreground, background, setOf("com.example")))

  private fun greeting(capabilities: Int = 1): ByteArray = words(magic, 1, targetUid, capabilities)

  private fun ack(): ByteArray = words(magic, 1, 0)

  private fun words(vararg values: Int): ByteArray =
    ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
      values.forEach { putInt(it) }
    }.array()

  private fun hex(value: String): ByteArray =
    value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  private fun deadline(timeoutMs: Long = 1_000): Long =
    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

  private fun awaitUninterruptibly(latch: CountDownLatch) {
    var interrupted = false
    while (true) {
      try {
        latch.await()
        break
      } catch (_: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
  }

  private companion object {
    const val magic = 0x334d484b
    const val targetUid = 1_010_123
    val foreground = 0xff21005d.toInt()
    val background = 0xffeaddff.toInt()
  }
}
