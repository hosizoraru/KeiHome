package os.kei.hyper.home.xposed

import android.util.AtomicFile
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/** Only descriptors survive reload; live handles always belong to the current plugin loader. */
internal object SystemUiProfilesCache {
  // Bump when the resolver proof/role contracts change; never an OEM version gate.
  private const val SCHEMA = 1
  private const val MAX_BYTES = 256 * 1024

  data class Profiles(
    val material: SystemUiMaterialProfile,
    val controlCenter: OptionalControlCenterRefresh,
    val restored: Boolean,
  )

  fun resolve(
    loader: ClassLoader,
    apkPath: String,
    directory: File?,
    fresh: (() -> Profiles)? = null,
  ): Profiles {
    val identity = identity(apkPath)
    val file = directory?.let { AtomicFile(File(it, "keihome-systemui-profiles.json")) }
    if (file != null) runCatching {
      check(file.baseFile.length() in 1..MAX_BYTES.toLong())
      val json = JSONObject(file.openRead().use { String(it.readBytes(), Charsets.UTF_8) })
      check(json.getInt("schema") == SCHEMA && json.getString("source") == identity)
      val material = readMaterial(json.getJSONObject("material"), loader)
      val center = json.optJSONObject("center")?.let { readCenter(it, loader) }
      return Profiles(material, OptionalControlCenterRefresh(center, json.stringOrNull("centerReason")), true)
    }
    val resolved = fresh?.invoke() ?: run {
      val material = SystemUiMaterialProfile.resolve(loader, apkPath, directory)
      Profiles(material, SystemUiControlCenterRefreshProfile.resolve(loader, apkPath, material, directory), false)
    }
    val material = resolved.material
    val center = resolved.controlCenter
    if (file != null) runCatching {
      val bytes = JSONObject().put("schema", SCHEMA).put("source", identity)
        .put("material", writeMaterial(material)).put("center", center.profile?.let(::writeCenter))
        .put("centerReason", center.unavailableReason).toString().toByteArray(Charsets.UTF_8)
      check(bytes.size <= MAX_BYTES)
      val output = file.startWrite()
      try { output.write(bytes); file.finishWrite(output) }
      catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
    return Profiles(material, center, false)
  }

  private fun identity(path: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(File(path).canonicalPath.toByteArray())
    ZipFile(path).use { zip ->
      zip.entries().asSequence().filter { it.name == "AndroidManifest.xml" ||
        Regex("classes(?:[0-9]+)?\\.dex").matches(it.name) }.sortedBy { it.name }.forEach {
        digest.update("\n${it.name}:${it.size}:${it.crc}".toByteArray())
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun type(name: String, loader: ClassLoader): Class<*> = when (name) {
    "void" -> Void.TYPE
    "boolean" -> Boolean::class.javaPrimitiveType!!
    "byte" -> Byte::class.javaPrimitiveType!!
    "char" -> Char::class.javaPrimitiveType!!
    "short" -> Short::class.javaPrimitiveType!!
    "int" -> Int::class.javaPrimitiveType!!
    "long" -> Long::class.javaPrimitiveType!!
    "float" -> Float::class.javaPrimitiveType!!
    "double" -> Double::class.javaPrimitiveType!!
    else -> Class.forName(name, false, loader)
  }
  private fun method(m: Method): JSONObject = JSONObject().put("owner", m.declaringClass.name)
    .put("name", m.name).put("parameters", JSONArray(m.parameterTypes.map { it.name }))
    .put("result", m.returnType.name).put("modifiers", m.modifiers)
  private fun field(f: Field): JSONObject = JSONObject().put("owner", f.declaringClass.name)
    .put("name", f.name).put("result", f.type.name).put("modifiers", f.modifiers)
  private fun methods(ms: List<Method>) = JSONArray(ms.map(::method))
  private fun JSONObject.method(key: String, loader: ClassLoader): Method {
    val j = getJSONObject(key)
    val parameters = j.getJSONArray("parameters")
    return type(j.getString("owner"), loader).getDeclaredMethod(j.getString("name"),
      *(0 until parameters.length()).map { type(parameters.getString(it), loader) }.toTypedArray()).also {
      check(it.returnType.name == j.getString("result") && it.modifiers == j.getInt("modifiers"))
      it.isAccessible = true
    }
  }
  private fun JSONObject.field(key: String, loader: ClassLoader): Field {
    val j = getJSONObject(key)
    return type(j.getString("owner"), loader).getDeclaredField(j.getString("name")).also {
      check(it.type.name == j.getString("result") && it.modifiers == j.getInt("modifiers"))
      it.isAccessible = true
    }
  }
  private fun JSONObject.methods(key: String, loader: ClassLoader): List<Method> {
    val rows = getJSONArray(key)
    check(rows.length() <= 2048)
    return (0 until rows.length()).map { JSONObject().put("m", rows.getJSONObject(it)).method("m", loader) }
  }
  private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

  private fun writeMaterial(p: SystemUiMaterialProfile): JSONObject {
    val q = p.proof
    return JSONObject().put("themed", method(p.themedGate)).put("capability", method(p.capabilityGate))
      .put("proof", JSONArray(listOf(q.themedGate, q.capabilityGate, q.materialOwner, q.materialSupportedField,
        q.blurReader, q.themeOwner, q.pluginThemeGetter, q.sysUiThemeGetter, q.pluginThemeField, q.sysUiThemeField)))
      .put("callers", methods(p.callers)).put("volumeReason", p.volumeRefreshUnavailableReason)
      .put("volume", p.volumeRefresh?.let { JSONObject().put("show", method(it.show)).put("flag", field(it.reinitializeFlag)) })
  }
  private fun readMaterial(j: JSONObject, loader: ClassLoader): SystemUiMaterialProfile {
    val q = j.getJSONArray("proof"); check(q.length() == 10)
    val proof = SystemUiMaterialProof(q.getString(0), q.getString(1), q.getString(2), q.getString(3), q.getString(4),
      q.getString(5), q.getString(6), q.getString(7), q.getString(8), q.getString(9))
    val volume = j.optJSONObject("volume")?.let { VolumeMaterialRefreshProfile(it.method("show", loader), it.field("flag", loader)) }
    val themed = j.method("themed", loader); val capability = j.method("capability", loader)
    check(themed.declaringClass === capability.declaringClass)
    return SystemUiMaterialProfile(themed, capability, proof, j.methods("callers", loader), volume, j.stringOrNull("volumeReason"))
  }
  private fun writeCenter(p: SystemUiControlCenterRefreshProfile): JSONObject = JSONObject()
    .put("event", method(p.lifecycleEvent)).put("lifecycle", field(p.lifecycleField)).put("state", method(p.lifecycleState))
    .put("theme", method(p.updateThemeResources)).put("children", field(p.childControllers))
    .put("dispatch", method(p.dispatchMaterialModeChanged)).put("repository", field(p.materialRepository))
    .put("flow", method(p.materialFlow)).put("value", method(p.materialValue)).put("disabled", p.disabledModeClass.name)
    .put("callers", methods(p.callers)).put("tile", writeTile(p.smallTile)).put("sliderReason", p.sliderUnavailableReason)
    .put("slider", p.slider?.let { JSONObject().put("blend", method(it.updateBlendBlur))
      .put("resources", method(it.updateResources)).put("callers", methods(it.callers)) })
  private fun readCenter(j: JSONObject, l: ClassLoader): SystemUiControlCenterRefreshProfile =
    SystemUiControlCenterRefreshProfile(j.method("event", l), j.field("lifecycle", l), j.method("state", l),
      j.method("theme", l), j.field("children", l), j.method("dispatch", l), j.field("repository", l),
      j.method("flow", l), j.method("value", l), type(j.getString("disabled"), l), readTile(j.getJSONObject("tile"), l),
      j.methods("callers", l), j.optJSONObject("slider")?.let {
        ControlCenterSliderRefreshProfile(it.method("blend", l), it.method("resources", l), it.methods("callers", l))
      }, j.stringOrNull("sliderReason"))
  private fun writeTile(p: ControlCenterSmallTileRefreshProfile): JSONObject = JSONObject()
    .put("material", method(p.materialChanged)).put("icon", method(p.icon)).put("added", field(p.added))
    .put("resources", method(p.updateResources)).put("update", method(p.updateIcon)).put("state", field(p.currentState))
    .put("enhance", method(p.updateEnhancement)).put("pending", field(p.updatePending)).put("complete", method(p.pendingUpdateCompleted))
    .put("backgroundReason", p.backgroundCacheUnavailableReason).put("background", p.backgroundCache?.let {
      JSONObject().put("state", method(it.backgroundForState)).put("set", method(it.setEnabledBackground))
        .put("enabled", field(it.enabledBackground)).put("disabled", field(it.disabledBackground))
        .put("internal", method(it.updateIconInternal)).put("callers", methods(it.callers))
    })
  private fun readTile(j: JSONObject, l: ClassLoader): ControlCenterSmallTileRefreshProfile =
    ControlCenterSmallTileRefreshProfile(j.method("material", l), j.method("icon", l), j.field("added", l),
      j.method("resources", l), j.method("update", l), j.field("state", l), j.method("enhance", l),
      j.field("pending", l), j.method("complete", l), j.optJSONObject("background")?.let {
        ControlCenterTileBackgroundCacheProfile(it.method("state", l), it.method("set", l), it.field("enabled", l),
          it.field("disabled", l), it.method("internal", l), it.methods("callers", l))
      }, j.stringOrNull("backgroundReason"))
}
