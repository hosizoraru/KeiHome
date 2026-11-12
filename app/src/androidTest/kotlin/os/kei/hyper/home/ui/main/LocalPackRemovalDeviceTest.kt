package os.kei.hyper.home.ui.main

import android.app.Application
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.CacheBucket
import os.kei.hyper.home.data.measureDiskCache
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconPackStore
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class LocalPackRemovalDeviceTest {
  @get:Rule(order = 0) val host = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
  private val owners = ViewModelStore()
  private val imports = File(app.noBackupFilesDir, "icon-packs")
  private val original = File(app.cacheDir, "share/qa-removal-${UUID.randomUUID()}.zip")
  private var ownedId: String? = null

  @After fun clean() {
    rule.runOnIdle { owners.clear() }
    ownedId?.let { IconPackStore(imports).remove(it) }
    original.delete()
  }

  @Test fun countsSearchCancellationRemovalAndStorageStayConsistent() {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.BLUE)
    original.parentFile!!.mkdirs()
    ZipOutputStream(original.outputStream()).use { zip ->
      zip.setComment(original.name)
      zip.putNextEntry(ZipEntry("res/drawable/os.kei.hyper.home.png"))
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
      zip.closeEntry()
    }
    bitmap.recycle()
    val originalBytes = original.readBytes()
    lateinit var model: HomeViewModel
    rule.runOnIdle {
      model = ViewModelProvider(owners, ViewModelProvider.AndroidViewModelFactory(app))[HomeViewModel::class.java]
      model.importIconPack(FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", original))
    }
    rule.waitUntil(60_000) { !model.iconPacks.value.importing }
    assertNull("Import failed: ${model.iconPacks.value.error}", model.iconPacks.value.error)
    assertTrue(model.iconPacks.value.scanned)
    val card = model.iconPacks.value.packs.single {
      (it.pack.source as? IconPackSource.Imported)?.record?.originalName == original.name
    }
    ownedId = card.pack.id
    val folder = (card.pack.source as IconPackSource.Imported).record.originalFile.parentFile!!
    val ownedBytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    val before = measureDiskCache(app.cacheDir, importedRoot = imports).bytes.getValue(CacheBucket.IMPORTED_PACKS)
    val sources = model.iconPacks.value.nativeSources
    val revision = model.importedPackRevision.value
    val section = mutableStateOf("installed")
    rule.setContent { KeiHomeTheme { KeiHomeScaffold { padding ->
      IconPacksPage(model, rememberLazyListState(), padding, Modifier.fillMaxSize(), section = section.value)
    } } }
    fun checkCount(visible: Int, total: Int) {
      rule.onNodeWithTag("packs.count").assertContentDescriptionEquals(
        app.getString(R.string.pack_list_result_count, visible, total))
    }
    val apkTotal = catalogPackCount(model.iconPacks.value, "installed")
    checkCount(apkTotal, apkTotal)
    rule.runOnIdle { section.value = "imported" }
    val total = catalogPackCount(model.iconPacks.value, "imported")
    checkCount(total, total)
    rule.onNode(hasSetTextAction()).performTextInput(card.pack.displayName)
    checkCount(1, total)
    rule.onNodeWithTag("pack.remove.${card.pack.id}").performScrollTo().performClick()
    rule.onNodeWithText(app.getString(R.string.pack_remove_message)).assertIsDisplayed()
    rule.onNodeWithText(app.getString(R.string.logs_cancel)).performClick()
    rule.runOnIdle {
      assertTrue(folder.exists())
      assertEquals(revision, model.importedPackRevision.value)
    }
    rule.onNodeWithTag("pack.remove.${card.pack.id}").performClick()
    rule.onNodeWithText(app.getString(R.string.pack_remove_action)).performClick()
    rule.waitUntil(15_000) {
      model.iconPacks.value.removing == null && model.iconPacks.value.packs.none { it.pack.id == card.pack.id }
    }
    checkCount(0, total - 1)
    assertFalse(folder.exists())
    assertArrayEquals(originalBytes, original.readBytes())
    assertEquals(sources, model.iconPacks.value.nativeSources)
    assertEquals(revision + 1, model.importedPackRevision.value)
    val after = measureDiskCache(app.cacheDir, importedRoot = imports).bytes.getValue(CacheBucket.IMPORTED_PACKS)
    assertEquals(ownedBytes, before - after)
  }
}
