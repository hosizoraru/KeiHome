package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PreservedIconInventoryTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val first = app("example.first", "First")
  private val second = app("example.second", "Second")

  @Test fun namedUpdatesAdditionsAndRemovalsQueryOnlyTargetsAndPreserveOtherRows() = runBlocking {
    val source = Source(first, second)
    val inventory = PreservedIconInventory(context, source)
    val initial = inventory.read(Locale.US)
    source.apps[first.packageName] = first.copy(versionCode = 2, updatedAt = 2)
    val changed = inventory.read(Locale.US, setOf(first.packageName))
    assertEquals(listOf(first.packageName), source.targets)
    assertEquals(1, source.fullReads)
    assertEquals(2L, changed.single { it.packageName == first.packageName }.versionCode)
    assertSame(initial.single { it.packageName == second.packageName },
      changed.single { it.packageName == second.packageName })
    assertSame(changed, inventory.read(Locale.US, setOf(first.packageName)))

    val added = app("example.added", "Added")
    source.apps.remove(first.packageName)
    source.apps[added.packageName] = added
    source.targets.clear()
    val membership = inventory.read(Locale.US, linkedSetOf(first.packageName, added.packageName))
    assertEquals(listOf(first.packageName, added.packageName), source.targets)
    assertEquals(1, source.fullReads)
    assertFalse(membership.any { it.packageName == first.packageName })
    assertEquals(listOf(added.packageName, second.packageName), membership.map { it.packageName })
    assertSame(changed.single { it.packageName == second.packageName },
      membership.single { it.packageName == second.packageName })
  }

  @Test fun firstPartialReadLocaleConfigurationAndExplicitRefreshUseAuthoritativeInventory() = runBlocking {
    val isolated = object : ContextWrapper(context) {
      var resourceContext = context.createConfigurationContext(Configuration(context.resources.configuration))
      override fun getApplicationContext(): Context = this
      override fun getResources() = resourceContext.resources
    }
    val source = Source(first, second)
    val inventory = PreservedIconInventory(isolated, source)
    inventory.read(Locale.US, setOf(first.packageName))
    assertEquals(1, source.fullReads)
    inventory.read(Locale.FRANCE, setOf(first.packageName))
    assertEquals(2, source.fullReads)
    isolated.resourceContext = context.createConfigurationContext(
      Configuration(context.resources.configuration).apply { fontScale += 0.1f })
    inventory.read(Locale.FRANCE, setOf(first.packageName))
    assertEquals(3, source.fullReads)
    inventory.read(Locale.FRANCE)
    assertEquals(4, source.fullReads)
    assertEquals(emptyList<String>(), source.targets)
  }

  @Test fun failureAndCancellationDoNotPublishPartialBatchesOrFallBackToFullEnumeration() = runBlocking {
    val source = Source(first, second)
    val inventory = PreservedIconInventory(context, source)
    val initial = inventory.read(Locale.US)
    source.apps[first.packageName] = first.copy(versionCode = 2)
    source.failure = IllegalStateException("Package query unavailable")
    try {
      inventory.read(Locale.US, linkedSetOf(first.packageName, second.packageName))
      fail("A failed target must reject the whole batch")
    } catch (_: IllegalStateException) { }
    assertSame(initial, inventory.read(Locale.US, emptySet()))
    source.failure = CancellationException("Cancelled scan")
    try {
      inventory.read(Locale.US, linkedSetOf(first.packageName, second.packageName))
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
    assertSame(initial, inventory.read(Locale.US, emptySet()))
    assertEquals(1, source.fullReads)
  }

  private class Source(vararg rows: PreservedIconApp) : PreservedIconInventorySource {
    val apps = rows.associateByTo(linkedMapOf()) { it.packageName }
    val targets = mutableListOf<String>()
    var fullReads = 0
    var failure: RuntimeException? = null
    override suspend fun readAll(): List<PreservedIconApp> {
      fullReads++
      return apps.values.map { it.copy() }
    }
    override suspend fun readPackage(packageName: String): PreservedIconApp? {
      targets += packageName
      if (packageName == "example.second") failure?.let { throw it }
      return apps[packageName]?.copy()
    }
  }

  private fun app(name: String, label: String) = PreservedIconApp(name, label, 1, 1, "", 0)
}
