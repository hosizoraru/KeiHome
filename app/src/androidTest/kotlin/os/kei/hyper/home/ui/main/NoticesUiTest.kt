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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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

  @Test fun overviewShowsCategoriesAndOpensProjectLicenseAndCompleteNotices() {
    start()
    awaitCategories()
    rule.onNodeWithTag("notices.search").assertIsDisplayed()
    for (category in NoticeCategory.entries) {
      rule.onNodeWithTag("notices.category.${category.key}").assertExists()
    }
    rule.onNodeWithTag("notices.category.presentation").assertIsDisplayed()
    rule.onNodeWithTag("notices.component.miuix").assertDoesNotExist()
    rule.onNodeWithTag("notices.component.androidx").assertDoesNotExist()
    rule.onNodeWithText(context.getString(R.string.notices_thanks)).assertDoesNotExist()
    rule.onNodeWithTag("notices.full").assertDoesNotExist()
    rule.onNodeWithTag("notices.project-license").assertIsDisplayed().performClick()
    awaitDocument()
    rule.onNodeWithTag("notices.document").assertTextEquals(asset("licenses/KeiHome-Apache-2.0.txt"))
    rule.onNodeWithTag("notices.back").performClick()
    rule.onNodeWithContentDescription(context.getString(R.string.project_license_documents_menu)).performClick()
    rule.onNodeWithText(context.getString(R.string.notices_full)).performClick()
    awaitDocument()
    rule.onNodeWithTag("notices.document").assertTextEquals(asset("THIRD_PARTY_NOTICES.md"))
  }

  @Test fun categorySearchIsScopedAndRestoredAfterComponentAndRootReturn() {
    start()
    awaitCategories()
    rule.onNodeWithTag("notices.category.system").performScrollTo().performClick()
    awaitComponent("dexkit")
    rule.onNodeWithTag("notices.component.miuix").assertDoesNotExist()
    rule.onNodeWithTag("notices.search").performClick().performTextReplacement("DexKit")
    rule.onNodeWithTag("notices.search").performImeAction()
    assertGroupCount(NoticeCategory.SYSTEM, 1)
    rule.onNodeWithTag("notices.component.dexkit").performScrollTo().performClick()
    rule.onNodeWithTag("notices.back").performClick()
    rule.onNodeWithTag("notices.search").assertTextEquals("DexKit")
    rule.onNodeWithTag("notices.search").performTextReplacement("MIUIX")
    awaitEmpty()
    rule.onNodeWithTag("notices.empty").assertExists()
    rule.onNodeWithTag("notices.search").performTextReplacement("DexKit")
    rule.onNodeWithTag("notices.back").performClick()
    assertEquals("", rule.onNodeWithTag("notices.search").fetchSemanticsNode()
      .config[SemanticsProperties.EditableText].text)
    rule.onNodeWithTag("notices.category.system").assertExists()
    rule.onNodeWithTag("notices.category.system").performScrollTo().performClick()
    rule.onNodeWithTag("notices.search").assertTextEquals("DexKit")
    awaitComponent("dexkit")
    rule.onNodeWithTag("notices.component.dexkit").assertExists()
  }

  @Test fun globalSearchMatchesSourcesAcrossCategoriesAndKeepsLicenseSearch() {
    start()
    awaitCategories()
    rule.onNodeWithTag("notices.search").performClick().performTextReplacement("github.com")
    rule.onNodeWithTag("notices.search").performImeAction()
    rule.onNodeWithTag("notices.category.system").assertDoesNotExist()
    rule.onNodeWithTag("notices.component.miuix").assertExists()
    rule.onNodeWithTag("notices.component.dexkit").performScrollTo().performClick()
    rule.onNodeWithTag("notices.back").performClick()
    rule.onNodeWithTag("notices.search").assertTextEquals("github.com")
    rule.onNodeWithTag("notices.search").performScrollTo().performTextReplacement("GNU GPL")
    awaitComponent("dexkit")
    rule.onNodeWithTag("notices.component.dexkit").assertExists()
    rule.onNodeWithTag("notices.component.miuix").assertDoesNotExist()
    assertGroupCount(NoticeCategory.SYSTEM, 1)
    rule.onNodeWithTag("notices.search").performTextReplacement("")
    rule.onNodeWithTag("notices.category.presentation").assertExists()
    rule.onNodeWithTag("notices.component.dexkit").assertDoesNotExist()
  }

  @Test fun aboutOpensPublicSourceAndLicenseLivesUnderNotices() {
    start("about")
    rule.onNodeWithText(context.getString(R.string.settings_source))
      .performScrollTo().performClick()
    assertEquals(listOf(ProjectLinks.SOURCE_URL), openedSources)
    rule.onNodeWithText(context.getString(R.string.project_license_title)).assertDoesNotExist()
    rule.onNodeWithTag("about.notices").performScrollTo().performClick()
    val search = rule.onNodeWithTag("notices.search").performClick()
    search.performTextReplacement(context.getString(R.string.project_license_title))
    search.performImeAction()
    rule.onNodeWithTag("notices.project-license").performScrollTo().performClick()
    awaitDocument()
    rule.onNodeWithText(context.getString(R.string.project_license_scope)).assertExists()
    rule.onNodeWithText(context.getString(R.string.project_license_source_status)).assertExists()
    rule.onNodeWithTag("notices.copy").assertIsEnabled()
    rule.onNodeWithTag("notices.back").performClick()
    rule.onNodeWithTag("notices.project-license").assertExists()
    rule.onNodeWithTag("notices.back").performClick()
    rule.onNodeWithText(context.getString(R.string.settings_source)).assertExists()
    rule.onNodeWithTag("notices.project-license").assertDoesNotExist()
  }

  @Test fun acknowledgmentsOpenContributorProfileAndReturnToAbout() {
    start("about")
    rule.onNodeWithTag("about.contributors").performScrollTo().performClick()
    rule.onNodeWithText("@jizizr").assertExists()
    rule.onNodeWithText(context.getString(R.string.about_contributors_jizizr_thanks)).assertExists()
    rule.onNodeWithTag("contributors.jizizr").assertIsDisplayed().performClick()
    rule.runOnIdle { assertEquals(listOf("https://github.com/jizizr"), openedSources) }
    rule.onNodeWithTag("contributors.back").performClick()
    rule.onNodeWithTag("about.contributors").assertExists()
  }

  @Test fun authorSearchKeepsReferenceSourceAndRestoresOnReturn() {
    start()
    rule.onNodeWithTag("notices.search").performScrollTo().performClick()
      .performTextReplacement("wxxsfxyzm")
    rule.onNodeWithTag("notices.search").performImeAction()
    assertGroupCount(NoticeCategory.REFERENCES, 1)
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
    rule.onNodeWithTag("notices.search").performScrollTo().performTextReplacement("no-such-notice-123")
    awaitEmpty()
    rule.onNodeWithText(context.getString(R.string.notices_search_empty)).assertExists()
    rule.onNodeWithText(context.getString(R.string.project_license_group_count,
      context.getString(NoticeCategory.REFERENCES.title), 1)).assertDoesNotExist()
  }

  @Test fun gplComponentStillOpensItsOwnUnmodifiedLicense() {
    start()
    awaitCategories()
    rule.onNodeWithTag("notices.search").performScrollTo().performClick()
      .performTextReplacement("DexKit")
    rule.onNodeWithTag("notices.search").performImeAction()
    assertGroupCount(NoticeCategory.SYSTEM, 1)
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

  private fun assertGroupCount(category: NoticeCategory, count: Int) {
    rule.onNodeWithText(context.getString(R.string.project_license_group_count,
      context.getString(category.title), count)).assertExists()
  }

  private fun awaitComponent(key: String) {
    rule.waitUntil(5_000) {
      rule.onAllNodesWithTag("notices.component.$key").fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun awaitEmpty() {
    rule.waitUntil(5_000) {
      rule.onAllNodesWithTag("notices.empty").fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun awaitCategories() {
    rule.waitUntil(5_000) {
      NoticeCategory.entries.all {
        rule.onAllNodesWithTag("notices.category.${it.key}").fetchSemanticsNodes().isNotEmpty()
      }
    }
  }

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
      val back = { routes = routes.dropLast(1) }
      when (route) {
        "about" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
          AboutLinks(showSource = true, showNotices = true, onOpenNotices = { open("notices") },
            showChannel = false, onOpenContributors = { open("about/contributors") })
        }
        "about/contributors" -> ContributorsScreen(onBack = back)
        else -> NoticesDestinationScreen(route, onBack = back, onNavigate = open)
      }
    }
  }
}
