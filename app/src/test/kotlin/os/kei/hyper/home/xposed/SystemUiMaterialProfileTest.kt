package os.kei.hyper.home.xposed

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiMaterialProfileTest {
  @Test
  fun resolvesOnlyTheTwoThemeAwareAndCapabilityGates() {
    val graph = graph()

    val proof = SystemUiMaterialContract.resolve(graph)

    assertEquals(THEMED, proof.themedGate)
    assertEquals(CAPABILITY, proof.capabilityGate)
    assertEquals(SUPPORT, proof.materialSupportedField)
    assertEquals(BLUR, proof.blurReader)
    assertEquals(PLUGIN_GETTER, proof.pluginThemeGetter)
    assertEquals(SYSUI_GETTER, proof.sysUiThemeGetter)
    assertEquals(PLUGIN_FIELD, proof.pluginThemeField)
    assertEquals(SYSUI_FIELD, proof.sysUiThemeField)
  }

  @Test
  fun rejectsCapabilityThatRetainsThemeIdentityCalls() {
    val original = graph()
    rejects(original.edit(CAPABILITY) { copy(calls = calls + PLUGIN_GETTER) })
  }

  @Test
  fun rejectsTwoCapabilityImplementationsEvenWhenBothUseTheSameBlurReader() {
    val original = graph()
    val second = original.methods.getValue(CAPABILITY).copy(
      id = "Lmiui/systemui/util/MiBlurCompat;->otherCapability(Landroid/content/res/Configuration;)Z",
      name = "otherCapability",
    )
    rejects(original.copy(methods = original.methods + (second.id to second)))
  }

  @Test
  fun acceptsARenamedCapabilityThroughItsFieldAndCallContract() {
    val original = graph()
    val renamed = original.methods.getValue(CAPABILITY).copy(
      id = "Lmiui/systemui/util/MiBlurCompat;->a(Landroid/content/res/Configuration;)Z",
      name = "a",
    )
    val updated = original.copy(methods = original.methods - CAPABILITY + (renamed.id to renamed))
    assertEquals(renamed.id, SystemUiMaterialContract.resolve(updated).capabilityGate)
  }

  @Test
  fun acceptsADirectContextCapability() {
    val original = graph()
    val context = original.methods.getValue(CAPABILITY).copy(
      id = CONTEXT_CAPABILITY,
      parameters = listOf(MATERIAL_CONTEXT),
      calls = setOf(BLUR, CONTEXT_RESOURCES, RESOURCES_CONFIGURATION, INTRINSICS_CHECK),
    )
    val updated = original.copy(methods = original.methods - CAPABILITY + (context.id to context))
    assertEquals(context.id, SystemUiMaterialContract.resolve(updated).capabilityGate)
  }

  @Test
  fun contextForwarderDoesNotMakeTheDirectConfigurationGateAmbiguous() {
    val original = graph()
    val forwarder = original.methods.getValue(CAPABILITY).copy(
      id = CONTEXT_CAPABILITY,
      parameters = listOf(MATERIAL_CONTEXT),
      calls = setOf(CAPABILITY, CONTEXT_RESOURCES, RESOURCES_CONFIGURATION, INTRINSICS_CHECK),
      reads = emptySet(),
    )
    val updated = original.copy(methods = original.methods + (forwarder.id to forwarder))
    assertEquals(CAPABILITY, SystemUiMaterialContract.resolve(updated).capabilityGate)
  }

  @Test
  fun rejectsAMissingOrUnrelatedCapabilityField() {
    val original = graph()
    rejects(original.edit(CAPABILITY) { copy(reads = emptySet()) })
    val other = "Lmiui/systemui/util/MiBlurCompat;->OTHER_SUPPORTED:Z"
    val changed = original.edit(CAPABILITY) { copy(reads = setOf(other)) }
    rejects(changed.copy(fields = changed.fields +
      (other to original.fields.getValue(SUPPORT).copy(id = other))))
  }

  @Test
  fun rejectsAMissingThemeGetterOrUnprovenThemeField() {
    val original = graph()
    rejects(original.edit(THEMED) { copy(calls = calls - SYSUI_GETTER) })
    rejects(original.edit(PLUGIN_GETTER) { copy(reads = setOf(SYSUI_FIELD)) })
    rejects(original.copy(methods = original.methods.filterValues {
      "/data/system/theme/miui.systemui.plugin" !in it.strings
    }))
  }

  @Test
  fun rejectsADifferentBlurReader() {
    rejects(graph().edit(CAPABILITY) {
      copy(calls = setOf("Lmiui/systemui/util/MiBlurCompat;->otherBlur(Landroid/content/res/Configuration;)I"))
    })
  }

  @Test
  fun rejectsSideEffectsAndNonStaticGateSignatures() {
    val original = graph()
    rejects(original.edit(CAPABILITY) { copy(writes = setOf(SUPPORT)) })
    rejects(original.edit(THEMED) { copy(modifiers = Modifier.PUBLIC) })
    rejects(original.edit(CAPABILITY) { copy(modifiers = Modifier.PUBLIC) })
  }

  @Test
  fun rejectsIndirectThemeReadsThroughABlurHelper() {
    val helper = method(
      id = "Lvendor/Helper;->read()V",
      owner = "vendor.Helper",
      name = "read",
      result = "void",
      calls = setOf(PLUGIN_GETTER),
    )
    val updated = graph().edit(BLUR) { copy(calls = calls + helper.id) }
    rejects(updated.copy(methods = updated.methods + (helper.id to helper)))
  }

  @Test
  fun rejectsAnArbitraryVoidHelperWithTheSameSignatureAsANullCheck() {
    rejects(graph().edit(CAPABILITY) {
      copy(calls = calls + "Lvendor/Helper;->read(Ljava/lang/Object;Ljava/lang/String;)V")
    })
  }

  @Test
  fun rejectsTwoThemedGatesEvenWithDifferentApiNames() {
    val original = graph()
    val other = original.methods.getValue(THEMED).copy(
      id = "Lmiui/systemui/util/MiBlurCompat;->otherThemed(Landroid/content/Context;)Z",
      name = "otherThemed",
    )
    val failure = rejects(original.copy(methods = original.methods + (other.id to other)))
    assertTrue(failure.message.orEmpty().contains("found 2"))
  }

  @Test
  fun recognizesAReloadConflictWithoutTreatingOtherErrorsAsReloads() {
    assertTrue(SystemUiMaterialDexKit.isLoaderConflict(
      "dlopen failed: library is already loaded in another classloader",
    ))
    assertTrue(SystemUiMaterialDexKit.isLoaderConflict(
      "library is already opened by another ClassLoader",
    ))
    assertTrue(SystemUiMaterialDexKit.isLoaderConflict(
      "dlopen failed: library is already loaded by ClassLoader",
    ))
    assertFalse(SystemUiMaterialDexKit.isLoaderConflict(
      "dlopen failed: library not found",
    ))
    assertFalse(SystemUiMaterialDexKit.isLoaderConflict(null))
  }

  @Test
  fun callerValidationPreservesTheExactDeclaredMethodWithoutInvocation() {
    val caller = CallerParent::class.java.getDeclaredMethod("invokesGate", IntArray::class.java)
    assertEquals(caller, reflectMaterialCaller(
      javaClass.classLoader, CALLER_DESCRIPTOR, Modifier.PUBLIC,
    ))
  }

  @Test
  fun callerValidationRejectsSuperclassFallback() {
    val childDescriptor = CALLER_DESCRIPTOR.replace("\$CallerParent;", "\$CallerChild;")
    assertThrows(NoSuchMethodException::class.java) {
      reflectMaterialCaller(javaClass.classLoader, childDescriptor, Modifier.PUBLIC)
    }
  }

  @Test
  fun callerValidationRejectsSignatureAndStaticModifierDrift() {
    val caller = CallerParent::class.java.getDeclaredMethod("invokesGate", IntArray::class.java)
    assertThrows(IllegalStateException::class.java) {
      requireExactMaterialCaller(
        caller, javaClass.classLoader, CALLER_DESCRIPTOR.replace("[I", "[J"), Modifier.PUBLIC,
      )
    }
    assertThrows(IllegalStateException::class.java) {
      requireExactMaterialCaller(
        caller, javaClass.classLoader, CALLER_DESCRIPTOR.replace("Ljava/lang/String;", "Z"),
        Modifier.PUBLIC,
      )
    }
    assertThrows(IllegalStateException::class.java) {
      requireExactMaterialCaller(
        caller, javaClass.classLoader, CALLER_DESCRIPTOR, Modifier.PUBLIC or Modifier.STATIC,
      )
    }
  }

  @Test
  fun callerValidationRejectsADifferentLiveLoaderResolution() {
    val owner = CallerParent::class.java
    val caller = owner.getDeclaredMethod("invokesGate", IntArray::class.java)
    val resource = "/${owner.name.replace('.', '/')}.class"
    val bytes = requireNotNull(owner.getResourceAsStream(resource)).use { it.readBytes() }
    val differentOwnerLoader = object : ClassLoader(javaClass.classLoader) {
      private val isolatedOwner = defineClass(owner.name, bytes, 0, bytes.size)

      override fun loadClass(name: String): Class<*> =
        if (name == owner.name) isolatedOwner else super.loadClass(name)
    }
    assertThrows(IllegalStateException::class.java) {
      requireExactMaterialCaller(caller, differentOwnerLoader, CALLER_DESCRIPTOR, Modifier.PUBLIC)
    }
  }

  @Test
  fun callerResolutionHandlesAllPrimitivesAndMultidimensionalArrays() {
    val descriptor = "Los/kei/hyper/home/xposed/SystemUiMaterialProfileTest\$PrimitiveCaller;" +
      "->calls(ZBCSIJFD[[I[[Ljava/lang/String;Ljava/lang/Object;)[[Ljava/lang/String;"
    val method = reflectMaterialCaller(javaClass.classLoader, descriptor, Modifier.PUBLIC)
    assertEquals(
      listOf("boolean", "byte", "char", "short", "int", "long", "float", "double",
        "[[I", "[[Ljava.lang.String;", "java.lang.Object"),
      method.parameterTypes.map { it.name },
    )
    assertEquals("[[Ljava.lang.String;", method.returnType.name)
  }

  @Test
  fun callerResolutionDoesNotInitializeOwnerOrArrayComponentTypes() {
    val owner = "Los/kei/hyper/home/xposed/SystemUiMaterialProfileTest\$InitializationSensitiveCaller;"
    val parameter = "Los/kei/hyper/home/xposed/SystemUiMaterialProfileTest\$InitializationSensitiveParameter;"
    val method = reflectMaterialCaller(
      javaClass.classLoader, "$owner->calls([[$parameter)V", Modifier.PUBLIC,
    )
    assertEquals("calls", method.name)
    assertEquals(Void.TYPE, method.returnType)
  }

  @Suppress("UNUSED_PARAMETER")
  private class PrimitiveCaller {
    fun calls(
      boolean: Boolean, byte: Byte, char: Char, short: Short, int: Int, long: Long,
      float: Float, double: Double, ints: Array<IntArray>, strings: Array<Array<String>>, any: Any,
    ): Array<Array<String>> = error("Resolving a caller must not invoke it")
  }

  private class InitializationSensitiveCaller {
    @Suppress("UNUSED_PARAMETER")
    fun calls(value: Array<Array<InitializationSensitiveParameter>>) = Unit

    companion object {
      init { error("Caller resolution initialized the owner") }
    }
  }

  private class InitializationSensitiveParameter {
    companion object {
      init { error("Caller resolution initialized an array component") }
    }
  }

  private open class CallerParent {
    @Suppress("UNUSED_PARAMETER")
    fun invokesGate(values: IntArray): String = error("Resolving a caller must not invoke it")
  }

  private class CallerChild : CallerParent()

  private fun MaterialGraph.edit(id: String, change: MaterialMethod.() -> MaterialMethod): MaterialGraph =
    copy(methods = methods + (id to methods.getValue(id).change()))

  private fun rejects(graph: MaterialGraph): IllegalStateException =
    assertThrows(IllegalStateException::class.java) { SystemUiMaterialContract.resolve(graph) }

  private fun graph(): MaterialGraph {
    val themed = method(
      id = THEMED,
      owner = MATERIAL_OWNER,
      name = "getBackgroundMaterialOpenedInDefaultTheme",
      result = "boolean",
      parameters = listOf(MATERIAL_CONTEXT),
      modifiers = Modifier.PUBLIC or Modifier.STATIC,
      calls = setOf(
        CONTEXT_RESOURCES,
        RESOURCES_CONFIGURATION,
        PLUGIN_GETTER,
        SYSUI_GETTER,
        BLUR,
        INTRINSICS_CHECK,
      ),
      reads = setOf(SUPPORT, THEME_INSTANCE),
    )
    val capability = method(
      id = CAPABILITY,
      owner = MATERIAL_OWNER,
      name = "getBackgroundMaterialOpened",
      result = "boolean",
      parameters = listOf(MATERIAL_CONFIGURATION),
      modifiers = Modifier.PUBLIC or Modifier.STATIC,
      calls = setOf(BLUR),
      reads = setOf(SUPPORT),
    )
    val blur = method(
      id = BLUR,
      owner = MATERIAL_OWNER,
      name = "getBlurCompat",
      result = "int",
      parameters = listOf(MATERIAL_CONFIGURATION),
      modifiers = Modifier.PUBLIC or Modifier.STATIC,
      calls = setOf(FIELD_GET),
    )
    val pluginGetter = method(
      id = PLUGIN_GETTER,
      owner = THEME_OWNER,
      name = "getDefaultPluginTheme",
      result = "boolean",
      reads = setOf(PLUGIN_FIELD),
    )
    val sysUiGetter = method(
      id = SYSUI_GETTER,
      owner = THEME_OWNER,
      name = "getDefaultSysUiTheme",
      result = "boolean",
      reads = setOf(SYSUI_FIELD),
    )
    val pluginUpdater = method(
      id = "Lmiui/systemui/util/ThemeUtils;->updateDefaultPluginTheme()V",
      owner = THEME_OWNER,
      name = "updateDefaultPluginTheme",
      result = "void",
      calls = setOf(PLUGIN_SETTER, FILE_EXISTS),
      strings = setOf("/data/system/theme/miui.systemui.plugin"),
    )
    val sysUiUpdater = method(
      id = "Lmiui/systemui/util/ThemeUtils;->updateDefaultSysUiTheme()V",
      owner = THEME_OWNER,
      name = "updateDefaultSysUiTheme",
      result = "void",
      calls = setOf(SYSUI_SETTER, FILE_EXISTS),
      strings = setOf("/data/system/theme/com.android.systemui"),
    )
    val pluginSetter = method(
      id = PLUGIN_SETTER,
      owner = THEME_OWNER,
      name = "setDefaultPluginTheme",
      result = "void",
      parameters = listOf("boolean"),
      writes = setOf(PLUGIN_FIELD),
    )
    val sysUiSetter = method(
      id = SYSUI_SETTER,
      owner = THEME_OWNER,
      name = "setDefaultSysUiTheme",
      result = "void",
      parameters = listOf("boolean"),
      writes = setOf(SYSUI_FIELD),
    )
    val methods = listOf(
      themed,
      capability,
      blur,
      pluginGetter,
      sysUiGetter,
      pluginUpdater,
      sysUiUpdater,
      pluginSetter,
      sysUiSetter,
    ).associateBy { it.id }
    val fields = listOf(
      field(SUPPORT, MATERIAL_OWNER, "boolean", Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL),
      field(THEME_INSTANCE, THEME_OWNER, THEME_OWNER, Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL),
      field(PLUGIN_FIELD, THEME_OWNER, "boolean", Modifier.PRIVATE or Modifier.STATIC),
      field(SYSUI_FIELD, THEME_OWNER, "boolean", Modifier.PRIVATE or Modifier.STATIC),
    ).associateBy { it.id }
    return MaterialGraph(methods, fields)
  }

  private fun method(
    id: String,
    owner: String,
    name: String,
    result: String,
    parameters: List<String> = emptyList(),
    modifiers: Int = Modifier.PUBLIC,
    calls: Set<String> = emptySet(),
    reads: Set<String> = emptySet(),
    writes: Set<String> = emptySet(),
    strings: Set<String> = emptySet(),
  ) = MaterialMethod(id, owner, name, result, parameters, modifiers, calls, reads, writes, strings)

  private fun field(id: String, owner: String, type: String, modifiers: Int) =
    MaterialField(id, owner, type, modifiers)

  private companion object {
    const val CALLER_DESCRIPTOR =
      "Los/kei/hyper/home/xposed/SystemUiMaterialProfileTest\$CallerParent;->invokesGate([I)Ljava/lang/String;"
    const val MATERIAL_OWNER = "miui.systemui.util.MiBlurCompat"
    const val THEME_OWNER = "miui.systemui.util.ThemeUtils"
    const val MATERIAL_CONTEXT = "android.content.Context"
    const val MATERIAL_CONFIGURATION = "android.content.res.Configuration"
    const val THEMED =
      "Lmiui/systemui/util/MiBlurCompat;->getBackgroundMaterialOpenedInDefaultTheme(" +
        "Landroid/content/Context;)Z"
    const val CAPABILITY =
      "Lmiui/systemui/util/MiBlurCompat;->getBackgroundMaterialOpened(" +
        "Landroid/content/res/Configuration;)Z"
    const val CONTEXT_CAPABILITY =
      "Lmiui/systemui/util/MiBlurCompat;->getBackgroundMaterialOpened(Landroid/content/Context;)Z"
    const val BLUR =
      "Lmiui/systemui/util/MiBlurCompat;->getBlurCompat(" +
        "Landroid/content/res/Configuration;)I"
    const val SUPPORT = "Lmiui/systemui/util/MiBlurCompat;->MATERIAL_SUPPORTED:Z"
    const val THEME_INSTANCE =
      "Lmiui/systemui/util/ThemeUtils;->INSTANCE:Lmiui/systemui/util/ThemeUtils;"
    const val PLUGIN_FIELD = "Lmiui/systemui/util/ThemeUtils;->defaultPluginTheme:Z"
    const val SYSUI_FIELD = "Lmiui/systemui/util/ThemeUtils;->defaultSysUiTheme:Z"
    const val PLUGIN_GETTER =
      "Lmiui/systemui/util/ThemeUtils;->getDefaultPluginTheme()Z"
    const val SYSUI_GETTER =
      "Lmiui/systemui/util/ThemeUtils;->getDefaultSysUiTheme()Z"
    const val PLUGIN_SETTER =
      "Lmiui/systemui/util/ThemeUtils;->setDefaultPluginTheme(Z)V"
    const val SYSUI_SETTER =
      "Lmiui/systemui/util/ThemeUtils;->setDefaultSysUiTheme(Z)V"
    const val CONTEXT_RESOURCES =
      "Landroid/content/Context;->getResources()Landroid/content/res/Resources;"
    const val RESOURCES_CONFIGURATION =
      "Landroid/content/res/Resources;->getConfiguration()" +
        "Landroid/content/res/Configuration;"
    const val FIELD_GET =
      "Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;"
    const val FILE_EXISTS = "Ljava/io/File;->exists()Z"
    const val INTRINSICS_CHECK =
      "Lkotlin/jvm/internal/l;->f(Ljava/lang/Object;Ljava/lang/String;)V"
  }
}
