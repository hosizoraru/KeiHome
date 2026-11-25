package os.kei.hyper.home.xposed

import android.content.Context
import android.net.Uri
import android.os.Looper
import java.lang.reflect.Modifier

/** Host adapters shared by ordinary apply, PC import and bundlecrossprocess. */
internal class ThemeConvertedGuard(
  private val context: Context,
  private val loader: ClassLoader,
  private val profile: ThemeImportProfile,
) {
  private val resourceContext = Class.forName("$PKG.ResourceContext", false, loader)
  private val resourceType = Class.forName("$PKG.basemodule.resource.model.Resource", false, loader)
  private val storage = ThemeConvertedGrantFile(context)
  /** Fixed-appearance copies that may stand in for a granted icons archive at the active path. */
  @Volatile var appearanceSiblings: (ConvertedIconArchive.Digests) -> Set<ConvertedIconArchive.Digests> = { emptySet() }
  private val content = ThemeConvertedContent(java.io.File(context.cacheDir, "keihome-rights")) { path ->
    // Read only the provider window, independently of a cached regular FD's cursor.
    val descriptor = context.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")
    descriptor?.let(::themeProviderInputStream)
  }
  private val approvals = ThemeConvertedApproval(storage::read, content::inspect)

  fun approve(resource: Any, nativeContext: Any?): Boolean {
    if (Looper.myLooper() === Looper.getMainLooper()) return false
    val supplied = subject(resource, nativeContext) ?: return false
    return approvals.approve(supplied)
  }

  // UI: snapshots only. Native lookup, provider reads and hashing stay on workers.
  fun iconsOnly(resource: Any): Boolean = approvals.iconsOnly(supplied(resource))

  fun validates(validator: Any, path: String): Boolean {
    if (Looper.myLooper() === Looper.getMainLooper()) return false
    val file = java.io.File(path)
    if (path != "/data/system/theme/icons" || file.canonicalPath != path || !file.isFile) return false
    val fields = validator.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
    val codes = fields.singleOrNull { Set::class.java.isAssignableFrom(it.type) } ?: return false
    if (codes.apply { isAccessible = true }.get(validator) != setOf("icons")) return false
    val resource = fields.singleOrNull { it.type == resourceType }?.apply { isAccessible = true }
      ?.get(validator) ?: return false
    val applied = identity(resource)
    if (!applied.hasNoStoreId) return false
    val nativeContext = contextFor("icons") ?: return false
    return storage.read().values.filter {
      (applied.localId == it.localId && applied.hash == it.proof.icons.sha1) ||
        (applied.localId == it.parentLocalId && applied.hash == it.proof.archive.sha1)
    }.any { grant ->
      val native = lookup(nativeContext, grant.localId) ?: return@any false
      val current = subject(native, nativeContext) ?: return@any false
      approvals.approve(current) && content.matchesIcons(path, grant, appearanceSiblings(grant.proof.icons))
    }
  }

  private fun subject(resource: Any, suppliedContext: Any?): ThemeConvertedSubject? {
    val supplied = supplied(resource)
    if (!supplied.icon.hasNoStoreId) return null
    val nativeContext = suppliedContext?.takeIf(resourceContext::isInstance) ?: contextFor("icons") ?: return null
    val current = lookup(nativeContext, supplied.icon.localId) ?: return null
    if (identity(current) != supplied.icon) return null
    fun parentOf(value: Any): Any? = profile["parent"].invoke(null, value,
      resourceContext.getMethod("getNewResourceContext").invoke(nativeContext))
    val parent = parentOf(current) ?: return null
    if (identity(parentOf(resource) ?: return null) != identity(parent)) return null
    val iconContent = path(current, nativeContext) ?: return null
    if (path(resource, nativeContext) != iconContent) return null
    val parentContext = contextFor("theme") ?: return null
    return ThemeConvertedSubject(supplied.icon, identity(parent),
      iconContent, path(parent, parentContext) ?: return null, supplied)
  }

  private fun identity(resource: Any) = ConvertedIconResource(
    string(resource, "getLocalId").orEmpty(), string(resource, "getHash").orEmpty().lowercase(java.util.Locale.ROOT),
    string(resource, "getOnlineId"), string(resource, "getProductId"), string(resource, "getAssemblyId"))

  private fun supplied(resource: Any): ConvertedIconSupplied {
    val related = (resource.javaClass.getMethod("getParentResources").invoke(resource) as? List<*>)?.firstOrNull()
    return ConvertedIconSupplied(identity(resource), string(resource, "getMetaPath"), string(resource, "getContentPath"),
      (resource.javaClass.getMethod("getResourceStorageType").invoke(resource) as? Enum<*>)?.name,
      related?.let { ConvertedIconParent(string(it, "getLocalId"), string(it, "getResourceCode"),
        string(it, "getMetaPath"), string(it, "getContentPath"),
        (it.javaClass.getMethod("getResourceStorageType").invoke(it) as? Enum<*>)?.name) })
  }

  private fun lookup(nativeContext: Any, id: String): Any? {
    val controller = Class.forName("$PKG.controller.ThemeController", false, loader)
      .getConstructor(resourceContext).newInstance(nativeContext)
    return profile["lookup"].invoke(profile["controllerManager"].invoke(controller), id)
  }

  private fun contextFor(code: String): Any? {
    val inner = profile["inner"].invoke(null)
    val manager = profile["registry"].invoke(inner)
    return profile["iconContext"].invoke(manager, code)
  }

  private fun path(resource: Any, nativeContext: Any): String? {
    val resolver = Class.forName("$PKG.model.ResourceResolver", false, loader)
      .getConstructor(resourceType, resourceContext).newInstance(resource, nativeContext)
    return resolver.javaClass.getMethod("getContentPath").invoke(resolver) as? String
  }

  private fun string(resource: Any, method: String) =
    resource.javaClass.getMethod(method).invoke(resource) as? String

  companion object {
    private const val PKG = "com.android.thememanager"
    const val RIGHTS_SUCCESS = 100000
  }
}

