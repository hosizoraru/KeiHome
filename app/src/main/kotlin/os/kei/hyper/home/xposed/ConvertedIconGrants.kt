package os.kei.hyper.home.xposed

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Exact Themes 11540 association and private persistence; no active theme files are written. */
internal class ConvertedIconGrants(private val loader: ClassLoader) {
  private val resourceClass = type("basemodule.resource.model.Resource")
  private val contextClass = type("ResourceContext")
  private val controllerClass = type("controller.ThemeController")
  private val resolverConstructor = type("model.ResourceResolver").getConstructor(resourceClass, contextClass)
  private val parentMethod = type("basemodule.resource.ResourceHelper").getMethod("h", resourceClass,
    type("basemodule.resource.NewResourceContext"))
  private val lock = Any()
  private var loaded: Map<String, ConvertedIconGrant>? = null

  fun register(context: Context, stagePath: String, localId: String, expected: ConvertedIconArchive.Proof) {
    checkVersion(context)
    val stage = File(stagePath)
    val directory = requireNotNull(stage.parentFile)
    require(stage.isAbsolute && stage.name == "icons.mtz" &&
      directory.parentFile == File("/data/system/theme/.keihome-import") &&
      UUID.fromString(directory.name).toString() == directory.name && stage.canonicalFile == stage) {
      "Converted proof is outside the system-owned staging path"
    }
    for (file in listOf(directory.parentFile!!, directory, stage)) {
      val stat = Os.lstat(file.path)
      require(stat.st_uid == 1000 && stat.st_mode and 0x12 == 0 &&
        if (file == stage) OsConstants.S_ISREG(stat.st_mode) else OsConstants.S_ISDIR(stat.st_mode)) {
        "Converted proof must remain system-owned without shared write access"
      }
    }
    val proof = ConvertedIconArchive.inspect(stage, expected.origin, context.cacheDir)
    require(proof == expected) { "Converted proof digest mismatch" }
    val ctx = iconContext()
    val icon = lookup(ctx, localId)
    val parent = requireNotNull(parent(icon, ctx)) { "Imported converted Icon has no native parent" }
    val grant = ConvertedIconGrant(proof, localId, snapshot(parent).localId)
    require(grant.matches(snapshot(icon), snapshot(parent))) { "Converted native association/store ID mismatch" }
    require(ConvertedIconArchive.digest(content(icon, ctx)) == proof.icons) { "Native Icon bytes do not match conversion" }
    synchronized(lock) {
      val updated = read(context).toMutableMap().apply { put(localId, grant) }
      require(updated.size <= 700) { "Converted approval limit" }
      write(context, updated)
      loaded = updated
    }
  }

  /** Background rights preflight rechecks current association and the complete native component. */
  fun eligible(resourceContext: Any, resource: Any): Boolean {
    if (string(resourceContext, "getResourceCode") != "icons") return false
    val ctx = appContext()
    val candidate = approved(resource, resourceContext, ctx) ?: return false
    val suppliedContent = content(resource, resourceContext)
    val currentContent = content(lookup(resourceContext, candidate.localId), resourceContext)
    return suppliedContent == currentContent &&
      ConvertedIconArchive.digest(currentContent) == candidate.proof.icons
  }

  /** This UI hook only limits related-component scope. It never grants rights by itself. */
  fun iconsOnly(resource: Any): Boolean = approved(resource, iconContext(), appContext()) != null

