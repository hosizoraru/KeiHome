package os.kei.hyper.home.xposed

import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.data.LauncherGlassPreferences
import os.kei.hyper.home.data.LauncherGlassStyle
import os.kei.hyper.home.status.IconRefreshReceipt

class NativeRuntimeIdentitySelectionTest {
  private val a = NativeMonetProcessIdentity(9000, 123L)
  private val b = a.copy(startTicks = 456L)
  private val now = 200L
  private val receipt = IconRefreshReceipt(1, 150L)

  private fun probe(selection: NativeRuntimeIdentitySelection, process: NativeMonetProcessIdentity,
    capabilities: Int, required: Int = NativeMonetProtocol.MONET_CAPABILITY, peerUid: Int = 10123): Boolean {
    val greeting = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
      .putInt(0x334d484b).putInt(1).putInt(10123).putInt(capabilities).array()
    return NativeMonetProtocol.exchange(ByteArrayInputStream(greeting), ByteArrayOutputStream(),
      targetUid = 10123, peerUid = peerUid, request = null, readinessCapability = required,
      onGreeting = { selection.observe(NativeMonetGreeting(process, it)) })
  }

  private class Leases(process: NativeMonetProcessIdentity) {
    val labels = LauncherLabelLease()
    val dock = DockGlassLease()
    val glass = LauncherGlassLease()
    init {
      labels.synchronize(1, 0, { 100L }, { true }) { attempted -> attempted(); process }
      dock.synchronize(enabled = true, user = 0, now = { 100L }, isCurrent = { true },
        cornerQ8 = 10957, cornerStyle = 1) { attempted -> attempted(); process }
      glass.synchronize(2, 0, { 100L }, { true }) { attempted -> attempted(); process }
    }
  }

  private fun assertEarlierAcksDiscarded(selection: NativeRuntimeIdentitySelection, leases: Leases) {
    assertTrue(selection.processChanged)
    val process = selection.acknowledgementProcess
    assertNull(process)
    assertNull(leases.labels.acknowledgedMode(process, now))
    assertNull(leases.dock.acknowledgedEnabled(process, 0, now))
    assertNull(leases.dock.acknowledgedStyle(process, 0, now))
    assertNull(leases.glass.acknowledgedStyle(process, 0, now))
    assertNull(selection.retainEarlierAcknowledgement(receipt))
  }

  @Test fun laterAuthenticatedDockProbeKeepsBAndDiscardsAllEarlierNativeReceipts() {
    for (next in listOf(b, a.copy(pid = 9001))) {
      val leases = Leases(a)
      val selection = NativeRuntimeIdentitySelection()
      assertTrue(probe(selection, a, 31))
      assertEquals(a, selection.acknowledgementProcess)
      assertEquals(1, leases.labels.acknowledgedMode(selection.acknowledgementProcess, now))
      assertEquals(true, leases.dock.acknowledgedEnabled(selection.acknowledgementProcess, 0, now))
      assertEquals(1, leases.dock.acknowledgedStyle(selection.acknowledgementProcess, 0, now))
      assertEquals(2, leases.glass.acknowledgedStyle(selection.acknowledgementProcess, 0, now))
      assertEquals(receipt, selection.retainEarlierAcknowledgement(receipt))
      assertTrue(probe(selection, next, 31, NativeMonetProtocol.DOCK_GLASS_CAPABILITY))
      assertEquals(next, selection.process)
      assertEquals(NativeMonetGreeting(next, 31), selection.greeting)
      assertEarlierAcksDiscarded(selection, leases)
    }
  }

  @Test fun authenticatedBStillReplacesAWhenBRejectsTheOptionalDockCapability() {
    val selection = NativeRuntimeIdentitySelection()
    val leases = Leases(a)
    assertTrue(probe(selection, a, 31))
    assertFalse(probe(selection, b, 1, NativeMonetProtocol.DOCK_GLASS_CAPABILITY))
    assertEquals(b, selection.process)
    assertTrue(selection.greeting!!.monetAvailable)
    assertFalse(selection.greeting!!.launcherGlassAvailable)
    assertEarlierAcksDiscarded(selection, leases)
  }

