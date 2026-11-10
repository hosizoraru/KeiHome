package os.kei.hyper.home.xposed

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.widget.ImageView
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** Rail can bind a prefetched Bitmap without ever issuing an image-loader request. */
internal data class HyperOsSidebarRailBinding(
  val bind: Method,
  val model: Field,
  val packageName: Field,
  val uid: Field,
  val image: Field,
  val holderRoot: Field,
  val alternateIconId: Int,
) {
  /** Returns the package and raw application UID; the caller applies its user policy. */
  fun identity(item: Any): Pair<String, Int>? {
    if (!model.declaringClass.isInstance(item)) return null
    val app = model.get(item) ?: return null
    val pkg = packageName.get(app) as? String ?: return null
    val appUid = uid.getInt(app)
    return if (pkg.isNotBlank() && appUid >= 0) pkg to appUid else null
  }

  fun imageView(holder: Any): ImageView? {
    if (image.declaringClass.isInstance(holder)) return image.get(holder) as? ImageView
    if (!holderRoot.declaringClass.isInstance(holder) || alternateIconId == 0) return null
    return (holderRoot.get(holder) as? View)?.findViewById(alternateIconId)
  }
}

/**
 * Binders authenticate discovery of the shared loader. The host runtime applies
 * its URI policy to all clients; rail supplies the exceptional cached-Bitmap path.
 */
