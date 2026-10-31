package os.kei.hyper.home.xposed

import android.content.Context
import android.content.res.Configuration
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.zip.ZipFile
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.util.DexSignUtil
import org.luckypray.dexkit.wrap.DexMethod

/**
 * A verified material-only substitution. Resolving gates and callers does not
 * initialize plugin classes or change theme identity used to select resources.
 *
 * The caller owns the live plugin loader, its ApplicationInfo.sourceDir, worker,
 * preference, hooks and lifetime. No DexKit objects survive resolution.
 */
internal data class SystemUiMaterialProfile(
  val themedGate: Method,
  val capabilityGate: Method,
  val proof: SystemUiMaterialProof,
  /** Direct DEX method callers for runtime-owned deoptimization; excludes <init>/<clinit>. */
  val callers: List<Method> = emptyList(),
  val volumeRefresh: VolumeMaterialRefreshProfile? = null,
  /** Main logs this through its framework logger; an optional miss never rejects the core profile. */
  val volumeRefreshUnavailableReason: String? = null,
) {
  fun capabilityArgument(context: Context): Any =
    when (capabilityGate.parameterTypes.single()) {
      Context::class.java -> context
      Configuration::class.java -> context.resources.configuration
      else -> error("Unverified material capability signature: $capabilityGate")
    }

  companion object {
    /**
     * Fails closed on contract drift or ambiguity. On module reload, pass the host's
     * codeCacheDir to permit an isolated copy of the module's own JNI library.
     */
    fun resolve(
      loader: ClassLoader,
      apkPath: String,
      nativeLibraryCacheDir: File? = null,
    ): SystemUiMaterialProfile {
      return SystemUiMaterialDexKit.open(apkPath, nativeLibraryCacheDir).use { dex ->
        val graph = readMaterialGraph(dex)
        val match = SystemUiMaterialContract.resolve(graph)
        val themed = reflectGate(loader, graph.methods.getValue(match.themedGate))
        val capability = reflectGate(loader, graph.methods.getValue(match.capabilityGate))
        check(themed.declaringClass === capability.declaringClass) {
          "Material gates resolved to different runtime owners"
        }
        val themedData = checkNotNull(dex.getMethodData(match.themedGate)) {
          "Selected material gate disappeared from DEX: ${match.themedGate}"
        }
        // Resolve only this selected method's callers while the bridge is open.
        val callers = themedData.callers.filter { it.isMethod }
          .distinctBy { it.descriptor }
          .sortedBy { it.descriptor }
          .map { caller ->
            reflectMaterialCaller(loader, caller.descriptor, caller.modifiers)
          }
        val volume = resolveOptionalVolumeRefresh { resolveVolumeRefresh(dex, loader) }
        SystemUiMaterialProfile(themed, capability, match, callers, volume.profile, volume.unavailableReason)
      }
    }

    /** DEX-only diagnostic: never loads or initializes any plugin classes. */
    fun inspect(
      apkPath: String,
      nativeLibraryCacheDir: File? = null,
    ): SystemUiMaterialProof =
      SystemUiMaterialDexKit.open(apkPath, nativeLibraryCacheDir).use {
        SystemUiMaterialContract.resolve(readMaterialGraph(it))
      }

    private fun reflectGate(loader: ClassLoader, node: MaterialMethod): Method {
      val owner = Class.forName(node.owner, false, loader)
      val parameter = when (node.parameters.single()) {
        MATERIAL_CONTEXT -> Context::class.java
        MATERIAL_CONFIGURATION -> Configuration::class.java
        else -> error("Unsupported material parameter: ${node.id}")
      }
      return owner.getDeclaredMethod(node.name, parameter).also {
        check(it.declaringClass === owner && it.returnType == Boolean::class.javaPrimitiveType &&
          Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) &&
          !Modifier.isNative(it.modifiers) && !it.isSynthetic) {
          "Runtime material signature differs from DEX: ${node.id}"
        }
        it.isAccessible = true
      }
    }
  }
}

internal data class VolumeMaterialRefreshProfile(val show: Method, val reinitializeFlag: Field)

internal data class OptionalVolumeRefresh(
  val profile: VolumeMaterialRefreshProfile?,
  val unavailableReason: String?,
)

