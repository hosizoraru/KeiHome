package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.ImeAction
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class LocalizedInputFieldTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test fun localizedClearUpdatesTextAndDecorativeSearchHasNoDescription() {
    val locale = mutableStateOf("en")
    val query = mutableStateOf("icons")
    val changes = mutableListOf<String>()
    rule.setContent {
      val configuration = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(locale.value))
      }
      CompositionLocalProvider(
        LocalContext provides context.createConfigurationContext(configuration),
        LocalConfiguration provides configuration,
      ) {
        KeiHomeTheme {
          LocalizedInputField(
            query = query.value,
            onQueryChange = { changes += it; query.value = it },
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
          )
        }
      }
    }
    for ((tag, description) in listOf("en" to "Clear text", "zh-CN" to "清空内容", "ja" to "入力をクリア")) {
      rule.runOnIdle { locale.value = tag; query.value = "icons" }
      rule.onNodeWithContentDescription("Search", useUnmergedTree = true).assertDoesNotExist()
      rule.onNodeWithContentDescription("Search Cleanup", useUnmergedTree = true).assertDoesNotExist()
      rule.onNode(hasSetTextAction(), useUnmergedTree = true).assertTextEquals("icons")
      rule.onNodeWithContentDescription(description, useUnmergedTree = true).performClick()
      rule.onNode(hasSetTextAction(), useUnmergedTree = true).assertTextEquals("")
      rule.onNodeWithContentDescription(description, useUnmergedTree = true).assertDoesNotExist()
    }
    rule.runOnIdle { assertEquals(listOf("", "", ""), changes) }
  }

  @Test fun textEntryAndSearchImeKeepTheirCallbacks() {
    val query = mutableStateOf("")
    val expanded = mutableStateOf(false)
    val searches = mutableListOf<String>()
    rule.setContent {
      KeiHomeTheme {
        LocalizedInputField(
          query = query.value,
          onQueryChange = { query.value = it },
          onSearch = { searches += it },
          expanded = expanded.value,
          onExpandedChange = { expanded.value = it },
          modifier = Modifier.testTag("search.input"),
        )
      }
    }
    val input = rule.onNodeWithTag("search.input", useUnmergedTree = true)
    input.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
    input.performClick().performTextInput("dock")
    input.assertTextEquals("dock").performImeAction()
    rule.runOnIdle {
      assertEquals("dock", query.value)
      assertEquals(true, expanded.value)
      assertEquals(listOf("dock"), searches)
    }
  }

  @Test fun disabledFieldAlsoDisablesClear() {
    val changes = mutableListOf<String>()
    rule.setContent {
      KeiHomeTheme {
        LocalizedInputField(
          query = "keep",
          onQueryChange = { changes += it },
          onSearch = {},
          expanded = false,
          onExpandedChange = {},
          modifier = Modifier.testTag("disabled.search"),
          enabled = false,
        )
      }
    }
    rule.onNodeWithTag("disabled.search", useUnmergedTree = true).assertIsNotEnabled().assertTextEquals("keep")
    rule.onNodeWithContentDescription(context.getString(R.string.search_clear_text), useUnmergedTree = true)
      .assertIsNotEnabled().performTouchInput { click() }
    rule.runOnIdle { assertEquals(emptyList<String>(), changes) }
  }
}