internal data class HyperOsSidebarProfile(
  val requestMethods: List<Method>,
  val bindMethods: List<Method>,
  val cancel: Method?,
  val rail: HyperOsSidebarRailBinding,
  val grid: HyperOsSidebarGridBinding,
) {
  companion object {
    const val PACKAGE = "com.miui.securitycenter"
    private const val DOCK = "com.miui.dock."
    private const val STRING = "java.lang.String"
    private const val IMAGE = "android.widget.ImageView"
    private const val BITMAP = "android.graphics.Bitmap"
    private const val PACKAGE_INFO = "android.content.pm.PackageInfo"

    @OptIn(DexKitExperimentalApi::class)
    fun resolve(context: Context, loader: ClassLoader): HyperOsSidebarProfile {
      check(Looper.myLooper() != Looper.getMainLooper()) { "Sidebar profile must resolve on a worker" }
      // Interned String is a bootstrap lock shared by successive module loaders.
      return synchronized(HyperOsSidebarProfileCache.KEY.intern()) {
        val source = try {
          HyperOsSidebarProfileCache.sourceIdentity(context.applicationInfo.sourceDir)
        } catch (error: Exception) {
          HyperOsSidebarProfileCache.invalidate()
          throw error
        }
        val restored = HyperOsSidebarProfileCache.restore(context, loader, source)
        val profile = restored ?: discover(context, loader, source)
        Log.i("KeiHomeSidebarProfile", "${if (restored == null) "resolved" else "restored"} profile " +
          "process=${Application.getProcessName()} pid=${Process.myPid()} " +
          "requests=${profile.requestMethods.size} cached=${System.getProperty(HyperOsSidebarProfileCache.KEY) != null}")
        profile
      }
    }

    @OptIn(DexKitExperimentalApi::class)
    private fun discover(context: Context, loader: ClassLoader, source: String): HyperOsSidebarProfile {
      return SystemUiMaterialDexKit.open(context.applicationInfo.sourceDir, context.cacheDir).use { dex ->
        dex.setThreadNum(1)
        val seeds = dex.findMethod {
          matcher { returnType = "void"; usingStrings("pkg_icon://", "pkg_icon_xspace://") }
        }
        check(seeds.size <= 128) { "Sidebar binder candidate budget exceeded" }
        val grid = seeds.filter { method ->
          !Modifier.isStatic(method.modifiers) &&
            method.paramTypeNames.singleOrNull()?.startsWith(DOCK + "allapps.") == true &&
            method.invokes.any {
              it.className == method.paramTypeNames.single() && it.returnTypeName == PACKAGE_INFO
            }
        }.singleRole("all-app binder")
        val gridRequests = grid.invokes.filter { it.isImageRequest() && Modifier.isStatic(it.modifiers) }
          .distinctBy { it.descriptor }
        val requestOwner = gridRequests.map { it.className }.distinct().singleRole("request owner")

        // A rail model getter must also be consumed by a named Dock class. This
        // excludes unrelated Security lists that happen to use the same URI scheme.
        val railBind = seeds.filter { method ->
          method.descriptor != grid.descriptor && !Modifier.isStatic(method.modifiers) &&
            method.paramCount == 1 &&
            method.usingStrings.containsAll(listOf("pkg_work_profile://", "?userId=")) &&
            method.invokes.any { it.className == IMAGE && it.name == "setImageBitmap" } &&
            method.invokes.any { it.className == requestOwner && it.isImageRequest() } &&
            method.declaredClass?.methods?.any { getter ->
              getter.paramCount == 0 && getter.returnTypeName !in setOf("void", STRING, BITMAP, "boolean") &&
                getter.usingFields.any {
                  it.usingType == FieldUsingType.Read && it.field.className == method.className &&
                    it.field.typeName == getter.returnTypeName
                } && getter.callers.any { it.className.startsWith(DOCK) }
            } == true
        }.singleRole("rail binder")
        val requests = (gridRequests + railBind.invokes.filter {
          it.className == requestOwner && it.isImageRequest() && Modifier.isStatic(it.modifiers)
        }).distinctBy { it.descriptor }
        check(requests.isNotEmpty() && requests.size <= 8) { "Sidebar request budget exceeded" }
        val nativeOwners = requests.map { request ->
          request.invokes.filter { it.isImageRequest() && !Modifier.isStatic(it.modifiers) }
            .map { it.className }.distinct().singleRole("native image loader")
        }.distinct()
        val nativeOwner = nativeOwners.singleRole("shared native image loader")
        val cancelCandidates = dex.findMethod {
          matcher { declaredClass = requestOwner; returnType = "void"; paramTypes(IMAGE) }
        }.filter { candidate ->
          Modifier.isStatic(candidate.modifiers) && candidate.invokes.any {
            it.className == nativeOwner && it.returnTypeName == "void" &&
              it.paramTypeNames == listOf(IMAGE)
          }
        }
        check(cancelCandidates.size <= 1) { "Ambiguous sidebar cancellation" }

        val reads = railBind.usingFields.filter { it.usingType == FieldUsingType.Read }
          .map { it.field }.distinctBy { it.descriptor }
        val railModel = reads.filter { field ->
          field.className == railBind.className &&
            reads.any { it.className == field.typeName && it.typeName == STRING } &&
            reads.any { it.className == field.typeName && it.typeName == "int" }
        }.singleRole("rail model")
        val pkg = reads.filter { it.className == railModel.typeName && it.typeName == STRING }
          .singleRole("rail package")
        val uid = reads.filter { it.className == railModel.typeName && it.typeName == "int" }
          .singleRole("rail UID")
        val image = reads.filter { it.typeName == IMAGE && it.className != railBind.className }
          .singleRole("rail image")
        val bind = railBind.reflect(loader)
        val holderRoot = generateSequence(bind.parameterTypes.single()) { it.superclass }
          .flatMap { it.declaredFields.asSequence() }
          .filter { it.type == View::class.java && !Modifier.isStatic(it.modifiers) }
          .toList().singleRole("rail holder root").accessible()

        val packageGetter = grid.invokes.filter {
          it.className == grid.paramTypeNames.single() && it.returnTypeName == PACKAGE_INFO &&
            it.paramCount == 0
        }.distinctBy { it.descriptor }.singleRole("grid package getter")
        val imageGetter = grid.invokes.filter {
          it.paramCount == 0 && it.returnTypeName == IMAGE && !Modifier.isStatic(it.modifiers)
        }.distinctBy { it.descriptor }.singleRole("grid image getter")
        val gridImage = imageGetter.usingFields.filter {
          it.usingType == FieldUsingType.Read && it.field.typeName == IMAGE
        }.map { it.field }.distinctBy { it.descriptor }.singleRole("grid image field")
        check(packageGetter.invokes.isEmpty() && imageGetter.invokes.isEmpty()) {
          "Grid identity access must remain a local field read"
        }
        val modelGetter = requireNotNull(railBind.declaredClass).methods.filter { getter ->
          getter.paramCount == 0 && getter.returnTypeName == railModel.typeName &&
            getter.usingFields.any {
              it.usingType == FieldUsingType.Read && it.field.descriptor == railModel.descriptor
            } && getter.callers.any { it.className.startsWith(DOCK) }
        }.singleRole("Dock rail model getter")
        val dockCaller = modelGetter.callers.filter { it.className.startsWith(DOCK) }
          .minBy { it.descriptor }
        val bitmap = railBind.invokes.filter { it.className == IMAGE && it.name == "setImageBitmap" }
          .distinctBy { it.descriptor }.singleRole("rail bitmap setter")
        val railRequests = railBind.invokes.filter { call ->
          requests.any { it.descriptor == call.descriptor }
        }.distinctBy { it.descriptor }
        val methods = linkedMapOf<String, JSONObject>()
        fun record(
          method: MethodData,
          calls: List<MethodData> = emptyList(),
          fields: List<String> = emptyList(),
          strings: List<String> = emptyList(),
        ) {
          // Only persist edges actually proved by this APK's native analysis.
          check(calls.all { call -> method.invokes.any { it.descriptor == call.descriptor } })
          check(fields.all { field -> method.usingFields.any {
            it.usingType == FieldUsingType.Read && it.field.descriptor == field
          } })
          check(method.usingStrings.containsAll(strings))
          methods[method.descriptor] = JSONObject()
            .put("d", method.descriptor).put("m", method.reflect(loader).modifiers)
            .put("c", JSONArray(calls.map { it.descriptor }.distinct()))
            .put("r", JSONArray(fields.distinct())).put("s", JSONArray(strings))
        }
        record(packageGetter)
        record(imageGetter, fields = listOf(gridImage.descriptor))
        record(modelGetter, fields = listOf(railModel.descriptor))
        record(dockCaller, calls = listOf(modelGetter))
        record(bitmap)
        record(grid, calls = listOf(packageGetter, imageGetter) + gridRequests,
          strings = listOf("pkg_icon://", "pkg_icon_xspace://"))
        record(railBind, calls = listOf(bitmap) + railRequests,
          fields = listOf(railModel.descriptor, pkg.descriptor, uid.descriptor, image.descriptor),
          strings = listOf("pkg_icon://", "pkg_icon_xspace://", "pkg_work_profile://", "?userId="))
        for (request in requests) {
          val native = request.invokes.filter {
            it.className == nativeOwner && it.isImageRequest() && !Modifier.isStatic(it.modifiers)
          }.distinctBy { it.descriptor }
          native.forEach { record(it) }
          record(request, calls = native)
        }
        val cancel = cancelCandidates.singleOrNull()
        if (cancel != null) {
          val native = cancel.invokes.filter {
            it.className == nativeOwner && it.returnTypeName == "void" &&
              it.paramTypeNames == listOf(IMAGE) && !Modifier.isStatic(it.modifiers)
          }.distinctBy { it.descriptor }.singleRole("native cancellation")
          record(native)
          record(cancel, calls = listOf(native))
        }
        val fields = listOf(railModel.getFieldInstance(loader), pkg.getFieldInstance(loader),
          uid.getFieldInstance(loader), image.getFieldInstance(loader), holderRoot,
          gridImage.getFieldInstance(loader))
        val proof = JSONObject()
          .put("schema", HyperOsSidebarProfileCache.SCHEMA).put("source", source)
          .put("grid", grid.descriptor).put("rail", railBind.descriptor)
          .put("requests", JSONArray(requests.map { it.descriptor }))
          .put("cancel", cancel?.descriptor ?: JSONObject.NULL)
          .put("packageGetter", packageGetter.descriptor).put("modelGetter", modelGetter.descriptor)
          .put("imageGetter", imageGetter.descriptor).put("gridImage", gridImage.descriptor)
          .put("dockCaller", dockCaller.descriptor).put("bitmap", bitmap.descriptor)
          .put("model", railModel.descriptor).put("package", pkg.descriptor)
          .put("uid", uid.descriptor).put("image", image.descriptor)
          .put("root", DexField(holderRoot).toString())
          .put("methods", JSONArray(methods.values.toList()))
          .put("fields", JSONArray(fields.map {
            JSONObject().put("d", DexField(it).toString()).put("m", it.modifiers)
          }))
        check(source == HyperOsSidebarProfileCache.sourceIdentity(context.applicationInfo.sourceDir)) {
          "Sidebar APK changed during discovery"
        }
        // Both discovery and restoration pass through exactly the same contracts.
        HyperOsSidebarProfileCache.validate(proof, context, loader).also {
          HyperOsSidebarProfileCache.publish(proof)
        }
      }
    }

    private fun MethodData.isImageRequest(): Boolean =
      returnTypeName == "void" && paramTypeNames.size in 2..6 &&
        paramTypeNames.take(2) == listOf(STRING, IMAGE)

    private fun MethodData.reflect(loader: ClassLoader): Method =
      getMethodInstance(loader).apply { isAccessible = true }

    private fun Field.accessible(): Field = apply { isAccessible = true }

    private fun <T> List<T>.singleRole(role: String): T {
      check(size == 1) { "Sidebar $role: expected one structural match, found $size" }
      return single()
    }
  }
}