  /** Native post-apply scans other components normally. Only the exact approved icons file passes. */
  fun validates(task: Any, path: String, context: Context): Boolean {
    val active = File(path)
    if (path != "/data/system/theme/icons" || active.canonicalPath != path || !active.isFile) return false
    val fields = task.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
    val codesField = fields.single { Set::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
    if (codesField.get(task) != setOf("icons")) return false
    val resourceField = fields.single { it.type == resourceClass }.apply { isAccessible = true }
    val applied = snapshot(requireNotNull(resourceField.get(task)))
    if (!applied.hasNoStoreId) return false
    val ctx = iconContext()
    val candidates = synchronized(lock) { read(context).values.toList() }.filter {
      (applied.localId == it.localId && applied.hash == it.proof.icons.sha1) ||
        (applied.localId == it.parentLocalId && applied.hash == it.proof.archive.sha1)
    }
    val grants = candidates.filter { grant ->
      val icon = lookup(ctx, grant.localId)
      approved(icon, ctx, context) == grant
    }
    if (grants.isEmpty()) return false
    val digest = ConvertedIconArchive.digest(active)
    return grants.any { it.proof.icons == digest }
  }

  private fun approved(resource: Any, ctx: Any, context: Context): ConvertedIconGrant? {
    val supplied = snapshot(resource)
    if (!supplied.hasNoStoreId) return null
    val grant = synchronized(lock) { read(context)[supplied.localId] } ?: return null
    val current = lookup(ctx, supplied.localId)
    val parent = parent(current, ctx) ?: return null
    if (supplied != snapshot(current) || !grant.matches(supplied, snapshot(parent))) return null
    // Also check the caller's parent association; stale detail resources cannot gain approval.
    if (snapshot(parent(resource, ctx) ?: return null) != snapshot(parent)) return null
    return grant
  }

  private fun iconContext(): Any {
    val inner = type("AppInnerContext").getMethod("zy").invoke(null)
    val registry = inner.javaClass.getMethod("n").invoke(inner)
    return requireNotNull(registry.javaClass.getMethod("f7l8", String::class.java).invoke(registry, "icons"))
  }

  private fun lookup(ctx: Any, localId: String): Any {
    val controller = controllerClass.getConstructor(contextClass).newInstance(ctx)
    val manager = controllerClass.getMethod("k").invoke(controller)
    return requireNotNull(manager.javaClass.getMethod("qrj", String::class.java).invoke(manager, localId)) {
      "Imported Icon is no longer in the native library"
    }
  }

  private fun parent(resource: Any, ctx: Any): Any? = parentMethod.invoke(null, resource,
    contextClass.getMethod("getNewResourceContext").invoke(ctx))

  private fun content(resource: Any, ctx: Any): File {
    val resolver = resolverConstructor.newInstance(resource, ctx)
    val file = File(requireNotNull(string(resolver, "getContentPath")))
    require(file.isAbsolute) { "Native Icon content path is not absolute" }
    // The native resolver can use /sdcard or /data/user/0 aliases. This path is native metadata,
    // not a caller handoff; canonicalize it and bind approval to the complete content digest.
    return file.canonicalFile.also { require(it.isFile) { "Native Icon content is not a regular file" } }
  }

  private fun snapshot(resource: Any) = ConvertedIconResource(
    requireNotNull(string(resource, "getLocalId")), requireNotNull(string(resource, "getHash")).lowercase(),
    string(resource, "getOnlineId"), string(resource, "getProductId"), string(resource, "getAssemblyId"))

  private fun appContext(): Context = type("basemodule.context.AppContextManager").getMethod("q")
    .invoke(null) as Context

  private fun type(name: String): Class<*> = Class.forName("com.android.thememanager.$name", false, loader)
  private fun string(target: Any, name: String): String? = target.javaClass.getMethod(name).invoke(target) as? String

  private fun checkVersion(context: Context) {
    require(context.packageName == ThemeIconImportRuntime.PACKAGE_NAME)
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    require(info.longVersionCode == 11540L && info.versionName == "11.5.4.0" &&
      info.applicationInfo?.uid == Process.myUid()) { "Unverified converted Icon contract" }
  }

  private fun storage(context: Context): AtomicFile = AtomicFile(File(
    context.getDir("keihome_converted_icons", Context.MODE_PRIVATE), "grants-v1.json"))

  // Called under lock. Corrupt/unknown storage fails closed; it cannot produce an approval.
  private fun read(context: Context): Map<String, ConvertedIconGrant> {
    loaded?.let { return it }
    checkVersion(context)
    val file = storage(context)
    val grants = LinkedHashMap<String, ConvertedIconGrant>()
    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
      val bytes = file.openRead().use { it.readBytesLimited(1024 * 1024) }
      val root = JSONObject(String(bytes, Charsets.UTF_8))
      require(root.getInt("version") == 1)
      val rows = root.getJSONArray("grants")
      require(rows.length() <= 700)
      repeat(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val origin = row.getString("origin")
        require(origin == "CONVERTED_APK" || origin == "CONVERTED_CLASSIC_ZIP")
        fun hash(key: String, length: Int): String = row.getString(key).also {
          require(it.matches(Regex("[0-9a-f]{$length}")))
        }
        val grant = ConvertedIconGrant(ConvertedIconArchive.Proof(origin,
          ConvertedIconArchive.Digests(hash("archiveSha1", 40), hash("archiveSha256", 64)),
          ConvertedIconArchive.Digests(hash("iconSha1", 40), hash("iconSha256", 64))),
          row.getString("localId"), row.getString("parentLocalId"))
        require(grant.localId.length in 1..128 && grant.parentLocalId.length in 1..128 &&
          grants.put(grant.localId, grant) == null)
      }
    }
    loaded = grants
    return grants
  }

  private fun write(context: Context, grants: Map<String, ConvertedIconGrant>) {
    val rows = JSONArray()
    grants.values.forEach { grant ->
      rows.put(JSONObject().put("origin", grant.proof.origin).put("localId", grant.localId)
        .put("parentLocalId", grant.parentLocalId).put("archiveSha1", grant.proof.archive.sha1)
        .put("archiveSha256", grant.proof.archive.sha256).put("iconSha1", grant.proof.icons.sha1)
        .put("iconSha256", grant.proof.icons.sha256))
    }
    val bytes = JSONObject().put("version", 1).put("grants", rows).toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= 1024 * 1024)
    val file = storage(context)
    val stream = file.startWrite()
    try { stream.write(bytes); file.finishWrite(stream) }
    catch (failure: Throwable) { file.failWrite(stream); throw failure }
  }

  private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val count = read(buffer)
      if (count < 0) break
      require(output.size() + count <= limit) { "Converted approval storage size limit" }
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }
}
