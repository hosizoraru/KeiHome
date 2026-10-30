package os.kei.hyper.home.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetPublicationTest {
  private val baseline = MonetColorSynchronizer.Publication(
    colors = IconColors(0xffccccff.toInt(), 0xff202030.toInt()),
    denied = setOf("com.example.themed"),
    process = NativeMonetProcessIdentity(42, 100L),
    theme = ActiveIconTheme.Signature(0, emptyList(), true, true),
    userId = 0,
  )

  @Test fun firstPublicationAndJavaReloadReconcilePackageSources() {
    assertTrue(baseline.needsPackageRefresh(null))
  }

  @Test fun changedBackgroundUsesTheObserverButForegroundOnlyStillRebuilds() {
    assertTrue(baseline.copy(colors = baseline.colors.copy(foreground = 0xffddffdd.toInt()))
      .needsPackageRefresh(baseline))
    assertFalse(baseline.copy(colors = baseline.colors.copy(background = 0xff203020.toInt()))
      .needsPackageRefresh(baseline))
    assertFalse(baseline.needsPackageRefresh(baseline))
  }

  @Test fun changedFallbackOrThemeSourcesStillReloadPackages() {
    assertTrue(baseline.copy(denied = emptySet()).needsPackageRefresh(baseline))
    assertTrue(baseline.copy(theme = baseline.theme!!.copy(revision = 1))
      .needsPackageRefresh(baseline))
  }

  @Test fun userAndNativeOwnerChangesStillReloadPackages() {
    assertTrue(baseline.copy(userId = 10).needsPackageRefresh(baseline))
    assertTrue(baseline.copy(process = NativeMonetProcessIdentity(43, 100L))
      .needsPackageRefresh(baseline))
    assertTrue(baseline.copy(process = NativeMonetProcessIdentity(42, 200L))
      .needsPackageRefresh(baseline))
  }
}