internal fun resolveOptionalVolumeRefresh(resolve: () -> VolumeMaterialRefreshProfile): OptionalVolumeRefresh =
  try {
    OptionalVolumeRefresh(resolve(), null)
  } catch (failure: Exception) {
    OptionalVolumeRefresh(null, "Optional volume material refresh unavailable: " +
      "${failure.javaClass.simpleName}: ${failure.message}")
  } catch (failure: LinkageError) {
    OptionalVolumeRefresh(null, "Optional volume material refresh unavailable: " +
      "${failure.javaClass.simpleName}: ${failure.message}")
  }

internal data class VolumeResetPrefix(
  val opcodes: List<Int>,
  val firstNumber: Long?,
  val firstWrittenField: String?,
  val firstCall: String?,
)

internal data class VolumeRefreshCandidate(
  val graph: MaterialGraph,
  val show: String,
  val config: String,
  val reinitialize: String,
  val initialize: String,
  val handlerDispatch: String,
  val handlerConstructor: String,
  val handlerSuperclass: String?,
  val resetPrefix: VolumeResetPrefix,
)

internal data class VolumeRefreshMatch(val show: MaterialMethod, val flag: MaterialField)

internal object VolumeMaterialRefreshContract {
  fun resolve(candidates: List<VolumeRefreshCandidate>): VolumeRefreshMatch {
    val matches = candidates.mapNotNull(::match).distinctBy { it.show.id to it.flag.id }
    check(matches.size == 1) { "Expected one volume refresh contract; found ${matches.size}" }
    return matches.single()
  }

  private fun match(candidate: VolumeRefreshCandidate): VolumeRefreshMatch? = with(candidate) {
    val show = graph.methods[candidate.show] ?: return null
    val config = graph.methods[candidate.config] ?: return null
    val reset = graph.methods[reinitialize] ?: return null
    val init = graph.methods[initialize] ?: return null
    if (!show.instanceVoid("int") || !config.instanceVoid("int") ||
      !reset.instanceVoid() || !init.instanceVoid() ||
      setOf(show.id, config.id, reset.id, init.id).size != 4 ||
      listOf(config, reset, init).any { it.owner != show.owner } ||
      reset.id !in show.calls || init.id !in reset.calls) return null

    val flags = (show.reads intersect config.writes intersect reset.writes).mapNotNull(graph.fields::get)
      .filter { it.owner == show.owner && it.type == "boolean" &&
        !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }
    val flag = flags.singleOrNull() ?: return null
    if (flag.id in show.writes || flag.id != resetPrefix.firstWrittenField ||
      resetPrefix.firstNumber != 0L) return null
    // Only optional Log.d precedes const/4 0 + iput-boolean. Reject earlier
    // branches/calls/writes, rather than accepting a later cleanup of this flag.
    val prefix = resetPrefix.opcodes
    val clearsFirst = prefix.take(2) == listOf(0x12, 0x5c)
    val logsThenClears = prefix.take(5) in listOf(
      listOf(0x54, 0x1a, 0x71, 0x12, 0x5c),
      listOf(0x54, 0x1b, 0x71, 0x12, 0x5c),
    ) && resetPrefix.firstCall == "Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I"
    if (!clearsFirst && !logsThenClears) return null

    val showing = graph.fields.values.singleOrNull {
      it.owner == show.owner && it.id.substringAfter("->") == "mShowing:Z" &&
        !Modifier.isStatic(it.modifiers)
    } ?: return null
    val dialog = graph.fields.values.singleOrNull {
      it.owner == show.owner && it.id.substringAfter("->") == "mNeedShowDialog:Z" &&
        !Modifier.isStatic(it.modifiers)
    } ?: return null
    if (showing.id !in show.reads || showing.id !in init.writes ||
      dialog.id !in show.reads || dialog.id !in reset.reads || dialog.id !in init.reads ||
      flag.id == showing.id || flag.id == dialog.id) return null

    val dispatch = graph.methods[handlerDispatch] ?: return null
    val constructor = graph.methods[handlerConstructor] ?: return null
    if (handlerSuperclass != "android.os.Handler" ||
      !dispatch.instanceVoid("android.os.Message") || dispatch.name != "handleMessage" ||
      show.id !in dispatch.calls || constructor.owner != dispatch.owner ||
      constructor.name != "<init>" || !constructor.instanceVoid(show.owner) ||
      "Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;" !in constructor.calls ||
      "Landroid/os/Handler;-><init>(Landroid/os/Looper;)V" !in constructor.calls) return null
    if (dispatch.reads.none { id -> graph.fields[id]?.let {
        it.owner == dispatch.owner && it.type == show.owner && !Modifier.isStatic(it.modifiers)
      } == true }) return null
    if (show.reads.none { id -> graph.fields[id]?.let {
        it.owner == show.owner && it.type == dispatch.owner && !Modifier.isStatic(it.modifiers) &&
          id in reset.reads
      } == true }) return null
    VolumeRefreshMatch(show, flag)
  }

