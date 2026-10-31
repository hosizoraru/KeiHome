package os.kei.hyper.home.xposed

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.util.DexSignUtil

/**
 * Handles only; resolution never initializes a plugin class or invokes UI code.
 *
 * After the original lifecycleEvent(owner, LifecycleOwner, Event) returns for
 * ON_START, runtime must check the main Looper and this owner's STARTED/RESUMED
 * lifecycle. Read repository -> materialFlow -> materialValue, preserving the
 * native Disabled filter, then updateThemeResources and dispatch to children.
 * Consume each owner's preference revision only after successful dispatch.
 */
internal data class SystemUiControlCenterRefreshProfile(
  val lifecycleEvent: Method,
  val lifecycleField: Field,
  val lifecycleState: Method,
  val updateThemeResources: Method,
  val childControllers: Field,
  val dispatchMaterialModeChanged: Method,
  val materialRepository: Field,
  val materialFlow: Method,
  val materialValue: Method,
  val disabledModeClass: Class<*>,
  val smallTile: ControlCenterSmallTileRefreshProfile,
  /** Selected hook callers, including at most one transparent static forwarding layer. */
  val callers: List<Method>,
  val slider: ControlCenterSliderRefreshProfile? = null,
  val sliderUnavailableReason: String? = null,
) {
  companion object {
    /** Run on the same owned worker as the core profile, with its exact APK/loader. */
    fun resolve(
      loader: ClassLoader,
      apkPath: String,
      materialProfile: SystemUiMaterialProfile,
      nativeLibraryCacheDir: File? = null,
    ): OptionalControlCenterRefresh = optionalControlCenterRefresh {
      check(DexSignUtil.getDescriptor(materialProfile.themedGate) == materialProfile.proof.themedGate)
      check(Class.forName(materialProfile.themedGate.declaringClass.name, false, loader) ===
        materialProfile.themedGate.declaringClass) { "Control center/core loader mismatch" }
      SystemUiMaterialDexKit.open(apkPath, nativeLibraryCacheDir).use { dex ->
        dex.setThreadNum(1)
        val reader = ControlCenterGraphReader(dex)
        val roots = reader.roots(materialProfile.proof.themedGate)
        val root = ControlCenterRefreshContract.resolve(roots, materialProfile.proof.themedGate, reader.rootFailures)
        val tiles = reader.tiles(root)
        val tile = ControlCenterSmallTileContract.resolve(tiles, root, reader.tileFailures)
        fun method(node: MaterialMethod) = reflectMaterialCaller(loader, node.id, node.modifiers)
        val flowValue = reflectControlCenterAbstractMethod(loader, root.value)
        val lifecycleField = reflectControlCenterField(loader, root.lifecycle)
        val lifecycleState = reflectControlCenterLifecycleState(lifecycleField, root.lifecycleState)
        val disabled = Class.forName(root.disabled.type, false, loader)
        check(Class.forName(root.dispatch.parameters.single(), false, loader).isAssignableFrom(disabled))
        val callers = (reader.hookCallers(root.event) + reader.hookCallers(tile.material) +
          reader.hookCallers(tile.completion)).distinctBy { it.id }.map(::method)
        val slider = optionalControlCenterSliderRefresh {
          val candidates = reader.sliders(root)
          val match = ControlCenterSliderContract.resolve(candidates, root, reader.sliderFailures)
          val sliderCallers = listOf(match.blend, match.baseBlend, match.defaultBlend)
            .flatMap(reader::hookCallers).distinctBy { it.id }.map(::method)
          reflectControlCenterSlider(loader, match, sliderCallers)
        }
        val backgroundCache = optionalControlCenterTileBackgroundCache {
          val match = ControlCenterTileBackgroundCacheContract.resolve(
            reader.tileBackgrounds(tile), tile, root, materialProfile.proof.pluginThemeGetter,
            reader.tileBackgroundFailures,
          )
          reflectControlCenterTileBackgroundCache(loader, match,
            reader.hookCallers(match.internalUpdate).map(::method))
        }
        SystemUiControlCenterRefreshProfile(
          method(root.event), lifecycleField, lifecycleState,
          method(root.theme), reflectControlCenterField(loader, root.children), method(root.dispatch),
          reflectControlCenterField(loader, root.repository), method(root.flow), flowValue, disabled,
          ControlCenterSmallTileRefreshProfile(
            method(tile.material), method(tile.icon), reflectControlCenterField(loader, tile.added),
            method(tile.resources), method(tile.update), reflectControlCenterField(loader, tile.state),
            method(tile.enhance), reflectControlCenterField(loader, tile.pending), method(tile.completion),
            backgroundCache.profile, backgroundCache.unavailableReason,
          ),
          callers, slider.profile, slider.unavailableReason,
        )
      }
    }
  }
}

/**
 * After the exact tile material callback, on main, use its existing icon and
 * state. When state is nonnull and updatePending is false: updateResources(),
 * updateIcon(state, false, tile.added, true, false), then updateEnhancement().
 * Never clear pending or pass null as the direct updateIcon state. If pending,
 * retry only after original pendingUpdateCompleted(icon, boolean, boolean,
 * boolean), or the next native tile callback; keep the revision unconsumed.
 */
internal data class ControlCenterSmallTileRefreshProfile(
  val materialChanged: Method,
  val icon: Method,
  val added: Field,
  val updateResources: Method,
  val updateIcon: Method,
  val currentState: Field,
  val updateEnhancement: Method,
  val updatePending: Field,
  val pendingUpdateCompleted: Method,
  val backgroundCache: ControlCenterTileBackgroundCacheProfile? = null,
  val backgroundCacheUnavailableReason: String? = null,
)

/**
 * Native cache bootstrap only; runtime owns real tile identity, main-thread and
 * effective-material admission. If neither cache is a GradientDrawable, select
 * with the current nonnull native state and accept only a GradientDrawable.
 * Call mutate() on that newly selected drawable before the native setter, then
 * retain updateResources -> updateIcon -> updateEnhancement ordering.
 *
 * The setter only assigns the cache and invalidates the image outline. It does
 * not mutate/clone the drawable; native updateResources later changes its radius.
 * A cached GradientDrawable with radius zero is valid and must not be replaced.
 * This preserves OEM scalar-radius behavior, not arbitrary custom artwork.
 * After internal updates, runtime may retry a missing cache without re-entering
 * updateIcon. Do not touch pending state or refill on every preference revision.
 */
internal data class ControlCenterTileBackgroundCacheProfile(
  val backgroundForState: Method,
  val setEnabledBackground: Method,
  val enabledBackground: Field,
  val disabledBackground: Field,
  val updateIconInternal: Method,
  val callers: List<Method>,
)

internal data class OptionalControlCenterTileBackgroundCache(
  val profile: ControlCenterTileBackgroundCacheProfile?,
  val unavailableReason: String?,
)

internal fun optionalControlCenterTileBackgroundCache(
  resolve: () -> ControlCenterTileBackgroundCacheProfile,
): OptionalControlCenterTileBackgroundCache = try {
  OptionalControlCenterTileBackgroundCache(resolve(), null)
} catch (failure: Exception) {
  OptionalControlCenterTileBackgroundCache(null, "Optional control center tile background cache unavailable: " +
    "${failure.javaClass.simpleName}: ${failure.message}")
} catch (failure: LinkageError) {
  OptionalControlCenterTileBackgroundCache(null, "Optional control center tile background cache unavailable: " +
    "${failure.javaClass.simpleName}: ${failure.message}")
}

/**
 * Before original updateBlendBlur on main, once per holder/preference revision,
 * invoke updateResources on that same receiver. The original then reapplies or
 * clears blending. This preserves native disableState, progress, and mirror state.
 */
internal data class ControlCenterSliderRefreshProfile(
  val updateBlendBlur: Method,
  val updateResources: Method,
  /** Includes calls through the inherited virtual declaration and default wrapper. */
  val callers: List<Method>,
)

internal data class OptionalControlCenterSliderRefresh(
  val profile: ControlCenterSliderRefreshProfile?,
  val unavailableReason: String?,
)

internal fun optionalControlCenterSliderRefresh(
  resolve: () -> ControlCenterSliderRefreshProfile,
): OptionalControlCenterSliderRefresh = try {
  OptionalControlCenterSliderRefresh(resolve(), null)
} catch (failure: Exception) {
  OptionalControlCenterSliderRefresh(null, "Optional control center slider refresh unavailable: " +
    "${failure.javaClass.simpleName}: ${failure.message}")
} catch (failure: LinkageError) {
  OptionalControlCenterSliderRefresh(null, "Optional control center slider refresh unavailable: " +
    "${failure.javaClass.simpleName}: ${failure.message}")
}

