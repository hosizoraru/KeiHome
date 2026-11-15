package os.kei.hyper.home.xposed

import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.util.DexSignUtil
import org.luckypray.dexkit.wrap.DexMethod

/** Host method roles, resolved from the installed DEX; no OEM version or obfuscated member table. */
internal class ThemeImportProfile private constructor(private val methods: Map<String, Method>) {
  operator fun get(role: String): Method = methods.getValue(role)

  companion object {
    private const val PREFIX = "com.android.thememanager."
    private val roles = setOf("rights", "downloadRights", "apply", "detailReady", "validate", "parent", "inner", "registry",
      "iconContext", "controllerManager", "lookup", "appContext")

    fun resolve(loader: ClassLoader, info: ApplicationInfo): ThemeImportProfile {
      require(info.packageName == ThemeIconImportRuntime.PACKAGE_NAME && info.uid == android.os.Process.myUid())
      val directory = File(info.dataDir, "code_cache/keihome-theme-profile")
      check(directory.isDirectory || directory.mkdirs())
      val apk = File(info.sourceDir)
      val dexStamp = ZipFile(apk).use { zip -> zip.entries().asSequence()
        .filter { it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) || it.name == "AndroidManifest.xml" }
        .sortedBy { it.name }.joinToString { "${it.name}:${it.size}:${it.crc}" } }
      require(dexStamp.isNotEmpty())
      val identity = "theme-import-roles-v2:${apk.canonicalPath}:$dexStamp"
      val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
      val cache = AtomicFile(File(directory, "$key.json"))
      val cached = runCatching {
        require(cache.baseFile.length() in 1..32768)
        val root = JSONObject(cache.openRead().use { it.readNBytes(32769).toString(Charsets.UTF_8) })
        require(root.getString("identity") == identity)
        val data = root.getJSONObject("roles")
        require(data.keys().asSequence().toSet() == roles)
        roles.associateWith { data.getString(it) }
      }.getOrNull()
      fun reflect(descriptors: Map<String, String>): ThemeImportProfile = ThemeImportProfile(descriptors.mapValues { (_, value) ->
        val signature = DexMethod(value)
        val owner = Class.forName(signature.className, false, loader)
        fun type(name: String): Class<*> = when (name) {
          "boolean" -> Boolean::class.javaPrimitiveType!!
          "int" -> Int::class.javaPrimitiveType!!
          "long" -> Long::class.javaPrimitiveType!!
          else -> Class.forName(name, false, loader)
        }
        owner.getDeclaredMethod(signature.name, *signature.paramTypeNames.map(::type).toTypedArray()).also {
          require(DexSignUtil.getDescriptor(it) == value && !Modifier.isNative(it.modifiers))
          it.isAccessible = true
        }
      })
      if (cached != null) runCatching { reflect(cached) }.getOrNull()?.let {
        android.util.Log.i("KeiHomeThemeProfile", "Restored ${roles.size} installed-APK method roles")
        return it
      }
      val started = System.nanoTime()
      val proof = inspect(info.sourceDir, directory)
      val profile = reflect(proof)
      val bytes = JSONObject().put("identity", identity).put("roles", JSONObject(proof)).toString().toByteArray()
      val stream = cache.startWrite()
      try { stream.write(bytes); cache.finishWrite(stream) } catch (failure: Throwable) { cache.failWrite(stream); throw failure }
      android.util.Log.i("KeiHomeThemeProfile", "Resolved ${proof.size} method roles in ${(System.nanoTime() - started) / 1_000_000}ms")
      return profile
    }

