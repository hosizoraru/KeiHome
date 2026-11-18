package os.kei.hyper.home.xposed

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTaskIconProfileTest {
  @Test fun admitsAppOwnedTasksRetainedFromDialerDetailsAndAlertEntrypoints() {
    val dialerAndAlertFlags = Intent.FLAG_ACTIVITY_NEW_TASK
    val detailFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    assertTrue(allowed(flags = dialerAndAlertFlags))
    assertTrue(allowed(flags = detailFlags))
  }

  @Test fun inactiveLauncherTaskCanUseRetainedRootIdentityWithoutLiveBaseActivity() {
    assertTrue(allowed(roots = listOf("os.kei.fixture")))
    assertFalse(allowed(roots = emptyList()))
    assertFalse(allowed(roots = listOf("os.kei.fixture", "foreign.identity")))
  }
  private val c = RecentTaskIconContract

  @Test fun acceptsObservedDirectFillAndNativeHandlerDelivery() {
    c.verify(fixture())
  }

  @Test fun acceptsAnAuthenticatedFillWrapper() {
    val methods = fixture()
    methods[c.CREATE] = methods.getValue(c.CREATE).copy(calls = setOf(c.FILL))
    c.verify(methods)
  }

  @Test fun acceptsObservedDescriptionGetterAndRejectsDisconnectedLookalike() {
    val methods = fixture()
    methods[c.FILL_BODY] = methods.getValue(c.FILL_BODY).copy(
      reads = emptySet(),
      calls = setOf(c.GET_DESCRIPTION, "${c.DESCRIPTION}-><init>(${c.DESCRIPTION})V"),
    )
    methods[c.GET_DESCRIPTION] = RecentTaskIconMethod(
      c.GET_DESCRIPTION, emptySet(), setOf("${c.TASK}->mTaskDescription:${c.DESCRIPTION}"), emptySet(),
    )
    c.verify(methods)
    methods[c.GET_DESCRIPTION] = methods.getValue(c.GET_DESCRIPTION).copy(reads = emptySet())
    assertThrows(IllegalStateException::class.java) { c.verify(methods) }
  }

  @Test fun rejectsSameNamedGetterWithoutTaskDescriptionCopy() {
    val methods = fixture()
    methods[c.FILL_BODY] = methods.getValue(c.FILL_BODY).copy(calls = emptySet())
    assertThrows(IllegalStateException::class.java) { c.verify(methods) }
  }

  @Test fun rejectsNotifierWithoutNativeHandlerDelivery() {
    val methods = fixture()
    methods[c.NOTIFY] = methods.getValue(c.NOTIFY).copy(calls =
      methods.getValue(c.NOTIFY).calls - "Landroid/os/Message;->sendToTarget()V")
    assertThrows(IllegalStateException::class.java) { c.verify(methods) }
  }

  @Test fun rejectsDisconnectedDescriptionNotification() {
    val methods = fixture()
    methods[c.UPDATE] = methods.getValue(c.UPDATE).copy(calls = emptySet())
    assertThrows(IllegalStateException::class.java) { c.verify(methods) }
  }

  @Test fun rejectsFilenameSetterThatDoesNotClearOldIcon() {
    val methods = fixture()
    methods[c.SET_FILENAME] = methods.getValue(c.SET_FILENAME).copy(writes = setOf(c.FILENAME_FIELD))
    assertThrows(IllegalStateException::class.java) { c.verify(methods) }
  }

  @Test fun rejectsCopyThatLosesUserIdentity() {
    val methods = fixture()
    val id = "${c.INFO}-><init>(${c.INFO})V"
    methods[id] = methods.getValue(id).copy(writes = methods.getValue(id).writes - "${c.INFO}->userId:I")
    assertThrows(IllegalStateException::class.java) { c.verify(methods) }
  }

  @Test fun rejectsRemovalWithoutDeliveryRatherThanRetainingDeadTasks() {
    val methods = fixture()
    methods.remove(c.REMOVE)
    assertThrows(IllegalStateException::class.java) { c.verify(methods) }
  }

  @Test fun preservesDocumentAlternateIdentityAndOtherUserTasks() {
    assertFalse(allowed(flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
    assertFalse(allowed(flags = Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    assertFalse(allowed(flags = Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS))
    assertFalse(allowed(flags = Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
    assertFalse(allowed(alternateIdentity = true))
    assertFalse(allowed(user = 10))
  }

  @Test fun explicitIconReplacementKeepsTheExistingLauncherIntentBoundary() {
    assertTrue(RecentTaskIconEligibility.legacyLauncherIntent(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER), false))
    assertTrue(RecentTaskIconEligibility.legacyLauncherIntent(null, null, false))
    assertFalse(RecentTaskIconEligibility.legacyLauncherIntent(Intent.ACTION_VIEW, null, true))
    assertFalse(RecentTaskIconEligibility.legacyLauncherIntent(Intent.ACTION_DIAL, null, false))
    assertFalse(RecentTaskIconEligibility.legacyLauncherIntent(null, null, true))
  }

  private fun allowed(
    flags: Int = 0, alternateIdentity: Boolean = false,
    user: Int = 0, roots: List<String> = listOf("os.kei.fixture", "os.kei.fixture"),
  ) = RecentTaskIconEligibility.allows(user, 0, flags, alternateIdentity,
    "os.kei.fixture", roots)

  private fun fixture(): MutableMap<String, RecentTaskIconMethod> {
    fun method(id: String, calls: Set<String> = emptySet(), reads: Set<String> = emptySet(),
      writes: Set<String> = emptySet()) = RecentTaskIconMethod(id, calls, reads, writes)
    return listOf(
      method(c.CREATE, calls = setOf(c.FILL_BODY)),
      method(c.FILL, calls = setOf(c.FILL_BODY)),
      method(c.FILL_BODY, calls = setOf("${c.DESCRIPTION}-><init>(${c.DESCRIPTION})V"),
        reads = setOf("${c.TASK}->mTaskDescription:${c.DESCRIPTION}"),
        writes = setOf(c.DESCRIPTION_FIELD, "${c.INFO}->baseIntent:Landroid/content/Intent;")),
      method(c.NOTIFY, calls = setOf(
        "Landroid/os/Handler;->obtainMessage(ILjava/lang/Object;)Landroid/os/Message;",
        "Landroid/os/Message;->sendToTarget()V",
        "${c.CONTROLLER}->forAllLocalListeners(${c.CONSUMER}Landroid/os/Message;)V",
      ), reads = setOf(c.HANDLER)),
      method(c.UPDATE, calls = setOf(c.NOTIFY)),
      method(c.REMOVE, calls = setOf("Landroid/os/Message;->sendToTarget()V"), reads = setOf(c.HANDLER)),
      method(c.SET_ICON, writes = setOf(c.ICON_FIELD)),
      method(c.SET_FILENAME, writes = setOf(c.ICON_FIELD, c.FILENAME_FIELD)),
      method("${c.INFO}-><init>(${c.INFO})V", writes = setOf(
        "${c.INFO}->taskId:I", "${c.INFO}->userId:I", c.DESCRIPTION_FIELD,
        "${c.INFO}->topActivity:Landroid/content/ComponentName;",
        "${c.INFO}->origActivity:Landroid/content/ComponentName;",
        "${c.INFO}->baseIntent:Landroid/content/Intent;",
      )),
      method("${c.RECENT}-><init>(${c.RECENT})V", calls = setOf("${c.INFO}-><init>(${c.INFO})V")),
    ).associateByTo(linkedMapOf()) { it.id }
  }
}