internal data class OptionalControlCenterRefresh(
  val profile: SystemUiControlCenterRefreshProfile?,
  val unavailableReason: String?,
)

internal fun optionalControlCenterRefresh(
  resolve: () -> SystemUiControlCenterRefreshProfile,
): OptionalControlCenterRefresh = try {
  OptionalControlCenterRefresh(resolve(), null)
} catch (failure: Exception) {
  OptionalControlCenterRefresh(null, "Optional control center material refresh unavailable: " +
    "${failure.javaClass.simpleName}: ${failure.message}")
} catch (failure: LinkageError) {
  OptionalControlCenterRefresh(null, "Optional control center material refresh unavailable: " +
    "${failure.javaClass.simpleName}: ${failure.message}")
}

internal enum class CcRootRole {
  Event, Create, Destroy, Init, Theme, ThemeSelector, Policy, NativeMode, Flow, Value,
  ChildrenAccess, RepositoryAccess, LifecycleAccess, LifecycleState, StartedCollector, CollectorConstructor,
  FlowReader, FilterConstructor, FilterCollect, EmitConstructor, FilterEmit, Dispatch,
}

internal data class ControlCenterRootGraph(
  val methods: Map<CcRootRole, MaterialMethod>,
  val fields: Map<String, MaterialField>,
  val disabledSuperclass: String?,
  val lifecycleObserverLinked: Boolean,
)

internal data class ControlCenterRootMatch(
  val event: MaterialMethod,
  val lifecycle: MaterialField,
  val theme: MaterialMethod,
  val children: MaterialField,
  val repository: MaterialField,
  val flow: MaterialMethod,
  val value: MaterialMethod,
  val disabled: MaterialField,
  val dispatch: MaterialMethod,
  val policy: MaterialMethod,
  val lifecycleState: MaterialMethod,
)

internal object ControlCenterRefreshContract {
  fun resolve(
    candidates: List<ControlCenterRootGraph>,
    gate: String,
    discoveryFailures: List<String> = emptyList(),
  ): ControlCenterRootMatch = selectControlCenterContract(
    "control center root", candidates, discoveryFailures,
    { it.methods[CcRootRole.Event]?.id ?: "<missing lifecycle event>" },
  ) { prove(it, gate) }

  private fun prove(c: ControlCenterRootGraph, gate: String): ControlCenterRootMatch {
    fun m(role: CcRootRole) = c.methods.getValue(role)
    fun field(id: String) = c.fields.getValue(id)
    fun accessor(role: CcRootRole, owner: String): MaterialField {
      val getter = m(role)
      check(getter.parameters == listOf(owner) && Modifier.isStatic(getter.modifiers) &&
        getter.owner == owner && getter.calls.isEmpty() && getter.writes.isEmpty())
      return field(getter.reads.single()).also {
        check(it.owner == owner && !Modifier.isStatic(it.modifiers) && getter.result == it.type)
      }
    }
    val event = m(CcRootRole.Event)
    val owner = event.owner
    check(event.result == "void" && Modifier.isStatic(event.modifiers) &&
      event.parameters == listOf(owner, CC_LIFECYCLE_OWNER, CC_EVENT) && c.lifecycleObserverLinked)
    val create = m(CcRootRole.Create)
    val destroy = m(CcRootRole.Destroy)
    val init = m(CcRootRole.Init)
    check(listOf(create, destroy, init).all { it.owner == owner && it.ccInstance("void") })
    check(CC_ADD_OBSERVER in create.calls && CC_REMOVE_OBSERVER in destroy.calls)
    val lifecycle = accessor(CcRootRole.LifecycleAccess, owner)
    check(lifecycle.type == CC_LIFECYCLE && lifecycle.id in create.reads && lifecycle.id in destroy.reads)
    val children = accessor(CcRootRole.ChildrenAccess, owner)
    check(children.type in CC_COLLECTIONS && Modifier.isFinal(children.modifiers) &&
      children.id in event.reads && children.id in init.reads)
    val dispatch = m(CcRootRole.Dispatch)
    check(dispatch.parameters.size == 1 && dispatch.ccInstance("void", dispatch.parameters.single()) &&
      dispatch.id in dispatch.calls && dispatch.calls.any { it.endsWith("->getInited()Z") } &&
      dispatch.calls.any { it.endsWith("->getChildControllers()Ljava/util/Collection;") } &&
      dispatch.calls.any { it.substringAfter("->").startsWith("onMaterialModeChanged(") })
    val base = "L${dispatch.owner.replace('.', '/')};->"
    check(listOf("dispatchStart", "dispatchStop", "dispatchDestroy").all { "$base$it()V" in event.calls } &&
      "${base}dispatchCreate()V" in init.calls)
    val collector = m(CcRootRole.StartedCollector)
    check(dispatch.id in collector.calls && m(CcRootRole.ChildrenAccess).id in collector.calls &&
      m(CcRootRole.LifecycleAccess).id in collector.calls && CC_STARTED in collector.reads &&
      CC_DESTROYED in collector.reads && CC_CURRENT_STATE in collector.calls &&
      collector.calls.any { it.startsWith("Landroidx/lifecycle/WithLifecycleStateKt;->") })
    val lifecycleState = m(CcRootRole.LifecycleState)
    check(lifecycleState.owner == lifecycle.type && lifecycleState.id == CC_CURRENT_STATE &&
      lifecycleState.name == "getCurrentState" && lifecycleState.parameters.isEmpty() &&
      lifecycleState.result == CC_STATE && !Modifier.isStatic(lifecycleState.modifiers) &&
      !Modifier.isNative(lifecycleState.modifiers))

    val nativeMode = m(CcRootRole.NativeMode)
    val repository = accessor(CcRootRole.RepositoryAccess, owner)
    val flow = m(CcRootRole.Flow)
    val value = m(CcRootRole.Value)
    check(repository.type == nativeMode.owner && nativeMode.ccInstance(dispatch.parameters.single()) &&
      gate in nativeMode.calls && flow.owner == repository.type && flow.ccInstance(flow.result) &&
      flow.calls.isEmpty() && flow.writes.isEmpty())
    val flowField = field(flow.reads.single())
    check(flowField.owner == flow.owner && flowField.type == flow.result &&
      !Modifier.isStatic(flowField.modifiers) && Modifier.isFinal(flowField.modifiers))
    check(value.owner == flow.result && value.name == "getValue" && value.result == "java.lang.Object" &&
      value.parameters.isEmpty() && !Modifier.isStatic(value.modifiers))
    val reader = m(CcRootRole.FlowReader)
    val collectorConstructor = m(CcRootRole.CollectorConstructor)
    check(collectorConstructor.owner == collector.owner &&
      collectorConstructor.name == "<init>" && owner in collectorConstructor.parameters &&
      collectorConstructor.id in reader.calls && m(CcRootRole.RepositoryAccess).id in reader.calls &&
      flow.id in reader.calls && create.calls.any { it.startsWith("L${reader.owner.replace('.', '/')};-><init>(") })
    val filterConstructor = m(CcRootRole.FilterConstructor)
    val collect = m(CcRootRole.FilterCollect)
    val emitConstructor = m(CcRootRole.EmitConstructor)
    val emit = m(CcRootRole.FilterEmit)
    check(filterConstructor.name == "<init>" && filterConstructor.id in reader.calls &&
      filterConstructor.owner == collect.owner && collect.name == "collect" &&
      (filterConstructor.writes intersect collect.reads).isNotEmpty() &&
      emitConstructor.name == "<init>" && emitConstructor.id in collect.calls &&
      emitConstructor.owner == emit.owner && emit.name == "emit")
    val disabled = (emit.reads intersect nativeMode.reads).map(::field).single {
      it.type.endsWith("\$Disabled") && it.owner == it.type &&
        Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
    }
    check(c.disabledSuperclass == dispatch.parameters.single() &&
      emit.calls.any { CC_EQUALS.matches(it) } &&
      emit.calls.any { it.substringAfter("->").startsWith("emit(") && !it.startsWith("L${emit.owner.replace('.', '/')};") })

    val theme = m(CcRootRole.Theme)
    val selector = m(CcRootRole.ThemeSelector)
    val policy = m(CcRootRole.Policy)
    check(theme.owner == owner && theme.ccInstance("void") &&
      selector.owner == owner && selector.ccInstance("int") &&
      selector.id in theme.calls && policy.id in selector.calls &&
      CC_THEME_ID in theme.calls && CC_THEME_CLEAR in theme.calls && CC_THEME_SET in theme.calls)
    check(policy.result == "boolean" && policy.parameters == listOf("android.content.Context") &&
      Modifier.isStatic(policy.modifiers) && gate in policy.calls && policy.writes.isEmpty())
    check(policy.reads.map(::field).any { it.type == "boolean" && Modifier.isStatic(it.modifiers) })
    return ControlCenterRootMatch(event, lifecycle, theme, children, repository, flow, value, disabled,
      dispatch, policy, lifecycleState)
  }
}

