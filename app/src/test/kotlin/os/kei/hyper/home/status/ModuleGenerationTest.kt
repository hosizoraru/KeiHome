package os.kei.hyper.home.status

import io.github.libxposed.service.HookedTarget.State
import os.kei.hyper.home.xposed.isTransientRuntimeAuthorityFailure
import os.kei.hyper.home.xposed.ownsReloadableJava
import os.kei.hyper.home.xposed.supportsRetiredCollectionHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleGenerationTest {
  @Test fun activeJavaOwnersMatchTheStaticScope() {
    for (process in listOf("com.miui.securitycenter", "com.miui.securitycenter:ui", "tv.danmaku.bili",
      "com.android.settings", "com.android.systemui", "com.android.thememanager", "com.android.intentresolver"))
      assertTrue(process, ownsReloadableJava(process, 10324))
    for (process in listOf("com.miui.securitycenter:cache", "com.miui.securitycenter.remote",
      "com.miui.securitycenter.power", "com.android.thememanager.aiicon",
      "com.android.settings:provider", "com.android.settings:remote",
      "tv.danmaku.bili:download", "tv.danmaku.bili:web", "com.miui.home", "system:ui", "unrelated"))
      assertFalse(process, ownsReloadableJava(process, 1000))
    assertTrue(ownsReloadableJava("system", 1000))
    assertTrue(ownsReloadableJava("system_server", 1000))
    assertFalse(ownsReloadableJava("system", 10324))
  }

  @Test fun retiredCollectionHintsCoverQuietAppHostsWithoutTouchingSystemServer() {
    for (process in listOf("com.android.systemui", "com.android.settings", "com.android.thememanager",
      "com.miui.securitycenter", "com.miui.securitycenter:ui", "com.android.intentresolver:chooser",
      "tv.danmaku.bili"))
      assertTrue(process, supportsRetiredCollectionHint(process))
    for (process in listOf("", "system", "system_server"))
      assertFalse(process, supportsRetiredCollectionHint(process))
  }

  @Test fun transientRuntimeAuthorityFailureIsDetected() {
    assertTrue(isTransientRuntimeAuthorityFailure(IllegalArgumentException("Unknown authority os.kei.hyper.home.runtime")))
    assertFalse(isTransientRuntimeAuthorityFailure(IllegalArgumentException("Unsupported argument")))
    assertFalse(isTransientRuntimeAuthorityFailure(IllegalStateException("Unknown authority os.kei.hyper.home.runtime")))
  }

  @Test fun recoveryHonorsFrameworkState() {
    assertTrue(shouldRecoverTarget(State.FAILED))
    assertTrue(shouldRecoverTarget(State.STALE))
    assertFalse(shouldRecoverTarget(State.UP_TO_DATE))
    assertFalse(shouldRecoverTarget(State.RELOADING))
  }
}