  @Test fun glassOnlyBIsRetainedWithoutPretendingMonetIsAvailable() {
    val selection = NativeRuntimeIdentitySelection()
    assertTrue(probe(selection, a, 31))
    assertFalse(probe(selection, b, 16, NativeMonetProtocol.DOCK_GLASS_CAPABILITY))
    assertEquals(b, selection.process)
    assertFalse(selection.greeting!!.monetAvailable)
    assertTrue(selection.greeting!!.launcherGlassAvailable)
    assertEarlierAcksDiscarded(selection, Leases(a))
  }

  @Test fun repeatedSameProcessGreetingsDoNotInvalidateCurrentAcksOrRequireGlassCapability() {
    val selection = NativeRuntimeIdentitySelection()
    val leases = Leases(a)
    repeat(3) {
      assertTrue(probe(selection, a, 1))
      assertFalse(selection.processChanged)
      assertTrue(selection.greeting!!.monetAvailable)
      assertFalse(selection.greeting!!.launcherGlassAvailable)
      assertEquals(a, selection.acknowledgementProcess)
      assertEquals(1, leases.labels.acknowledgedMode(selection.acknowledgementProcess, now))
      assertEquals(receipt, selection.retainEarlierAcknowledgement(receipt))
    }
  }

  @Test fun failedUnauthenticatedProbeCannotReplaceTheLastAuthenticatedProcess() {
    val selection = NativeRuntimeIdentitySelection()
    assertTrue(probe(selection, b, 31))
    assertFalse(probe(selection, a, 31, peerUid = 10124))
    selection.observe(null)
    assertEquals(b, selection.process)
    assertEquals(b, selection.acknowledgementProcess)
    assertFalse(selection.processChanged)
  }

  @Test fun seeingAAgainCannotResurrectItsEarlierAcksInTheSameTick() {
    val selection = NativeRuntimeIdentitySelection()
    for (process in listOf(a, b, a)) assertTrue(probe(selection, process, 31))
    assertEquals(a, selection.process)
    assertEarlierAcksDiscarded(selection, Leases(a))
  }

  @Test fun savedChoiceReplaysToBAndSubsequentUnchangedRenewalsRemainQuiet() {
    val values = mutableMapOf<String, Any>(LauncherGlassPreferences.KEY to 2)
    val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
      arrayOf(SharedPreferences::class.java)) { _, method, _ ->
      if (method.name == "getAll") values.toMap() else error("Preference must not be modified")
    } as SharedPreferences
    val saved = LauncherGlassPreferences.read(preferences)
    assertEquals(LauncherGlassStyle.DARK, saved)
    val lease = LauncherGlassLease()
    var refreshes = 0
    fun synchronize(process: NativeMonetProcessIdentity, time: Long) {
      if (lease.synchronize(LauncherGlassPreferences.read(preferences).nativeValue, 0, { time }, { true }) {
          attempted -> attempted(); process
        }) refreshes++
    }
    synchronize(a, 100)
    val changed = NativeRuntimeIdentitySelection()
    probe(changed, a, 31)
    probe(changed, b, 31, NativeMonetProtocol.DOCK_GLASS_CAPABILITY)
    assertNull(lease.acknowledgedStyle(changed.acknowledgementProcess, 0, now))
    assertEquals(saved, LauncherGlassPreferences.read(preferences))
    val nextTick = NativeRuntimeIdentitySelection()
    probe(nextTick, b, 31)
    assertNull(lease.acknowledgedStyle(nextTick.acknowledgementProcess, 0, now))
    synchronize(b, 300)
    assertEquals(2, lease.acknowledgedStyle(nextTick.acknowledgementProcess, 0, 300))
    assertEquals(2, refreshes)
    repeat(3) { synchronize(b, 400L + it) }
    assertEquals(2, refreshes)
    assertEquals(mapOf(LauncherGlassPreferences.KEY to 2), values)
  }
}