internal enum class CcTileRole {
  Material, Config, Icon, IconMaterial, Resources, Update, DefaultUpdate, InternalUpdate,
  Enhance, EnhancePolicy, Blend, Clear, Completion,
}

internal data class ControlCenterTileGraph(
  val methods: Map<CcTileRole, MaterialMethod>,
  val fields: Map<String, MaterialField>,
  val completionFirstNumber: Long?,
  val completionFirstWrite: String?,
  val clearFirstNumber: Long?,
)

internal data class ControlCenterTileMatch(
  val material: MaterialMethod,
  val icon: MaterialMethod,
  val added: MaterialField,
  val resources: MaterialMethod,
  val update: MaterialMethod,
  val state: MaterialField,
  val enhance: MaterialMethod,
  val pending: MaterialField,
  val completion: MaterialMethod,
)

internal object ControlCenterSmallTileContract {
  fun resolve(
    candidates: List<ControlCenterTileGraph>,
    root: ControlCenterRootMatch,
    discoveryFailures: List<String> = emptyList(),
  ): ControlCenterTileMatch = selectControlCenterContract(
    "small-tile cleanup contract", candidates, discoveryFailures,
    { it.methods[CcTileRole.Material]?.id ?: "<missing material callback>" },
  ) { prove(it, root) }

  private fun prove(c: ControlCenterTileGraph, root: ControlCenterRootMatch): ControlCenterTileMatch {
    fun m(role: CcTileRole) = checkNotNull(c.methods[role]) { "Missing method role: $role" }
    fun fields(ids: Set<String>) = ids.map { checkNotNull(c.fields[it]) { "Missing DEX field: $it" } }
    fun singleField(label: String, values: List<MaterialField>): MaterialField =
      ccUnique(label, values) { "${it.id} modifiers=${it.modifiers}" }
    val material = m(CcTileRole.Material)
    val config = m(CcTileRole.Config)
    val icon = m(CcTileRole.Icon)
    val resources = m(CcTileRole.Resources)
    val update = m(CcTileRole.Update)
    val defaults = m(CcTileRole.DefaultUpdate)
    val internal = m(CcTileRole.InternalUpdate)
    val enhance = m(CcTileRole.Enhance)
    val iconMaterial = m(CcTileRole.IconMaterial)
    check(material.ccInstance("void", root.dispatch.parameters.single()) &&
      config.owner == material.owner && config.ccInstance("void", "int") &&
      icon.owner == material.owner && icon.ccInstance(icon.result) &&
      listOf(resources, update, enhance, iconMaterial, internal, defaults).all { it.owner == icon.result }) {
      "tile/icon ownership or callback signature: icon=${icon.id}; config=${config.id}"
    }
    check(listOf(icon.id, root.policy.id, iconMaterial.id).all { it in material.calls } &&
      enhance.id in iconMaterial.calls &&
      listOf(icon.id, resources.id, defaults.id, enhance.id).all { it in config.calls }) {
      "material/config call links: material=${material.calls}; config=${config.calls}; icon=${iconMaterial.calls}"
    }
    check(resources.ccInstance("void") && enhance.ccInstance("void") &&
      update.parameters.size == 5 && update.parameters.drop(1) == List(4) { "boolean" } &&
      update.ccInstance("void", *update.parameters.toTypedArray()) &&
      internal.parameters == update.parameters && internal.ccInstance("void", *update.parameters.toTypedArray())) {
      "native icon update signatures: update=${update.id}; internal=${internal.id}"
    }
    val state = singleField("current-state field shared by update/resources/default wrapper",
      fields(update.writes intersect resources.reads intersect defaults.reads).filter {
      it.owner == icon.result && it.type == update.parameters.first() && it.ccMutable()
    })
    val added = singleField("native added flag read by config", fields(config.reads).filter {
      it.owner == material.owner && it.type == "boolean" && it.ccMutable()
    })
    val iconField = singleField("tile-owned icon field", fields(icon.reads).filter {
      it.owner == material.owner && it.type == icon.result
    })
    check(!Modifier.isStatic(iconField.modifiers)) { "Icon field is static: ${iconField.id}" }
    check(Modifier.isStatic(defaults.modifiers) && update.id in defaults.calls &&
      defaults.parameters == listOf(icon.result, state.type, "boolean", "boolean", "boolean", "boolean", "int", "java.lang.Object")) {
      "current-state default wrapper: ${defaults.id}; calls=${defaults.calls}"
    }
    val pending = singleField("mutable pending flag read/written by update",
      fields(update.reads intersect update.writes).filter {
      it.owner == icon.result && it.type == "boolean" && it.ccMutable()
    })
    val completion = m(CcTileRole.Completion)
    check(completion.owner == icon.result && Modifier.isStatic(completion.modifiers) &&
      completion.result == "void" && completion.parameters == listOf(icon.result, "boolean", "boolean", "boolean") &&
      pending.id in completion.writes && state.id in completion.reads &&
      c.completionFirstWrite == pending.id && c.completionFirstNumber == 0L &&
      internal.id in completion.calls && internal.id in update.calls &&
      update.calls.any { it.endsWith("->post(Ljava/lang/Runnable;)Z") }) {
      "pending completion: ${completion.id}; firstNumber=${c.completionFirstNumber}; " +
        "firstWrite=${c.completionFirstWrite}; calls=${completion.calls}"
    }
    val blend = m(CcTileRole.Blend)
    val clear = m(CcTileRole.Clear)
    check(listOf(root.policy.id, blend.id, clear.id).all { it in resources.calls } &&
      blend.owner == icon.result && blend.ccInstance("void", state.type, "boolean") &&
      clear.ccInstance("void", "android.view.View") && c.clearFirstNumber == 0L) {
      "resource on/off paths: clear=${clear.id}; firstNumber=${c.clearFirstNumber}; calls=${resources.calls}"
    }
    // The void cleanup wrapper ignores both helpers' boolean success results.
    check(clear.calls.any { it.endsWith("->setMiViewBlurModeCompat(Landroid/view/View;I)Z") } &&
      clear.calls.any { it.endsWith("->clearBackgroundBlendConfig(Landroid/view/View;)Z") }) {
      "cleanup helper signatures require boolean results: ${clear.id}; calls=${clear.calls}"
    }
    val enhancePolicy = m(CcTileRole.EnhancePolicy)
    check(enhancePolicy.id in enhance.calls && enhancePolicy.owner == root.policy.owner &&
      root.policy.id in enhancePolicy.calls &&
      enhancePolicy.calls.any { it.contains("->enableBlurEnhanceFlagByMode(") } &&
      enhancePolicy.calls.any { it.contains("->disableBlurEnhanceFlagByMode(") }) {
      "enhancement on/off paths: ${enhancePolicy.id}; calls=${enhancePolicy.calls}"
    }
    return ControlCenterTileMatch(material, icon, added, resources, update, state, enhance, pending, completion)
  }
}

internal enum class CcTileBackgroundRole {
  InternalUpdate, Select, Active, Disabled, Restricted, SetEnabled, SetDisabled,
  Size, Radius, SetRadius, EnabledAccess, DisabledAccess, Outline,
}

internal data class ControlCenterTileBackgroundGraph(
  val methods: Map<CcTileBackgroundRole, MaterialMethod>,
  val fields: Map<String, MaterialField>,
  val outlineSuperclass: String?,
  val outlineInstalled: Boolean,
  val imageTypeHierarchy: List<String>,
)

internal data class ControlCenterTileBackgroundMatch(
  val selector: MaterialMethod,
  val setter: MaterialMethod,
  val enabled: MaterialField,
  val disabled: MaterialField,
  val internalUpdate: MaterialMethod,
)

