package os.kei.hyper.home.ui.main

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class PackSettingsOpenCurrentIconPackTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val storageName = "open_current_icon_pack_test_${UUID.randomUUID()}"
  private val tag = "icon-packs.open-current"

  @After fun clean() {
    context.deleteSharedPreferences(storageName)
  }

  @Test fun defaultOnSwitchDispatchesAndPersistsFalseAndTrueAcrossReopenedPreferences() {
    val storage = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    assertTrue(storage.edit()
      .putBoolean("auto_apply_icons", false)
      .putBoolean("installed_icons_only", true)
      .putBoolean("remember_menus", false)
      .putBoolean("icon_previews", false)
      .putString("unrelated_key", "keep")
      .commit())
    val originalValues = storage.all.toMap()
    var preferences by mutableStateOf(AppPreferences(storage, SystemIconFallback.entries))
    val originalState = preferences.state.value
    val changes = mutableListOf<Boolean>()
    val unrelatedChanges = mutableListOf<String>()
    rule.setContent {
      KeiHomeTheme {
        val current = preferences
        val state by current.state.collectAsStateWithLifecycle()
        Column(Modifier.verticalScroll(rememberScrollState())) {
          PackSettingsContent(
            preferences = state,
            onAutoApply = { unrelatedChanges += "auto-apply" },
            onInstalledOnly = { unrelatedChanges += "installed-only" },
            onRememberMenus = { unrelatedChanges += "remember-menus" },
            onCompactHints = { unrelatedChanges += "compact-hints" },
            onPreviews = { unrelatedChanges += "previews" },
            onOpenCurrentIconPack = {
              changes += it
              current.setOpenCurrentIconPack(it)
            },
          )
        }
      }
    }

    rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.pack_settings_experience)).assertExists()
    toggle().assertIsOn()
    rule.runOnIdle {
      assertTrue(originalState.openCurrentIconPack)
      assertFalse(storage.contains(KEY))
      assertEquals(originalValues, storage.all)
      assertEquals(emptyList<Boolean>(), changes)
      assertEquals(emptyList<String>(), unrelatedChanges)
    }

    for ((index, value) in listOf(false, true).withIndex()) {
      toggle().performClick()
      if (value) toggle().assertIsOn() else toggle().assertIsOff()
      val expectedChanges = listOf(false, true).take(index + 1)
      rule.runOnIdle {
        val expectedState = originalState.copy(openCurrentIconPack = value)
        assertEquals(expectedChanges, changes)
        assertEquals(emptyList<String>(), unrelatedChanges)
        assertEquals(expectedState, preferences.state.value)
        assertEquals(value, storage.getBoolean(KEY, !value))
        assertEquals(originalValues + (KEY to value), storage.all)

        val reopened = AppPreferences(
          context.getSharedPreferences(storageName, Context.MODE_PRIVATE), SystemIconFallback.entries)
        assertEquals(expectedState, reopened.state.value)
        preferences = reopened
      }
      if (value) toggle().assertIsOn() else toggle().assertIsOff()
      rule.runOnIdle {
        assertEquals("Restoring preferences must not dispatch another callback", expectedChanges, changes)
        assertEquals(emptyList<String>(), unrelatedChanges)
        assertEquals(originalValues + (KEY to value), storage.all)
      }
    }
  }

  private fun toggle() = rule.onNode(
    isToggleable() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

  private companion object {
    const val KEY = "open_current_icon_pack"
  }
}
