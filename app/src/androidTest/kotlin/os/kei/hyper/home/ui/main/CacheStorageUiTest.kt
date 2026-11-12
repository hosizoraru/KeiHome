package os.kei.hyper.home.ui.main

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.CacheBucket
import os.kei.hyper.home.data.DiskCacheUsage
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import os.kei.hyper.home.ui.components.KeiHomeScaffold

class CacheStorageUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val app: Application = ApplicationProvider.getApplicationContext()

  @Test fun perfectDownloadsCannotChangeDefaultBudgetAndCustomStillOverridesIt() {
    var sample by mutableStateOf(sample())
    var custom by mutableLongStateOf(0L)
    rule.setContent { KeiHomeTheme {
      KeiHomeScaffold { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
          CacheStorageContent(sample, false, false, custom, {}, {})
        }
      }
    } }
    rule.onNodeWithTag("cache.storage.status").assertIsDisplayed()
    rule.onNodeWithText(app.getString(R.string.storage_ok)).assertIsDisplayed()
    val fixedLimit = app.getString(R.string.storage_budget_pill,
      android.text.format.Formatter.formatShortFileSize(app, 520_000_000L))
    rule.onNodeWithTag("cache.storage.limit").assertTextContains(fixedLimit)
    rule.runOnIdle { sample = sample(perfect = 600_000_000L) }
    rule.onNodeWithText(app.getString(R.string.storage_warning)).assertIsDisplayed()
    rule.onNodeWithTag("cache.storage.limit").assertTextContains(fixedLimit)
    val graph = rule.onNodeWithTag("cache.storage.graph-region").fetchSemanticsNode().boundsInRoot
    val legend = rule.onNodeWithTag("cache.storage.legend").fetchSemanticsNode().boundsInRoot
    assertEquals(graph.width, legend.width, 1f)
    if (InstrumentationRegistry.getArguments().getString("storageScreenshot") == "true") {
      rule.mainClock.advanceTimeBy(1_000)
      rule.waitForIdle()
      val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
      File(app.cacheDir, "cache-storage-preview.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      bitmap.recycle()
    }
    rule.runOnIdle { custom = 750_000_000L }
    rule.onNodeWithText(app.getString(R.string.storage_ok)).assertIsDisplayed()
  }

  @Test fun budgetEditorValidatesSavesAndRestoresAutomaticMode() {
    var custom by mutableLongStateOf(0L)
    rule.setContent { KeiHomeTheme {
      KeiHomeScaffold { padding ->
        Column(Modifier.padding(padding)) {
          CacheBudgetEditor(custom, custom.takeIf { it != 0L } ?: 520_000_000L) { custom = it }
        }
      }
    } }
    rule.onNodeWithTag("cache.budget.edit").performClick()
    rule.onNodeWithTag("cache.budget.input").performTextClearance()
    rule.onNodeWithTag("cache.budget.input").performTextInput("49")
    rule.onNodeWithTag("cache.budget.save").assertIsNotEnabled()
    rule.onNodeWithTag("cache.budget.input").performTextClearance()
    rule.onNodeWithTag("cache.budget.input").performTextInput("750")
    rule.onNodeWithTag("cache.budget.save").performClick()
    rule.runOnIdle { assertEquals(750_000_000L, custom) }
    rule.onNodeWithTag("cache.budget.edit").performClick()
    rule.onNodeWithTag("cache.budget.auto").performClick()
    rule.runOnIdle { assertEquals(0L, custom) }
  }

  @Test fun toolbarEditPrecedesRefreshAndOpensTheThresholdDialog() {
    val store = ViewModelStore()
    val model = ViewModelProvider(store,
      ViewModelProvider.AndroidViewModelFactory(app))[HomeViewModel::class.java]
    try {
      rule.setContent { KeiHomeTheme {
        SettingsDestinationScreen(model, "cache", {}, {}, {})
      } }
      val edit = rule.onNodeWithTag("cache.budget.edit").assertIsDisplayed()
      val refresh = rule.onNodeWithTag("settings.cache.refresh").assertIsDisplayed()
      org.junit.Assert.assertTrue(edit.fetchSemanticsNode().boundsInRoot.right <=
        refresh.fetchSemanticsNode().boundsInRoot.left)
      rule.onNodeWithText(app.getString(R.string.storage_budget_help)).assertDoesNotExist()
      edit.performClick()
      rule.onNodeWithTag("cache.budget.input").assertIsDisplayed()
      rule.onNodeWithText(app.getString(R.string.storage_cancel)).performClick()
    } finally {
      rule.runOnIdle { store.clear() }
    }
  }

  @Test fun resourceRowsNavigateAndLargeTextKeepsPreferencesReachable() {
    val destinations = mutableListOf<String>()
    rule.setContent { KeiHomeTheme {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          CacheStorageContent(sample(perfect = 600_000_000L), false, false, 0L, {},
            destinations::add, onOpenLogs = { destinations.add("log") })
        }
      }
    } }
    rule.onNodeWithTag("cache.category.0").assertHasNoClickAction()
    rule.onNodeWithTag("cache.category.4").performScrollTo().performClick()
    rule.onNodeWithTag("cache.category.5").performScrollTo().performClick()
    rule.onNodeWithTag("cache.category.6").performScrollTo().performClick()
    rule.runOnIdle { assertEquals(listOf("apple", "perfect", "log"), destinations) }
    rule.onNodeWithTag("cache.storage.preferences").performScrollTo().assertIsDisplayed()
  }

  @Test fun logsAreIncludedInDisplayedTotalsButPreviewMemoryIsSeparate() {
    val sample = CacheSample(
      DiskCacheUsage(mapOf(CacheBucket.OTHER to 13L, CacheBucket.LOGS to 17L), true),
      memory = 99L, apple = 19L, perfect = 23L, capturedAt = 0L)
    assertEquals(72L, sample.total)
    assertEquals(sample.total, sample.categories.sum())
    assertEquals(17L, sample.categories[6])
    assertEquals(sample.total, sample.copy(memory = 0L).total)
  }

  @Test fun partialMeasurementDoesNotClaimHealthyUsage() {
    rule.setContent { KeiHomeTheme {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        CacheStorageContent(sample().copy(disk = DiskCacheUsage(emptyMap(), false)),
          false, false, 0L, {}, {})
      }
    } }
    rule.onNodeWithText(app.getString(R.string.storage_partial)).assertIsDisplayed()
    rule.onNodeWithText(app.getString(R.string.storage_ok)).assertDoesNotExist()
  }

  private fun sample(perfect: Long = 0L) = CacheSample(
    DiskCacheUsage(mapOf(CacheBucket.CURRENT_ICONS to 50_000_000L), true),
    memory = 3_000_000L, apple = 20_000_000L, perfect = perfect, capturedAt = 1_700_000_000_000L)
}
