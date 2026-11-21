package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import os.kei.hyper.home.status.NativeIconImportReport
import os.kei.hyper.home.status.NativeThemeWindowActivity
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class NativeThemeManualFallbackUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val storageName = "manual_theme_fallback_test_${UUID.randomUUID()}"

  @After fun clean() {
    context.deleteSharedPreferences(storageName)
  }

  @Test fun switchPersistsAndOnlyOffersManualHandoffForAnImportedRecord() {
    val storage = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    val prefs = AppPreferences(storage, SystemIconFallback.entries)
    val imported = NativeIconImportReport("token", 0, "source", "IMPORTED", "local",
      convertedReady = false)
    var report by mutableStateOf(imported)
    var busy by mutableStateOf(false)
    var launch: Intent? = null
    var launchOptions: Bundle? = null
    rule.setContent {
      KeiHomeTheme {
        val preferences by prefs.state.collectAsStateWithLifecycle()
        Column {
          PackSettingsContent(
            preferences, onAutoApply = {}, onInstalledOnly = {}, onRememberMenus = {}, onPreviews = {},
            showAutoApply = false, showInstalledOnly = false, showRememberMenus = false, showPreviews = false,
            onManualThemeFallback = prefs::setManualThemeFallback,
          )
          val host = LocalContext.current
          val recordingHost = object : ContextWrapper(host) {
            override fun startActivity(intent: Intent) {
              launch = intent
            }

            override fun startActivity(intent: Intent, options: Bundle?) {
              launch = intent
              launchOptions = options
            }
          }
          CompositionLocalProvider(LocalContext provides recordingHost) {
            NativeThemeLink(report, autoApply = false, enabled = !busy,
              manualFallback = preferences.manualThemeFallback)
          }
        }
      }
    }
    val toggle = rule.onNode(isToggleable() and hasAnyAncestor(hasTestTag("icon-packs.manual-theme-fallback")),
      useUnmergedTree = true)
    val view = rule.onNodeWithContentDescription(context.getString(R.string.pack_action_view))
    val apply = rule.onNodeWithContentDescription(context.getString(R.string.pack_apply_native))
    toggle.assertIsOff()
    view.assertDoesNotExist()
    apply.assertDoesNotExist()

    toggle.performClick().assertIsOn()
    view.assertIsEnabled().performClick()
    apply.assertDoesNotExist()
    rule.onNodeWithText(context.getString(R.string.pack_manual_theme_validation_incomplete)).assertExists()
    rule.runOnIdle {
      assertTrue(AppPreferences(storage, SystemIconFallback.entries).state.value.manualThemeFallback)
      assertTrue(prefs.state.value.autoApplyIconPack)
      val activity = requireNotNull(launch)
      assertEquals(1, launchOptions?.getInt("android.activity.windowingMode"))
      val detail = requireNotNull(activity.getParcelableExtra(NativeThemeWindowActivity.THEME, Intent::class.java))
      assertEquals("com.android.thememanager", detail.component?.packageName)
      assertEquals("local", detail.data?.fragment)
      assertEquals("icons", detail.getStringExtra("REQUEST_RESOURCE_CODE"))
      assertFalse(detail.hasExtra("keihome.AUTO_APPLY_TOKEN"))
      busy = true
    }
    view.assertIsNotEnabled()
    rule.runOnIdle { busy = false; report = imported.copy(status = "UNCERTAIN") }
    view.assertDoesNotExist()
    rule.runOnIdle { report = imported.copy(status = "FAILED") }
    view.assertDoesNotExist()
    rule.runOnIdle { report = imported.copy(localId = null) }
    view.assertDoesNotExist()

    toggle.performClick().assertIsOff()
    rule.runOnIdle {
      assertFalse(AppPreferences(storage, SystemIconFallback.entries).state.value.manualThemeFallback)
      report = imported.copy(convertedReady = true)
    }
    apply.assertIsEnabled()
  }
}
