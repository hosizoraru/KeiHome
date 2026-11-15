package os.kei.hyper.home.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.iconpack.IconPackEntry

class AppsInspectionStateDeviceTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()

  @Test fun sourceChangeHidesInspectionInTheFirstCompositionEvenWithRetainedOldRows() {
    val old = row("old entry")
    val updated = row("new entry")
    val host = Host(ready(old))
    start(host)
    open(host, old)
    val closingOldSheet = host.inspection.dismissFinished

    change {
      host.revision.value = "new-theme"
      host.display.value = pending(old)
      host.requireClosed.value = true
    }
    rule.runOnIdle {
      assertTrue(host.display.value.loading)
      assertNull(host.inspection.row)
      assertFalse(host.inspection.show)
    }
    change { host.inspection.open(old) }
    rule.runOnIdle { assertNull(host.inspection.row) }
    change { host.display.value = ready(updated) }
    rule.runOnIdle {
      assertNull(host.inspection.row)
      assertFalse(host.inspection.show)
    }

    change { host.requireClosed.value = false }
    open(host, updated)
    change { closingOldSheet() }
    rule.runOnIdle {
      assertTrue(host.inspection.show)
      assertSame(updated, host.inspection.row)
    }
    change {
      host.revision.value = null
      host.requireClosed.value = true
    }
    rule.runOnIdle { assertNull(host.inspection.row) }
  }

  @Test fun metadataReplacementResolvesCurrentRowByProfileAndPackageIdentity() {
    val personal = row("old metadata")
    val work = row("work metadata", userId = 10)
    val updated = row("new metadata").copy(app = personal.app.copy(label = "Updated label", versionCode = 2),
      dynamic = true)
    assertNotSame(personal.entry, updated.entry)
    val host = Host(ready(personal, work))
    start(host)
    open(host, personal)
    change { host.display.value = ready(work, updated) }
    rule.runOnIdle {
      assertTrue(host.inspection.show)
      assertSame(updated, host.inspection.row)
      assertSame(updated.entry, host.inspection.row?.entry)
    }
  }

  @Test fun removalClearsSelectionSoReinstallDoesNotReopenTheSheet() {
    val personal = row("personal")
    val work = row("work", userId = 10)
    val host = Host(ready(personal, work))
    start(host)
    open(host, personal)
    change {
      host.display.value = ready(work)
      host.requireClosed.value = true
    }
    rule.runOnIdle {
      assertNull(host.inspection.row)
      assertFalse(host.inspection.show)
    }
    change { host.display.value = ready(work, personal) }
    rule.runOnIdle {
      assertNull(host.inspection.row)
      assertFalse(host.inspection.show)
    }
  }

  @Test fun pendingMetadataProjectionDoesNotPermanentlyDiscardSelection() {
    val old = row("old metadata")
    val updated = row("new metadata")
    val host = Host(ready(old))
    start(host)
    open(host, old)
    change { host.display.value = AppsBrowserDisplay(Any(), null) }
    rule.runOnIdle {
      assertNull(host.inspection.row)
      assertFalse(host.inspection.show)
    }
    change { host.display.value = ready(updated) }
    rule.runOnIdle {
      assertTrue(host.inspection.show)
      assertSame(updated, host.inspection.row)
    }
    change { host.inspection.dismiss() }
    change { host.inspection.dismissFinished() }
    change { host.display.value = ready(old) }
    rule.runOnIdle {
      assertNull(host.inspection.row)
      assertFalse(host.inspection.show)
    }
  }

  private fun start(host: Host) {
    rule.setContent {
      val inspection = rememberAppsInspectionState(host.revision.value, host.display.value)
      if (host.requireClosed.value) {
        // Check during composition, not after effects: a one-frame old-entry/new-source pair fails.
        assertNull(inspection.row)
        assertFalse(inspection.show)
      }
      SideEffect { host.inspection = inspection }
    }
    rule.waitForIdle()
  }

  private fun open(host: Host, row: IconBrowserRow) {
    change { host.inspection.open(row) }
    rule.runOnIdle {
      assertTrue(host.inspection.show)
      assertSame(row, host.inspection.row)
    }
  }

  private fun change(action: () -> Unit) {
    rule.runOnIdle(action)
    rule.waitForIdle()
  }

  private class Host(display: AppsBrowserDisplay) {
    val revision = mutableStateOf<String?>("old-theme")
    val display = mutableStateOf(display, referentialEqualityPolicy())
    val requireClosed = mutableStateOf(false)
    lateinit var inspection: AppsInspectionUi
  }

  private fun row(entry: String, userId: Long = 0) = IconBrowserRow(
    InstalledIconCapability("App", "org.keihome.inspection", userId, userId == 0L,
      IconCapabilityStatus.DETECTED, IconCapabilityStatus.NOT_DETECTED),
    IconPackEntry(entry),
  )

  private fun ready(vararg rows: IconBrowserRow): AppsBrowserDisplay {
    val request = Any()
    return AppsBrowserDisplay(request, AppsBrowserResult(request, rows.toList(), rows.size))
  }

  private fun pending(vararg rows: IconBrowserRow): AppsBrowserDisplay {
    val dataset = Any()
    return AppsBrowserDisplay(Any(), AppsBrowserResult(Any(), rows.toList(), rows.size, dataset = dataset),
      dataset = dataset)
  }
}