internal object ControlCenterTileBackgroundCacheContract {
  fun resolve(
    candidates: List<ControlCenterTileBackgroundGraph>,
    tile: ControlCenterTileMatch,
    root: ControlCenterRootMatch,
    pluginThemeGetter: String,
    discoveryFailures: List<String> = emptyList(),
  ): ControlCenterTileBackgroundMatch = selectControlCenterContract(
    "small-tile background cache contract", candidates, discoveryFailures,
    { it.methods[CcTileBackgroundRole.Select]?.id ?: "<missing background selector>" },
  ) { prove(it, tile, root, pluginThemeGetter) }

  private fun prove(
    c: ControlCenterTileBackgroundGraph,
    tile: ControlCenterTileMatch,
    root: ControlCenterRootMatch,
    pluginThemeGetter: String,
  ): ControlCenterTileBackgroundMatch {
    fun m(role: CcTileBackgroundRole) = checkNotNull(c.methods[role]) { "Missing background role: $role" }
    fun fields(ids: Set<String>) = ids.map { checkNotNull(c.fields[it]) { "Missing background field: $it" } }
    fun single(label: String, values: List<MaterialField>) = ccUnique(label, values) { it.id }
    val owner = tile.icon.result
    val internal = m(CcTileBackgroundRole.InternalUpdate)
    val select = m(CcTileBackgroundRole.Select)
    val active = m(CcTileBackgroundRole.Active)
    val disabled = m(CcTileBackgroundRole.Disabled)
    val restricted = m(CcTileBackgroundRole.Restricted)
    val setter = m(CcTileBackgroundRole.SetEnabled)
    val disabledSetter = m(CcTileBackgroundRole.SetDisabled)
    val size = m(CcTileBackgroundRole.Size)
    val radius = m(CcTileBackgroundRole.Radius)
    val setRadius = m(CcTileBackgroundRole.SetRadius)
    val selectors = listOf(select, active, disabled)
    check(listOf(internal, restricted, setter, disabledSetter, size, radius, setRadius).plus(selectors)
      .all { it.owner == owner } &&
      internal.ccInstance("void", *tile.update.parameters.toTypedArray()) &&
      internal.id in tile.update.calls && internal.id in tile.completion.calls &&
      tile.state.id in internal.writes && root.policy.id in internal.calls &&
      listOf(select, active, disabled, setter, disabledSetter).all { it.id in internal.calls }) {
      "selected icon update/current state/native background call links: ${internal.id}; calls=${internal.calls}"
    }
    check(selectors.all { it.ccInstance(CC_DRAWABLE, tile.state.type) && it.writes.isEmpty() } &&
      restricted.ccInstance("boolean") && restricted.writes.isEmpty() &&
      tile.state.id in restricted.reads && restricted.id in disabled.calls &&
      listOf(active.id, disabled.id).all { it in select.calls } &&
      selectors.all { CC_CONTEXT_THEME in it.calls && CC_THEME_DRAWABLE in it.calls &&
        pluginThemeGetter !in it.calls && root.policy.id !in it.calls }) {
      "state-selected themed drawables without state writes/material substitution: ${selectors.map { it.id }}"
    }
    val context = single("shared native resource context", fields(
      select.reads intersect active.reads intersect disabled.reads).filter {
      it.owner == owner && it.type == "android.content.Context" &&
        !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
    })
    check(selectors.all { candidate ->
      candidate.reads.any { id -> c.fields.getValue(id).let {
        it.type == "int" && Modifier.isStatic(it.modifiers) && it.owner.endsWith("\$drawable")
      } }
    } && fields(select.reads).any { it.owner == tile.state.type && it.type == "int" } &&
      fields(active.reads).any { it.owner == tile.state.type && it.type == "int" }) {
      "native state/resource selection: context=${context.id}"
    }
    val enabledField = single("enabled cache written by native setter", fields(setter.writes).filter {
      it.owner == owner && it.type == CC_DRAWABLE && it.ccMutable()
    })
    val disabledField = single("disabled cache written by native setter", fields(disabledSetter.writes).filter {
      it.owner == owner && it.type == CC_DRAWABLE && it.ccMutable()
    })
    val image = single("same image used by cache invalidation/update/enhancement",
      fields(setter.reads intersect disabledSetter.reads intersect internal.reads intersect tile.enhance.reads)
        .filter { it.owner == owner && !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) })
    check(c.imageTypeHierarchy.firstOrNull() == image.type &&
      "android.widget.ImageView" in c.imageTypeHierarchy) { "cache outline target must inherit native ImageView" }
    // DEX can name the framework declaration for this inherited method.
    val invalidates = (c.imageTypeHierarchy + "android.view.View")
      .map { "L${it.replace('.', '/')};->invalidateOutline()V" }.toSet()
    check(enabledField.id != disabledField.id &&
      listOf(setter to enabledField, disabledSetter to disabledField).all { (method, field) ->
        method.ccInstance("void", CC_DRAWABLE) && method.writes == setOf(field.id) &&
          method.reads == setOf(image.id) && method.calls.size == 1 && method.calls.single() in invalidates
      }) { "cache setter must only assign its drawable and invalidate the native image: ${setter.id}" }
    val caches = setOf(enabledField.id, disabledField.id)
    check(size.ccInstance("void") && size.id in tile.resources.calls &&
      listOf(radius.id, setRadius.id).all { it in size.calls } &&
      radius.ccInstance("float") && fields(radius.reads intersect size.writes).any {
        it.owner == owner && it.type == "float" && it.ccMutable()
      } &&
      setRadius.ccInstance("void", "float") && setRadius.reads == caches &&
      setRadius.writes.isEmpty() && setRadius.calls == setOf(CC_SET_GRADIENT_RADIUS)) {
      "native resource size/radius must update the same two caches: ${setRadius.id}"
    }
    val enabledAccess = m(CcTileBackgroundRole.EnabledAccess)
    val disabledAccess = m(CcTileBackgroundRole.DisabledAccess)
    check(listOf(enabledAccess to enabledField, disabledAccess to disabledField).all { (access, field) ->
      access.owner == owner && Modifier.isStatic(access.modifiers) && access.result == CC_DRAWABLE &&
        access.parameters == listOf(owner) && access.reads == setOf(field.id) &&
        access.writes.isEmpty() && access.calls.isEmpty()
    }) { "outline accessors must read the same cache fields" }
    val outline = m(CcTileBackgroundRole.Outline)
    check(c.outlineInstalled && c.outlineSuperclass == "android.view.ViewOutlineProvider" &&
      outline.ccInstance("void", "android.view.View", "android.graphics.Outline") &&
      outline.writes.isEmpty() && fields(outline.reads).count {
        it.owner == outline.owner && it.type == owner &&
          !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
      } == 1 &&
      listOf(enabledAccess.id, disabledAccess.id, radius.id, pluginThemeGetter, CC_GRADIENT_RADIUS,
        CC_OUTLINE_RECT, CC_OUTLINE_ALPHA).all { it in outline.calls }) {
      "installed native outline must consume these caches and actual theme fallback: ${outline.id}"
    }
    return ControlCenterTileBackgroundMatch(select, setter, enabledField, disabledField, internal)
  }
}

internal enum class CcSliderRole {
  Blend, Resources, Config, Configuration, Material, BaseBlend, DefaultBlend,
  ProgressBlend, ApplyBlend, Clear, ResourceDefaults, ResourceLoad,
}

internal data class ControlCenterSliderGraph(
  val methods: Map<CcSliderRole, MaterialMethod>,
  val fields: Map<String, MaterialField>,
  val holderAncestors: List<String>,
  val configurationCalls: List<String>,
  val applyBlendCalls: List<String>,
  val applyBlendNumbers: List<Long>,
  val resourceNumbers: List<Long>,
  val resourceDefaultNumbers: List<Long>,
  val clearFirstNumber: Long?,
)

internal data class ControlCenterSliderMatch(
  val blend: MaterialMethod,
  val resources: MaterialMethod,
  val baseBlend: MaterialMethod,
  val defaultBlend: MaterialMethod,
)

internal object ControlCenterSliderContract {
  fun resolve(
    candidates: List<ControlCenterSliderGraph>,
    root: ControlCenterRootMatch,
    discoveryFailures: List<String> = emptyList(),
  ): ControlCenterSliderMatch = selectControlCenterContract(
    "slider resource cleanup contract", candidates, discoveryFailures,
    { it.methods[CcSliderRole.Blend]?.id ?: "<missing slider blend method>" },
  ) { prove(it, root) }

