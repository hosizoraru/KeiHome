package os.kei.hyper.home.ui.main

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.CustomIconMaskStore
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import os.kei.hyper.home.ui.components.KeiHomeScaffold

/**
 * Mutation tests use private SharedPreferences and a private document provider/store. Full
 * production route/back/three-column coverage lives in PackSettingsNavigationTest.
 */
class IconMaskSettingsUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun viewingPreservesPreferencesAndOnlyExplicitMasterTapPersistsMaskChoice() {
    Fixture().use { fixture ->
      fixture.preferences.setAutoApplyIconPack(true)
      val before = fixture.preferences.state.value
      val userBefore = AppPreferences.get(fixture.base).state.value
      assertTrue("Mask adaptation defaults on", before.completeSystemIconConfig)
      show(fixture)
      compose.onNodeWithTag("icon-mask.complete").performScrollTo().assertIsDisplayed()
      compose.runOnIdle {
        assertEquals(before, fixture.preferences.state.value)
        assertEquals(0, fixture.notifications.get())
      }
      compose.onNodeWithTag("icon-mask.complete").performClick()
      compose.runOnIdle {
        assertEquals(before.copy(completeSystemIconConfig = false), fixture.preferences.state.value)
        assertEquals(0, fixture.notifications.get())
      }
      val reopened = AppPreferences(fixture.sharedPreferences, listOf(SystemIconFallback.DEFAULT))
      assertFalse("The explicit choice must survive reopening the store", reopened.state.value.completeSystemIconConfig)
      compose.onNodeWithTag("icon-mask.complete").performClick()
      compose.runOnIdle {
        assertEquals(before, fixture.preferences.state.value)
        assertEquals("No user setting or automatic-apply preference may change",
          userBefore, AppPreferences.get(fixture.base).state.value)
      }
    }
  }

  @Test fun cancellingRestorePreservesCustomAndConfirmationOnlyRemovesThatMask() {
    Fixture().use { fixture ->
      val userBefore = AppPreferences.get(fixture.base).state.value
      fixture.source.writeText(validMask)
      compose.runOnIdle { fixture.controller.importMask(fixture.uri) }
      awaitController(fixture)
      val original = requireNotNull(fixture.controller.state.value.custom)
      val persistedBefore = fixture.record.readBytes()
      val preferencesBefore = fixture.preferences.state.value
      val downloadedResource = File(fixture.context.noBackupFilesDir, "retained-resource.zip").apply {
        writeBytes(byteArrayOf(10, 20, 30))
      }
      show(fixture)
      compose.onNodeWithTag("icon-mask.restore").performScrollTo().performClick()
      waitForDisplayed("icon-mask.restore-cancel")
      compose.onNodeWithTag("icon-mask.restore-cancel").assertIsDisplayed().performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("icon-mask.restore-confirm").fetchSemanticsNodes().isEmpty()
      }
      compose.onNodeWithTag("icon-mask.restore-confirm").assertDoesNotExist()
      compose.runOnIdle {
        assertEquals(original, fixture.controller.state.value.custom)
        assertEquals(1, fixture.notifications.get())
        assertEquals(preferencesBefore, fixture.preferences.state.value)
      }
      assertArrayEquals(persistedBefore, fixture.record.readBytes())
      assertEquals(original, runBlocking { fixture.store.read() })
      compose.onNodeWithTag("icon-mask.restore").performScrollTo().performClick()
      waitForDisplayed("icon-mask.restore-confirm")
      compose.onNodeWithTag("icon-mask.restore-confirm").assertIsDisplayed().performClick()
      awaitController(fixture)
      compose.runOnIdle {
        assertNull(fixture.controller.state.value.custom)
        assertEquals(2, fixture.notifications.get())
        assertEquals(preferencesBefore, fixture.preferences.state.value)
        assertEquals(userBefore, AppPreferences.get(fixture.base).state.value)
      }
      assertNull(runBlocking { fixture.store.read() })
      assertFalse(fixture.record.exists())
      assertArrayEquals(byteArrayOf(10, 20, 30), downloadedResource.readBytes())
    }
  }

  @Test fun rejectedImportKeepsSavedCustomPreviewAndDoesNotReportAChange() {
    Fixture().use { fixture ->
      fixture.source.writeText(validMask)
      compose.runOnIdle { fixture.controller.importMask(fixture.uri) }
      awaitController(fixture)
      val valid = requireNotNull(fixture.controller.state.value.custom)
      val before = fixture.record.readBytes()
      val preferencesBefore = fixture.preferences.state.value
      val userBefore = AppPreferences.get(fixture.base).state.value
      show(fixture)
      fixture.source.writeText("<IconTransform/>")
      compose.runOnIdle { fixture.controller.importMask(fixture.uri) }
      awaitController(fixture)
      compose.onNodeWithTag("icon-mask.error").performScrollTo().assertIsDisplayed()
      compose.onNodeWithTag("icon-mask.filename").performScrollTo().assertIsDisplayed()
      compose.onNodeWithTag("icon-mask.shape").assertIsDisplayed()
      compose.runOnIdle {
        assertEquals(IconMaskOperation.IMPORT, fixture.controller.state.value.error)
        assertEquals(valid, fixture.controller.state.value.custom)
        assertEquals(1, fixture.notifications.get())
        assertEquals(preferencesBefore, fixture.preferences.state.value)
        assertEquals(userBefore, AppPreferences.get(fixture.base).state.value)
      }
      assertArrayEquals(before, fixture.record.readBytes())
      assertEquals(valid, runBlocking { fixture.store.read() })
    }
  }

  private fun show(fixture: Fixture) {
    compose.runOnIdle { fixture.controller.load() }
    awaitController(fixture)
    val host = Os4NavigationTestHost()
    compose.setContent {
      host.Provide {
        KeiHomeTheme {
          val state by fixture.controller.state.collectAsStateWithLifecycle()
          val preferences by fixture.preferences.state.collectAsStateWithLifecycle()
          KeiHomeScaffold { insets ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insets).padding(16.dp)) {
              IconMaskSettingsContent(state, preferences.completeSystemIconConfig,
                onCompleteSystemIconConfig = fixture.preferences::setCompleteSystemIconConfig,
                onReset = fixture.controller::reset)
            }
          }
        }
      }
    }
    compose.waitForIdle()
  }

  private fun awaitController(fixture: Fixture) {
    compose.waitUntil(10_000) { !fixture.controller.state.value.busy }
    compose.waitForIdle()
    assertTrue(fixture.controller.state.value.loaded)
  }

  private fun waitForDisplayed(tag: String) {
    compose.waitUntil(10_000) {
      val nodes = compose.onAllNodesWithTag(tag)
      nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
    }
  }

  private class Fixture : AutoCloseable {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "mask-ui-${UUID.randomUUID()}"
    private val root = File(base.cacheDir, id).apply { mkdirs() }
    val source = File(root, "input.xml")
    private val provider = object : ContentProvider() {
      override fun onCreate() = true
      override fun getType(uri: Uri) = "application/xml"
      override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("custom-mask.xml")) }
      override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
      override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
      override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = error("Read only")
      override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
    }.apply { attachInfo(base, ProviderInfo().apply { authority = "test.$id" }) }
    private val resolver = ContentResolver.wrap(provider)
    val context = object : ContextWrapper(base) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
      override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
      override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
      override fun getContentResolver(): ContentResolver = resolver
    }
    val uri: Uri = Uri.parse("content://test.$id/mask.xml")
    val sharedPreferences = base.getSharedPreferences(id, Context.MODE_PRIVATE)
    val preferences = AppPreferences(sharedPreferences, listOf(SystemIconFallback.DEFAULT))
    val store = CustomIconMaskStore(context)
    val record = File(context.filesDir, "icon-shape/custom-mask.json")
    val notifications = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller = IconMaskController(context, scope) { notifications.incrementAndGet() }
    override fun close() {
      scope.cancel()
      base.deleteSharedPreferences(id)
      root.deleteRecursively()
    }
  }

  private companion object {
    const val validMask = """<IconTransform><Config name="ConfigIconMask" value="M0 0H100V100H0Z"/></IconTransform>"""
  }
}