/** Only a bounded String enters Properties; no module-owned object crosses generations. */
internal object HyperOsSidebarProfileCache {
  const val KEY = "keihome.sidebar.profile.v1"
  const val MAX_BYTES = 16 * 1024
  // Bump whenever the discovery proof or restoration contracts change.
  const val SCHEMA = 3

  fun sourceIdentity(path: String): String {
    val file = File(path).canonicalFile
    val length = file.length()
    check(length in 1..512L * 1024 * 1024) { "Sidebar APK size outside discovery budget" }
    val entries = ZipFile(file).use { zip ->
      val candidates = zip.entries().asSequence().filter {
        it.name == "AndroidManifest.xml" ||
          (!it.name.contains('/') && it.name.startsWith("classes") && it.name.endsWith(".dex"))
      }.take(130).toList()
      check(candidates.size in 2..129)
      candidates.map {
        check(!it.isDirectory && it.crc >= 0 && it.size >= 0)
        Triple(it.name, it.crc, it.size)
      }.toList().sortedBy { it.first }
    }
    check(entries.size in 2..129 && entries.map { it.first }.distinct().size == entries.size)
    check(entries.count { it.first == "AndroidManifest.xml" } == 1)
    // Updated OEM APKs can retain every published version field. Hash actual
    // bytes on this worker, once per resolution, never on the image hot path.
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(64 * 1024).use { input ->
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        check(total <= length) { "Sidebar APK grew during fingerprinting" }
        digest.update(buffer, 0, count)
      }
      check(total == length && file.length() == length) { "Sidebar APK changed during fingerprinting" }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    return JSONObject().put("path", file.path).put("sha256", hash).toString()
  }

