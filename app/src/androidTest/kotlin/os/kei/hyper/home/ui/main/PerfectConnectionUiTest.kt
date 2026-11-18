package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.iconpack.perfect.PerfectGitNetworkOptions
import os.kei.hyper.home.iconpack.perfect.PerfectGitNetworkPreferences
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold

class PerfectConnectionUiTest {
  @get:Rule(order = 0) val host = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val preferences get() = PerfectGitNetworkPreferences(
    InstrumentationRegistry.getInstrumentation().targetContext)
  private lateinit var original: PerfectGitNetworkOptions
  @Before fun preserveConnection() {
    original = preferences.read()
    preferences.write(PerfectGitNetworkOptions())
  }
  @After fun restoreConnection() { preferences.write(original) }

  @Test fun exampleCanBeSavedAndRetryIsRequestedOnlyAfterPersistence() {
    var retried = 0
    rule.setContent { KeiHomeTheme { Scaffold { insets -> Box(Modifier.padding(insets).padding(24.dp)) {
      PerfectConnectionSettings(onSaved = {
        assertEquals("https://gh-proxy.org/", preferences.read().acceleration)
        retried++
      })
    } } } }
    rule.onNodeWithTag("perfect.connection.open").performClick()
    rule.onNodeWithTag("perfect.connection.fill_example").performScrollTo().performClick()
    rule.onNodeWithTag("perfect.connection.save").performClick()
    rule.waitUntil(10_000) { retried == 1 }
  }

  @Test fun invalidAccelerationCannotBePersistedAndCancelDoesNotRetry() {
    var retried = 0
    rule.setContent { KeiHomeTheme { Scaffold { insets -> Box(Modifier.padding(insets).padding(24.dp)) {
      PerfectConnectionSettings(onSaved = { retried++ })
    } } } }
    rule.onNodeWithTag("perfect.connection.open").performClick()
    rule.onNodeWithTag("perfect.connection.acceleration").performScrollTo()
      .performTextReplacement("http://insecure.example/")
    rule.onNodeWithTag("perfect.connection.save").performClick()
    rule.waitUntil(5_000) {
      rule.onAllNodesWithTag("perfect.connection.error").fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithTag("perfect.connection.error").assertIsDisplayed()
    assertEquals(PerfectGitNetworkOptions(), preferences.read())
    assertEquals(0, retried)
    rule.onNodeWithTag("perfect.connection.cancel").performClick()
    assertEquals(0, retried)
  }
}
