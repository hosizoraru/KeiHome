package os.kei.hyper.home.xposed

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconResourceInspectorTest {
  private val context = InstrumentationRegistry.getInstrumentation().context
  private val inspector = IconResourceInspector(context.packageManager)
  private val configuration = Configuration(context.resources.configuration)

  private fun activity(resource: String): ActivityInfo = ActivityInfo().apply {
    packageName = context.packageName
    applicationInfo = context.applicationInfo
    icon = context.resources.getIdentifier(resource, "drawable", context.packageName)
    check(icon != 0)
  }

  @Test
  fun skipsOnlyProvenStaticVector() {
    assertFalse(inspector.requiresRefresh(listOf(activity("fixture_static")), configuration))
  }

  @Test
  fun refreshesNestedNightColorsAndMultipleActivities() {
    assertTrue(inspector.requiresRefresh(
      listOf(activity("fixture_static"), activity("fixture_night")), configuration,
    ))
  }

  @Test
  fun refreshesUnsupportedSelectorEvenWhenItsPixelsMatch() {
    assertTrue(inspector.requiresRefresh(listOf(activity("fixture_selector")), configuration))
  }

  @Test
  fun dependencyGraphRejectsUnsupportedNestedColorSelector() {
    val info = activity("fixture_nested_selector")
    val light = Configuration(configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        Configuration.UI_MODE_NIGHT_NO
    }
    val dark = Configuration(configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        Configuration.UI_MODE_NIGHT_YES
    }
    val lightResources = context.packageManager.getResourcesForApplication(info.applicationInfo, light)
    val darkResources = context.packageManager.getResourcesForApplication(info.applicationInfo, dark)
    assertFalse(StableIconResourceGraph(lightResources, darkResources).isStable(info.icon))
  }
}