  private fun MaterialMethod.instanceVoid(vararg parameters: String): Boolean =
    result == "void" && this.parameters == parameters.toList() &&
      !Modifier.isStatic(modifiers) && !Modifier.isNative(modifiers) && !Modifier.isAbstract(modifiers)
}

@OptIn(DexKitExperimentalApi::class)
private fun resolveVolumeRefresh(dex: DexKitBridge, loader: ClassLoader): VolumeMaterialRefreshProfile {
  val shows = dex.findMethod { matcher { name = "showH"; returnType = "void"; paramTypes("int") } }
  check(shows.size <= 8) { "Volume show candidates exceed budget" }
  val candidates = shows.flatMap { show ->
    val members = dex.findMethod { matcher { declaredClass = show.className } }
    check(members.size <= 256) { "Volume controller exceeds method budget" }
    fun member(name: String, parameters: List<String>): MethodData? = members.singleOrNull {
      it.name == name && it.paramTypeNames == parameters && it.returnTypeName == "void"
    }
    val config = member("onConfigChanged", listOf("int")) ?: return@flatMap emptyList()
    val reset = member("reInit", emptyList()) ?: return@flatMap emptyList()
    val init = member("initPanelView", emptyList()) ?: return@flatMap emptyList()
    val dispatchers = show.callers.filter {
      it.name == "handleMessage" && it.paramTypeNames == listOf("android.os.Message")
    }.distinctBy { it.descriptor }
    check(dispatchers.size <= 8) { "Volume handler candidates exceed budget" }
    dispatchers.mapNotNull { dispatcher ->
      val constructors = dex.findMethod {
        matcher { declaredClass = dispatcher.className; name = "<init>"; paramTypes(show.className) }
      }
      val constructor = constructors.singleOrNull() ?: return@mapNotNull null
      val fields = linkedMapOf<String, MaterialField>()
      val methods = listOf(show, config, reset, init, dispatcher, constructor).associate { method ->
        val uses = method.usingFields
        uses.forEach { use -> use.field.let {
          fields[it.descriptor] = MaterialField(it.descriptor, it.className, it.typeName, it.modifiers)
        } }
        method.descriptor to MaterialMethod(
          method.descriptor, method.className, method.name, method.returnTypeName,
          method.paramTypeNames, method.modifiers, method.invokes.map { it.descriptor }.toSet(),
          uses.filter { it.usingType == FieldUsingType.Read }.map { it.field.descriptor }.toSet(),
          uses.filter { it.usingType == FieldUsingType.Write }.map { it.field.descriptor }.toSet(),
        )
      }
      VolumeRefreshCandidate(
        MaterialGraph(methods, fields), show.descriptor, config.descriptor, reset.descriptor,
        init.descriptor, dispatcher.descriptor, constructor.descriptor,
        dispatcher.declaredClass?.superClass?.name,
        VolumeResetPrefix(reset.opCodes.take(5), reset.usingNumbers.firstOrNull()?.longValue(),
          reset.usingFields.firstOrNull { it.usingType == FieldUsingType.Write }?.field?.descriptor,
          reset.invokes.firstOrNull()?.descriptor),
      )
    }
  }
  val match = VolumeMaterialRefreshContract.resolve(candidates)
  val show = reflectMaterialCaller(loader, match.show.id, match.show.modifiers)
  val field = show.declaringClass.getDeclaredField(match.flag.id.substringAfter("->").substringBefore(':'))
  check(field.declaringClass === show.declaringClass && field.type == java.lang.Boolean.TYPE &&
    DexSignUtil.getDescriptor(field) == match.flag.id &&
    !Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers)) {
    "Runtime volume refresh field differs from DEX: ${match.flag.id}"
  }
  field.isAccessible = true
  return VolumeMaterialRefreshProfile(show, field)
}