  private fun prove(c: ControlCenterSliderGraph, root: ControlCenterRootMatch): ControlCenterSliderMatch {
    fun m(role: CcSliderRole) = checkNotNull(c.methods[role]) { "Missing slider role: $role" }
    fun fields(ids: Set<String>) = ids.map { checkNotNull(c.fields[it]) { "Missing slider field: $it" } }
    fun single(label: String, values: List<MaterialField>) = ccUnique(label, values) { it.id }
    val blend = m(CcSliderRole.Blend)
    val resources = m(CcSliderRole.Resources)
    val config = m(CcSliderRole.Config)
    val progressBlend = m(CcSliderRole.ProgressBlend)
    check(blend.ccInstance("void", "boolean") && resources.ccInstance("void") &&
      config.ccInstance("void", "int") && progressBlend.ccInstance("void") &&
      listOf(resources, config, progressBlend).all { it.owner == blend.owner } &&
      root.policy.id in blend.calls && progressBlend.id in blend.calls) {
      "slider ownership/signatures or selected policy link: ${blend.id}; calls=${blend.calls}"
    }
    val configuration = m(CcSliderRole.Configuration)
    val material = m(CcSliderRole.Material)
    val baseBlend = m(CcSliderRole.BaseBlend)
    val defaults = m(CcSliderRole.DefaultBlend)
    val base = baseBlend.owner
    val baseConfig = "L${base.replace('.', '/')};->${config.name}(I)V"
    check(base in c.holderAncestors && base != blend.owner && baseBlend.ccInstance("void", "boolean") &&
      baseBlend.name == blend.name && configuration.owner == base &&
      configuration.ccInstance("void", "android.content.res.Configuration") &&
      material.owner == base && material.ccInstance("void", root.dispatch.parameters.single()) &&
      defaults.owner == base && Modifier.isStatic(defaults.modifiers) && defaults.result == "void" &&
      defaults.parameters == listOf(base, "boolean", "int", "java.lang.Object") &&
      defaults.id in material.calls && baseBlend.id in defaults.calls) {
      "inherited material dispatch/default wrapper: base=${baseBlend.id}; ancestors=${c.holderAncestors}"
    }
    val configIndex = c.configurationCalls.indexOf(baseConfig)
    val blendIndex = c.configurationCalls.indexOf(baseBlend.id)
    check(baseConfig in configuration.calls && baseBlend.id in configuration.calls &&
      configIndex >= 0 && blendIndex > configIndex && resources.id in config.calls &&
      resources.writes.isEmpty()) {
      "native configuration must refresh resources before virtual blend: ${c.configurationCalls}; config=${config.calls}"
    }
    val binding = single("shared slider binding", fields(resources.reads intersect
      progressBlend.reads intersect blend.reads).filter {
      it.owner == blend.owner && !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
    })
    val progress = single("same progress field restored and blended",
      fields(resources.reads intersect progressBlend.reads intersect blend.reads).filter {
        it.owner == binding.type && !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
      })
    val track = single("same track field restored and cleared", fields(resources.reads intersect blend.reads).filter {
      it.owner == binding.type && it.id != progress.id && it.type == progress.type &&
        !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
    })
    val disabled = single("existing slider disableState", fields(resources.reads intersect progressBlend.reads).filter {
      it.owner == blend.owner && it.type == "boolean" && it.ccMutable()
    })
    val drawableFields = fields(resources.reads).filter {
      Modifier.isStatic(it.modifiers) && it.type == "int" && it.owner.endsWith("\$drawable")
    }
    check(drawableFields.size == 3 && drawableFields.map { it.owner }.distinct().size == 1 &&
      resources.reads.filter { c.fields.getValue(it).owner == binding.type }.toSet() == setOf(progress.id, track.id) &&
      disabled.id !in resources.writes) { "enabled/disabled fill and track drawable relationship: ${resources.reads}" }
    val apply = m(CcSliderRole.ApplyBlend)
    val clear = m(CcSliderRole.Clear)
    check(apply.id in progressBlend.calls && Modifier.isStatic(apply.modifiers) && apply.result == "void" &&
      apply.parameters.size == 2 && apply.parameters.first() == "android.view.View" &&
      c.applyBlendCalls.firstOrNull() == CC_SET_BACKGROUND &&
      c.applyBlendNumbers.take(2) == listOf(0L, 1L) && apply.writes.isEmpty() &&
      clear.id in blend.calls && clear.ccInstance("void", "android.view.View") &&
      c.clearFirstNumber == 0L &&
      clear.calls.any { it.endsWith("->setMiViewBlurModeCompat(Landroid/view/View;I)Z") } &&
      clear.calls.any { it.endsWith("->clearBackgroundBlendConfig(Landroid/view/View;)Z") }) {
      "progress drawable removal and native off cleanup: apply=${c.applyBlendCalls}; clear=${clear.calls}"
    }
    val loadDefaults = m(CcSliderRole.ResourceDefaults)
    val load = m(CcSliderRole.ResourceLoad)
    check(Modifier.isStatic(loadDefaults.modifiers) && loadDefaults.result == "void" &&
      loadDefaults.parameters == listOf(load.owner, "android.view.View", "int", "boolean", "int", "java.lang.Object") &&
      loadDefaults.owner == load.owner && load.ccInstance("void", "android.view.View", "int", "boolean") &&
      loadDefaults.calls == setOf(load.id) &&
      resources.calls.all { it == loadDefaults.id || CC_INTRINSIC_CHECK.matches(it) } &&
      loadDefaults.id in resources.calls && c.resourceNumbers.firstOrNull() == 2L &&
      c.resourceDefaultNumbers == listOf(2L, 1L) &&
      setOf(CC_VIEW_CONTEXT, CC_CONTEXT_DRAWABLE, CC_SET_BACKGROUND).all { it in load.calls } &&
      load.calls.all { it in setOf(CC_VIEW_CONTEXT, CC_CONTEXT_DRAWABLE, CC_SET_BACKGROUND,
        "Landroid/view/View;->setBackgroundResource(I)V") || CC_INTRINSIC_CHECK.matches(it) } &&
      load.writes.isEmpty() && loadDefaults.writes.isEmpty()) {
      "forced fresh drawable reload without slider state writes: defaults=${loadDefaults.calls}; " +
        "numbers=${c.resourceNumbers}/${c.resourceDefaultNumbers}; load=${load.calls}"
    }
    return ControlCenterSliderMatch(blend, resources, baseBlend, defaults)
  }
}

private fun <T, R> selectControlCenterContract(
  label: String,
  candidates: List<T>,
  discoveryFailures: List<String>,
  identify: (T) -> String,
  prove: (T) -> R,
): R {
  val failures = discoveryFailures.toMutableList()
  val matches = mutableListOf<R>()
  val accepted = mutableListOf<String>()
  candidates.forEach { candidate ->
    try {
      matches += prove(candidate)
      accepted += identify(candidate)
    } catch (failure: Exception) {
      failures += "${identify(candidate)} [proof]: ${failure.javaClass.simpleName}: ${failure.message}"
    }
  }
  val unique = matches.distinct()
  check(unique.size == 1) {
    "Expected one complete $label; found ${unique.size}; read=${candidates.size}; " +
      "rejected=${failures.size}; " +
      (if (accepted.isNotEmpty()) "accepted=${accepted.take(4)}; " else "") +
      failures.take(6).joinToString(" | ") { it.take(700) } +
      (if (failures.size > 6) " | ${failures.size - 6} more rejections" else "")
  }
  return unique.single()
}

internal fun <T> ccUnique(label: String, candidates: List<T>, describe: (T) -> String): T {
  require(candidates.size == 1) {
    "$label: expected one; found ${candidates.size}; candidates=${candidates.take(4).map(describe)}"
  }
  return candidates.single()
}

/** Only selected role bodies and their immediate structural links are snapshotted. */
@OptIn(DexKitExperimentalApi::class)
private class ControlCenterGraphReader(private val dex: DexKitBridge) {
  val rootFailures = mutableListOf<String>()
  val tileFailures = mutableListOf<String>()
  val sliderFailures = mutableListOf<String>()
  val tileBackgroundFailures = mutableListOf<String>()
  private val memberCache = mutableMapOf<String, List<MethodData>>()
  private val fields = linkedMapOf<String, MaterialField>()

