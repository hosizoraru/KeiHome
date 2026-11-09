package os.kei.hyper.home.ui.main

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackDescription
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class PackGuidanceDeviceTest {
  private val rule = createComposeRule()
  @get:Rule val lifecycle = RuleChain.outerRule(object : ExternalResource() {
    override fun before() {
      // HyperOS may reject the next test Activity while the previous host has closed.
      // Bring our app forward before ActivityScenario is created, not in @Before.
      val i = InstrumentationRegistry.getInstrumentation()
      android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(
        "am start -W -n os.kei.hyper.home/.MainActivity")).use { it.readBytes() }
      i.waitForIdleSync()
    }
  }).around(rule)
  private val context: Context = ApplicationProvider.getApplicationContext()
  private fun text(id: Int) = context.getString(id)

  @Test fun sharedGuidanceCollapsesAndCanBeReadFromTheHelpDialog() {
    rule.setContent { KeiHomeTheme { Column {
      PackHelpAction(listOf("Help-only explanation"))
      PackGuidanceCard(listOf("Brief explanation", "Page explanation"))
    } } }
    rule.onNodeWithText("Brief explanation").assertExists()
    rule.onNodeWithText("Page explanation").assertDoesNotExist()
    rule.onNodeWithText(text(R.string.pack_guidance_title)).performClick()
    rule.onNodeWithText("Page explanation").assertExists()
    rule.onNodeWithText(text(R.string.pack_guidance_title)).performClick()
    rule.onNodeWithText("Page explanation").assertDoesNotExist()
    rule.onNodeWithContentDescription(text(R.string.pack_guidance_title)).performClick()
    rule.onNodeWithText("Help-only explanation").assertExists()
    rule.onNodeWithText(text(R.string.pack_guidance_done)).performClick()
    rule.onNodeWithText("Help-only explanation").assertDoesNotExist()
  }

  @Test fun authorsAndRegenerationStayVisibleWithoutAnExplanationToggle() {
    var imports = 0
    card(author = "Mr_Bocchi", enabled = true) { imports++ }
    rule.onNodeWithText(context.getString(R.string.pack_guidance_author, "Mr_Bocchi")).assertExists()
    rule.onNodeWithText(text(R.string.pack_action_apply_short)).assertIsEnabled()
    rule.onNodeWithText(text(R.string.pack_action_update_short)).performClick()
    rule.runOnIdle { assertEquals(1, imports) }
    rule.onNodeWithText(text(R.string.icon_pack_details)).assertDoesNotExist()
    rule.onNodeWithText(text(R.string.icon_pack_appearance_preview_note)).assertDoesNotExist()
  }

  @Test fun missingAuthorIsNotInventedAndBusyRegenerationIsDisabled() {
    card(author = null, enabled = false) { error("Busy action must not run") }
    rule.onNodeWithText(text(R.string.pack_action_update_short)).assertIsNotEnabled()
    rule.onNodeWithText(context.getString(R.string.pack_guidance_author, "Unknown")).assertDoesNotExist()
  }

  private fun card(author: String?, enabled: Boolean, onImport: () -> Unit) {
    val pack = IconPackDescriptor("test.pack", "Test pack", IconPackFormat.INSTALLED_APK,
      IconPackSource.Installed("test.pack", 1, 0, "1"), emptyList(), description = IconPackDescription(author = author))
    val previews = IconPackPreviewRepository(context)
    rule.setContent { KeiHomeTheme {
      IconPackCard(IconPackCardData(pack, 0, emptyList()), previews, IconPackAppearance.SYSTEM,
        nativeId = "fixture", current = false, importing = false, enabled = enabled,
        result = null, onImport = onImport, onRecheck = {})
    } }
  }
}
