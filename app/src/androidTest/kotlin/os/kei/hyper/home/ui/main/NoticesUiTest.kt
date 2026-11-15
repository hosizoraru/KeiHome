package os.kei.hyper.home.ui.main

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class NoticesUiTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val navigation = Os4NavigationTestHost()
  private val openedSources = mutableListOf<String>()

  @Test fun aboutProjectEntryShowsScopeAndKeepsPrivateSourceStatus() {
    start("about")
    rule.onNodeWithText(context.getString(R.string.settings_source_private)).assertExists()
    rule.onNodeWithTag("about.project-license").performScrollTo().performClick()
    awaitDocument()
    rule.onNodeWithText(context.getString(R.string.project_license_scope)).assertExists()
    rule.onNodeWithText(context.getString(R.string.project_license_source_status)).assertExists()
    rule.onNodeWithTag("notices.copy").assertIsEnabled()
    rule.onNodeWithTag("notices.back").performClick()
    rule.onNodeWithText(context.getString(R.string.settings_source_private)).assertExists()
    rule.onNodeWithTag("about.project-license").assertExists()
  }

  @Test fun authorSearchKeepsReferenceSourceAndRestoresOnReturn() {
    start()
    rule.onNodeWithTag("notices.search").performScrollTo().performClick()
      .performTextReplacement("wxxsfxyzm")
    rule.onNodeWithTag("notices.search").performImeAction()
    rule.onNodeWithTag("notices.component.back-gesture").performScrollTo().performClick()
    rule.onNodeWithText(context.getString(R.string.notices_back_gesture_thanks)).assertExists()
    rule.onNodeWithTag("notices.source").performScrollTo().performClick()
    rule.runOnIdle {
      assertEquals(listOf("https://github.com/wxxsfxyzm/MiuiBackGestureHook"), openedSources)
    }
    rule.onNodeWithTag("notices.license.apache").performScrollTo().performClick()
    awaitDocument()
    rule.onNodeWithTag("notices.back").performClick()
    rule.onNodeWithTag("notices.back").performClick()
    rule.onNodeWithTag("notices.search").assertTextEquals("wxxsfxyzm")
    rule.onNodeWithTag("notices.component.back-gesture").assertExists()
    rule.onNodeWithTag("notices.component.miuix").assertDoesNotExist()
  }

  @Test fun gplComponentStillOpensItsOwnUnmodifiedLicense() {
    start()
    rule.waitUntil(5_000) {
      rule.onAllNodesWithTag("notices.component.dexkit").fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithTag("notices.search").performScrollTo().performClick()
      .performTextReplacement("DexKit")
    rule.onNodeWithTag("notices.search").performImeAction()
    rule.onNodeWithTag("notices.component.dexkit").performScrollTo().performClick()
    rule.onNodeWithTag("notices.license.gpl").performScrollTo().performClick()
    awaitDocument()
    rule.onNodeWithTag("notices.document").assertTextEquals(asset("licenses/GPL-3.0.txt"))
    rule.onNodeWithText(context.getString(R.string.project_license_scope)).assertDoesNotExist()
  }

  @Test fun fullDocumentRemainsCopyableAfterScrollingToTheEnd() {
    start("license/keihome")
    awaitDocument()
    val expected = asset("licenses/KeiHome-Apache-2.0.txt")
    assertEquals(asset("licenses/Apache-2.0.txt"), expected)
    rule.onNodeWithTag("notices.document").assertTextEquals(expected)
    rule.onNodeWithTag("notices.scroll").performSemanticsAction(SemanticsActions.ScrollBy) {
      it(0f, 100_000f)
    }
    rule.onNodeWithTag("notices.copy").assertIsDisplayed().performClick()
    rule.runOnIdle {
      val clip = rule.activity.getSystemService(ClipboardManager::class.java).primaryClip
      assertEquals(expected, clip?.getItemAt(0)?.text?.toString())
    }
  }

  private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }

  private fun awaitDocument() {
    rule.waitUntil(5_000) {
      rule.onAllNodesWithTag("notices.document").fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun start(initialRoute: String = "notices") {
    rule.setContent {
      KeiHomeTheme {
        navigation.Provide {
          CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) { openedSources += uri }
          }) { NoticesHost(initialRoute) }
        }
      }
    }
  }

  @Composable
  private fun NoticesHost(initialRoute: String) {
    var routes by rememberSaveable { mutableStateOf(listOf(initialRoute)) }
    val pages = rememberSaveableStateHolder()
    val route = routes.last()
    val open: (String) -> Unit = { routes = routes + it }
    pages.SaveableStateProvider(route) {
      if (route == "about") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
          AboutLinks(showSource = true, showNotices = true, onOpenNotices = { open("notices") },
            showChannel = false, onOpenProjectLicense = { open("license/keihome") })
        }
      } else {
        NoticesDestinationScreen(route, onBack = { routes = routes.dropLast(1) }, onNavigate = open)
      }
    }
  }
}
