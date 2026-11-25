package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherRebuildScheduleTest {
  @Test fun aSwitchWhileTheScreenIsOnWaitsForTheNextScreenOff() {
    val schedule = LauncherRebuildSchedule(delayMs = 100)
    assertNull(schedule.archiveReplaced(interactive = true))
    assertEquals(100L, schedule.screenOff())
    assertTrue(schedule.due(interactive = false))
    // One rebuild per change.
    assertFalse(schedule.due(interactive = false))
    assertNull(schedule.screenOff())
  }

  @Test fun aSwitchWhileTheScreenIsOffRebuildsAfterTheDelay() {
    val schedule = LauncherRebuildSchedule(delayMs = 100)
    assertEquals(100L, schedule.archiveReplaced(interactive = false))
    assertTrue(schedule.due(interactive = false))
  }

  @Test fun turningTheScreenOnKeepsTheChangeForTheNextScreenOff() {
    val schedule = LauncherRebuildSchedule(delayMs = 100)
    schedule.archiveReplaced(interactive = true)
    assertEquals(100L, schedule.screenOff())
    // The user woke the phone before the delay ended: no rebuild in front of them.
    assertFalse(schedule.due(interactive = true))
    assertTrue(schedule.pending)
    assertEquals(100L, schedule.screenOff())
    assertTrue(schedule.due(interactive = false))
  }

  @Test fun noChangeMeansNoRebuild() {
    val schedule = LauncherRebuildSchedule()
    assertNull(schedule.screenOff())
    assertFalse(schedule.due(interactive = false))
  }

  @Test fun aFailedRebuildIsRetriedAtTheNextScreenOff() {
    val schedule = LauncherRebuildSchedule(delayMs = 100)
    schedule.archiveReplaced(interactive = false)
    assertTrue(schedule.due(interactive = false))
    schedule.retry()
    assertEquals(100L, schedule.screenOff())
  }

  @Test fun anAppliedImportRebuildsAtOnceWhileHomeIsInTheBackground() {
    val schedule = LauncherRebuildSchedule(delayMs = 100)
    assertEquals(0L, schedule.archiveReplaced(interactive = true, urgent = true))
    assertTrue(schedule.due(interactive = true))
  }

  @Test fun anAppliedImportRebuildsAtOnceEvenWhileHomeIsInView() {
    // Themes' apply has just reloaded Home; completing it now is part of the same apply.
    val schedule = LauncherRebuildSchedule(delayMs = 100)
    assertEquals(0L, schedule.archiveReplaced(interactive = true, urgent = true))
    assertTrue(schedule.due(interactive = true))
  }

  @Test fun aDarkModeSwitchNeverRebuildsWhileTheScreenIsOn() {
    val schedule = LauncherRebuildSchedule(delayMs = 100)
    assertNull(schedule.archiveReplaced(interactive = true, urgent = false))
    assertFalse(schedule.due(interactive = true))
  }

  @Test fun anUrgentRebuildCanceledByTheScreenWaitsForTheNextScreenOff() {
    val schedule = LauncherRebuildSchedule(delayMs = 100)
    schedule.archiveReplaced(interactive = true, urgent = true)
    schedule.screenOn()
    assertFalse(schedule.due(interactive = true))
    assertEquals(100L, schedule.screenOff())
    assertTrue(schedule.due(interactive = false))
  }
}