internal fun reflectMaterialCaller(loader: ClassLoader, descriptor: String, modifiers: Int): Method {
  // DexMethod is used only as a descriptor parser. Its reflection helpers can
  // fall back to JNI and initialize unrelated SystemUI classes on this worker.
  val signature = DexMethod(descriptor)
  require(signature.isMethod) { "Material caller is not a method: $descriptor" }
  val owner = Class.forName(signature.className, false, loader)
  val parameters = signature.paramTypeNames.map { materialCallerType(it, loader) }.toTypedArray()
  val method = owner.getDeclaredMethod(signature.name, *parameters)
  return requireExactMaterialCaller(method, loader, descriptor, modifiers).also {
    it.isAccessible = true
  }
}

private fun materialCallerType(name: String, loader: ClassLoader): Class<*> = when (name) {
  "boolean" -> java.lang.Boolean.TYPE
  "byte" -> java.lang.Byte.TYPE
  "char" -> java.lang.Character.TYPE
  "short" -> java.lang.Short.TYPE
  "int" -> java.lang.Integer.TYPE
  "long" -> java.lang.Long.TYPE
  "float" -> java.lang.Float.TYPE
  "double" -> java.lang.Double.TYPE
  "void" -> Void.TYPE
  else -> Class.forName(
    if (name.endsWith("[]")) DexSignUtil.getTypeSign(name).replace('/', '.') else name,
    false,
    loader,
  )
}

internal fun requireExactMaterialCaller(
  method: Method,
  loader: ClassLoader,
  descriptor: String,
  modifiers: Int,
): Method = method.also {
  // Keep deoptimization on the exact declared executable in the live loader.
  check(DexSignUtil.getDescriptor(it) == descriptor &&
    it.declaringClass === Class.forName(it.declaringClass.name, false, loader) &&
    Modifier.isStatic(it.modifiers) == Modifier.isStatic(modifiers) &&
    !Modifier.isNative(it.modifiers) && !Modifier.isAbstract(it.modifiers)) {
    "Runtime material caller differs from DEX: $descriptor => $it"
  }
}

/** Descriptors, not live theme fields: diagnostics must not read or initialize ThemeUtils. */
internal data class SystemUiMaterialProof(
  val themedGate: String,
  val capabilityGate: String,
  val materialOwner: String,
  val materialSupportedField: String,
  val blurReader: String,
  val themeOwner: String,
  val pluginThemeGetter: String,
  val sysUiThemeGetter: String,
  val pluginThemeField: String,
  val sysUiThemeField: String,
)

private const val MATERIAL_CONTEXT = "android.content.Context"
private const val MATERIAL_CONFIGURATION = "android.content.res.Configuration"
private const val PLUGIN_THEME_MARKER = "/data/system/theme/miui.systemui.plugin"
private const val SYSUI_THEME_MARKER = "/data/system/theme/com.android.systemui"
private const val MAX_MATERIAL_OWNERS = 8
private const val MAX_OWNER_METHODS = 256
private const val MAX_MATERIAL_DEPENDENCIES = 128

internal data class MaterialField(
  val id: String,
  val owner: String,
  val type: String,
  val modifiers: Int,
)

internal data class MaterialMethod(
  val id: String,
  val owner: String,
  val name: String,
  val result: String,
  val parameters: List<String>,
  val modifiers: Int,
  val calls: Set<String> = emptySet(),
  val reads: Set<String> = emptySet(),
  val writes: Set<String> = emptySet(),
  val strings: Set<String> = emptySet(),
)

internal data class MaterialGraph(
  val methods: Map<String, MaterialMethod>,
  val fields: Map<String, MaterialField>,
)