  private fun members(owner: String): List<MethodData> = memberCache.getOrPut(owner) {
    check(memberCache.size < 48) { "Control center owner budget exceeded" }
    dex.findMethod { matcher { declaredClass = owner } }.also {
      check(it.size <= 256) { "Control center member budget exceeded: $owner" }
    }
  }

  private fun method(owner: String, name: String, parameters: List<String>? = null): MethodData =
    ccUnique("$owner.$name(${parameters ?: "*"})",
      members(owner).filter { it.name == name && (parameters == null || it.paramTypeNames == parameters) },
      { it.descriptor })

  private fun snapshot(data: MethodData): MaterialMethod {
    val uses = data.usingFields
    uses.forEach { use -> use.field.let {
      fields[it.descriptor] = MaterialField(it.descriptor, it.className, it.typeName, it.modifiers)
    } }
    return MaterialMethod(data.descriptor, data.className, data.name, data.returnTypeName,
      data.paramTypeNames, data.modifiers, data.invokes.map { it.descriptor }.toSet(),
      uses.filter { it.usingType == FieldUsingType.Read }.map { it.field.descriptor }.toSet(),
      uses.filter { it.usingType == FieldUsingType.Write }.map { it.field.descriptor }.toSet())
  }

  private fun accessor(owner: String, type: String, field: String? = null): MethodData = members(owner).single {
    Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(owner) && it.returnTypeName == type &&
      it.invokes.isEmpty() && it.usingFields.size == 1 && it.usingFields.single().let { use ->
        use.usingType == FieldUsingType.Read && (field == null || use.field.descriptor == field)
      }
  }

  fun roots(gate: String): List<ControlCenterRootGraph> {
    val seeds = dex.findMethod { matcher { name = "updateThemeRes"; returnType = "void"; paramTypes() } }
    check(seeds.size <= 8) { "Control center root candidate budget exceeded" }
    val nativeModes = checkNotNull(dex.getMethodData(gate)).callers.filter {
      it.name == "getCurrentMaterialMode" && it.paramTypeNames.isEmpty()
    }
    check(nativeModes.size <= 8) { "Material repository candidate budget exceeded" }
    return seeds.flatMap { seed -> nativeModes.mapNotNull { nativeMode ->
      readCandidate(rootFailures, "${seed.descriptor} / ${nativeMode.descriptor}") { root(seed, nativeMode) }
    } }
  }

  private fun root(theme: MethodData, nativeMode: MethodData): ControlCenterRootGraph {
    val owner = theme.className
    val nodes = linkedMapOf<CcRootRole, MethodData>()
    fun put(role: CcRootRole, data: MethodData): MethodData = data.also { nodes[role] = it }
    put(CcRootRole.Theme, theme)
    val selector = put(CcRootRole.ThemeSelector, method(owner, "getThemeRes", emptyList()))
    put(CcRootRole.Policy, selector.invokes.single {
      it.returnTypeName == "boolean" && it.paramTypeNames == listOf("android.content.Context")
    })
    val event = put(CcRootRole.Event, members(owner).single {
      it.paramTypeNames == listOf(owner, CC_LIFECYCLE_OWNER, CC_EVENT) &&
        it.invokes.any { call -> call.name == "dispatchStart" }
    })
    put(CcRootRole.Create, method(owner, "onCreate", emptyList()))
    put(CcRootRole.Destroy, method(owner, "onDestroy", emptyList()))
    val init = put(CcRootRole.Init, method(owner, "init", emptyList()))
    val childrenField = init.usingFields.single {
      it.usingType == FieldUsingType.Read && it.field.className == owner && it.field.typeName in CC_COLLECTIONS
    }.field
    val children = put(CcRootRole.ChildrenAccess, accessor(owner, childrenField.typeName, childrenField.descriptor))
    put(CcRootRole.LifecycleAccess, accessor(owner, CC_LIFECYCLE))
    val collector = put(CcRootRole.StartedCollector, children.callers.single {
      it.invokes.any { call -> call.name == "dispatchMaterialModeChanged" } &&
        it.usingFields.any { use -> use.field.descriptor == CC_STARTED }
    })
    put(CcRootRole.LifecycleState, collector.invokes.filter {
      it.descriptor == CC_CURRENT_STATE
    }.distinctBy { it.descriptor }.single())
    put(CcRootRole.Dispatch, collector.invokes.single { it.name == "dispatchMaterialModeChanged" })
    val repoAccess = put(CcRootRole.RepositoryAccess, accessor(owner, nativeMode.className))
    put(CcRootRole.NativeMode, nativeMode)
    val flow = put(CcRootRole.Flow, method(nativeMode.className, "getMaterial", emptyList()))
    put(CcRootRole.Value, method(flow.returnTypeName, "getValue", emptyList()))
    val reader = put(CcRootRole.FlowReader, repoAccess.callers.single {
      it.invokes.any { call -> call.descriptor == flow.descriptor } &&
        it.invokes.any { call -> call.isConstructor && call.className == collector.className }
    })
    put(CcRootRole.CollectorConstructor, reader.invokes.single {
      it.isConstructor && it.className == collector.className
    })
    val filter = put(CcRootRole.FilterConstructor, reader.invokes.single {
      it.isConstructor && it.className != collector.className &&
        it.className.startsWith("$owner\$") && members(it.className).any { member -> member.name == "collect" }
    })
    val collect = put(CcRootRole.FilterCollect, method(filter.className, "collect"))
    val emitConstructor = put(CcRootRole.EmitConstructor, collect.invokes.single {
      it.isConstructor && it.className.startsWith("$owner\$")
    })
    val emit = put(CcRootRole.FilterEmit, method(emitConstructor.className, "emit"))
    val disabled = emit.usingFields.single {
      it.field.typeName.endsWith("\$Disabled") && it.usingType == FieldUsingType.Read
    }.field
    val callers = boundedHookCallers(event)
    val observerLinked = callers.any {
      it.name == "onStateChanged" && it.paramTypeNames == listOf(CC_LIFECYCLE_OWNER, CC_EVENT) &&
        it.declaredClass?.interfaces?.any { type -> type.name == "androidx.lifecycle.LifecycleEventObserver" } == true
    }
    return ControlCenterRootGraph(nodes.mapValues { snapshot(it.value) }, fields.toMap(),
      disabled.type.superClass?.name, observerLinked)
  }

  fun tiles(root: ControlCenterRootMatch): List<ControlCenterTileGraph> {
    val seeds = dex.findMethod {
      matcher { name = "onMaterialModeChanged"; returnType = "void"; paramTypes(root.dispatch.parameters.single()) }
    }
    check(seeds.size <= 64) { "Small-tile candidate budget exceeded" }
    // The public icon getter is a seed; a complete config/material/cleanup graph is still required.
    return seeds.filter { seed ->
      seed.invokes.any { it.name == "getIcon" && it.paramTypeNames.isEmpty() &&
        it.className == seed.className && it.returnTypeName != "android.widget.ImageView" }
    }.mapNotNull { seed ->
      readCandidate(tileFailures, seed.descriptor) { tile(seed) }
    }
  }

  private fun tile(material: MethodData): ControlCenterTileGraph {
    val nodes = linkedMapOf<CcTileRole, MethodData>()
    fun put(role: CcTileRole, select: () -> MethodData): MethodData = try {
      select().also { nodes[role] = it }
    } catch (failure: IllegalArgumentException) {
      throw IllegalArgumentException("$role: ${failure.message}", failure)
    } catch (failure: NoSuchElementException) {
      throw NoSuchElementException("$role: ${failure.message}")
    }
    fun invoked(caller: MethodData, name: String): MethodData =
      ccUnique("${caller.descriptor} invokes $name",
        caller.invokes.filter { it.name == name }.distinctBy { it.descriptor }, { it.descriptor })
    put(CcTileRole.Material) { material }
    put(CcTileRole.Config) { method(material.className, "onConfigurationChanged", listOf("int")) }
    val icon = put(CcTileRole.Icon) { method(material.className, "getIcon", emptyList()) }
    val owner = icon.returnTypeName
    put(CcTileRole.IconMaterial) { method(owner, "onMaterialModeChanged", material.paramTypeNames) }
    val resources = put(CcTileRole.Resources) { method(owner, "updateResources", emptyList()) }
    val update = put(CcTileRole.Update) { method(owner, "updateIcon") }
    put(CcTileRole.DefaultUpdate) { method(owner, "updateIcon\$default") }
    put(CcTileRole.InternalUpdate) { method(owner, "updateIconInternal") }
    val enhance = put(CcTileRole.Enhance) { method(owner, "updateBackgroundBlurEnhanceFlag", emptyList()) }
    put(CcTileRole.EnhancePolicy) { invoked(enhance, "updateBlurEnhanceClip") }
    put(CcTileRole.Blend) { invoked(resources, "applyBlendColor") }
    val clear = put(CcTileRole.Clear) { invoked(resources, "clearMiBlurBlendEffect") }
    val pending = update.usingFields.filter {
      it.field.className == owner && it.field.typeName == "boolean" && it.usingType == FieldUsingType.Write
    }.map { it.field.descriptor }.toSet()
    val completion = put(CcTileRole.Completion) { ccUnique("$owner native pending completion", members(owner).filter {
      Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(owner, "boolean", "boolean", "boolean") &&
        it.usingFields.any { use -> use.usingType == FieldUsingType.Write && use.field.descriptor in pending }
    }, { it.descriptor }) }
    return ControlCenterTileGraph(nodes.mapValues { snapshot(it.value) }, fields.toMap(),
      completion.usingNumbers.firstOrNull()?.longValue(),
      completion.usingFields.firstOrNull { it.usingType == FieldUsingType.Write }?.field?.descriptor,
      clear.usingNumbers.firstOrNull()?.longValue())
  }

