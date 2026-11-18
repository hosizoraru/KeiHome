package os.kei.hyper.home.xposed

import android.content.Intent
import org.junit.Assert.assertFalse

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTaskIconProfileTest {
  @Test fun explicitRootLaunchCanBeVerifiedWithoutLauncherActionOrCategory() {
    fun allowed(action: String?, categories: Set<String>? = null, hasData: Boolean = false,
                flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK) = RecentTaskIconEligibility.allows(
      0, 0, action, categories, flags, hasData, false, false,
      "os.kei.fixture", "os.kei.fixture", setOf("os.kei.fixture"),
    )
    assertTrue(allowed(null))
    assertTrue(allowed(RecentTaskIconEligibility.MAIN))
    assertFalse(allowed(Intent.ACTION_VIEW))
    assertFalse(allowed(Intent.ACTION_DIAL))
    assertFalse(allowed(null, setOf(Intent.CATEGORY_DEFAULT)))
    assertFalse(allowed(null, hasData = true))
    assertFalse(allowed(null, flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
  }

  @Test fun inactiveLauncherTaskCanUseRetainedRootIdentityWithoutLiveBaseActivity() {
    fun allowed(roots: Set<String>, base: String? = null) = RecentTaskIconEligibility.allows(
      0, 0, RecentTaskIconEligibility.MAIN, setOf(RecentTaskIconEligibility.LAUNCHER),
      Intent.FLAG_ACTIVITY_NEW_TASK, false, false, false,
      "os.kei.fixture", base, roots,
    )
    assertTrue(allowed(setOf("os.kei.fixture")))
    assertFalse(allowed(emptySet()))
    assertFalse(allowed(setOf("os.kei.fixture", "foreign.identity")))
    assertFalse(allowed(setOf("os.kei.fixture"), "foreign.identity"))
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

  @Test fun allowsOnlyAPlainLauncherRootIntentForTheCurrentUser() {
    assertTrue(
      RecentTaskIconEligibility.allows(
        userId = 0, contextUserId = 0, action = RecentTaskIconEligibility.MAIN,
        categories = setOf(RecentTaskIconEligibility.LAUNCHER), flags = 0,
        hasData = false, hasType = false, hasSelector = false,
        componentPackage = "com.example.app", basePackage = "com.example.app",
        rootPackages = setOf("com.example.app"),
      ),
    )
  }

  @Test fun rejectsDocumentsBrowsersAndWorkProfiles() {
    fun allowed(
      user: Int = 0, action: String? = RecentTaskIconEligibility.MAIN,
      categories: Set<String>? = setOf(RecentTaskIconEligibility.LAUNCHER),
      flags: Int = 0, data: Boolean = false, type: Boolean = false,
      selector: Boolean = false,
    ) = RecentTaskIconEligibility.allows(
      userId = user, contextUserId = 0, action = action, categories = categories,
      flags = flags, hasData = data, hasType = type, hasSelector = selector,
      componentPackage = "com.example.app", basePackage = "com.example.app",
      rootPackages = setOf("com.example.app"),
    )

    assert(!allowed(flags = 0x00080000))
    assert(!allowed(action = "android.intent.action.VIEW"))
    assert(!allowed(categories = setOf(RecentTaskIconEligibility.LAUNCHER, "android.intent.category.DEFAULT")))
    assert(!allowed(data = true))
    assert(!allowed(type = true))
    assert(!allowed(selector = true))
    assert(!allowed(user = 10))
  }

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