private fun readMaterialGraph(dex: DexKitBridge): MaterialGraph {
  dex.setThreadNum(1)
  // Public API names are search seeds only; acceptance requires the graph below.
  val seeds = dex.findMethod {
    matcher {
      name = "getBackgroundMaterialOpenedInDefaultTheme"
      returnType = "boolean"
      paramTypes(MATERIAL_CONTEXT)
      modifiers(Modifier.PUBLIC or Modifier.STATIC)
    }
  }.distinctBy { it.descriptor }
  val owners = seeds.map { it.className }.distinct()
  check(owners.size <= MAX_MATERIAL_OWNERS) { "Too many material gate owners: $owners" }
  val methods = linkedMapOf<String, MaterialMethod>()
  val fields = linkedMapOf<String, MaterialField>()

  fun snapshot(method: MethodData) {
    val uses = method.usingFields
    uses.forEach {
      val field = it.field
      fields[field.descriptor] = MaterialField(
        field.descriptor, field.className, field.typeName, field.modifiers,
      )
    }
    methods[method.descriptor] = MaterialMethod(
      method.descriptor, method.className, method.name, method.returnTypeName,
      method.paramTypeNames, method.modifiers,
      method.invokes.map { it.descriptor }.toSet(),
      uses.filter { it.usingType == FieldUsingType.Read }.map { it.field.descriptor }.toSet(),
      uses.filter { it.usingType == FieldUsingType.Write }.map { it.field.descriptor }.toSet(),
      method.usingStrings.toSet(),
    )
  }

  val themeOwners = seeds.flatMap { it.invokes }.filter {
    it.name == "getDefaultPluginTheme" || it.name == "getDefaultSysUiTheme"
  }.map { it.className }.distinct()
  check(themeOwners.size <= MAX_MATERIAL_OWNERS) { "Too many material theme owners: $themeOwners" }
  (owners + themeOwners).distinct().forEach { owner ->
    val members = dex.findMethod { matcher { declaredClass = owner } }
    check(members.size <= MAX_OWNER_METHODS) { "Material owner graph exceeds budget: $owner" }
    members.forEach(::snapshot)
  }
  // Include APK-defined helper bodies so an indirect ThemeUtils call is not
  // mistaken for an ungated capability. Framework calls terminate the walk.
  val pending = ArrayDeque(seeds.map { it.descriptor } + methods.values.filter {
    it.owner in owners && it.result == "boolean" &&
      it.parameters.singleOrNull() in setOf(MATERIAL_CONTEXT, MATERIAL_CONFIGURATION) &&
      it.calls.any { call ->
        methods[call]?.let { callee ->
          callee.owner == it.owner && callee.result == "int" &&
            callee.parameters == listOf(MATERIAL_CONFIGURATION)
        } == true
      }
  }.map { it.id })
  val visited = hashSetOf<String>()
  while (pending.isNotEmpty()) {
    val id = pending.removeFirst()
    if (!visited.add(id) || id.startsWith("Ljava/") || id.startsWith("Landroid/")) continue
    check(visited.size <= MAX_MATERIAL_DEPENDENCIES) { "Material dependency graph exceeds budget" }
    if (id !in methods) dex.getMethodData(id)?.let(::snapshot)
    methods[id]?.calls?.let(pending::addAll)
  }
  return MaterialGraph(methods, fields)
}

/**
 * Accepts a direct Configuration or Context capability gate. A transparent Context
 * forwarding overload does not compete with the direct Configuration implementation.
 */
internal object SystemUiMaterialContract {
  fun resolve(graph: MaterialGraph): SystemUiMaterialProof {
    val matches = graph.methods.values.filter {
      it.isGate(MATERIAL_CONTEXT)
    }.mapNotNull { themed -> match(graph, themed) }
    check(matches.size == 1) {
      "Expected one verified SystemUI material profile; found ${matches.size}: " +
        matches.joinToString { "${it.themedGate} => ${it.capabilityGate}" }
    }
    return matches.single()
  }

