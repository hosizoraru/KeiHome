package os.kei.hyper.home.data

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libxposed.service.HookedTarget
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.FrameworkTarget

class InitialActivationPreferencesTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val scoped = FrameworkSnapshot("Test", 102, true, null, null)

  private fun isolated(block: (String) -> Unit) {
    val id = "activation_test_${UUID.randomUUID()}"
    try { block(id) } finally { AtomicFile(File(context.noBackupFilesDir, id)).delete() }
  }

  @Test fun skipAndProcessRecreationDoNotCompleteButRealBootChangeDoes() = isolated { id -> runBlocking {
    val first = InitialActivationPreferences(context, id) { 42 }
    first.observe(scoped)
    assertEquals(InitialActivationStatus.REBOOT_REQUIRED, first.state.value)
    val guideId = "${id}_guide"
    try {
      OnboardingPreferences(context, guideId, existingConfiguration = false).complete()
      val restored = InitialActivationPreferences(context, id) { 42 }
      restored.observe(scoped)
      assertEquals(InitialActivationStatus.REBOOT_REQUIRED, restored.state.value)
      val rebooted = InitialActivationPreferences(context, id) { 43 }
      rebooted.observe(null)
      assertEquals(InitialActivationStatus.COMPLETE, rebooted.state.value)
      val upgraded = InitialActivationPreferences(context, id) { null }
      upgraded.observe(scoped)
      assertEquals(InitialActivationStatus.COMPLETE, upgraded.state.value)
    } finally {
      AppLocalStorage.open(context, guideId).close()
      com.tencent.mmkv.MMKV.removeStorage(guideId)
    }
  } }

  @Test fun alreadyLoadedOlderGenerationDoesNotAskExistingUsersToReboot() = isolated { id -> runBlocking {
    val preferences = InitialActivationPreferences(context, id) { null }
    preferences.observe(scoped.copy(target = FrameworkTarget(HookedTarget.State.STALE, 109000394)))
    assertEquals(InitialActivationStatus.COMPLETE, preferences.state.value)
  } }

  @Test fun unknownBootAndFailedTargetDoNotClaimARebootWasObserved() = isolated { id -> runBlocking {
    val unknown = InitialActivationPreferences(context, id) { null }
    unknown.observe(scoped.copy(target = FrameworkTarget(HookedTarget.State.FAILED, 109000394)))
    assertEquals(InitialActivationStatus.UNKNOWN, unknown.state.value)
    val known = InitialActivationPreferences(context, id) { 42 }
    known.observe(scoped)
    assertEquals(InitialActivationStatus.REBOOT_REQUIRED, known.state.value)
  } }

  @Test fun rebootsBeforeScopeAuthorizationDoNotFulfilFirstActivation() = isolated { id -> runBlocking {
    InitialActivationPreferences(context, id) { 42 }.observe(scoped.copy(systemInScope = false))
    val beforeGrant = InitialActivationPreferences(context, id) { 43 }
    beforeGrant.observe(scoped)
    assertEquals(InitialActivationStatus.REBOOT_REQUIRED, beforeGrant.state.value)
    val afterGrant = InitialActivationPreferences(context, id) { 44 }
    afterGrant.observe(null)
    assertEquals(InitialActivationStatus.COMPLETE, afterGrant.state.value)
  } }
}
