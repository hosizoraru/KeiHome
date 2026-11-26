package os.kei.hyper.home.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.ReleaseNotesPreferences
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.Text

class ReleaseNotesReadDeviceTest {
  @get:Rule(order = 0) val host = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val id = "release_read_${UUID.randomUUID()}"

  @After fun clean() {
    AppLocalStorage.open(context, id).close()
    MMKV.removeStorage(id)
  }

  @Test fun unreadViewCanAcknowledgeOneVersionAndMarksOpenedNotesWhenLeaving() {
    ReleaseNotesPreferences(context, id, "1.9.0", false).markRead(setOf("1.9.0"))
    val update = ReleaseNotesPreferences(context, id, "1.11.0", true)
    rule.setContent {
      KeiHomeTheme {
        var open by remember { mutableStateOf(true) }
        val state by update.state.collectAsStateWithLifecycle()
        if (open) ReleaseNotesScreen(onBack = { open = false }, currentVersion = state.observedVersion,
          unreadVersions = state.unreadVersions.toSet(), onReadVersions = update::markRead)
        else Text("Done")
      }
    }
    val unread = context.getString(R.string.release_notes_ui_unread)
    rule.onNode(hasText(unread, substring = true) and hasAnyAncestor(hasTestTag("release-notes.views"))).performClick()
    rule.onNodeWithTag("release-notes.release.1.9.0").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.read.1.11.0").performScrollTo().performClick()
    rule.waitForIdle()
    rule.onNodeWithTag("release-notes.release.1.11.0").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.toggle.1.10.0").performScrollTo().performClick()
    rule.onNodeWithTag("release-notes.body.1.10.0").assertExists()
    rule.onNodeWithTag("release-notes.back").performClick()
    rule.onNodeWithText("Done").assertExists()
    val saved = ReleaseNotesPreferences(context, id, "1.11.0+another", true).state.value
    assertTrue(saved.unreadVersions.isEmpty())
    assertEquals("1.11.0", saved.restartReminderVersion)
  }

  @Test fun freshInstallationReadStateSurvivesRelaunchAndRestoredBackupsGetANewBaseline() {
    val first = ReleaseNotesPreferences(context, id, "1.10.0", false, installationTime = 100L)
    assertEquals(listOf("1.10.0"), first.state.value.unreadVersions)
    first.markRead(setOf("1.10.0"))
    assertTrue(ReleaseNotesPreferences(context, id, "1.10.0+same", false,
      installationTime = 100L).state.value.unreadVersions.isEmpty())
    val upgrade = ReleaseNotesPreferences(context, id, "1.11.0", true, installationTime = 100L)
    assertEquals(listOf("1.11.0"), upgrade.state.value.unreadVersions)
    upgrade.markRead(setOf("1.11.0"))
    val restored = ReleaseNotesPreferences(context, id, "1.11.0", false, installationTime = 200L).state.value
    assertEquals(listOf("1.11.0"), restored.unreadVersions)
    assertEquals("", restored.pendingVersion)
    assertEquals("", restored.restartReminderVersion)
  }
}