  private fun match(graph: MaterialGraph, themed: MaterialMethod): SystemUiMaterialProof? {
    if (themed.writes.isNotEmpty()) return null
    val calls = themed.calls.mapNotNull(graph.methods::get)
    val plugin = calls.singleOrNull { it.name == "getDefaultPluginTheme" } ?: return null
    val sysui = calls.singleOrNull { it.name == "getDefaultSysUiTheme" } ?: return null
    if (plugin.owner != sysui.owner || plugin.id == sysui.id || plugin.owner == themed.owner) return null
    val pluginField = themeField(graph, plugin, PLUGIN_THEME_MARKER) ?: return null
    val sysuiField = themeField(graph, sysui, SYSUI_THEME_MARKER) ?: return null
    if (pluginField == sysuiField) return null

    val support = themed.reads.mapNotNull(graph.fields::get).singleOrNull {
      it.owner == themed.owner && it.type == "boolean" && Modifier.isStatic(it.modifiers) &&
        Modifier.isFinal(it.modifiers)
    } ?: return null
    // The two gates must use the same configuration reader in their shared owner.
    val blur = calls.singleOrNull {
      it.owner == themed.owner && it.result == "int" &&
        it.parameters == listOf(MATERIAL_CONFIGURATION) && Modifier.isStatic(it.modifiers) &&
        !Modifier.isNative(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
        it.writes.isEmpty() &&
        "Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;" in it.calls
    } ?: return null
    if (themed.calls.any { it !in setOf(plugin.id, sysui.id, blur.id) && !safeCall(it, true) }) {
      return null
    }
    // ThemeUtils.INSTANCE is the only permitted extra field in the themed gate.
    if (themed.reads.any {
        it != support.id && graph.fields[it]?.let { field ->
          field.owner == plugin.owner && field.type == plugin.owner &&
            Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers)
        } != true
      }) return null
    if (themed.calls.filterNot { it == plugin.id || it == sysui.id }.any {
        hasThemeDependency(graph, it, plugin.owner)
      }) return null

    val capabilityCandidates = graph.methods.values.filter {
      it.owner == themed.owner &&
        (it.isGate(MATERIAL_CONFIGURATION) || it.isGate(MATERIAL_CONTEXT)) &&
        it.writes.isEmpty() &&
        it.reads == setOf(support.id) && blur.id in it.calls &&
        it.calls.all { call -> call == blur.id || safeCall(call, it.parameters == listOf(MATERIAL_CONTEXT)) }
    }
    // A Context forwarding overload is not a second implementation. Two direct
    // implementations are ambiguous, even if one has the familiar API name.
    val capability = capabilityCandidates.singleOrNull() ?: return null
    if (capability.calls.any { hasThemeDependency(graph, it, plugin.owner) }) return null
    return SystemUiMaterialProof(
      themed.id, capability.id, themed.owner, support.id, blur.id, plugin.owner,
      plugin.id, sysui.id, pluginField, sysuiField,
    )
  }

  private fun themeField(graph: MaterialGraph, getter: MaterialMethod, marker: String): String? {
    if (getter.result != "boolean" || getter.parameters.isNotEmpty() ||
      !Modifier.isPublic(getter.modifiers) || Modifier.isNative(getter.modifiers) ||
      Modifier.isAbstract(getter.modifiers) || getter.calls.isNotEmpty() ||
      getter.writes.isNotEmpty()) return null
    val fieldId = getter.reads.singleOrNull() ?: return null
    val field = graph.fields[fieldId] ?: return null
    if (field.owner != getter.owner || field.type != "boolean" ||
      !Modifier.isStatic(field.modifiers) || Modifier.isFinal(field.modifiers)) return null
    val updaters = graph.methods.values.filter {
      it.owner == getter.owner && marker in it.strings &&
        "Ljava/io/File;->exists()Z" in it.calls
    }
    val proven = updaters.any { updater ->
      fieldId in updater.writes || updater.calls.mapNotNull(graph.methods::get).any {
        it.owner == getter.owner && it.result == "void" && it.parameters == listOf("boolean") &&
          fieldId in it.writes
      }
    }
    return fieldId.takeIf { proven }
  }

  private fun hasThemeDependency(graph: MaterialGraph, root: String, themeOwner: String): Boolean {
    val pending = ArrayDeque<String>()
    val visited = hashSetOf<String>()
    pending.add(root)
    while (pending.isNotEmpty()) {
      val id = pending.removeFirst()
      if (!visited.add(id)) continue
      if (visited.size > MAX_MATERIAL_DEPENDENCIES) return true
      if (id.startsWith("L${themeOwner.replace('.', '/')};->")) return true
      val method = graph.methods[id] ?: continue
      if ((method.reads + method.writes).any { graph.fields[it]?.owner == themeOwner }) return true
      pending.addAll(method.calls)
    }
    return false
  }

  private fun MaterialMethod.isGate(parameter: String): Boolean =
    result == "boolean" && parameters == listOf(parameter) &&
      Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) &&
      !Modifier.isNative(modifiers) && !Modifier.isAbstract(modifiers) &&
      modifiers and 0x1000 == 0

  private fun safeCall(id: String, allowResources: Boolean): Boolean =
    (allowResources && id in setOf(
      "Landroid/content/Context;->getResources()Landroid/content/res/Resources;",
      "Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;",
    )) || id.matches(NULL_CHECK)

  // The OEM obfuscates Intrinsics' simple name. Restrict the namespace as well
  // as the signature; an arbitrary void helper may still inspect theme identity.
  private val NULL_CHECK =
    Regex("Lkotlin/jvm/internal/[^;]+;->[^;(]+\\(Ljava/lang/Object;Ljava/lang/String;\\)V")
}