    /** DEX-only diagnostics; does not initialize host classes or install hooks. */
    @OptIn(DexKitExperimentalApi::class)
    fun inspect(apkPath: String, cacheDirectory: File): Map<String, String> =
      SystemUiMaterialDexKit.open(apkPath, cacheDirectory).use { dex ->
        dex.setThreadNum(1)
        val resource = PREFIX + "basemodule.resource.model.Resource"
        val context = PREFIX + "ResourceContext"
        val newContext = PREFIX + "basemodule.resource.NewResourceContext"
        val controller = PREFIX + "controller.ThemeController"
        val manager = PREFIX + "controller.ResourceDataManager"
        val inner = PREFIX + "AppInnerContext"
        val registry = PREFIX + "ResourceContextManager"
        val result = linkedMapOf<String, MethodData>()
        fun pick(role: String, owner: String, returns: String, params: List<String>,
          static: Boolean, body: (MethodData) -> Boolean = { true }): MethodData {
          val candidates = dex.findMethod { matcher { declaredClass = owner; returnType = returns; paramTypes(params) } }
          check(candidates.size <= 64) { "Themes $role candidate budget exceeded" }
          val matches = candidates.filter { Modifier.isStatic(it.modifiers) == static && body(it) }
          check(matches.size == 1) { "Themes $role: expected one verified role; found ${matches.size}" }
          return matches.single().also { result[role] = it }
        }
        val rights = pick("rights", PREFIX + "module.detail.util.ResourceRightsHelper", "miui.drm.DrmManager\$DrmResult",
          listOf(context, controller, PREFIX + "controller.online.DrmService", resource), true) {
          it.invokes.any { call -> call.className == PREFIX + "controller.online.DrmService" && call.returnTypeName == "miui.drm.DrmManager\$DrmResult" }
        }
        pick("downloadRights", PREFIX + "controller.online.DrmService",
          PREFIX + "controller.online.DrmService\$GettingRightsResponse",
          listOf(resource, "int", "java.lang.String", "boolean"), false)
        pick("apply", PREFIX + "module.detail.util.ResourceApplyHelper", "void",
          listOf("android.app.Activity", "java.lang.String", resource, "int", "int", "boolean",
            "androidx.lifecycle.MutableLiveData", "boolean", "boolean", "boolean", "boolean"), true) {
          "spwallpaper" in it.usingStrings && it.invokes.any { call -> call.className == PREFIX + "util.ThemeApplyUtils" }
        }
        pick("detailReady", PREFIX + "module.detail.view.ThemeDetailActivity", "void", listOf(resource, "java.lang.String", "boolean"), false) {
          it.usingStrings.containsAll(listOf("REQUEST_APPLY_EVENT", "REQUEST_BUY_EVENT", "is_local"))
        }
        pick("validate", PREFIX + "util.ApplyValidateThemeTask", "android.util.Pair",
          listOf("android.content.Context", "java.lang.String", "java.lang.String"), false) {
          "validate theme in " in it.usingStrings && it.invokes.any { call -> call.className == "miui.drm.DrmManager" && call.name == "isLegal" }
        }
        val parent = pick("parent", PREFIX + "basemodule.resource.ResourceHelper", resource, listOf(resource, newContext), true) {
          it.invokes.any { call -> call.className == resource && call.name == "getParentResources" }
        }
        check(rights.invokes.any { it.descriptor == parent.descriptor }) { "Themes rights/parent graph mismatch" }
        pick("inner", inner, inner, emptyList(), true)
        pick("registry", inner, registry, emptyList(), false)
        pick("iconContext", registry, context, listOf("java.lang.String"), false) {
          it.invokes.any { call -> call.className == context && call.name == "setNewResourceContext" }
        }
        val controllerManager = pick("controllerManager", controller, manager, emptyList(), false)
        check(rights.invokes.any { it.descriptor == controllerManager.descriptor }) { "Themes controller graph mismatch" }
        val memory = pick("memoryLookup", PREFIX + "controller.MemoryDataManager", resource, listOf("java.lang.String"), false) {
          it.invokes.any { call -> call.className == "java.util.Map" && call.name == "get" } &&
            it.usingFields.any { use -> use.field.typeName == "java.util.Map" } &&
            dex.findMethod { matcher { declaredClass = manager; name = it.name; returnType = resource; paramTypes("java.lang.String") } }
              .singleOrNull()?.callers?.any { caller -> caller.invokes.any { call ->
                call.className == resource && call.name == "getLocalId"
              } } == true
        }
        val base = dex.findMethod { matcher { declaredClass = manager; name = memory.name; returnType = resource; paramTypes("java.lang.String") } }.single()
        result.remove("memoryLookup"); result["lookup"] = base
        pick("appContext", PREFIX + "basemodule.context.AppContextManager", "android.content.Context", emptyList(), true)
        check(result.keys == roles)
        result.mapValues { it.value.descriptor }
      }
  }
}
