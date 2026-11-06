package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.status.FrameworkManagerProtocol
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.TextButton

class FrameworkManagerHelpTest {
  @get:Rule val compose = createComposeRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test fun unavailableBridgeOffersManualEntryInsteadOfADeadEnd() {
    assumeTrue(context.packageManager.getLaunchIntentForPackage("org.lsposed.manager") == null)
    assumeTrue(context.packageManager.getLaunchIntentForPackage("org.matrix.vector.manager") == null)
    compose.setContent {
      KeiHomeTheme {
        val base = LocalContext.current
        val unavailable = remember(base) { UnavailableBridge(base) }
        CompositionLocalProvider(LocalContext provides unavailable) {
          val action = rememberFrameworkManagerAction()
          TextButton("Open", modifier = Modifier.testTag("framework-open"), onClick = action)
        }
      }
    }
    compose.onNodeWithTag("framework-open").performClick()
    val copy = context.getString(R.string.framework_entry_copy)
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodesWithText(copy).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText(context.getString(R.string.framework_entry_help,
      FrameworkManagerProtocol.DIAL_CODE)).assertIsDisplayed()
    compose.onNodeWithText(context.getString(R.string.framework_entry_dial)).assertIsDisplayed()
    val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    File(context.cacheDir, "framework-manager-help.png").outputStream().use {
      screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    screenshot.recycle()
    compose.onNodeWithText(context.getString(R.string.logs_cancel)).performClick()
    compose.onNodeWithText(copy).assertDoesNotExist()
  }

  private class UnavailableBridge(base: Context) : ContextWrapper(base) {
    override fun sendOrderedBroadcast(intent: Intent, permission: String?, options: Bundle?,
      receiver: BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?,
      initialExtras: Bundle?) {
      throw SecurityException("Unavailable framework entry")
    }
  }
}
