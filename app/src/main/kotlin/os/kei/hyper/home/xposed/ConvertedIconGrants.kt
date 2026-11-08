package os.kei.hyper.home.xposed

import android.content.Context
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Dynamically resolved native association and private persistence. */
internal class ConvertedIconGrants(
  private val loader: ClassLoader,
  private val profile: ThemeImportProfile,
  private val hashTiming: (String, Long) -> Unit = { _, _ -> },
) {
  private val resourceClass = type("basemodule.resource.model.Resource")
  private val contextClass = type("ResourceContext")
  private val controllerClass = type("controller.ThemeController")
  private val resolverConstructor = type("model.ResourceResolver").getConstructor(resourceClass, contextClass)
  private val parentMethod = profile["parent"]
  private val approvals = ConvertedIconApprovals()
  // Public model getters read fields; the separately resolved parent role reads parent metadata.
  private val resourceGetters = listOf("getLocalId", "getHash", "getOnlineId", "getProductId",
    "getAssemblyId", "getMetaPath", "getContentPath", "getResourceStorageType", "getParentResources")
    .associateWith { resourceClass.getMethod(it) }
  private val relatedClass = type("basemodule.resource.model.RelatedResource")
  private val relatedGetters = listOf("getLocalId", "getResourceCode", "getMetaPath",
    "getContentPath", "getResourceStorageType")
    .associateWith { relatedClass.getMethod(it) }

  /** Owned startup worker; association snapshots limit scope, never authorize DRM. */
  fun initialize(context: Context) {
    requireWorker()
    approvals.load { read(context) }
    val entries = approvals.entries()
    if (entries.isEmpty()) return
    val ctx = iconContext()
    for (entry in entries) {
      if (Thread.currentThread().isInterrupted) throw InterruptedException("Converted approval preload interrupted")
      try {
        association(lookup(ctx, entry.grant.localId), ctx, entry.grant)?.let { approvals.publish(entry, it) }
      } catch (failure: Exception) {
        if (failure is InterruptedException) throw failure
        // A removed native resource cannot stop other valid receipts from warming.
      }
    }
  }

  fun retire() = approvals.retire()

  fun register(context: Context, stagePath: String, localId: String, expected: ConvertedIconArchive.Proof) {
    requireWorker()
    checkHost(context)
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
    val approved = requireNotNull(association(icon, ctx, grant))
    require(ConvertedIconArchive.digest(File(approved.contentPath)) == proof.icons) {
      "Native Icon bytes do not match conversion"
    }
    check(approvals.register(grant, approved, { read(context) }, { write(context, it) })) {
      "Converted approval generation retired"
    }
  }

  /** Background rights preflight rechecks current association and the complete native component. */
  fun eligible(resourceContext: Any, resource: Any): Boolean {
    requireWorker()
    if (string(resourceContext, "getResourceCode") != "icons") return false
    approvals.load { read(appContext()) }
    val entry = approvals.entry(snapshot(resource).localId) ?: return false
    var accepted = false
    try {
      val approved = association(resource, resourceContext, entry.grant) ?: return false
      if (digest(File(approved.contentPath), "rights") != entry.grant.proof.icons ||
        supplied(resource) != approved.supplied) return false
      accepted = approvals.publish(entry, approved, preflight = true)
      return accepted
    } finally {
      if (!accepted) approvals.invalidate(entry)
    }
  }

  /** UI: immutable values and field getters only; no store, native resolver, hash or writer lock. */
  fun iconsOnly(resource: Any): Boolean = try { approvals.iconsOnly(supplied(resource)) }
    catch (_: Exception) { false }

  /** Native post-apply scans other components normally. Only the exact approved icons file passes. */
  fun validates(task: Any, path: String, context: Context): Boolean {
    requireWorker()
    val active = File(path)
    if (path != "/data/system/theme/icons" || active.canonicalPath != path || !active.isFile) return false
    val fields = task.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
    val codesField = fields.single { Set::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
    if (codesField.get(task) != setOf("icons")) return false
    val resourceField = fields.single { it.type == resourceClass }.apply { isAccessible = true }
    val applied = snapshot(requireNotNull(resourceField.get(task)))
    if (!applied.hasNoStoreId) return false
    val ctx = iconContext()
    approvals.load { read(context) }
    val candidates = approvals.entries().filter {
      (applied.localId == it.grant.localId && applied.hash == it.grant.proof.icons.sha1) ||
        (applied.localId == it.grant.parentLocalId && applied.hash == it.grant.proof.archive.sha1)
    }
    val grants = candidates.filter { entry ->
      val icon = lookup(ctx, entry.grant.localId)
      association(icon, ctx, entry.grant) != null
    }
    if (grants.isEmpty()) return false
    val digest = digest(active, "post-apply")
    return grants.any { approvals.isCurrent(it) && it.grant.proof.icons == digest }
  }

  private fun association(resource: Any, ctx: Any, grant: ConvertedIconGrant): ConvertedIconAssociation? {
    val supplied = supplied(resource)
    if (!supplied.icon.hasNoStoreId) return null
    val current = lookup(ctx, supplied.icon.localId)
    val parent = parent(current, ctx) ?: return null
    val parentSnapshot = snapshot(parent)
    if (supplied.icon != snapshot(current) || !grant.matches(supplied.icon, parentSnapshot)) return null
    // Also check the caller's parent association; stale detail resources cannot gain approval.
    if (snapshot(parent(resource, ctx) ?: return null) != parentSnapshot) return null
    val currentContent = content(current, ctx)
    if (content(resource, ctx) != currentContent) return null
    return ConvertedIconAssociation(supplied, parentSnapshot, currentContent.path)
  }

  private fun digest(file: File, stage: String): ConvertedIconArchive.Digests {
    val started = System.nanoTime()
    try { return ConvertedIconArchive.digest(file) }
    finally { hashTiming(stage, System.nanoTime() - started) }
  }

  private fun iconContext(): Any {
    val inner = profile["inner"].invoke(null)
    val registry = profile["registry"].invoke(inner)
    return requireNotNull(profile["iconContext"].invoke(registry, "icons"))
  }

  private fun lookup(ctx: Any, localId: String): Any {
    val controller = controllerClass.getConstructor(contextClass).newInstance(ctx)
    val manager = profile["controllerManager"].invoke(controller)
    return requireNotNull(profile["lookup"].invoke(manager, localId)) {
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

  private fun snapshot(resource: Any): ConvertedIconResource {
    fun value(name: String) = resourceGetters.getValue(name).invoke(resource) as? String
    return ConvertedIconResource(requireNotNull(value("getLocalId")),
      requireNotNull(value("getHash")).lowercase(), value("getOnlineId"), value("getProductId"), value("getAssemblyId"))
  }

  private fun supplied(resource: Any): ConvertedIconSupplied {
    fun value(name: String) = resourceGetters.getValue(name).invoke(resource)
    val parent = (value("getParentResources") as? List<*>)?.firstOrNull()
    fun related(name: String) = parent?.let { relatedGetters.getValue(name).invoke(it) }
    return ConvertedIconSupplied(snapshot(resource), value("getMetaPath") as? String,
      value("getContentPath") as? String, (value("getResourceStorageType") as? Enum<*>)?.name,
      parent?.let { ConvertedIconParent(related("getLocalId") as? String, related("getResourceCode") as? String,
        related("getMetaPath") as? String, related("getContentPath") as? String,
        (related("getResourceStorageType") as? Enum<*>)?.name) })
  }

  private fun requireWorker() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Converted approval requires a worker thread" }
  }

  private fun appContext(): Context = profile["appContext"].invoke(null) as Context

  private fun type(name: String): Class<*> = Class.forName("com.android.thememanager.$name", false, loader)
  private fun string(target: Any, name: String): String? = target.javaClass.getMethod(name).invoke(target) as? String

  private fun checkHost(context: Context) {
    require(context.packageName == ThemeIconImportRuntime.PACKAGE_NAME)
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    require(info.applicationInfo?.uid == Process.myUid() && profile["parent"].declaringClass.classLoader ===
      resourceClass.classLoader) { "Themes host identity or resolved loader changed" }
  }

  private fun storage(context: Context): AtomicFile = AtomicFile(File(
    context.getDir("keihome_converted_icons", Context.MODE_PRIVATE), "grants-v1.json"))

  // Called only by workers under the publication state's writer lock.
  private fun read(context: Context): Map<String, ConvertedIconGrant> {
    checkHost(context)
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
        require(ConvertedIconArchive.supportsOrigin(origin))
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

/** Values copied from native fields; no host Resource, Context or resolver is retained. */
internal data class ConvertedIconParent(val localId: String?, val code: String?, val metaPath: String?,
  val contentPath: String?, val storageType: String?)

internal data class ConvertedIconSupplied(val icon: ConvertedIconResource, val metaPath: String?,
  val contentPath: String?, val storageType: String?, val parent: ConvertedIconParent?)

internal data class ConvertedIconAssociation(val supplied: ConvertedIconSupplied,
  val parent: ConvertedIconResource, val contentPath: String)

/**
 * The writer alone touches persistence. Readers observe the last complete publication even while
 * AtomicFile is stalled. A receipt token binds preflight results to this registration/generation.
 */
internal class ConvertedIconApprovals {
  internal class Entry(val grant: ConvertedIconGrant, val association: ConvertedIconAssociation? = null,
    val token: Any = Any())
  private data class State(val entries: Map<String, Entry>? = null, val retired: Boolean = false)
  private val writer = Any()
  private val state = AtomicReference(State())

  fun load(read: () -> Map<String, ConvertedIconGrant>) {
    if (state.get().entries != null) return
    synchronized(writer) {
      val before = state.get()
      check(!before.retired) { "Converted approval generation retired" }
      if (before.entries != null) return
      val entries = read().mapValues { Entry(it.value) }
      check(state.compareAndSet(before, State(entries))) { "Converted approval generation retired" }
    }
  }

  fun register(grant: ConvertedIconGrant, association: ConvertedIconAssociation,
    read: () -> Map<String, ConvertedIconGrant>, persist: (Map<String, ConvertedIconGrant>) -> Unit): Boolean {
    synchronized(writer) {
      load(read)
      require(grant.matches(association.supplied.icon, association.parent))
      val grants = state.get().entries.orEmpty().mapValues { it.value.grant } + (grant.localId to grant)
      require(grants.size <= 700) { "Converted approval limit" }
      persist(grants)
      val entry = Entry(grant, association)
      while (true) {
        val before = state.get()
        if (before.retired) return false
        if (state.compareAndSet(before, State(before.entries.orEmpty() + (grant.localId to entry)))) {
          return true
        }
      }
    }
  }

  fun entry(localId: String): Entry? = state.get().entries?.get(localId)
  fun entries(): List<Entry> = state.get().entries?.values?.toList().orEmpty()
  fun isCurrent(candidate: Entry): Boolean = entry(candidate.grant.localId)?.token === candidate.token

  fun publish(entry: Entry, association: ConvertedIconAssociation, preflight: Boolean = false): Boolean {
    require(entry.grant.matches(association.supplied.icon, association.parent))
    return update(entry, association, preflight)
  }

  fun invalidate(entry: Entry) { update(entry, null, preflight = true) }

  private fun update(entry: Entry, association: ConvertedIconAssociation?, preflight: Boolean): Boolean {
    while (true) {
      val before = state.get()
      val entries = before.entries ?: return false
      if (before.retired || entries[entry.grant.localId]?.token !== entry.token) return false
      // A slow startup lookup cannot replace a newer completed preflight. A failed preflight
      // revokes its token so older in-flight association reads cannot restore stale approval.
      if (!preflight && entries[entry.grant.localId]?.association != null) return false
      val updated = Entry(entry.grant, association, if (association == null) Any() else entry.token)
      if (state.compareAndSet(before, State(entries + (entry.grant.localId to updated)))) return true
    }
  }

  // A validated association can conservatively limit scope even without a fresh DRM preflight.
  // This result is deliberately not used by either rights or post-apply validation.
  fun iconsOnly(supplied: ConvertedIconSupplied): Boolean =
    entry(supplied.icon.localId)?.association?.supplied == supplied

  fun retire() { state.set(State(retired = true)) }
}