  fun invalidate() {
    synchronized(KEY.intern()) { System.getProperties().remove(KEY) }
  }

  fun restore(
    context: Context,
    loader: ClassLoader,
    source: String? = null,
  ): HyperOsSidebarProfile? = synchronized(KEY.intern()) {
    val properties = System.getProperties()
    val value = properties[KEY] ?: return@synchronized null
    try {
      check(value is String && value.length <= MAX_BYTES &&
        value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
      val proof = JSONObject(value)
      check(proof.getInt("schema") == SCHEMA &&
        proof.getString("source") == (source ?: sourceIdentity(context.applicationInfo.sourceDir)))
      validate(proof, context, loader)
    } catch (_: Exception) {
      // Source/schema/contract failures retire only the property owned here.
      properties.remove(KEY, value)
      null
    } catch (_: LinkageError) {
      properties.remove(KEY, value)
      null
    }
  }

  fun publish(proof: JSONObject) {
    val value = proof.toString()
    synchronized(KEY.intern()) {
      if (value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) System.setProperty(KEY, value)
      else invalidate()
    }
  }

  private data class Node(
    val method: Method,
    val calls: Set<String>,
    val reads: Set<String>,
    val strings: Set<String>,
  )

  private fun JSONArray.strings(limit: Int): Set<String> {
    check(length() <= limit)
    val values = (0 until length()).map { getString(it) }
    check(values.distinct().size == values.size)
    return values.toSet()
  }

  fun validate(proof: JSONObject, context: Context, loader: ClassLoader): HyperOsSidebarProfile {
    check(proof.getInt("schema") == SCHEMA)
    val methodData = proof.getJSONArray("methods")
    val fieldData = proof.getJSONArray("fields")
    check(methodData.length() in 1..40 && fieldData.length() == 6)
    val nodes = (0 until methodData.length()).map { index ->
      val data = methodData.getJSONObject(index)
      val descriptor = data.getString("d")
      val method = DexMethod(descriptor).getMethodInstance(loader)
      check(DexMethod(method).toString() == descriptor && method.modifiers == data.getInt("m"))
      descriptor to Node(method.apply { isAccessible = true },
        data.getJSONArray("c").strings(16), data.getJSONArray("r").strings(5),
        data.getJSONArray("s").strings(4))
    }
    check(nodes.map { it.first }.distinct().size == nodes.size)
    val graph = nodes.toMap()
    val fields = (0 until fieldData.length()).map { index ->
      val data = fieldData.getJSONObject(index)
      val descriptor = data.getString("d")
      val field = DexField(descriptor).getFieldInstance(loader)
      check(DexField(field).toString() == descriptor && field.modifiers == data.getInt("m"))
      check(!Modifier.isStatic(field.modifiers))
      descriptor to field.apply { isAccessible = true }
    }
    check(fields.map { it.first }.distinct().size == fields.size)
    val fieldMap = fields.toMap()
    check(graph.values.all { node ->
      node.calls.all { it in graph } && node.reads.all { it in fieldMap }
    })
    fun id(role: String) = proof.getString(role)
    fun node(role: String) = requireNotNull(graph[id(role)])
    fun field(role: String) = requireNotNull(fieldMap[id(role)])
    fun concrete(method: Method, static: Boolean) {
      check(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers) == static)
      check(!Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers))
    }
    fun imageRequest(method: Method, static: Boolean) {
      concrete(method, static)
      check(method.returnType == Void.TYPE && method.parameterTypes.size in 2..6 &&
        method.parameterTypes.take(2) == listOf(String::class.java, ImageView::class.java))
    }
    val grid = node("grid")
    val rail = node("rail")
    check(grid.method != rail.method)
    for (binder in listOf(grid, rail)) {
      concrete(binder.method, false)
      check(binder.method.returnType == Void.TYPE && binder.method.parameterTypes.size == 1)
    }
    check(grid.method.parameterTypes.single().name.startsWith("com.miui.dock.allapps."))
    check(grid.strings == setOf("pkg_icon://", "pkg_icon_xspace://"))
    check(rail.strings == setOf("pkg_icon://", "pkg_icon_xspace://", "pkg_work_profile://", "?userId="))
    val packageGetter = node("packageGetter")
    concrete(packageGetter.method, false)
    check(packageGetter.method.declaringClass == grid.method.parameterTypes.single() &&
      packageGetter.method.parameterTypes.isEmpty() &&
      packageGetter.method.returnType.name == "android.content.pm.PackageInfo")
    val imageGetter = node("imageGetter")
    val gridImage = field("gridImage")
    // A concrete holder can inherit this field getter from an abstract base.
    check(!Modifier.isStatic(imageGetter.method.modifiers) &&
      !Modifier.isAbstract(imageGetter.method.modifiers))
    check(imageGetter.method.parameterTypes.isEmpty() &&
      imageGetter.method.returnType == ImageView::class.java &&
      imageGetter.method.declaringClass.isAssignableFrom(grid.method.declaringClass))
    check(gridImage.type == ImageView::class.java &&
      gridImage.declaringClass.isAssignableFrom(grid.method.declaringClass) &&
      imageGetter.reads == setOf(id("gridImage")) && imageGetter.calls.isEmpty() &&
      imageGetter.strings.isEmpty())
    val model = field("model")
    val pkg = field("package")
    val uid = field("uid")
    val image = field("image")
    val root = field("root")
    check(model.declaringClass == rail.method.declaringClass &&
      pkg.declaringClass == model.type && uid.declaringClass == model.type)
    check(pkg.type == String::class.java && uid.type == Int::class.javaPrimitiveType)
    check(image.type == ImageView::class.java && root.type == View::class.java)
    val holder = rail.method.parameterTypes.single()
    check(root.declaringClass.isAssignableFrom(holder) &&
      holder.isAssignableFrom(image.declaringClass))
    val modelGetter = node("modelGetter")
    concrete(modelGetter.method, false)
    check(modelGetter.method.declaringClass == rail.method.declaringClass &&
      modelGetter.method.parameterTypes.isEmpty() && modelGetter.method.returnType == model.type)
    check(modelGetter.reads == setOf(id("model")) && modelGetter.calls.isEmpty())
    val dockCaller = node("dockCaller")
    check(dockCaller.method.declaringClass.name.startsWith("com.miui.dock.") &&
      dockCaller.calls == setOf(id("modelGetter")))
    val bitmap = node("bitmap").method
    check(bitmap.declaringClass == ImageView::class.java && bitmap.name == "setImageBitmap" &&
      bitmap.parameterTypes.toList() == listOf(android.graphics.Bitmap::class.java) &&
      bitmap.returnType == Void.TYPE)
    concrete(bitmap, false)
    val requestIds = proof.getJSONArray("requests").strings(8)
    check(requestIds.isNotEmpty())
    val requests = requestIds.map { requireNotNull(graph[it]) }
    requests.forEach { imageRequest(it.method, true) }
    check(requests.map { it.method.declaringClass }.distinct().size == 1)
    val gridRequests = grid.calls.intersect(requestIds)
    val railRequests = rail.calls.intersect(requestIds)
    check(gridRequests.isNotEmpty() && railRequests.isNotEmpty() &&
      gridRequests + railRequests == requestIds)
    check(grid.calls == gridRequests + setOf(id("packageGetter"), id("imageGetter")) &&
      rail.calls == railRequests + id("bitmap"))
    check(rail.reads == setOf(id("model"), id("package"), id("uid"), id("image")))
    val nativeIds = requests.flatMap { it.calls }.toSet()
    check(requests.all { it.calls.isNotEmpty() } && nativeIds.size in 1..8)
    val native = nativeIds.map { requireNotNull(graph[it]).method }
    native.forEach { imageRequest(it, false) }
    val nativeOwner = native.map { it.declaringClass }.distinct().single()
    val cancelId = if (proof.isNull("cancel")) null else id("cancel")
    val cancel = cancelId?.let { requireNotNull(graph[it]) }
    val nativeCancelIds = cancel?.calls.orEmpty()
    if (cancel != null) {
      concrete(cancel.method, true)
      check(cancel.method.declaringClass == requests.first().method.declaringClass &&
        cancel.method.returnType == Void.TYPE &&
        cancel.method.parameterTypes.toList() == listOf(ImageView::class.java))
      check(nativeCancelIds.size == 1)
      val nativeCancel = requireNotNull(graph[nativeCancelIds.single()]).method
      concrete(nativeCancel, false)
      check(nativeCancel.declaringClass == nativeOwner && nativeCancel.returnType == Void.TYPE &&
        nativeCancel.parameterTypes.toList() == listOf(ImageView::class.java))
    }
    val roleIds = setOf(id("grid"), id("rail"), id("packageGetter"), id("modelGetter"), id("imageGetter"),
      id("dockCaller"), id("bitmap"))
    check(roleIds.size == 7)
    check(graph.keys == roleIds + requestIds + nativeIds + listOfNotNull(cancelId) + nativeCancelIds)
    check(fieldMap.keys == setOf(id("model"), id("package"), id("uid"), id("image"), id("root"), id("gridImage")))
    val leaves = setOf(id("packageGetter"), id("bitmap")) + nativeIds + nativeCancelIds
    check(leaves.all { requireNotNull(graph[it]).let { node ->
      node.calls.isEmpty() && node.reads.isEmpty() && node.strings.isEmpty()
    } })
    check((requests + listOfNotNull(cancel, dockCaller)).all {
      it.reads.isEmpty() && it.strings.isEmpty()
    })
    check(grid.reads.isEmpty() && modelGetter.strings.isEmpty())
    return HyperOsSidebarProfile(
      requestMethods = requests.map { it.method },
      bindMethods = listOf(grid.method, rail.method),
      cancel = cancel?.method,
      rail = HyperOsSidebarRailBinding(rail.method, model, pkg, uid, image, root,
        context.resources.getIdentifier("icon_view", "id", HyperOsSidebarProfile.PACKAGE)),
      grid = HyperOsSidebarGridBinding(grid.method, packageGetter.method, imageGetter.method),
    )
  }
}

internal data class HyperOsSidebarGridBinding(
  val bind: Method,
  val packageInfo: Method,
  val image: Method,
) {
  fun identity(model: Any): Pair<String, Int>? = runCatching {
    val info = packageInfo.invoke(model) as? PackageInfo ?: return@runCatching null
    val name = info.packageName?.takeIf { it.isNotBlank() } ?: return@runCatching null
    val uid = info.applicationInfo?.uid?.takeIf { it >= 0 } ?: return@runCatching null
    name to uid
  }.getOrNull()

  fun imageView(holder: Any): ImageView? = runCatching {
    image.invoke(holder) as? ImageView
  }.getOrNull()
}
