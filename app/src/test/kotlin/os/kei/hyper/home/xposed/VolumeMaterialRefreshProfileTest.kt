package os.kei.hyper.home.xposed

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMaterialRefreshProfileTest {
  @Test fun selectsTheUniqueSharedInstanceFlag() {
    val match = VolumeMaterialRefreshContract.resolve(listOf(candidate()))
    assertEquals(SHOW, match.show.id)
    assertEquals(FLAG, match.flag.id)
  }

  @Test fun acceptsClearBeforeLoggingButRejectsLateClearAndTrueAssignment() {
    val candidate = candidate()
    assertEquals(FLAG, VolumeMaterialRefreshContract.resolve(listOf(candidate.copy(
      resetPrefix = candidate.resetPrefix.copy(opcodes = listOf(0x12, 0x5c)),
    ))).flag.id)
    rejects(candidate.copy(resetPrefix = candidate.resetPrefix.copy(firstNumber = 1L)))
    rejects(candidate.copy(resetPrefix = candidate.resetPrefix.copy(
      opcodes = listOf(0x38, 0x12, 0x5c),
    )))
    rejects(candidate.copy(resetPrefix = candidate.resetPrefix.copy(firstWrittenField = SHOWING)))
    rejects(candidate.copy(resetPrefix = candidate.resetPrefix.copy(
      firstCall = "Lvendor/Other;->mutate(Ljava/lang/String;Ljava/lang/String;)I",
    )))
  }

  @Test fun rejectsMissingConfigWriteOrPanelInitialization() {
    rejects(candidate().edit(CONFIG) { copy(writes = emptySet()) })
    rejects(candidate().edit(RESET) { copy(calls = emptySet()) })
    rejects(candidate().edit(SHOW) { copy(calls = emptySet()) })
  }

  @Test fun rejectsStaticMethodsOrImmutableFlags() {
    rejects(candidate().edit(SHOW) { copy(modifiers = Modifier.STATIC) })
    rejects(candidate().edit(CONFIG) { copy(modifiers = Modifier.STATIC) })
    val candidate = candidate()
    for (modifier in listOf(Modifier.STATIC, Modifier.FINAL)) {
      rejects(candidate.copy(graph = candidate.graph.copy(fields = candidate.graph.fields +
        (FLAG to candidate.graph.fields.getValue(FLAG).copy(modifiers = modifier)))))
    }
  }

  @Test fun rejectsAmbiguousSharedFlagsAndShowImplementations() {
    val otherFlag = "Lfixture/Volume;->anotherFlag:Z"
    val ambiguous = candidate().edit(SHOW) { copy(reads = reads + otherFlag) }
      .edit(CONFIG) { copy(writes = writes + otherFlag) }
      .edit(RESET) { copy(writes = writes + otherFlag) }
    rejects(ambiguous.copy(graph = ambiguous.graph.copy(fields = ambiguous.graph.fields +
      (otherFlag to MaterialField(otherFlag, OWNER, "boolean", Modifier.PUBLIC)))))
    val original = candidate()
    val otherShow = original.graph.methods.getValue(SHOW).copy(id = "Lfixture/Volume;->otherShow(I)V")
    val other = original.edit(DISPATCH) { copy(calls = setOf(otherShow.id)) }
      .let { it.copy(show = otherShow.id, graph = it.graph.copy(methods =
        it.graph.methods - SHOW + (otherShow.id to otherShow))) }
    assertThrows(IllegalStateException::class.java) {
      VolumeMaterialRefreshContract.resolve(listOf(original, other))
    }
  }

  @Test fun requiresVisibilityAndDialogFieldRelationships() {
    rejects(candidate().edit(SHOW) { copy(reads = reads - SHOWING) })
    rejects(candidate().edit(SHOW) { copy(reads = reads - DIALOG) })
    rejects(candidate().edit(INIT) { copy(writes = emptySet()) })
    rejects(candidate().edit(RESET) { copy(reads = reads - DIALOG) })
  }

  @Test fun requiresNativeMainHandlerDispatchAndControllerOwnership() {
    rejects(candidate().copy(handlerSuperclass = "java.lang.Object"))
    rejects(candidate().edit(DISPATCH) { copy(calls = emptySet()) })
    rejects(candidate().edit(DISPATCH) { copy(reads = emptySet()) })
    rejects(candidate().edit(CONSTRUCTOR) { copy(calls = setOf(HANDLER_INIT)) })
    rejects(candidate().edit(SHOW) { copy(reads = reads - HANDLER_FIELD) })
  }

  @Test fun optionalFailureIsReportedWithoutEscapingIntoCoreResolution() {
    val unavailable = resolveOptionalVolumeRefresh {
      VolumeMaterialRefreshContract.resolve(emptyList())
      error("Missing volume contract was accepted")
    }
    assertNull(unavailable.profile)
    assertTrue(unavailable.unavailableReason.orEmpty().startsWith(
      "Optional volume material refresh unavailable:",
    ))
    val linkageFailure = resolveOptionalVolumeRefresh { throw NoClassDefFoundError("optional view") }
    assertNull(linkageFailure.profile)
    assertTrue(linkageFailure.unavailableReason.orEmpty().contains("optional view"))
  }

  private fun rejects(candidate: VolumeRefreshCandidate) =
    assertThrows(IllegalStateException::class.java) {
      VolumeMaterialRefreshContract.resolve(listOf(candidate))
    }

  private fun VolumeRefreshCandidate.edit(id: String, change: MaterialMethod.() -> MaterialMethod) =
    copy(graph = graph.copy(methods = graph.methods + (id to graph.methods.getValue(id).change())))

  private fun candidate(): VolumeRefreshCandidate {
    val methods = listOf(
      method(SHOW, "showH", listOf("int"), calls = setOf(RESET),
        reads = setOf(FLAG, SHOWING, DIALOG, HANDLER_FIELD)),
      method(CONFIG, "onConfigChanged", listOf("int"), writes = setOf(FLAG)),
      method(RESET, "reInit", calls = setOf(LOG, INIT), reads = setOf(DIALOG, HANDLER_FIELD),
        writes = setOf(FLAG)),
      method(INIT, "initPanelView", reads = setOf(DIALOG), writes = setOf(SHOWING)),
      method(DISPATCH, "handleMessage", listOf("android.os.Message"), owner = HANDLER,
        calls = setOf(SHOW), reads = setOf(OUTER_FIELD)),
      method(CONSTRUCTOR, "<init>", listOf(OWNER), owner = HANDLER,
        calls = setOf(MAIN_LOOPER, HANDLER_INIT)),
    ).associateBy { it.id }
    val fields = listOf(
      MaterialField(FLAG, OWNER, "boolean", Modifier.PUBLIC),
      MaterialField(SHOWING, OWNER, "boolean", Modifier.PRIVATE),
      MaterialField(DIALOG, OWNER, "boolean", Modifier.PRIVATE),
      MaterialField(HANDLER_FIELD, OWNER, HANDLER, Modifier.PRIVATE),
      MaterialField(OUTER_FIELD, HANDLER, OWNER, Modifier.FINAL),
    ).associateBy { it.id }
    return VolumeRefreshCandidate(
      MaterialGraph(methods, fields), SHOW, CONFIG, RESET, INIT, DISPATCH, CONSTRUCTOR,
      "android.os.Handler", VolumeResetPrefix(listOf(0x54, 0x1a, 0x71, 0x12, 0x5c), 0, FLAG, LOG),
    )
  }

  private fun method(
    id: String, name: String, parameters: List<String> = emptyList(), owner: String = OWNER,
    calls: Set<String> = emptySet(), reads: Set<String> = emptySet(), writes: Set<String> = emptySet(),
  ) = MaterialMethod(id, owner, name, "void", parameters, Modifier.PUBLIC, calls, reads, writes)

  private companion object {
    const val OWNER = "fixture.Volume"
    const val HANDLER = "fixture.VolumeHandler"
    const val SHOW = "Lfixture/Volume;->showH(I)V"
    const val CONFIG = "Lfixture/Volume;->onConfigChanged(I)V"
    const val RESET = "Lfixture/Volume;->reInit()V"
    const val INIT = "Lfixture/Volume;->initPanelView()V"
    const val FLAG = "Lfixture/Volume;->needsRebuild:Z"
    const val SHOWING = "Lfixture/Volume;->mShowing:Z"
    const val DIALOG = "Lfixture/Volume;->mNeedShowDialog:Z"
    const val HANDLER_FIELD = "Lfixture/Volume;->handler:Lfixture/VolumeHandler;"
    const val OUTER_FIELD = "Lfixture/VolumeHandler;->owner:Lfixture/Volume;"
    const val DISPATCH = "Lfixture/VolumeHandler;->handleMessage(Landroid/os/Message;)V"
    const val CONSTRUCTOR = "Lfixture/VolumeHandler;-><init>(Lfixture/Volume;)V"
    const val MAIN_LOOPER = "Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;"
    const val HANDLER_INIT = "Landroid/os/Handler;-><init>(Landroid/os/Looper;)V"
    const val LOG = "Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I"
  }
}