/** Reads the existing grants-v1.json contract; never creates a second receipt format. */
internal class ThemeConvertedGrantFile(private val context: Context) {
  fun read(): Map<String, ConvertedIconGrant> {
    require(context.packageName == ThemeIconImportRuntime.PACKAGE_NAME &&
      context.applicationInfo.uid == android.os.Process.myUid())
    val file = android.util.AtomicFile(java.io.File(
      context.getDir("keihome_converted_icons", Context.MODE_PRIVATE), "grants-v1.json"))
    if (!file.baseFile.exists() && !java.io.File(file.baseFile.path + ".bak").exists()) return emptyMap()
    val bytes = file.openRead().use { input ->
      val output = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 1024 * 1024) { "Converted approval storage size limit" }
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    }
    return decode(bytes)
  }

  companion object {
    fun decode(bytes: ByteArray): Map<String, ConvertedIconGrant> {
      require(bytes.size <= 1024 * 1024)
      val root = org.json.JSONObject(String(bytes, Charsets.UTF_8))
      require(root.getInt("version") == 1)
      val rows = root.getJSONArray("grants")
      require(rows.length() <= 700)
      val result = LinkedHashMap<String, ConvertedIconGrant>()
      repeat(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val origin = row.getString("origin")
        require(ConvertedIconArchive.supportsOrigin(origin))
        fun hash(key: String, length: Int) = row.getString(key).also {
          require(it.matches(Regex("[0-9a-f]{$length}")))
        }
        val grant = ConvertedIconGrant(ConvertedIconArchive.Proof(origin,
          ConvertedIconArchive.Digests(hash("archiveSha1", 40), hash("archiveSha256", 64)),
          ConvertedIconArchive.Digests(hash("iconSha1", 40), hash("iconSha256", 64))),
          row.getString("localId"), row.getString("parentLocalId"))
        require(grant.localId.length in 1..128 && grant.parentLocalId.length in 1..128 &&
          result.put(grant.localId, grant) == null)
      }
      return result
    }
  }
}