  fun tileBackgrounds(tile: ControlCenterTileMatch): List<ControlCenterTileBackgroundGraph> {
    val owner = tile.icon.result
    return listOfNotNull(readCandidate(tileBackgroundFailures, owner) {
      val nodes = linkedMapOf<CcTileBackgroundRole, MethodData>()
      fun put(role: CcTileBackgroundRole, read: () -> MethodData): MethodData = try {
        read().also { nodes[role] = it }
      } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("$role: ${failure.message}", failure)
      }
      fun invoked(caller: MethodData, name: String) = ccUnique("${caller.descriptor} invokes $name",
        caller.invokes.filter { it.className == owner && it.name == name }.distinctBy { it.descriptor }) {
        it.descriptor
      }
      val internal = put(CcTileBackgroundRole.InternalUpdate) { method(owner, "updateIconInternal", tile.update.parameters) }
      val select = put(CcTileBackgroundRole.Select) { invoked(internal, "getBackgroundDrawable") }
      put(CcTileBackgroundRole.Active) { invoked(select, "getActiveBackgroundDrawable") }
      val disabled = put(CcTileBackgroundRole.Disabled) { invoked(select, "getDisabledBackgroundDrawable") }
      put(CcTileBackgroundRole.Restricted) { invoked(disabled, "isRestrictedState") }
      val setter = put(CcTileBackgroundRole.SetEnabled) { invoked(internal, "setEnabledBg") }
      val disabledSetter = put(CcTileBackgroundRole.SetDisabled) { invoked(internal, "setDisabledBg") }
      val resources = checkNotNull(dex.getMethodData(tile.resources.id))
      val size = put(CcTileBackgroundRole.Size) { invoked(resources, "updateSize") }
      put(CcTileBackgroundRole.Radius) { invoked(size, "getCornerRadius") }
      put(CcTileBackgroundRole.SetRadius) { invoked(size, "setCornerRadius") }
      fun cacheWrite(writer: MethodData) = ccUnique("${writer.descriptor} drawable write",
        writer.usingFields.filter { it.usingType == FieldUsingType.Write &&
          it.field.className == owner && it.field.typeName == CC_DRAWABLE }) { it.field.descriptor }.field.descriptor
      val enabledAccess = put(CcTileBackgroundRole.EnabledAccess) { accessor(owner, CC_DRAWABLE, cacheWrite(setter)) }
      val disabledAccess = put(CcTileBackgroundRole.DisabledAccess) { accessor(owner, CC_DRAWABLE, cacheWrite(disabledSetter)) }
      val outline = put(CcTileBackgroundRole.Outline) {
        val callers = enabledAccess.callers
        check(callers.size <= 16) { "Tile background outline candidate budget exceeded" }
        ccUnique("native cache outline consumer", callers.filter {
          it.name == "getOutline" && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf("android.view.View", "android.graphics.Outline") &&
            it.invokes.any { call -> call.descriptor == disabledAccess.descriptor }
        }.distinctBy { it.descriptor }) { it.descriptor }
      }
      val outlineInstalled = members(owner).filter { it.isConstructor }.any { constructor ->
        constructor.invokes.any { it.name == "setOutlineProvider" &&
          it.paramTypeNames == listOf("android.view.ViewOutlineProvider") && it.returnTypeName == "void" } &&
          constructor.invokes.any { provider ->
            provider.isConstructor && provider.className == outline.className &&
              provider.paramTypeNames == listOf(owner) && provider.usingFields.any {
                it.usingType == FieldUsingType.Write && it.field.typeName == owner &&
                  outline.usingFields.any { read -> read.usingType == FieldUsingType.Read &&
                    read.field.descriptor == it.field.descriptor }
              }
          }
      }
      val image = ccUnique("cache setter image read", setter.usingFields.filter {
        it.usingType == FieldUsingType.Read && it.field.className == owner
      }) { it.field.descriptor }.field
      val imageTypeHierarchy = generateSequence(image.type) { it.superClass }.take(8).map { it.name }.toList()
      ControlCenterTileBackgroundGraph(nodes.mapValues { snapshot(it.value) }, fields.toMap(),
        outline.declaredClass?.superClass?.name, outlineInstalled, imageTypeHierarchy)
    })
  }

  fun sliders(root: ControlCenterRootMatch): List<ControlCenterSliderGraph> {
    val seeds = checkNotNull(dex.getMethodData(root.policy.id)).callers.filter {
      it.name == "updateBlendBlur" && it.returnTypeName == "void" && it.paramTypeNames == listOf("boolean")
    }.distinctBy { it.descriptor }
    check(seeds.size <= 16) { "Slider candidate budget exceeded" }
    return seeds.mapNotNull { seed ->
      readCandidate(sliderFailures, seed.descriptor) { slider(seed, root) }
    }
  }

  private fun slider(blend: MethodData, root: ControlCenterRootMatch): ControlCenterSliderGraph {
    val nodes = linkedMapOf<CcSliderRole, MethodData>()
    fun put(role: CcSliderRole, read: () -> MethodData): MethodData = try {
      read().also { nodes[role] = it }
    } catch (failure: IllegalArgumentException) {
      throw IllegalArgumentException("$role: ${failure.message}", failure)
    }
    fun invoked(caller: MethodData, name: String) = ccUnique("${caller.descriptor} invokes $name",
      caller.invokes.filter { it.name == name }.distinctBy { it.descriptor }) { it.descriptor }
    put(CcSliderRole.Blend) { blend }
    val resources = put(CcSliderRole.Resources) { method(blend.className, "updateResources", emptyList()) }
    put(CcSliderRole.Config) { method(blend.className, "onConfigurationChanged", listOf("int")) }
    val progress = put(CcSliderRole.ProgressBlend) { invoked(blend, "updateProgressBlendColors") }
    val apply = put(CcSliderRole.ApplyBlend) { invoked(progress, "applyBlendColor") }
    val clear = put(CcSliderRole.Clear) { invoked(blend, "clearMiBlurBlendEffect") }
    val loadDefaults = put(CcSliderRole.ResourceDefaults) { invoked(resources, "setBackgroundResourceEx\$default") }
    put(CcSliderRole.ResourceLoad) { invoked(loadDefaults, "setBackgroundResourceEx") }
    val ancestors = generateSequence(blend.declaredClass?.superClass) { it.superClass }
      .take(6).map { it.name }.toList()
    val configuration = put(CcSliderRole.Configuration) {
      ccUnique("inherited slider configuration", ancestors.flatMap { members(it) }.filter {
        it.name == "updateConfiguration" && it.paramTypeNames == listOf("android.content.res.Configuration")
      }) { it.descriptor }
    }
    put(CcSliderRole.BaseBlend) { invoked(configuration, blend.name) }
    val material = put(CcSliderRole.Material) {
      method(configuration.className, "onMaterialModeChanged", root.dispatch.parameters)
    }
    put(CcSliderRole.DefaultBlend) { invoked(material, "${blend.name}\$default") }
    return ControlCenterSliderGraph(nodes.mapValues { snapshot(it.value) }, fields.toMap(), ancestors,
      configuration.invokes.map { it.descriptor }, apply.invokes.map { it.descriptor },
      apply.usingNumbers.map { it.longValue() }, resources.usingNumbers.map { it.longValue() },
      loadDefaults.usingNumbers.map { it.longValue() }, clear.usingNumbers.firstOrNull()?.longValue())
  }

  private fun <T> readCandidate(failures: MutableList<String>, id: String, read: () -> T): T? = try {
    read()
  } catch (failure: NoSuchElementException) {
    failures += "$id [discovery]: ${failure.message}"
    null
  } catch (failure: IllegalArgumentException) {
    failures += "$id [discovery]: ${failure.message}"
    null
  }

  fun hookCallers(method: MaterialMethod): List<MaterialMethod> =
    boundedHookCallers(checkNotNull(dex.getMethodData(method.id))).map(::snapshot)

  private fun boundedHookCallers(target: MethodData): List<MethodData> {
    val direct = target.callers.filter { it.isMethod }.distinctBy { it.descriptor }
    check(direct.size <= 64) { "Control center hook caller budget exceeded" }
    val wrappers = direct.filter {
      Modifier.isStatic(it.modifiers) && it.className == target.className &&
        it.paramTypeNames == target.paramTypeNames && it.returnTypeName == target.returnTypeName &&
        it.invokes.map { call -> call.descriptor }.toSet() == setOf(target.descriptor)
    }
    val result = (direct + wrappers.flatMap { it.callers.filter { caller -> caller.isMethod } })
      .distinctBy { it.descriptor }
    check(result.size <= 96) { "Control center forwarding caller budget exceeded" }
    return result
  }
}

