package os.kei.hyper.home.xposed

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiControlCenterRefreshProfileTest {
  @Test fun selectsNativeOwnerEventActualFlowAndExistingChildTree() {
    val root = resolve(root())
    assertEquals(OWNER, root.event.owner)
    assertEquals(CHILDREN, root.children.id)
    assertEquals(REPOSITORY, root.repository.id)
    assertEquals("getMaterial", root.flow.name)
    assertEquals("getValue", root.value.name)
    assertFalse(root.value.id == m(CcRootRole.NativeMode).id)
  }

  @Test fun rejectsUnrelatedOrUnregisteredLifecycleCallbacks() {
    rejects(root().copy(lifecycleObserverLinked = false))
    rejects(root().edit(CcRootRole.Event) { copy(parameters = parameters.drop(1)) })
    rejects(root().edit(CcRootRole.Create) { copy(calls = calls - ADD) })
    rejects(root().edit(CcRootRole.Destroy) { copy(calls = calls - REMOVE) })
    rejects(root().edit(CcRootRole.Event) { copy(reads = emptySet()) })
  }

  @Test fun requiresStartedAndDestroyedGuardsAndRecursiveInitializedDispatch() {
    rejects(root().edit(CcRootRole.StartedCollector) { copy(reads = reads - STARTED) })
    rejects(root().edit(CcRootRole.StartedCollector) { copy(reads = reads - DESTROYED) })
    rejects(root().edit(CcRootRole.LifecycleState) { copy(owner = "fixture.UnrelatedLifecycle") })
    rejects(root().edit(CcRootRole.Dispatch) { copy(calls = calls - id) })
    rejects(root().edit(CcRootRole.Dispatch) { copy(calls = calls.filterNot { "getInited" in it }.toSet()) })
    rejects(root().edit(CcRootRole.Init) { copy(reads = emptySet()) })
    val unrelated = field(OWNER, "userUnlockedListeners", "java.util.ArrayList", Modifier.FINAL)
    val graph = root()
    rejects(graph.copy(fields = graph.fields + (unrelated.id to unrelated))
      .edit(CcRootRole.ChildrenAccess) { copy(reads = setOf(unrelated.id)) })
  }

  @Test fun requiresActualRepositoryFlowAndPreservesDisabledAdmission() {
    rejects(root().edit(CcRootRole.FlowReader) { copy(calls = calls - m(CcRootRole.Flow).id) })
    rejects(root().edit(CcRootRole.Flow) { copy(writes = setOf(FLOW_FIELD)) })
    rejects(root().edit(CcRootRole.Value) { copy(owner = MODE) })
    rejects(root().edit(CcRootRole.FilterEmit) { copy(reads = emptySet()) })
    rejects(root().edit(CcRootRole.FilterEmit) { copy(calls = setOf("Lfixture/Sink;->emit(Ljava/lang/Object;)V")) })
    rejects(root().edit(CcRootRole.FilterCollect) { copy(calls = emptySet()) })
    rejects(root().copy(disabledSuperclass = "java.lang.Object"))
  }

  @Test fun tiesWrapperThemeToSelectedGateAndKeepsNativePowerRestriction() {
    rejects(root().edit(CcRootRole.NativeMode) { copy(calls = emptySet()) })
    rejects(root().edit(CcRootRole.Theme) { copy(calls = calls - THEME_CLEAR) })
    rejects(root().edit(CcRootRole.ThemeSelector) { copy(calls = emptySet()) })
    rejects(root().edit(CcRootRole.Policy) { copy(calls = emptySet()) })
    rejects(root().edit(CcRootRole.Policy) { copy(reads = emptySet()) })
  }

  @Test fun rejectsAmbiguousContractsEvenWhenTheyShareTheEvent() {
    val first = root()
    val second = first.edit(CcRootRole.Theme) { copy(id = methodId(OWNER, "otherTheme", "void")) }
    assertThrows(IllegalStateException::class.java) {
      ControlCenterRefreshContract.resolve(listOf(first, second), GATE)
    }
  }

  @Test fun selectsCompleteSmallTileNativeCleanup() {
    val match = tileResolve(tile())
    assertEquals(ICON_OWNER, match.update.owner)
    assertEquals(STATE_FIELD, match.state.id)
    assertEquals(ADDED, match.added.id)
    assertEquals(PENDING, match.pending.id)
    assertTrue(Modifier.isStatic(match.completion.modifiers))
  }

  @Test fun cleanupHelpersReturnBooleanEvenThoughTheWrapperReturnsVoid() {
    val candidate = tile()
    val clear = candidate.methods.getValue(CcTileRole.Clear)
    assertEquals("void", clear.result)
    assertTrue(SET_BLUR_MODE in clear.calls)
    assertTrue(CLEAR_BLEND in clear.calls)
    tileResolve(candidate)
    for (helper in listOf(SET_BLUR_MODE, CLEAR_BLEND)) {
      val wrongResult = helper.dropLast(1) + "V"
      val failure = rejectsTile(candidate.edit(CcTileRole.Clear) {
        copy(calls = calls - helper + wrongResult)
      })
      assertTrue(failure.message.orEmpty().contains("cleanup helper signatures require boolean results"))
      assertTrue(failure.message.orEmpty().contains(wrongResult))
    }
  }

  @Test fun rejectsPositiveOnlyTileRefreshAndMissingEnhancementCleanup() {
    rejectsTile(tile().edit(CcTileRole.Resources) { copy(calls = calls - t(CcTileRole.Clear).id) })
    rejectsTile(tile().edit(CcTileRole.Clear) { copy(calls = calls.filterNot { "clearBackgroundBlendConfig" in it }.toSet()) })
    rejectsTile(tile().copy(clearFirstNumber = 1))
    rejectsTile(tile().edit(CcTileRole.EnhancePolicy) { copy(calls = calls.filterNot { "disableBlurEnhanceFlagByMode" in it }.toSet()) })
    rejectsTile(tile().edit(CcTileRole.EnhancePolicy) { copy(calls = calls - m(CcRootRole.Policy).id) })
  }

  @Test fun requiresNativeCurrentStateAndAddedStateRelationships() {
    rejectsTile(tile().edit(CcTileRole.DefaultUpdate) { copy(reads = emptySet()) })
    rejectsTile(tile().edit(CcTileRole.Update) { copy(writes = writes - STATE_FIELD) })
    rejectsTile(tile().edit(CcTileRole.Config) { copy(reads = emptySet()) })
    rejectsTile(tile().edit(CcTileRole.Config) { copy(calls = calls - t(CcTileRole.DefaultUpdate).id) })
    rejectsTile(tile().edit(CcTileRole.Update) { copy(modifiers = Modifier.STATIC) })
  }

  @Test fun requiresPendingCompletionBeforeRevisionCanBeConsumed() {
    rejectsTile(tile().copy(completionFirstNumber = 1))
    rejectsTile(tile().copy(completionFirstWrite = ADDED))
    rejectsTile(tile().edit(CcTileRole.Completion) { copy(calls = emptySet()) })
    rejectsTile(tile().edit(CcTileRole.Completion) { copy(reads = emptySet()) })
    rejectsTile(tile().edit(CcTileRole.Update) { copy(calls = calls.filterNot { "->post(" in it }.toSet()) })
  }

  @Test fun rejectsMissingOrAmbiguousSmallTileContracts() {
    assertThrows(IllegalStateException::class.java) {
      ControlCenterSmallTileContract.resolve(emptyList(), resolve(root()))
    }
    val second = tile().edit(CcTileRole.Material) { copy(id = id.replace("onMaterialModeChanged", "anotherCallback")) }
    assertThrows(IllegalStateException::class.java) {
      ControlCenterSmallTileContract.resolve(listOf(tile(), second), resolve(root()))
    }
  }

  @Test fun reportsEachRejectedCandidateAndItsFailedRelationship() {
    val missingAdded = tile().edit(CcTileRole.Config) { copy(reads = emptySet()) }
    val positiveOnly = tile().edit(CcTileRole.EnhancePolicy) {
      copy(calls = calls.filterNot { "disableBlurEnhanceFlagByMode" in it }.toSet())
    }.edit(CcTileRole.Material) { copy(id = id.replace("onMaterialModeChanged", "otherMaterialCallback")) }
    val failure = assertThrows(IllegalStateException::class.java) {
      ControlCenterSmallTileContract.resolve(listOf(missingAdded, positiveOnly), resolve(root()))
    }
    val message = failure.message.orEmpty()
    assertTrue(message.contains("rejected=2"))
    assertTrue(message.contains("native added flag read by config"))
    assertTrue(message.contains("otherMaterialCallback"))
    assertTrue(message.contains("enhancement on/off paths"))
  }

  @Test fun discoveryFailuresRemainVisibleWhenNoGraphCouldBeRead() {
    val optional = optionalControlCenterRefresh {
      ControlCenterSmallTileContract.resolve(emptyList(), resolve(root()), listOf(
        "Lvendor/Tile;->onMaterialModeChanged(Lvendor/Mode;)V [discovery]: " +
          "Completion: expected one; found 2",
      ))
      error("Missing tile graph was accepted")
    }
    assertNull(optional.profile)
    assertTrue(optional.unavailableReason.orEmpty().contains("read=0; rejected=1"))
    assertTrue(optional.unavailableReason.orEmpty().contains("[discovery]: Completion: expected one; found 2"))
  }

  @Test fun memberAmbiguityReportsExactCandidateSignatures() {
    val failure = assertThrows(IllegalArgumentException::class.java) {
      ccUnique("native pending completion", listOf(
        "Lvendor/Icon;->first(Lvendor/Icon;ZZZ)V", "Lvendor/Icon;->second(Lvendor/Icon;ZZZ)V",
      )) { it }
    }
    assertTrue(failure.message.orEmpty().contains("found 2"))
    assertTrue(failure.message.orEmpty().contains("first(Lvendor/Icon;ZZZ)V"))
    assertTrue(failure.message.orEmpty().contains("second(Lvendor/Icon;ZZZ)V"))
  }

  @Test fun optionalFailureNeverEscapesIntoTheCoreMaterialProfile() {
    val missing = optionalControlCenterRefresh {
      resolve(root().copy(lifecycleObserverLinked = false))
      error("Invalid contract accepted")
    }
    assertNull(missing.profile)
    assertTrue(missing.unavailableReason.orEmpty().contains("Expected one complete control center root"))
    val linkage = optionalControlCenterRefresh { throw NoClassDefFoundError("optional tile") }
    assertNull(linkage.profile)
    assertTrue(linkage.unavailableReason.orEmpty().contains("optional tile"))
  }

  @Test fun selectsNativeTileBackgroundSelectorSetterAndBothOutlineCaches() {
    val match = backgroundResolve(background())
    assertEquals(b(CcTileBackgroundRole.Select).id, match.selector.id)
    assertEquals(b(CcTileBackgroundRole.SetEnabled).id, match.setter.id)
    assertEquals(BG_ENABLED, match.enabled.id)
    assertEquals(BG_DISABLED, match.disabled.id)
    assertEquals(t(CcTileRole.InternalUpdate).id, match.internalUpdate.id)
  }

  @Test fun backgroundCacheRequiresSelectedIconStateAndNativeResourceSelection() {
    rejectsBackground(background().edit(CcTileBackgroundRole.InternalUpdate) { copy(calls = calls - m(CcRootRole.Policy).id) })
    rejectsBackground(background().edit(CcTileBackgroundRole.InternalUpdate) { copy(writes = emptySet()) })
    rejectsBackground(background().edit(CcTileBackgroundRole.Select) { copy(owner = "fixture.Unrelated") })
    rejectsBackground(background().edit(CcTileBackgroundRole.Select) { copy(parameters = listOf("java.lang.Object")) })
    rejectsBackground(background().edit(CcTileBackgroundRole.Select) { copy(writes = setOf(STATE_FIELD)) })
    rejectsBackground(background().edit(CcTileBackgroundRole.Active) { copy(calls = calls - THEME_DRAWABLE) })
    rejectsBackground(background().edit(CcTileBackgroundRole.Disabled) { copy(reads = reads - BG_CONTEXT) })
    rejectsBackground(background().edit(CcTileBackgroundRole.Restricted) { copy(reads = emptySet()) })
  }

  @Test fun backgroundSetterMustOnlyAssignItsCacheAndInvalidateTheSameImage() {
    val withEffect = rejectsBackground(background().edit(CcTileBackgroundRole.SetEnabled) {
      copy(calls = calls + SET_BACKGROUND)
    })
    assertTrue(withEffect.message.orEmpty().contains("cache setter must only assign"))
    rejectsBackground(background().edit(CcTileBackgroundRole.SetEnabled) { copy(writes = writes + STATE_FIELD) })
    rejectsBackground(background().edit(CcTileBackgroundRole.SetEnabled) { copy(modifiers = Modifier.STATIC) })
    rejectsBackground(background().edit(CcTileBackgroundRole.SetDisabled) { copy(writes = setOf(BG_ENABLED)) })
    rejectsBackground(background().edit(CcTileBackgroundRole.SetEnabled) { copy(reads = emptySet()) })
    val graph = background()
    rejectsBackground(graph.copy(fields = graph.fields + (BG_ENABLED to
      graph.fields.getValue(BG_ENABLED).copy(modifiers = Modifier.FINAL))))
  }

  @Test fun backgroundInvalidationAcceptsOnlyTheNativeImageHierarchy() {
    backgroundResolve(background().edit(CcTileBackgroundRole.SetEnabled) {
      copy(calls = setOf("Lfixture/Image;->invalidateOutline()V"))
    })
    backgroundResolve(background().edit(CcTileBackgroundRole.SetEnabled) {
      copy(calls = setOf("Landroid/view/View;->invalidateOutline()V"))
    })
    rejectsBackground(background().edit(CcTileBackgroundRole.SetEnabled) {
      copy(calls = setOf("Lfixture/Unrelated;->invalidateOutline()V"))
    })
    rejectsBackground(background().copy(imageTypeHierarchy = listOf("fixture.Image", "java.lang.Object")))
    rejectsBackground(background().copy(imageTypeHierarchy = listOf("fixture.Other", "android.widget.ImageView")))
  }

  @Test fun backgroundCachesMustFeedNativeSizeAndInstalledOutline() {
    rejectsBackground(background().edit(CcTileBackgroundRole.Size) { copy(calls = emptySet()) })
    rejectsBackground(background().edit(CcTileBackgroundRole.SetRadius) { copy(reads = setOf(BG_ENABLED)) })
    rejectsBackground(background().edit(CcTileBackgroundRole.SetRadius) { copy(calls = emptySet()) })
    rejectsBackground(background().edit(CcTileBackgroundRole.EnabledAccess) { copy(reads = setOf(BG_DISABLED)) })
    rejectsBackground(background().edit(CcTileBackgroundRole.Outline) { copy(calls = calls - PLUGIN_THEME_GETTER) })
    rejectsBackground(background().copy(outlineInstalled = false))
    rejectsBackground(background().copy(outlineSuperclass = "java.lang.Object"))
  }

  @Test fun missingAmbiguousOrLinkageBackgroundCacheNeverRejectsExistingTileRefresh() {
    val tile = backgroundTile()
    val optional = optionalControlCenterTileBackgroundCache {
      ControlCenterTileBackgroundCacheContract.resolve(emptyList(), tile, resolve(root()), PLUGIN_THEME_GETTER,
        listOf("$ICON_OWNER [discovery]: Outline: expected one; found 0"))
      error("Missing native background cache accepted")
    }
    assertNull(optional.profile)
    assertTrue(optional.unavailableReason.orEmpty().contains("Outline: expected one; found 0"))
    val first = background()
    val otherSelect = b(CcTileBackgroundRole.Select).id.replace("getBackgroundDrawable", "otherBackground")
    val second = first.edit(CcTileBackgroundRole.Select) { copy(id = otherSelect) }
      .edit(CcTileBackgroundRole.InternalUpdate) {
        copy(calls = calls - b(CcTileBackgroundRole.Select).id + otherSelect)
      }
    val failure = assertThrows(IllegalStateException::class.java) {
      ControlCenterTileBackgroundCacheContract.resolve(listOf(first, second), tile, resolve(root()), PLUGIN_THEME_GETTER)
    }
    assertTrue(failure.message.orEmpty().contains("found 2"))
    val linkage = optionalControlCenterTileBackgroundCache { throw NoClassDefFoundError("optional background") }
    assertNull(linkage.profile)
    assertTrue(linkage.unavailableReason.orEmpty().contains("optional background"))
    assertEquals(STATE_FIELD, tileResolve(tile()).state.id)
  }

  @Test fun backgroundReflectionDoesNotInitializeOrInvokeItsUiOwner() {
    val owner = SensitiveBackground::class.java.name
    val drawable = "android.graphics.drawable.Drawable"
    val select = method(owner, "select", drawable, listOf("java.lang.Object"), Modifier.PRIVATE)
    val setter = method(owner, "cache", parameters = listOf(drawable), modifiers = Modifier.PRIVATE)
    val internal = method(owner, "update", parameters = listOf("java.lang.Object") + List(4) { "boolean" })
    val match = ControlCenterTileBackgroundMatch(select, setter,
      field(owner, "enabled", drawable), field(owner, "disabled", drawable), internal)
    val profile = reflectControlCenterTileBackgroundCache(requireNotNull(javaClass.classLoader), match, emptyList())
    assertSame(SensitiveBackground::class.java, profile.backgroundForState.declaringClass)
    assertSame(profile.enabledBackground.declaringClass, profile.setEnabledBackground.declaringClass)
    assertSame(profile.backgroundForState.returnType, profile.disabledBackground.type)
    assertTrue(profile.callers.isEmpty())
  }

  private class SensitiveBackground {
    @Suppress("unused") private var enabled: android.graphics.drawable.Drawable? = null
    @Suppress("unused") private var disabled: android.graphics.drawable.Drawable? = null
    @Suppress("UNUSED_PARAMETER") private fun select(state: Any): android.graphics.drawable.Drawable =
      error("Resolution invoked background selector")
    @Suppress("UNUSED_PARAMETER") private fun cache(drawable: android.graphics.drawable.Drawable) {
      error("Resolution invoked background setter")
    }
    @Suppress("UNUSED_PARAMETER") fun update(state: Any, a: Boolean, b: Boolean, c: Boolean, d: Boolean) {
      error("Resolution invoked icon update")
    }
    companion object {
      init { error("Resolution initialized icon UI owner") }
    }
  }

  @Test fun selectsSliderResourcesOnTheSameNativeHolder() {
    val match = sliderResolve(slider())
    assertEquals(s(CcSliderRole.Blend).id, match.blend.id)
    assertEquals(s(CcSliderRole.Resources).id, match.resources.id)
    assertEquals(match.blend.owner, match.resources.owner)
    assertEquals(listOf("boolean"), match.blend.parameters)
    assertTrue(match.resources.parameters.isEmpty())
    assertEquals(SLIDER_BASE, match.baseBlend.owner)
    assertEquals(s(CcSliderRole.DefaultBlend).id, match.defaultBlend.id)
  }

  @Test fun sliderRequiresInstanceSignaturesAndSelectedMaterialPolicy() {
    rejectsSlider(slider().edit(CcSliderRole.Blend) { copy(modifiers = Modifier.STATIC) })
    rejectsSlider(slider().edit(CcSliderRole.Resources) { copy(parameters = listOf("int")) })
    rejectsSlider(slider().edit(CcSliderRole.Resources) { copy(owner = "fixture.OtherHolder") })
    rejectsSlider(slider().edit(CcSliderRole.Blend) { copy(calls = calls - m(CcRootRole.Policy).id) })
    rejectsSlider(slider().edit(CcSliderRole.Material) { copy(calls = emptySet()) })
    rejectsSlider(slider().edit(CcSliderRole.DefaultBlend) { copy(calls = emptySet()) })
  }

  @Test fun sliderRequiresNativeConfigResourcesBeforeVirtualBlend() {
    val graph = slider()
    val failure = rejectsSlider(graph.copy(configurationCalls = graph.configurationCalls.reversed()))
    assertTrue(failure.message.orEmpty().contains("refresh resources before virtual blend"))
    rejectsSlider(graph.copy(holderAncestors = listOf("fixture.UnrelatedHolder")))
    rejectsSlider(graph.edit(CcSliderRole.Config) { copy(calls = emptySet()) })
    rejectsSlider(graph.edit(CcSliderRole.Configuration) { copy(calls = emptySet()) })
    rejectsSlider(graph.edit(CcSliderRole.BaseBlend) { copy(name = "unrelatedBlend") })
  }

  @Test fun sliderRequiresTheSameProgressTrackAndDisableStateForReload() {
    val failure = rejectsSlider(slider().edit(CcSliderRole.ProgressBlend) { copy(reads = reads - SLIDER_PROGRESS) })
    assertTrue(failure.message.orEmpty().contains("same progress field restored and blended"))
    rejectsSlider(slider().edit(CcSliderRole.Blend) { copy(reads = reads - SLIDER_TRACK) })
    rejectsSlider(slider().edit(CcSliderRole.Resources) { copy(reads = reads - SLIDER_DISABLED) })
    rejectsSlider(slider().edit(CcSliderRole.Resources) { copy(reads = reads - SLIDER_DISABLED_DRAWABLE) })
    val graph = slider()
    val binding = graph.fields.getValue(SLIDER_BINDING)
    rejectsSlider(graph.copy(fields = graph.fields + (binding.id to binding.copy(modifiers = Modifier.STATIC))))
  }

  @Test fun sliderRequiresDestructiveBlendAndCompleteNativeOffCleanup() {
    rejectsSlider(slider().copy(applyBlendNumbers = listOf(1L, 1L)))
    rejectsSlider(slider().copy(applyBlendCalls = listOf("Lfixture/Other;->unrelated()V")))
    rejectsSlider(slider().edit(CcSliderRole.ProgressBlend) { copy(calls = emptySet()) })
    rejectsSlider(slider().copy(clearFirstNumber = 1L))
    val failure = rejectsSlider(slider().edit(CcSliderRole.Clear) { copy(calls = calls - CLEAR_BLEND) })
    assertTrue(failure.message.orEmpty().contains("native off cleanup"))
  }

  @Test fun sliderResourcesMustForceFreshDrawablesAndPreserveSliderState() {
    rejectsSlider(slider().copy(resourceNumbers = listOf(0L, 0L, 0L)))
    rejectsSlider(slider().copy(resourceDefaultNumbers = listOf(2L, 0L)))
    rejectsSlider(slider().edit(CcSliderRole.ResourceLoad) { copy(calls = calls - GET_DRAWABLE) })
    rejectsSlider(slider().edit(CcSliderRole.Resources) { copy(writes = setOf(SLIDER_DISABLED)) })
    val failure = rejectsSlider(slider().edit(CcSliderRole.Resources) {
      copy(calls = calls + "Landroid/widget/ProgressBar;->setProgress(I)V")
    })
    assertTrue(failure.message.orEmpty().contains("forced fresh drawable reload without slider state writes"))
  }

  @Test fun sliderMissingAmbiguousOrLinkageFailuresStayOptionalAndDiagnostic() {
    val missing = optionalControlCenterSliderRefresh {
      ControlCenterSliderContract.resolve(emptyList(), resolve(root()),
        listOf("Lvendor/Holder;->updateBlendBlur(Z)V [discovery]: Resources: expected one; found 0"))
      error("Missing slider was accepted")
    }
    assertNull(missing.profile)
    assertTrue(missing.unavailableReason.orEmpty().contains("Resources: expected one; found 0"))
    val first = slider()
    val second = first.edit(CcSliderRole.Resources) { copy(id = methodId(SLIDER, "otherResources", "void")) }
      .edit(CcSliderRole.Config) { copy(calls = setOf(methodId(SLIDER, "otherResources", "void"))) }
    val failure = assertThrows(IllegalStateException::class.java) {
      ControlCenterSliderContract.resolve(listOf(first, second), resolve(root()))
    }
    assertTrue(failure.message.orEmpty().contains("found 2"))
    val linkage = optionalControlCenterSliderRefresh { throw NoClassDefFoundError("optional slider") }
    assertNull(linkage.profile)
    assertTrue(linkage.unavailableReason.orEmpty().contains("optional slider"))
    assertEquals(CHILDREN, resolve(root()).children.id)
  }

  @Test fun sliderReflectionDoesNotInitializeOrInvokeItsUiHolder() {
    val owner = SensitiveSlider::class.java.name
    val blend = method(owner, "updateBlendBlur", parameters = listOf("boolean"))
    val resources = method(owner, "updateResources", modifiers = Modifier.PRIVATE)
    val profile = reflectControlCenterSlider(requireNotNull(javaClass.classLoader),
      ControlCenterSliderMatch(blend, resources, blend, blend), emptyList())
    assertSame(SensitiveSlider::class.java, profile.updateBlendBlur.declaringClass)
    assertSame(profile.updateBlendBlur.declaringClass, profile.updateResources.declaringClass)
    assertTrue(Modifier.isPrivate(profile.updateResources.modifiers))
    assertTrue(profile.callers.isEmpty())
  }

  private class SensitiveSlider {
    @Suppress("UNUSED_PARAMETER") fun updateBlendBlur(themeChanged: Boolean) {
      error("Resolution invoked slider blend")
    }
    @Suppress("unused") private fun updateResources() { error("Resolution invoked slider resources") }
    companion object {
      init { error("Resolution initialized the slider UI holder") }
    }
  }

  @Test fun resolvingFieldsDoesNotInitializeTheUiOwner() {
    val owner = SensitiveOwner::class.java.name
    val field = reflectControlCenterField(requireNotNull(javaClass.classLoader),
      field(owner, "needsUpdate", "boolean", Modifier.PRIVATE))
    assertEquals("needsUpdate", field.name)
    assertFalse(Modifier.isStatic(field.modifiers))
  }

  @Test fun lifecycleGetterComesFromTheLiveFieldTypeWithoutInitializingEitherLoader() {
    val fixtureTypes = listOf(SensitiveLifecycleOwner::class.java, SensitiveLifecycle::class.java,
      SensitiveLifecycleState::class.java)
    val bytecode = fixtureTypes.associate { type ->
      type.name to requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))
        .use { it.readBytes() }
    }
    val pluginLoader = object : ClassLoader(javaClass.classLoader) {
      override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(this) {
          val loaded = findLoadedClass(name) ?: bytecode[name]?.let { defineClass(name, it, 0, it.size) }
            ?: super.loadClass(name, false)
          if (resolve) resolveClass(loaded)
          loaded
        }
    }
    val owner = Class.forName(SensitiveLifecycleOwner::class.java.name, false, pluginLoader)
    val field = owner.getDeclaredField("lifecycle")
    val node = method(SensitiveLifecycle::class.java.name, "getCurrentState",
      SensitiveLifecycleState::class.java.name, modifiers = Modifier.PUBLIC or Modifier.ABSTRACT)
    val getter = reflectControlCenterLifecycleState(field, node)
    assertSame(field.type, getter.declaringClass)
    assertSame(pluginLoader, getter.declaringClass.classLoader)
    assertNotSame(SensitiveLifecycle::class.java, getter.declaringClass)
    assertSame(Class.forName(node.result, false, pluginLoader), getter.returnType)
  }

  @Test fun lifecycleGetterRejectsOwnerAndDescriptorDrift() {
    val field = SensitiveLifecycleOwner::class.java.getDeclaredField("lifecycle")
    val node = method(SensitiveLifecycle::class.java.name, "getCurrentState",
      SensitiveLifecycleState::class.java.name, modifiers = Modifier.PUBLIC or Modifier.ABSTRACT)
    assertThrows(IllegalStateException::class.java) {
      reflectControlCenterLifecycleState(field, node.copy(owner = "fixture.UnrelatedLifecycle"))
    }
    assertThrows(IllegalStateException::class.java) {
      reflectControlCenterLifecycleState(field, node.copy(id = node.id.substringBefore(')') + ")I"))
    }
    assertThrows(IllegalStateException::class.java) {
      reflectControlCenterLifecycleState(field, node.copy(parameters = listOf("int")))
    }
  }

  private class SensitiveLifecycleOwner {
    @Suppress("unused") private var lifecycle: SensitiveLifecycle? = null
    companion object {
      init { error("Resolving a lifecycle field initialized the UI owner") }
    }
  }

  private abstract class SensitiveLifecycle {
    abstract fun getCurrentState(): SensitiveLifecycleState
    companion object {
      init { error("Resolving the getter initialized the lifecycle type") }
    }
  }

  private enum class SensitiveLifecycleState {
    STARTED;
    companion object {
      init { error("Resolving the getter initialized its state type") }
    }
  }

  private class SensitiveOwner {
    @Suppress("unused") private var needsUpdate = false
    companion object {
      init { error("Resolving a field initialized the UI owner") }
    }
  }

  private fun resolve(candidate: ControlCenterRootGraph) =
    ControlCenterRefreshContract.resolve(listOf(candidate), GATE)

  private fun rejects(candidate: ControlCenterRootGraph) =
    assertThrows(IllegalStateException::class.java) { resolve(candidate) }

  private fun tileResolve(candidate: ControlCenterTileGraph) =
    ControlCenterSmallTileContract.resolve(listOf(candidate), resolve(root()))

  private fun rejectsTile(candidate: ControlCenterTileGraph) =
    assertThrows(IllegalStateException::class.java) { tileResolve(candidate) }

  private fun sliderResolve(candidate: ControlCenterSliderGraph) =
    ControlCenterSliderContract.resolve(listOf(candidate), resolve(root()))

  private fun rejectsSlider(candidate: ControlCenterSliderGraph) =
    assertThrows(IllegalStateException::class.java) { sliderResolve(candidate) }

  private fun backgroundTile() = tileResolve(tile()
    .edit(CcTileRole.Resources) { copy(calls = calls + b(CcTileBackgroundRole.Size).id) }
    .edit(CcTileRole.Enhance) { copy(reads = setOf(BG_IMAGE)) })

  private fun backgroundResolve(candidate: ControlCenterTileBackgroundGraph) =
    ControlCenterTileBackgroundCacheContract.resolve(listOf(candidate), backgroundTile(), resolve(root()), PLUGIN_THEME_GETTER)

  private fun rejectsBackground(candidate: ControlCenterTileBackgroundGraph) =
    assertThrows(IllegalStateException::class.java) { backgroundResolve(candidate) }

  private fun ControlCenterRootGraph.edit(role: CcRootRole, change: MaterialMethod.() -> MaterialMethod) =
    copy(methods = methods + (role to methods.getValue(role).change()))

  private fun ControlCenterTileGraph.edit(role: CcTileRole, change: MaterialMethod.() -> MaterialMethod) =
    copy(methods = methods + (role to methods.getValue(role).change()))

  private fun ControlCenterSliderGraph.edit(role: CcSliderRole, change: MaterialMethod.() -> MaterialMethod) =
    copy(methods = methods + (role to methods.getValue(role).change()))

  private fun ControlCenterTileBackgroundGraph.edit(role: CcTileBackgroundRole, change: MaterialMethod.() -> MaterialMethod) =
    copy(methods = methods + (role to methods.getValue(role).change()))

  private fun m(role: CcRootRole): MaterialMethod = when (role) {
    CcRootRole.Event -> method(OWNER, "nativeLifecycle", "void", listOf(OWNER, LIFE_OWNER, EVENT), Modifier.STATIC)
    CcRootRole.Create -> method(OWNER, "onCreate")
    CcRootRole.Destroy -> method(OWNER, "onDestroy")
    CcRootRole.Init -> method(OWNER, "init")
    CcRootRole.Theme -> method(OWNER, "updateThemeRes")
    CcRootRole.ThemeSelector -> method(OWNER, "getThemeRes", "int")
    CcRootRole.Policy -> method(POLICY, "materialWithoutPower", "boolean", listOf("android.content.Context"), Modifier.STATIC)
    CcRootRole.NativeMode -> method(REPO, "getCurrentMaterialMode", MODE)
    CcRootRole.Flow -> method(REPO, "getMaterial", FLOW)
    CcRootRole.Value -> method(FLOW, "getValue", "java.lang.Object", modifiers = Modifier.PUBLIC or Modifier.ABSTRACT)
    CcRootRole.ChildrenAccess -> method(OWNER, "childrenAccess", "java.util.ArrayList", listOf(OWNER), Modifier.STATIC)
    CcRootRole.RepositoryAccess -> method(OWNER, "repoAccess", REPO, listOf(OWNER), Modifier.STATIC)
    CcRootRole.LifecycleAccess -> method(OWNER, "lifeAccess", LIFE, listOf(OWNER), Modifier.STATIC)
    CcRootRole.LifecycleState -> method(LIFE, "getCurrentState", "androidx.lifecycle.Lifecycle\$State",
      modifiers = Modifier.PUBLIC or Modifier.ABSTRACT)
    CcRootRole.StartedCollector -> method(COLLECTOR, "invokeSuspend", "java.lang.Object", listOf("java.lang.Object"))
    CcRootRole.CollectorConstructor -> method(COLLECTOR, "<init>", parameters = listOf(OWNER, "java.lang.Object"))
    CcRootRole.FlowReader -> method(READER, "invokeSuspend", "java.lang.Object", listOf("java.lang.Object"))
    CcRootRole.FilterConstructor -> method(FILTER, "<init>", parameters = listOf(FLOW))
    CcRootRole.FilterCollect -> method(FILTER, "collect", "java.lang.Object")
    CcRootRole.EmitConstructor -> method(EMITTER, "<init>")
    CcRootRole.FilterEmit -> method(EMITTER, "emit", "java.lang.Object")
    CcRootRole.Dispatch -> method(CHILD, "dispatchMaterialModeChanged", parameters = listOf(MODE))
  }

  private fun root(): ControlCenterRootGraph {
    val methods = CcRootRole.entries.associateWith(::m).toMutableMap()
    fun link(role: CcRootRole, calls: Set<String> = emptySet(), reads: Set<String> = emptySet(),
      writes: Set<String> = emptySet()) {
      methods[role] = m(role).copy(calls = calls, reads = reads, writes = writes)
    }
    fun id(role: CcRootRole) = m(role).id
    link(CcRootRole.Event, setOf("Lfixture/Child;->dispatchStart()V", "Lfixture/Child;->dispatchStop()V",
      "Lfixture/Child;->dispatchDestroy()V"), setOf(CHILDREN))
    link(CcRootRole.Create, setOf(ADD, methodId(READER, "<init>", "void")), setOf(LIFECYCLE))
    link(CcRootRole.Destroy, setOf(REMOVE), setOf(LIFECYCLE))
    link(CcRootRole.Init, setOf("Lfixture/Child;->dispatchCreate()V"), setOf(CHILDREN))
    link(CcRootRole.ChildrenAccess, reads = setOf(CHILDREN))
    link(CcRootRole.RepositoryAccess, reads = setOf(REPOSITORY))
    link(CcRootRole.LifecycleAccess, reads = setOf(LIFECYCLE))
    link(CcRootRole.Dispatch, setOf(id(CcRootRole.Dispatch), "Lfixture/ViewController;->getInited()Z",
      "Lfixture/Child;->getChildControllers()Ljava/util/Collection;", "Lfixture/Listener;->onMaterialModeChanged(Lfixture/Mode;)V"))
    link(CcRootRole.StartedCollector, setOf(id(CcRootRole.Dispatch), id(CcRootRole.ChildrenAccess),
      id(CcRootRole.LifecycleAccess), CURRENT_STATE, "Landroidx/lifecycle/WithLifecycleStateKt;->await()V"),
      setOf(STARTED, DESTROYED))
    link(CcRootRole.NativeMode, setOf(GATE), setOf(DISABLED_FIELD))
    link(CcRootRole.Flow, reads = setOf(FLOW_FIELD))
    link(CcRootRole.FlowReader, setOf(id(CcRootRole.CollectorConstructor), id(CcRootRole.RepositoryAccess),
      id(CcRootRole.Flow), id(CcRootRole.FilterConstructor)))
    link(CcRootRole.FilterConstructor, writes = setOf(FILTER_FLOW))
    link(CcRootRole.FilterCollect, setOf(id(CcRootRole.EmitConstructor)), setOf(FILTER_FLOW))
    link(CcRootRole.FilterEmit, setOf("Lkotlin/jvm/internal/l;->b(Ljava/lang/Object;Ljava/lang/Object;)Z",
      "Lfixture/Sink;->emit(Ljava/lang/Object;)V"), setOf(DISABLED_FIELD))
    link(CcRootRole.Theme, setOf(id(CcRootRole.ThemeSelector), THEME_CLEAR,
      "Landroid/view/ContextThemeWrapper;->setTheme(I)V", "Landroid/view/ContextThemeWrapper;->getThemeResId()I"))
    link(CcRootRole.ThemeSelector, setOf(id(CcRootRole.Policy)))
    link(CcRootRole.Policy, setOf(GATE), setOf(POWER))
    val fields = listOf(
      field(OWNER, "lifecycle", LIFE, Modifier.FINAL), field(OWNER, "children", "java.util.ArrayList", Modifier.FINAL),
      field(OWNER, "repository", REPO, Modifier.FINAL), field(REPO, "material", FLOW, Modifier.FINAL),
      field(FILTER, "flow", FLOW, Modifier.FINAL), field(DISABLED, "INSTANCE", DISABLED, Modifier.STATIC or Modifier.FINAL),
      field(POLICY, "superPower", "boolean", Modifier.STATIC),
    ).associateBy { it.id }
    return ControlCenterRootGraph(methods, fields, MODE, true)
  }

  private fun t(role: CcTileRole): MaterialMethod = when (role) {
    CcTileRole.Material -> method(TILE, "onMaterialModeChanged", parameters = listOf(MODE))
    CcTileRole.Config -> method(TILE, "onConfigurationChanged", parameters = listOf("int"))
    CcTileRole.Icon -> method(TILE, "getIcon", ICON_OWNER)
    CcTileRole.IconMaterial -> method(ICON_OWNER, "onMaterialModeChanged", parameters = listOf(MODE))
    CcTileRole.Resources -> method(ICON_OWNER, "updateResources")
    CcTileRole.Update -> method(ICON_OWNER, "updateIcon", parameters = listOf(STATE) + List(4) { "boolean" })
    CcTileRole.DefaultUpdate -> method(ICON_OWNER, "updateIcon\$default", parameters =
      listOf(ICON_OWNER, STATE) + List(4) { "boolean" } + listOf("int", "java.lang.Object"), modifiers = Modifier.STATIC)
    CcTileRole.InternalUpdate -> method(ICON_OWNER, "updateIconInternal", parameters = listOf(STATE) + List(4) { "boolean" })
    CcTileRole.Enhance -> method(ICON_OWNER, "updateBackgroundBlurEnhanceFlag")
    CcTileRole.EnhancePolicy -> method(POLICY, "updateBlurEnhanceClip", parameters = listOf("android.view.View", "boolean"))
    CcTileRole.Blend -> method(ICON_OWNER, "applyBlendColor", parameters = listOf(STATE, "boolean"))
    CcTileRole.Clear -> method("fixture.Common", "clearMiBlurBlendEffect", parameters = listOf("android.view.View"))
    CcTileRole.Completion -> method(ICON_OWNER, "postedCompletion", parameters =
      listOf(ICON_OWNER, "boolean", "boolean", "boolean"), modifiers = Modifier.STATIC)
  }

  private fun tile(): ControlCenterTileGraph {
    val methods = CcTileRole.entries.associateWith(::t).toMutableMap()
    fun id(role: CcTileRole) = t(role).id
    fun link(role: CcTileRole, calls: Set<String> = emptySet(), reads: Set<String> = emptySet(),
      writes: Set<String> = emptySet()) {
      methods[role] = t(role).copy(calls = calls, reads = reads, writes = writes)
    }
    link(CcTileRole.Material, setOf(id(CcTileRole.Icon), m(CcRootRole.Policy).id, id(CcTileRole.IconMaterial)))
    link(CcTileRole.Config, setOf(id(CcTileRole.Icon), id(CcTileRole.Resources),
      id(CcTileRole.DefaultUpdate), id(CcTileRole.Enhance)), setOf(ADDED))
    link(CcTileRole.Icon, reads = setOf(ICON_FIELD))
    link(CcTileRole.IconMaterial, setOf(id(CcTileRole.Enhance)))
    link(CcTileRole.Resources, setOf(m(CcRootRole.Policy).id, id(CcTileRole.Blend), id(CcTileRole.Clear)), setOf(STATE_FIELD))
    link(CcTileRole.Update, setOf(id(CcTileRole.InternalUpdate), "Landroid/view/View;->post(Ljava/lang/Runnable;)Z"),
      setOf(PENDING), setOf(STATE_FIELD, PENDING))
    link(CcTileRole.DefaultUpdate, setOf(id(CcTileRole.Update)), setOf(STATE_FIELD))
    link(CcTileRole.Enhance, setOf(id(CcTileRole.EnhancePolicy)))
    link(CcTileRole.EnhancePolicy, setOf(m(CcRootRole.Policy).id,
      "Lfixture/Policy;->enableBlurEnhanceFlagByMode(Landroid/view/View;)V",
      "Lfixture/Policy;->disableBlurEnhanceFlagByMode(Landroid/view/View;)V"))
    link(CcTileRole.Clear, setOf(SET_BLUR_MODE, CLEAR_BLEND))
    link(CcTileRole.Completion, setOf(id(CcTileRole.InternalUpdate)), setOf(STATE_FIELD), setOf(PENDING))
    val fields = listOf(field(TILE, "icon", ICON_OWNER), field(TILE, "added", "boolean"),
      field(ICON_OWNER, "state", STATE), field(ICON_OWNER, "pending", "boolean")).associateBy { it.id }
    return ControlCenterTileGraph(methods, fields, 0, PENDING, 0)
  }

  private fun s(role: CcSliderRole): MaterialMethod = when (role) {
    CcSliderRole.Blend -> method(SLIDER, "updateBlendBlur", parameters = listOf("boolean"))
    CcSliderRole.Resources -> method(SLIDER, "updateResources", modifiers = Modifier.PRIVATE)
    CcSliderRole.Config -> method(SLIDER, "onConfigurationChanged", parameters = listOf("int"))
    CcSliderRole.Configuration -> method(SLIDER_BASE, "updateConfiguration",
      parameters = listOf("android.content.res.Configuration"))
    CcSliderRole.Material -> method(SLIDER_BASE, "onMaterialModeChanged", parameters = listOf(MODE))
    CcSliderRole.BaseBlend -> method(SLIDER_BASE, "updateBlendBlur", parameters = listOf("boolean"))
    CcSliderRole.DefaultBlend -> method(SLIDER_BASE, "updateBlendBlur\$default",
      parameters = listOf(SLIDER_BASE, "boolean", "int", "java.lang.Object"), modifiers = Modifier.STATIC)
    CcSliderRole.ProgressBlend -> method(SLIDER, "updateProgressBlendColors")
    CcSliderRole.ApplyBlend -> method("fixture.Util", "applyBlendColor",
      parameters = listOf("android.view.View", "fixture.Token"), modifiers = Modifier.STATIC)
    CcSliderRole.Clear -> method("fixture.Common", "clearMiBlurBlendEffect", parameters = listOf("android.view.View"))
    CcSliderRole.ResourceDefaults -> method("fixture.Common", "setBackgroundResourceEx\$default",
      parameters = listOf("fixture.Common", "android.view.View", "int", "boolean", "int", "java.lang.Object"),
      modifiers = Modifier.STATIC)
    CcSliderRole.ResourceLoad -> method("fixture.Common", "setBackgroundResourceEx",
      parameters = listOf("android.view.View", "int", "boolean"))
  }

  private fun b(role: CcTileBackgroundRole): MaterialMethod = when (role) {
    CcTileBackgroundRole.InternalUpdate -> t(CcTileRole.InternalUpdate)
    CcTileBackgroundRole.Select -> method(ICON_OWNER, "getBackgroundDrawable", DRAWABLE, listOf(STATE))
    CcTileBackgroundRole.Active -> method(ICON_OWNER, "getActiveBackgroundDrawable", DRAWABLE, listOf(STATE))
    CcTileBackgroundRole.Disabled -> method(ICON_OWNER, "getDisabledBackgroundDrawable", DRAWABLE, listOf(STATE))
    CcTileBackgroundRole.Restricted -> method(ICON_OWNER, "isRestrictedState", "boolean")
    CcTileBackgroundRole.SetEnabled -> method(ICON_OWNER, "setEnabledBg", parameters = listOf(DRAWABLE))
    CcTileBackgroundRole.SetDisabled -> method(ICON_OWNER, "setDisabledBg", parameters = listOf(DRAWABLE))
    CcTileBackgroundRole.Size -> method(ICON_OWNER, "updateSize")
    CcTileBackgroundRole.Radius -> method(ICON_OWNER, "getCornerRadius", "float")
    CcTileBackgroundRole.SetRadius -> method(ICON_OWNER, "setCornerRadius", parameters = listOf("float"))
    CcTileBackgroundRole.EnabledAccess -> method(ICON_OWNER, "accessEnabled", DRAWABLE, listOf(ICON_OWNER), Modifier.STATIC)
    CcTileBackgroundRole.DisabledAccess -> method(ICON_OWNER, "accessDisabled", DRAWABLE, listOf(ICON_OWNER), Modifier.STATIC)
    CcTileBackgroundRole.Outline -> method(BG_PROVIDER, "getOutline",
      parameters = listOf("android.view.View", "android.graphics.Outline"))
  }

  private fun background(): ControlCenterTileBackgroundGraph {
    val methods = CcTileBackgroundRole.entries.associateWith(::b).toMutableMap()
    fun id(role: CcTileBackgroundRole) = b(role).id
    fun link(role: CcTileBackgroundRole, calls: Set<String> = emptySet(), reads: Set<String> = emptySet(),
      writes: Set<String> = emptySet()) {
      methods[role] = b(role).copy(calls = calls, reads = reads, writes = writes)
    }
    val selectors = listOf(CcTileBackgroundRole.Select, CcTileBackgroundRole.Active, CcTileBackgroundRole.Disabled)
    link(CcTileBackgroundRole.InternalUpdate, (selectors +
      listOf(CcTileBackgroundRole.SetEnabled, CcTileBackgroundRole.SetDisabled)).map(::id).toSet() + m(CcRootRole.Policy).id,
      setOf(BG_IMAGE), setOf(STATE_FIELD))
    link(CcTileBackgroundRole.Select, setOf(id(CcTileBackgroundRole.Active), id(CcTileBackgroundRole.Disabled),
      CONTEXT_THEME, THEME_DRAWABLE), setOf(BG_CONTEXT, BG_STATE, BG_RESOURCE))
    link(CcTileBackgroundRole.Active, setOf(CONTEXT_THEME, THEME_DRAWABLE), setOf(BG_CONTEXT, BG_STATE, BG_RESOURCE))
    link(CcTileBackgroundRole.Disabled, setOf(CONTEXT_THEME, THEME_DRAWABLE, id(CcTileBackgroundRole.Restricted)),
      setOf(BG_CONTEXT, BG_RESOURCE))
    link(CcTileBackgroundRole.Restricted, reads = setOf(STATE_FIELD))
    link(CcTileBackgroundRole.SetEnabled, setOf(BG_INVALIDATE), setOf(BG_IMAGE), setOf(BG_ENABLED))
    link(CcTileBackgroundRole.SetDisabled, setOf(BG_INVALIDATE), setOf(BG_IMAGE), setOf(BG_DISABLED))
    link(CcTileBackgroundRole.Size, setOf(id(CcTileBackgroundRole.Radius), id(CcTileBackgroundRole.SetRadius)),
      writes = setOf(BG_SIZE))
    link(CcTileBackgroundRole.Radius, reads = setOf(BG_SIZE))
    link(CcTileBackgroundRole.SetRadius, setOf(SET_GRADIENT_RADIUS), setOf(BG_ENABLED, BG_DISABLED))
    link(CcTileBackgroundRole.EnabledAccess, reads = setOf(BG_ENABLED))
    link(CcTileBackgroundRole.DisabledAccess, reads = setOf(BG_DISABLED))
    link(CcTileBackgroundRole.Outline, setOf(id(CcTileBackgroundRole.EnabledAccess),
      id(CcTileBackgroundRole.DisabledAccess), id(CcTileBackgroundRole.Radius),
      PLUGIN_THEME_GETTER, GRADIENT_RADIUS, OUTLINE_RECT, OUTLINE_ALPHA), setOf(BG_CAPTURE))
    val fields = listOf(field(ICON_OWNER, "context", "android.content.Context", Modifier.FINAL),
      field(ICON_OWNER, "image", "fixture.Image", Modifier.FINAL), field(ICON_OWNER, "state", STATE),
      field(ICON_OWNER, "enabled", DRAWABLE), field(ICON_OWNER, "disabled", DRAWABLE),
      field(ICON_OWNER, "size", "float"), field(BG_PROVIDER, "owner", ICON_OWNER, Modifier.FINAL),
      field(STATE, "state", "int"), field("fixture.R\$drawable", "background", "int", Modifier.STATIC)).associateBy { it.id }
    return ControlCenterTileBackgroundGraph(methods, fields, "android.view.ViewOutlineProvider", true,
      listOf("fixture.Image", "android.widget.ImageView"))
  }

  private fun slider(): ControlCenterSliderGraph {
    val methods = CcSliderRole.entries.associateWith(::s).toMutableMap()
    fun id(role: CcSliderRole) = s(role).id
    fun link(role: CcSliderRole, calls: Set<String> = emptySet(), reads: Set<String> = emptySet()) {
      methods[role] = s(role).copy(calls = calls, reads = reads)
    }
    val applyCalls = listOf(SET_BACKGROUND,
      "Lfixture/Util;->setMiViewBlurAndBlendColor(Landroid/view/View;ILfixture/Token;)V")
    val configurationCalls = listOf(methodId(SLIDER_BASE, "onConfigurationChanged", "void", listOf("int")),
      id(CcSliderRole.BaseBlend))
    link(CcSliderRole.Blend, setOf(m(CcRootRole.Policy).id, id(CcSliderRole.ProgressBlend), id(CcSliderRole.Clear)),
      setOf(SLIDER_BINDING, SLIDER_PROGRESS, SLIDER_TRACK))
    link(CcSliderRole.Resources, setOf(id(CcSliderRole.ResourceDefaults)),
      setOf(SLIDER_BINDING, SLIDER_PROGRESS, SLIDER_TRACK, SLIDER_DISABLED,
        SLIDER_DISABLED_DRAWABLE, SLIDER_DRAWABLE, SLIDER_TRACK_DRAWABLE))
    link(CcSliderRole.Config, setOf(id(CcSliderRole.Resources)))
    link(CcSliderRole.Configuration, configurationCalls.toSet())
    link(CcSliderRole.Material, setOf(id(CcSliderRole.DefaultBlend)))
    link(CcSliderRole.DefaultBlend, setOf(id(CcSliderRole.BaseBlend)))
    link(CcSliderRole.ProgressBlend, setOf(id(CcSliderRole.ApplyBlend)),
      setOf(SLIDER_BINDING, SLIDER_PROGRESS, SLIDER_DISABLED))
    link(CcSliderRole.ApplyBlend, applyCalls.toSet())
    link(CcSliderRole.Clear, setOf(SET_BLUR_MODE, CLEAR_BLEND))
    link(CcSliderRole.ResourceDefaults, setOf(id(CcSliderRole.ResourceLoad)))
    link(CcSliderRole.ResourceLoad, setOf(GET_CONTEXT, GET_DRAWABLE, SET_BACKGROUND))
    val fields = listOf(
      field(SLIDER, "binding", "fixture.SliderBinding", Modifier.FINAL),
      field(SLIDER, "disabled", "boolean"),
      field("fixture.SliderBinding", "progress", "fixture.View", Modifier.FINAL),
      field("fixture.SliderBinding", "track", "fixture.View", Modifier.FINAL),
      field("fixture.R\$drawable", "fill", "int", Modifier.STATIC),
      field("fixture.R\$drawable", "disabled", "int", Modifier.STATIC),
      field("fixture.R\$drawable", "track", "int", Modifier.STATIC),
    ).associateBy { it.id }
    return ControlCenterSliderGraph(methods, fields, listOf("fixture.IntermediateHolder", SLIDER_BASE),
      configurationCalls, applyCalls, listOf(0L, 1L), listOf(2L, 0L, 0L), listOf(2L, 1L), 0L)
  }

  private fun method(owner: String, name: String, result: String = "void", parameters: List<String> = emptyList(),
    modifiers: Int = Modifier.PUBLIC) =
    MaterialMethod(methodId(owner, name, result, parameters), owner, name, result, parameters, modifiers)

  private fun methodId(owner: String, name: String, result: String, parameters: List<String> = emptyList()) =
    "${sign(owner)}->$name(${parameters.joinToString("") { sign(it) }})${sign(result)}"

  private fun field(owner: String, name: String, type: String, modifiers: Int = Modifier.PRIVATE) =
    MaterialField("${sign(owner)}->$name:${sign(type)}", owner, type, modifiers)

  private fun sign(type: String) = when (type) {
    "void" -> "V"
    "boolean" -> "Z"
    "int" -> "I"
    "float" -> "F"
    else -> "L${type.replace('.', '/')};"
  }

  private companion object {
    const val OWNER = "fixture.Root"
    const val REPO = "fixture.Repository"
    const val FLOW = "fixture.Flow"
    const val POLICY = "fixture.Policy"
    const val MODE = "fixture.Mode"
    const val DISABLED = "fixture.Mode\$Disabled"
    const val CHILD = "fixture.Child"
    const val COLLECTOR = "fixture.Root\$Collector"
    const val READER = "fixture.Root\$Reader"
    const val FILTER = "fixture.Root\$Filter"
    const val EMITTER = "fixture.Root\$Emitter"
    const val LIFE = "androidx.lifecycle.Lifecycle"
    const val LIFE_OWNER = "androidx.lifecycle.LifecycleOwner"
    const val EVENT = "androidx.lifecycle.Lifecycle\$Event"
    const val GATE = "Lfixture/Blur;->themeGate(Landroid/content/Context;)Z"
    const val CHILDREN = "Lfixture/Root;->children:Ljava/util/ArrayList;"
    const val REPOSITORY = "Lfixture/Root;->repository:Lfixture/Repository;"
    const val LIFECYCLE = "Lfixture/Root;->lifecycle:Landroidx/lifecycle/Lifecycle;"
    const val FLOW_FIELD = "Lfixture/Repository;->material:Lfixture/Flow;"
    const val FILTER_FLOW = "Lfixture/Root\$Filter;->flow:Lfixture/Flow;"
    const val DISABLED_FIELD = "Lfixture/Mode\$Disabled;->INSTANCE:Lfixture/Mode\$Disabled;"
    const val POWER = "Lfixture/Policy;->superPower:Z"
    const val STARTED = "Landroidx/lifecycle/Lifecycle\$State;->STARTED:Landroidx/lifecycle/Lifecycle\$State;"
    const val DESTROYED = "Landroidx/lifecycle/Lifecycle\$State;->DESTROYED:Landroidx/lifecycle/Lifecycle\$State;"
    const val ADD = "Landroidx/lifecycle/Lifecycle;->addObserver(Landroidx/lifecycle/LifecycleObserver;)V"
    const val REMOVE = "Landroidx/lifecycle/Lifecycle;->removeObserver(Landroidx/lifecycle/LifecycleObserver;)V"
    const val CURRENT_STATE = "Landroidx/lifecycle/Lifecycle;->getCurrentState()Landroidx/lifecycle/Lifecycle\$State;"
    const val THEME_CLEAR = "Landroid/view/ContextThemeWrapper;->setTheme(Landroid/content/res/Resources\$Theme;)V"
    const val TILE = "fixture.Tile"
    const val ICON_OWNER = "fixture.Icon"
    const val STATE = "fixture.TileState"
    const val ICON_FIELD = "Lfixture/Tile;->icon:Lfixture/Icon;"
    const val ADDED = "Lfixture/Tile;->added:Z"
    const val STATE_FIELD = "Lfixture/Icon;->state:Lfixture/TileState;"
    const val PENDING = "Lfixture/Icon;->pending:Z"
    // From systemui-plugin.apk's CommonUtils.clearMiBlurBlendEffect disassembly.
    const val SET_BLUR_MODE = "Lmiui/systemui/util/MiBlurCompat;->setMiViewBlurModeCompat(Landroid/view/View;I)Z"
    const val CLEAR_BLEND = "Lmiuix/core/util/MiuiBlurUtils;->clearBackgroundBlendConfig(Landroid/view/View;)Z"
    const val SLIDER = "fixture.SliderHolder"
    const val SLIDER_BASE = "fixture.BaseHolder"
    const val SLIDER_BINDING = "Lfixture/SliderHolder;->binding:Lfixture/SliderBinding;"
    const val SLIDER_DISABLED = "Lfixture/SliderHolder;->disabled:Z"
    const val SLIDER_PROGRESS = "Lfixture/SliderBinding;->progress:Lfixture/View;"
    const val SLIDER_TRACK = "Lfixture/SliderBinding;->track:Lfixture/View;"
    const val SLIDER_DRAWABLE = "Lfixture/R\$drawable;->fill:I"
    const val SLIDER_DISABLED_DRAWABLE = "Lfixture/R\$drawable;->disabled:I"
    const val SLIDER_TRACK_DRAWABLE = "Lfixture/R\$drawable;->track:I"
    const val SET_BACKGROUND = "Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V"
    const val GET_CONTEXT = "Landroid/view/View;->getContext()Landroid/content/Context;"
    const val GET_DRAWABLE = "Landroid/content/Context;->getDrawable(I)Landroid/graphics/drawable/Drawable;"
    const val DRAWABLE = "android.graphics.drawable.Drawable"
    const val BG_PROVIDER = "fixture.IconOutline"
    const val BG_ENABLED = "Lfixture/Icon;->enabled:Landroid/graphics/drawable/Drawable;"
    const val BG_DISABLED = "Lfixture/Icon;->disabled:Landroid/graphics/drawable/Drawable;"
    const val BG_IMAGE = "Lfixture/Icon;->image:Lfixture/Image;"
    const val BG_CONTEXT = "Lfixture/Icon;->context:Landroid/content/Context;"
    const val BG_SIZE = "Lfixture/Icon;->size:F"
    const val BG_STATE = "Lfixture/TileState;->state:I"
    const val BG_RESOURCE = "Lfixture/R\$drawable;->background:I"
    const val BG_CAPTURE = "Lfixture/IconOutline;->owner:Lfixture/Icon;"
    const val BG_INVALIDATE = "Landroid/widget/ImageView;->invalidateOutline()V"
    const val CONTEXT_THEME = "Landroid/content/Context;->getTheme()Landroid/content/res/Resources\$Theme;"
    const val THEME_DRAWABLE = "Landroid/content/res/Resources\$Theme;->getDrawable(I)Landroid/graphics/drawable/Drawable;"
    const val PLUGIN_THEME_GETTER = "Lfixture/Theme;->getDefaultPluginTheme()Z"
    const val SET_GRADIENT_RADIUS = "Landroid/graphics/drawable/GradientDrawable;->setCornerRadius(F)V"
    const val GRADIENT_RADIUS = "Landroid/graphics/drawable/GradientDrawable;->getCornerRadius()F"
    const val OUTLINE_RECT = "Landroid/graphics/Outline;->setRoundRect(IIIIF)V"
    const val OUTLINE_ALPHA = "Landroid/graphics/Outline;->setAlpha(F)V"
  }
}