/** One JNI owner per module loader; never mistake a load conflict for working JNI. */
internal object SystemUiMaterialDexKit {
  private var nativeLoaded = false
  private var privateCopyLoaded = false

  @Synchronized
  fun open(apkPath: String, cacheDir: File?): DexKitBridge {
    require(File(apkPath).isFile && File(apkPath).canRead()) {
      "Unreadable SystemUI plugin APK: $apkPath"
    }
    check(SystemUiMaterialDexKit::class.java.classLoader === DexKitBridge::class.java.classLoader) {
      "DexKit and its native loader must belong to the same module ClassLoader"
    }
    if (!nativeLoaded) {
      try {
        System.loadLibrary("dexkit")
      } catch (failure: UnsatisfiedLinkError) {
        if (!isLoaderConflict(failure.message)) throw failure
        checkNotNull(cacheDir) {
          "DexKit belongs to an older module loader; pass the host codeCacheDir for reload"
        }
        try {
          loadPrivateCopy(cacheDir)
          privateCopyLoaded = true
        } catch (copyFailure: Throwable) {
          copyFailure.addSuppressed(failure)
          throw copyFailure
        }
      }
      nativeLoaded = true
    }
    // Opening verifies the native entry point in this loader, including reloads.
    return try {
      createValidBridge(apkPath)
    } catch (failure: UnsatisfiedLinkError) {
      if (privateCopyLoaded || cacheDir == null || !isNativeBindingFailure(failure.message)) {
        throw failure
      }
      try {
        loadPrivateCopy(cacheDir)
        privateCopyLoaded = true
        createValidBridge(apkPath)
      } catch (copyFailure: Throwable) {
        copyFailure.addSuppressed(failure)
        throw copyFailure
      }
    }
  }

  internal fun isLoaderConflict(message: String?): Boolean =
    message?.lowercase()?.let {
      (it.contains("already loaded") || it.contains("already opened")) &&
        (it.contains("classloader") || it.contains("class loader"))
    } == true

  private fun isNativeBindingFailure(message: String?): Boolean =
    message?.contains("no implementation found", ignoreCase = true) == true ||
      message?.contains("native method not found", ignoreCase = true) == true

  private fun createValidBridge(apkPath: String): DexKitBridge {
    val bridge = DexKitBridge.create(apkPath)
    if (!bridge.isValid) {
      bridge.close()
      error("DexKit could not open the plugin APK: $apkPath")
    }
    return bridge
  }

  private fun loadPrivateCopy(cacheDir: File) {
    val moduleLoader = SystemUiMaterialDexKit::class.java.classLoader
    check(moduleLoader === DexKitBridge::class.java.classLoader) {
      "DexKit and its native loader must belong to the same module ClassLoader"
    }
    // The module generation's own locator handles extracted libraries and APK
    // entries. The plugin APK and guessed paths are never native-library sources.
    val source = (moduleLoader as? BaseDexClassLoader)?.findLibrary("dexkit")
      ?: error("Module ClassLoader cannot locate libdexkit.so")
    val directory = File(cacheDir, "keihome-material-dexkit")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create DexKit reload directory" }
    val copy = File.createTempFile("libdexkit-", ".so", directory)
    try {
      copy.outputStream().use { output ->
        val zipSeparator = source.indexOf("!/")
        if (zipSeparator >= 0) {
          ZipFile(source.substring(0, zipSeparator)).use { zip ->
            val entry = requireNotNull(zip.getEntry(source.substring(zipSeparator + 2)))
            zip.getInputStream(entry).use { it.copyTo(output) }
          }
        } else {
          File(source).inputStream().use { it.copyTo(output) }
        }
      }
      check(copy.setReadOnly()) { "Cannot protect DexKit reload library" }
      System.load(copy.absolutePath)
    } finally {
      // dlopen keeps the mapping alive. Do not accumulate files across reloads.
      copy.delete()
    }
  }
}