internal fun reflectControlCenterTileBackgroundCache(
  loader: ClassLoader,
  match: ControlCenterTileBackgroundMatch,
  callers: List<Method>,
): ControlCenterTileBackgroundCacheProfile {
  fun method(node: MaterialMethod) = reflectMaterialCaller(loader, node.id, node.modifiers)
  val select = method(match.selector)
  val setter = method(match.setter)
  val internal = method(match.internalUpdate)
  val enabled = reflectControlCenterField(loader, match.enabled)
  val disabled = reflectControlCenterField(loader, match.disabled)
  check(listOf(setter.declaringClass, internal.declaringClass, enabled.declaringClass, disabled.declaringClass)
    .all { it === select.declaringClass } && select.parameterTypes.single() === internal.parameterTypes.first() &&
    setter.parameterTypes.single() === select.returnType && enabled.type === select.returnType &&
    disabled.type === select.returnType) { "Control center tile background owner/type drift" }
  return ControlCenterTileBackgroundCacheProfile(select, setter, enabled, disabled, internal, callers)
}

internal fun reflectControlCenterSlider(
  loader: ClassLoader,
  match: ControlCenterSliderMatch,
  callers: List<Method>,
): ControlCenterSliderRefreshProfile {
  val blend = reflectMaterialCaller(loader, match.blend.id, match.blend.modifiers)
  val resources = reflectMaterialCaller(loader, match.resources.id, match.resources.modifiers)
  check(blend.declaringClass === resources.declaringClass) { "Control center slider owner drift" }
  return ControlCenterSliderRefreshProfile(blend, resources, callers)
}

internal fun reflectControlCenterField(loader: ClassLoader, node: MaterialField): Field {
  val owner = Class.forName(node.owner, false, loader)
  return owner.getDeclaredField(node.id.substringAfter("->").substringBefore(':')).also {
    check(DexSignUtil.getDescriptor(it) == node.id && it.declaringClass === owner &&
      Modifier.isStatic(it.modifiers) == Modifier.isStatic(node.modifiers) &&
      Modifier.isFinal(it.modifiers) == Modifier.isFinal(node.modifiers)) { "Control center field drift: ${node.id}" }
    it.isAccessible = true
  }
}

internal fun reflectControlCenterLifecycleState(field: Field, node: MaterialMethod): Method {
  // A literal Class.forName for Lifecycle can be rewritten to KeiHome's own
  // obfuscated AndroidX class by R8. This type belongs to the live plugin field.
  val owner = field.type
  check(owner.name == node.owner && node.parameters.isEmpty()) { "Control center lifecycle owner drift" }
  return owner.getDeclaredMethod(node.name).also {
    check(it.declaringClass === owner && DexSignUtil.getDescriptor(it) == node.id &&
      !Modifier.isStatic(it.modifiers) && !Modifier.isNative(it.modifiers)) {
      "Control center lifecycle getter drift: ${node.id}"
    }
    it.isAccessible = true
  }
}

private fun reflectControlCenterAbstractMethod(loader: ClassLoader, node: MaterialMethod): Method =
  Class.forName(node.owner, false, loader).getDeclaredMethod(node.name).also {
    check(node.parameters.isEmpty() && DexSignUtil.getDescriptor(it) == node.id &&
      !Modifier.isStatic(it.modifiers) && !Modifier.isNative(it.modifiers)) { "Control center flow getter drift" }
    it.isAccessible = true
  }

private fun MaterialMethod.ccInstance(result: String, vararg parameters: String): Boolean =
  this.result == result && this.parameters == parameters.toList() &&
    !Modifier.isStatic(modifiers) && !Modifier.isNative(modifiers) && !Modifier.isAbstract(modifiers)

private fun MaterialField.ccMutable(): Boolean = !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)

private const val CC_LIFECYCLE = "androidx.lifecycle.Lifecycle"
private const val CC_LIFECYCLE_OWNER = "androidx.lifecycle.LifecycleOwner"
private const val CC_EVENT = "androidx.lifecycle.Lifecycle\$Event"
private const val CC_STATE = "androidx.lifecycle.Lifecycle\$State"
private const val CC_STARTED = "Landroidx/lifecycle/Lifecycle\$State;->STARTED:Landroidx/lifecycle/Lifecycle\$State;"
private const val CC_DESTROYED = "Landroidx/lifecycle/Lifecycle\$State;->DESTROYED:Landroidx/lifecycle/Lifecycle\$State;"
private const val CC_ADD_OBSERVER = "Landroidx/lifecycle/Lifecycle;->addObserver(Landroidx/lifecycle/LifecycleObserver;)V"
private const val CC_REMOVE_OBSERVER = "Landroidx/lifecycle/Lifecycle;->removeObserver(Landroidx/lifecycle/LifecycleObserver;)V"
private const val CC_CURRENT_STATE = "Landroidx/lifecycle/Lifecycle;->getCurrentState()Landroidx/lifecycle/Lifecycle\$State;"
private const val CC_THEME_ID = "Landroid/view/ContextThemeWrapper;->getThemeResId()I"
private const val CC_THEME_CLEAR = "Landroid/view/ContextThemeWrapper;->setTheme(Landroid/content/res/Resources\$Theme;)V"
private const val CC_THEME_SET = "Landroid/view/ContextThemeWrapper;->setTheme(I)V"
private const val CC_SET_BACKGROUND = "Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V"
private const val CC_VIEW_CONTEXT = "Landroid/view/View;->getContext()Landroid/content/Context;"
private const val CC_CONTEXT_DRAWABLE = "Landroid/content/Context;->getDrawable(I)Landroid/graphics/drawable/Drawable;"
private const val CC_DRAWABLE = "android.graphics.drawable.Drawable"
private const val CC_CONTEXT_THEME = "Landroid/content/Context;->getTheme()Landroid/content/res/Resources\$Theme;"
private const val CC_THEME_DRAWABLE = "Landroid/content/res/Resources\$Theme;->getDrawable(I)Landroid/graphics/drawable/Drawable;"
private const val CC_SET_GRADIENT_RADIUS = "Landroid/graphics/drawable/GradientDrawable;->setCornerRadius(F)V"
private const val CC_GRADIENT_RADIUS = "Landroid/graphics/drawable/GradientDrawable;->getCornerRadius()F"
private const val CC_OUTLINE_RECT = "Landroid/graphics/Outline;->setRoundRect(IIIIF)V"
private const val CC_OUTLINE_ALPHA = "Landroid/graphics/Outline;->setAlpha(F)V"
private val CC_COLLECTIONS = setOf("java.util.ArrayList", "java.util.Collection", "java.util.List")
private val CC_EQUALS = Regex("Lkotlin/jvm/internal/[^;]+;->[^;(]+\\(Ljava/lang/Object;Ljava/lang/Object;\\)Z")
private val CC_INTRINSIC_CHECK = Regex("Lkotlin/jvm/internal/[^;]+;->[^;(]+\\(Ljava/lang/Object;Ljava/lang/String;\\)V")
