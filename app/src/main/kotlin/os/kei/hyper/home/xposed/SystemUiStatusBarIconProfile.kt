package os.kei.hyper.home.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.util.AtomicFile
import android.util.SparseArray
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
import org.luckypray.dexkit.util.DexSignUtil

internal data class SystemUiStatusBarIconGraph(
  val consumer: MaterialMethod,
  val drawable: MaterialMethod,
  val update: MaterialMethod,
  val entryPack: MaterialField,
  val cached: List<MaterialField>,
  val descriptorIcon: MaterialField,
  val viewDescriptor: MaterialField,
)

/** Roles, signatures and linked reads/writes, never an OEM version or member-name gate. */
internal object SystemUiStatusBarIconContract {
  const val STATUS = "com.android.internal.statusbar.StatusBarIcon"
  const val BITMAP = "Lcom/miui/systemui/graphics/AppIconsManager;->getAppIconBitmap(ILjava/lang/String;)Landroid/graphics/Bitmap;"
  const val CREATE = "Landroid/graphics/drawable/Icon;->createWithBitmap(Landroid/graphics/Bitmap;)Landroid/graphics/drawable/Icon;"
  const val SMALL = "Landroid/app/Notification;->getSmallIcon()Landroid/graphics/drawable/Icon;"
  const val LOAD = "Landroid/graphics/drawable/Icon;->loadDrawableAsUser(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;"
  private val cacheCalls = setOf(
    "Landroid/util/SparseArray;->get(I)Ljava/lang/Object;",
    "Landroid/util/SparseArray;->put(ILjava/lang/Object;)V",
    "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;",
    "Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;",
    "Landroid/graphics/Bitmap;->isRecycled()Z",
    "Ljava/util/concurrent/ConcurrentHashMap;-><init>()V",
    "Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V",
    "Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;",
    "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
    "Ljava/lang/StringBuilder;->toString()Ljava/lang/String;",
  )

  fun proveCacheReader(calls: Set<String>, logger: String, loggingCalls: Set<String>) {
    check(calls.containsAll(setOf(
      "Landroid/util/SparseArray;->get(I)Ljava/lang/Object;",
      "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;",
      "Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;",
    )) && calls.all { it in cacheCalls || it == logger })
    check(loggingCalls.all {
      it == "Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I" ||
        it == "Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I"
    })
  }

  fun prove(g: SystemUiStatusBarIconGraph) {
    fun MaterialMethod.instance(result: String, parameters: List<String>) =
      this.result == result && this.parameters == parameters && !Modifier.isStatic(modifiers) &&
        !Modifier.isAbstract(modifiers) && !Modifier.isNative(modifiers)
    val c = g.consumer
    check(c.parameters.size == 2 && c.instance(STATUS, listOf(g.entryPack.owner, "boolean")) &&
      c.calls.containsAll(setOf(BITMAP, CREATE, SMALL)))
    check(g.entryPack.id in c.reads && !Modifier.isStatic(g.entryPack.modifiers))
    check(g.cached.size in 1..4 && g.cached.distinctBy { it.id }.size == g.cached.size)
    check(g.cached.all { it.owner == g.entryPack.type && it.type == STATUS &&
      !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) &&
      it.id in c.reads && it.id in c.writes })
    check(g.drawable.instance(Drawable::class.java.name, listOf(STATUS)) &&
      LOAD in g.drawable.calls && g.descriptorIcon.id in g.drawable.reads)
    check(g.descriptorIcon.owner == STATUS && g.descriptorIcon.type == Icon::class.java.name &&
      !Modifier.isStatic(g.descriptorIcon.modifiers))
    check(g.update.owner == g.drawable.owner && g.update.instance("boolean", listOf("boolean")) &&
      g.drawable.id in g.update.calls && g.viewDescriptor.id in g.update.reads &&
      g.update.calls.any { it.endsWith("->setImageDrawable(Landroid/graphics/drawable/Drawable;)V") })
    check(g.viewDescriptor.owner == g.drawable.owner && g.viewDescriptor.type == STATUS &&
      !Modifier.isStatic(g.viewDescriptor.modifiers))
  }
}

/** Startup-worker handles only. The two descriptor caches are distinguished by live identity. */
internal data class SystemUiStatusBarIconProfile(
  val consumer: Method,
  val drawable: Method,
  val update: Method,
  val entryPack: Field,
  val cached: List<Field>,
  val descriptorIcon: Field,
  val viewDescriptor: Field,
  val bitmap: Method,
  val nativeManager: Method? = null,
  val nativeCaches: List<Field> = emptyList(),
  val cacheReader: Method? = null,
  val descriptorPackage: Field? = null,
  val descriptorUser: Field? = null,
  val bootstrap: SystemUiStatusBarEntryProfile? = null,
) {
  companion object {
    private const val SCHEMA = 5
    private const val MAX_BYTES = 16 * 1024

    fun resolve(context: Context, loader: ClassLoader): SystemUiStatusBarIconProfile {
      val application = context.applicationContext ?: context
      check(application.applicationInfo.packageName == "com.android.systemui")
      val producer = "com.miui.systemui.graphics.AppIconsManager"
      check(Class.forName(producer, false, loader).classLoader === loader) {
        "Status-bar app-icon producer/supplied loader mismatch"
      }
      val apk = application.packageManager.getApplicationInfo("com.android.systemui", 0).sourceDir
      return resolve(loader, apk, context.codeCacheDir)
    }

    fun resolve(
      loader: ClassLoader, apkPath: String, directory: File?,
      fresh: (() -> SystemUiStatusBarIconProfile)? = null,
    ): SystemUiStatusBarIconProfile {
      val source = sourceFingerprint(apkPath)
      val file = directory?.let { AtomicFile(File(it, "keihome-statusbar-icon-profile.json")) }
      if (file != null) {
        try {
          check(file.baseFile.length() in 1..MAX_BYTES.toLong())
          val bytes = file.openRead().use { it.readNBytes(MAX_BYTES + 1) }
          check(bytes.size in 1..MAX_BYTES)
          val j = JSONObject(String(bytes, Charsets.UTF_8))
          check(j.getInt("schema") == SCHEMA && j.getString("source") == source)
          return restore(j, loader).also(::validate)
        } catch (failure: Throwable) { recoverableSystemUiFailure(failure) }
      }
      val p = (fresh?.invoke() ?: discover(loader, apkPath, directory)).also(::validate)
      if (file != null) {
        try {
          val j = JSONObject().put("schema", SCHEMA).put("source", source)
          fun method(key: String, m: Method) { j.put(key, DexSignUtil.getDescriptor(m)) }
          fun field(key: String, f: Field) { j.put(key, DexSignUtil.getDescriptor(f)) }
          method("consumer", p.consumer); method("drawable", p.drawable); method("update", p.update)
          field("entryPack", p.entryPack); field("icon", p.descriptorIcon); field("view", p.viewDescriptor)
          j.put("cached", JSONArray(p.cached.map { DexSignUtil.getDescriptor(it) }))
          method("nativeManager", requireNotNull(p.nativeManager))
          method("cacheReader", requireNotNull(p.cacheReader))
          j.put("nativeCaches", JSONArray(p.nativeCaches.map { DexSignUtil.getDescriptor(it) }))
          p.bootstrap?.let { b ->
            fun fields(values: List<Field>) = JSONArray(values.map { DexSignUtil.getDescriptor(it) })
            j.put("bootstrap", JSONObject()
              .put("roots", fields(b.registryRoots)).put("maps", fields(b.registryMaps))
              .put("rows", JSONArray(b.rowEntries.map { fields(it) }))
              .put("entryMaps", JSONArray(b.entryMaps.map { fields(it) }))
              .put("viewNotification", DexSignUtil.getDescriptor(b.viewNotification))
              .put("entryNotification", DexSignUtil.getDescriptor(b.entryNotification))
              .put("importantConversation", DexSignUtil.getDescriptor(b.importantConversation))
              .put("stores", JSONArray(b.stores.map { fields(it.fields()) }))
              .put("views", fields(b.packViews)).put("updateEntry", DexSignUtil.getDescriptor(b.updateEntry)))
          }
          val bytes = j.toString().toByteArray(Charsets.UTF_8)
          check(bytes.size <= MAX_BYTES)
          val output = file.startWrite()
          try { output.write(bytes); file.finishWrite(output) }
          catch (failure: Throwable) { file.failWrite(output); throw failure }
        } catch (failure: Throwable) { recoverableSystemUiFailure(failure) }
      }
      return p
    }

    internal fun sourceFingerprint(path: String): String {
      val digest = MessageDigest.getInstance("SHA-256")
      digest.update(File(path).canonicalPath.toByteArray(Charsets.UTF_8))
      ZipFile(path).use { zip ->
        zip.entries().asSequence().filter {
          it.name == "AndroidManifest.xml" || Regex("classes(?:[0-9]+)?\\.dex").matches(it.name)
        }.sortedBy { it.name }.forEach {
          digest.update("\n${it.name}:${it.size}:${it.crc}".toByteArray(Charsets.UTF_8))
        }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun restore(j: JSONObject, loader: ClassLoader): SystemUiStatusBarIconProfile {
      fun type(descriptor: String): Class<*> = when (descriptor) {
        "Z" -> Boolean::class.javaPrimitiveType!!
        "I" -> Int::class.javaPrimitiveType!!
        else -> Class.forName(descriptor.removePrefix("L").removeSuffix(";").replace('/', '.'), false, loader)
      }
      fun field(id: String): Field = type(id.substringBefore("->"))
        .getDeclaredField(id.substringAfter("->").substringBefore(':')).also {
          check(DexSignUtil.getDescriptor(it) == id); it.isAccessible = true
        }
      fun method(id: String): Method {
        val owner = type(id.substringBefore("->"))
        return owner.declaredMethods.single { DexSignUtil.getDescriptor(it) == id }
          .also { it.isAccessible = true }
      }
      val rows = j.getJSONArray("cached")
      check(rows.length() in 1..4)
      val caches = j.getJSONArray("nativeCaches")
      check(caches.length() in 1..4)
      return SystemUiStatusBarIconProfile(
        method(j.getString("consumer")), method(j.getString("drawable")), method(j.getString("update")),
        field(j.getString("entryPack")), (0 until rows.length()).map { field(rows.getString(it)) },
        field(j.getString("icon")), field(j.getString("view")), bitmapGetter(),
        method(j.getString("nativeManager")), (0 until caches.length()).map { field(caches.getString(it)) },
        method(j.getString("cacheReader")),
        Class.forName(SystemUiStatusBarIconContract.STATUS, false, loader).getDeclaredField("pkg")
          .apply { isAccessible = true },
        Class.forName(SystemUiStatusBarIconContract.STATUS, false, loader).getDeclaredField("user")
          .apply { isAccessible = true },
        j.optJSONObject("bootstrap")?.let { b ->
          fun fields(key: String): List<Field> {
            val values = b.getJSONArray(key)
            check(values.length() <= 16)
            return (0 until values.length()).map { field(values.getString(it)) }
          }
          val rows = b.getJSONArray("rows")
          check(rows.length() in 0..8)
          fun paths(key: String, limit: Int): List<List<Field>> {
            val values = b.getJSONArray(key)
            check(values.length() <= limit)
            return (0 until values.length()).map { i ->
              val path = values.getJSONArray(i)
              check(path.length() in 1..5)
              (0 until path.length()).map { field(path.getString(it)) }
            }
          }
          SystemUiStatusBarEntryProfile(fields("roots"), fields("maps"),
            (0 until rows.length()).map { i ->
              val path = rows.getJSONArray(i)
              check(path.length() in 1..3)
              (0 until path.length()).map { field(path.getString(it)) }
            }, fields("views"), method(b.getString("updateEntry")),
            field(b.getString("viewNotification")), field(b.getString("entryNotification")),
            method(b.getString("importantConversation")),
            paths("entryMaps", 4), paths("stores", 4).map {
              check(it.size == 5)
              SystemUiStatusBarStoreProfile(it[0], it[1], it[2], it[3], it[4])
            })
        },
      )
    }

    private fun bitmapGetter(): Method = Icon::class.java.getDeclaredMethod("getBitmap")
      .also { check(it.returnType == Bitmap::class.java); it.isAccessible = true }

    private fun validate(p: SystemUiStatusBarIconProfile) {
      val status = p.descriptorIcon.declaringClass
      check(status.name == SystemUiStatusBarIconContract.STATUS)
      check(p.consumer.returnType === status && p.consumer.parameterTypes.contentEquals(
        arrayOf(p.entryPack.declaringClass, Boolean::class.javaPrimitiveType)))
      check(p.drawable.parameterTypes.contentEquals(arrayOf(status)) &&
        p.drawable.returnType === Drawable::class.java &&
        ImageView::class.java.isAssignableFrom(p.drawable.declaringClass))
      check(p.update.declaringClass === p.drawable.declaringClass &&
        p.update.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
        p.update.returnType == Boolean::class.javaPrimitiveType)
      check(p.descriptorIcon.type === Icon::class.java && p.viewDescriptor.type === status &&
        p.viewDescriptor.declaringClass === p.drawable.declaringClass)
      check(p.cached.size in 1..4 && p.cached.distinct().size == p.cached.size &&
        p.cached.all { it.declaringClass === p.entryPack.type && it.type === status &&
          !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) })
      check(listOf(p.consumer, p.drawable, p.update).all {
        !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
          !Modifier.isNative(it.modifiers)
      })
      check(listOf(p.entryPack, p.descriptorIcon, p.viewDescriptor).none { Modifier.isStatic(it.modifiers) })
      val lookup = requireNotNull(p.nativeManager)
      check(Modifier.isStatic(lookup.modifiers) && lookup.returnType === Any::class.java &&
        lookup.parameterTypes.contentEquals(arrayOf(Class::class.java)))
      check(p.nativeCaches.size in 1..4 && p.nativeCaches.distinct().size == p.nativeCaches.size &&
        p.nativeCaches.all { it.type === SparseArray::class.java && !Modifier.isStatic(it.modifiers) &&
          it.declaringClass.name == "com.miui.systemui.graphics.AppIconsManager" })
      val reader = requireNotNull(p.cacheReader)
      check(reader.declaringClass === p.nativeCaches.first().declaringClass &&
        !Modifier.isStatic(reader.modifiers) && reader.returnType === Bitmap::class.java &&
        reader.parameterTypes.contentEquals(arrayOf(String::class.java, Int::class.javaPrimitiveType,
          Boolean::class.javaPrimitiveType)))
      val pkg = requireNotNull(p.descriptorPackage)
      val user = requireNotNull(p.descriptorUser)
      check(pkg.declaringClass === status && pkg.type === String::class.java &&
        user.declaringClass === status && user.type === UserHandle::class.java)
      p.bootstrap?.validate(p)
    }

    @OptIn(DexKitExperimentalApi::class)
    private fun discover(loader: ClassLoader, path: String, directory: File?): SystemUiStatusBarIconProfile =
      SystemUiMaterialDexKit.open(path, directory).use { dex ->
        dex.setThreadNum(1)
        val contract = SystemUiStatusBarIconContract
        val consumers = dex.findMethod { matcher { returnType = contract.STATUS } }
        check(consumers.size <= 64)
        val consumer = consumers.filter { c ->
          c.paramTypeNames.size == 2 && c.paramTypeNames.last() == "boolean" &&
            c.invokes.map { it.descriptor }.containsAll(listOf(contract.BITMAP, contract.CREATE, contract.SMALL))
        }.singleOrNull() ?: error("Status-bar app-icon descriptor role absent or ambiguous")
        val loaders = dex.findMethod {
          matcher { returnType = Drawable::class.java.name; paramTypes(contract.STATUS) }
        }
        check(loaders.size <= 64)
        val drawable = loaders.filter { c -> c.invokes.any { it.descriptor == contract.LOAD } }
          .singleOrNull() ?: error("Status-bar user drawable role absent or ambiguous")
        val updates = dex.findMethod {
          matcher { declaredClass = drawable.className; returnType = "boolean"; paramTypes("boolean") }
        }
        check(updates.size <= 32)
        val update = updates.filter { c -> c.invokes.any { it.descriptor == drawable.descriptor } &&
          c.invokes.any { it.descriptor.endsWith("->setImageDrawable(Landroid/graphics/drawable/Drawable;)V") }
        }.singleOrNull() ?: error("Status-bar native drawable refresh role absent or ambiguous")
        val fields = LinkedHashMap<String, MaterialField>()
        fun snapshot(m: MethodData): MaterialMethod {
          val uses = m.usingFields
          uses.forEach { use -> use.field.let {
            fields[it.descriptor] = MaterialField(it.descriptor, it.className, it.typeName, it.modifiers)
          } }
          return MaterialMethod(m.descriptor, m.className, m.name, m.returnTypeName, m.paramTypeNames,
            m.modifiers, m.invokes.map { it.descriptor }.toSet(),
            uses.filter { it.usingType == FieldUsingType.Read }.map { it.field.descriptor }.toSet(),
            uses.filter { it.usingType == FieldUsingType.Write }.map { it.field.descriptor }.toSet())
        }
        val c = snapshot(consumer); val d = snapshot(drawable); val u = snapshot(update)
        val cached = (c.reads intersect c.writes).map { fields.getValue(it) }
          .filter { it.type == contract.STATUS && !Modifier.isStatic(it.modifiers) }
        val pack = c.reads.map { fields.getValue(it) }.single {
          it.owner == c.parameters.first() && cached.isNotEmpty() && cached.all { f -> f.owner == it.type }
        }
        val icon = d.reads.map { fields.getValue(it) }.single {
          it.owner == contract.STATUS && it.type == Icon::class.java.name
        }
        val view = u.reads.map { fields.getValue(it) }.single {
          it.owner == d.owner && it.type == contract.STATUS
        }
        contract.prove(SystemUiStatusBarIconGraph(c, d, u, pack, cached, icon, view))
        val nativeLookup = consumer.invokes.distinctBy { it.descriptor }.single {
          Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(Class::class.java.name) &&
            it.returnTypeName == Any::class.java.name
        }
        val nativeReaders = dex.findMethod {
          matcher { declaredClass = "com.miui.systemui.graphics.AppIconsManager"
            returnType = Bitmap::class.java.name; paramTypes("java.lang.String", "int", "boolean") }
        }
        check(nativeReaders.size <= 16)
        val nativeReader = nativeReaders.single { m ->
          m.invokes.map { it.descriptor }.containsAll(listOf(
            "Landroid/util/SparseArray;->get(I)Ljava/lang/Object;",
            "Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;",
            "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;"))
        }
        val logger = nativeReader.invokes.distinctBy { it.descriptor }.single {
          it.className == nativeReader.className && Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(String::class.java.name) && it.returnTypeName == "void"
        }
        contract.proveCacheReader(nativeReader.invokes.map { it.descriptor }.toSet(),
          logger.descriptor, logger.invokes.map { it.descriptor }.toSet())
        val nativeFields = nativeReader.usingFields.filter { it.usingType == FieldUsingType.Read }
          .map { it.field }.distinctBy { it.descriptor }.filter {
            it.className == "com.miui.systemui.graphics.AppIconsManager" &&
              it.typeName == SparseArray::class.java.name && !Modifier.isStatic(it.modifiers)
          }
        check(nativeFields.size in 1..4)
        fun reflected(f: MaterialField): Field =
          Class.forName(f.owner, false, loader).getDeclaredField(f.id.substringAfter("->").substringBefore(':'))
            .also { check(DexSignUtil.getDescriptor(it) == f.id); it.isAccessible = true }
        val resolved = SystemUiStatusBarIconProfile(
          consumer.getMethodInstance(loader).apply { isAccessible = true },
          drawable.getMethodInstance(loader).apply { isAccessible = true },
          update.getMethodInstance(loader).apply { isAccessible = true },
          reflected(pack), cached.map(::reflected), reflected(icon), reflected(view), bitmapGetter(),
          nativeLookup.getMethodInstance(loader).apply { isAccessible = true },
          nativeFields.map { it.getFieldInstance(loader).apply { isAccessible = true } },
          nativeReader.getMethodInstance(loader).apply { isAccessible = true },
          Class.forName(contract.STATUS, false, loader).getDeclaredField("pkg").apply { isAccessible = true },
          Class.forName(contract.STATUS, false, loader).getDeclaredField("user").apply { isAccessible = true },
        )
        resolved.copy(bootstrap = discoverEntries(dex, loader, nativeLookup, resolved))
      }

    @OptIn(DexKitExperimentalApi::class)
    private fun discoverEntries(
      dex: org.luckypray.dexkit.DexKitBridge, loader: ClassLoader,
      registry: MethodData, p: SystemUiStatusBarIconProfile,
    ): SystemUiStatusBarEntryProfile? = try {
      fun reads(m: MethodData) = m.usingFields.filter { it.usingType == FieldUsingType.Read }
        .map { it.field }.distinctBy { it.descriptor }
      fun handle(f: org.luckypray.dexkit.result.FieldData) = f.getFieldInstance(loader)
        .apply { isAccessible = true }
      val registryClass = p.nativeManager!!.declaringClass
      val roots = reads(registry).map(::handle).filter { Modifier.isStatic(it.modifiers) &&
        (it.type === registryClass || Map::class.java.isAssignableFrom(it.type)) }
      check(roots.size <= 2)
      val maps = if (roots.any { it.type === registryClass }) {
        registryClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) &&
          Map::class.java.isAssignableFrom(it.type) }.onEach { it.isAccessible = true }
      } else emptyList()
      check(maps.size <= 8)
      val entry = p.consumer.parameterTypes.first()
      val getters = dex.findMethod { matcher { returnType = entry.name; paramCount = 0 } }
      check(getters.size <= 128)
      val paths = ArrayList<List<Field>>()
      for (getter in getters) {
        val row = Class.forName(getter.className, false, loader)
        if (!android.view.View::class.java.isAssignableFrom(row)) continue
        // Inspect getter backing fields without invoking getters, including lazy ones.
        val fields = LinkedHashMap<String, Field>()
        val seen = HashSet<String>()
        fun gather(m: MethodData, depth: Int) {
          if (depth > 3 || seen.size >= 24 || !seen.add(m.descriptor)) return
          reads(m).forEach { f ->
            if (!Modifier.isStatic(f.modifiers)) fields[f.descriptor] = handle(f)
          }
          m.invokes.filter { it.paramCount == 0 && !Modifier.isStatic(it.modifiers) &&
            it.className.startsWith("com.android.systemui.") }.forEach { gather(it, depth + 1) }
        }
        gather(getter, 0)
        fun walk(type: Class<*>, path: List<Field>) {
          if (path.size >= 3) return
          fields.values.filter { it.declaringClass.isAssignableFrom(type) && it !in path }.forEach { f ->
            val next = path + f
            if (f.type === entry) paths += next
            else if (!f.type.isPrimitive && f.type !== String::class.java) walk(f.type, next)
          }
        }
        walk(row, emptyList())
      }
      val rows = paths.distinct()
      check(rows.size <= 8)
      val pairReaders = dex.findMethod {
        matcher { declaredClass = p.consumer.declaringClass.name; paramTypes(entry.name) }
      }.filter { m -> m.invokes.any { it.descriptor == DexSignUtil.getDescriptor(p.consumer) } }
      val updates = dex.findMethod {
        matcher { declaredClass = p.consumer.declaringClass.name; returnType = "void";
          paramTypes(entry.name, "boolean") }
      }.filter { m -> p.cached.all { cached -> m.usingFields.any {
        it.usingType == FieldUsingType.Write && it.field.descriptor == DexSignUtil.getDescriptor(cached)
      } } && m.invokes.any { called -> pairReaders.any { it.descriptor == called.descriptor } } }
      val safe = dex.findMethod {
        matcher { declaredClass = p.consumer.declaringClass.name; returnType = "void"; paramTypes(entry.name) }
      }.filter { m -> m.invokes.any { called -> updates.any { it.descriptor == called.descriptor } } }
      val localCalls = setOf(
        "Landroid/service/notification/NotificationListenerService\$Ranking;->getChannel()Landroid/app/NotificationChannel;",
        "Landroid/service/notification/NotificationListenerService\$Ranking;->getConversationShortcutInfo()Landroid/content/pm/ShortcutInfo;",
        "Landroid/service/notification/NotificationListenerService\$Ranking;->isConversation()Z",
        "Landroid/app/NotificationChannel;->isImportantConversation()Z",
        "Landroid/service/notification/StatusBarNotification;->getNotification()Landroid/app/Notification;",
        "Landroid/app/Notification;->isStyle(Ljava/lang/Class;)Z",
        "Ljava/util/Set;->contains(Ljava/lang/Object;)Z",
      )
      val important = consumerPredicate(dex, loader, p, localCalls)
      fun allFields(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.toList()
      val viewNotification = allFields(p.drawable.declaringClass).single {
        !Modifier.isStatic(it.modifiers) && StatusBarNotification::class.java.isAssignableFrom(it.type)
      }.apply { isAccessible = true }
      val entryNotification = allFields(entry).single {
        !Modifier.isStatic(it.modifiers) && StatusBarNotification::class.java.isAssignableFrom(it.type)
      }.apply { isAccessible = true }
      val entryGetters = dex.findMethod {
        matcher { returnType = entry.name; paramTypes(String::class.java.name) }
      }.filter { m -> m.invokes.isNotEmpty() && m.invokes.all {
        it.descriptor in setOf("Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;",
          "Landroid/util/ArrayMap;->get(Ljava/lang/Object;)Ljava/lang/Object;")
      } && m.usingFields.none { it.usingType == FieldUsingType.Write } }
      val entryMapFields = entryGetters.mapNotNull { getter ->
        reads(getter).singleOrNull()?.let(::handle)?.takeIf { Map::class.java.isAssignableFrom(it.type) }
      }.distinct()
      val collectionReaders = dex.findMethod {
        matcher { returnType = Collection::class.java.name; paramCount = 0 }
      }
      val entryMaps = collectionReaders.flatMap { reader ->
        val collectionFields = reads(reader).filter { candidate ->
          candidate.className == reader.className &&
            entryMapFields.any { it.declaringClass.name == candidate.typeName }
        }
        if (collectionFields.isEmpty()) return@flatMap emptyList()
        val pipeline = Class.forName(reader.className, false, loader)
        val managerFields = p.consumer.declaringClass.declaredFields.filter {
          !Modifier.isStatic(it.modifiers) && it.type.isAssignableFrom(pipeline) && it.type !== Any::class.java
        }
        val rootsRead = collectionFields.map(::handle)
        managerFields.flatMap { managerField ->
          rootsRead.flatMap { collection ->
            entryMapFields.filter { it.declaringClass === collection.type }.map { entryMap ->
              listOf(managerField.apply { isAccessible = true }, collection, entryMap)
            }
          }
        }
      }.distinct()
      check(entryMaps.size <= 4 && (rows.isNotEmpty() || entryMaps.isNotEmpty()))
      val stores = updates.flatMap { update ->
        val listenerSets = reads(update).map(::handle).filter {
          it.declaringClass === p.consumer.declaringClass && Set::class.java.isAssignableFrom(it.type)
        }
        update.invokes.filter { it.className != update.className && it.paramTypeNames == listOf(entry.name) }
          .flatMap { callback ->
            reads(callback).map(::handle).flatMap { captured ->
              val store = captured.type
              val storeFields = store.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
              val managerField = storeFields.singleOrNull { it.type === p.consumer.declaringClass }
              val viewMap = storeFields.singleOrNull { Map::class.java.isAssignableFrom(it.type) }
              // The manager can declare an interface while the store keeps its concrete
              // collection. Prefer that verified collection node over the pipeline interface.
              val collectionField = storeFields.singleOrNull { f ->
                entryMaps.any { it[1].type === f.type }
              } ?: storeFields.singleOrNull { f ->
                entryMaps.any { it.first().type.isAssignableFrom(f.type) }
              }
              if (managerField == null || viewMap == null || collectionField == null) emptyList()
              else listenerSets.map { set ->
                SystemUiStatusBarStoreProfile(set, captured, managerField.apply { isAccessible = true },
                  collectionField.apply { isAccessible = true }, viewMap.apply { isAccessible = true })
              }
            }
          }
      }.distinct()
      check(stores.size <= 4)
      val views = p.entryPack.type.declaredFields.filter { !Modifier.isStatic(it.modifiers) &&
        p.drawable.declaringClass.isAssignableFrom(it.type) }.onEach { it.isAccessible = true }
      SystemUiStatusBarEntryProfile(roots, maps, rows, views,
        safe.single().getMethodInstance(loader).apply { isAccessible = true },
        viewNotification, entryNotification, important, entryMaps, stores)
        .also { it.validate(p) }
    } catch (failure: Throwable) {
      recoverableSystemUiFailure(failure)
      null
    }

    @OptIn(DexKitExperimentalApi::class)
    private fun consumerPredicate(
      dex: org.luckypray.dexkit.DexKitBridge, loader: ClassLoader, p: SystemUiStatusBarIconProfile,
      localCalls: Set<String>,
    ): Method {
      val entry = p.consumer.parameterTypes.first()
      val consumers = dex.findMethod {
        matcher { declaredClass = p.consumer.declaringClass.name; name = p.consumer.name
          paramTypes(entry.name, "boolean") }
      }
      val candidates = consumers.single().invokes.filter {
        it.className == p.consumer.declaringClass.name && it.returnTypeName == "boolean" &&
          it.paramTypeNames == listOf(entry.name) &&
          it.invokes.any { call -> call.descriptor == "Landroid/app/NotificationChannel;->isImportantConversation()Z" }
      }
      val predicate = candidates.distinctBy { it.descriptor }.single()
      check(predicate.usingFields.none { it.usingType == FieldUsingType.Write })
      check(predicate.opNames.none { it.startsWith("new-") || it.startsWith("aput") ||
        it.startsWith("sput") || it.startsWith("iput") })
      check(predicate.invokes.all { call ->
        call.descriptor in localCalls || (call.paramCount == 0 && call.className == entry.name &&
          call.usingFields.size == 1 && call.usingFields.single().usingType == FieldUsingType.Read &&
          call.invokes.isEmpty() && call.opNames.none { it.startsWith("new-") || it.startsWith("aput") ||
            it.startsWith("sput") || it.startsWith("iput") })
      }) { "Conversation predicate contains an unverified call" }
      return predicate.getMethodInstance(loader).apply { isAccessible = true }
    }
  }
}

/** Existing ownership links only. Runtime reads fields rather than invoking a supplier or getter. */
internal data class SystemUiStatusBarEntryProfile(
  val registryRoots: List<Field>,
  val registryMaps: List<Field>,
  val rowEntries: List<List<Field>>,
  val packViews: List<Field>,
  val updateEntry: Method,
  val viewNotification: Field,
  val entryNotification: Field,
  val importantConversation: Method,
  val entryMaps: List<List<Field>> = emptyList(),
  val stores: List<SystemUiStatusBarStoreProfile> = emptyList(),
) {
  fun validate(p: SystemUiStatusBarIconProfile) {
    val registry = requireNotNull(p.nativeManager).declaringClass
    check(registryRoots.size <= 2 && registryMaps.size <= 8 && registryRoots.all {
      it.declaringClass === registry && Modifier.isStatic(it.modifiers) &&
        (it.type === registry || Map::class.java.isAssignableFrom(it.type))
    })
    check(registryMaps.all { it.declaringClass === registry && !Modifier.isStatic(it.modifiers) &&
      Map::class.java.isAssignableFrom(it.type) })
    check(rowEntries.size <= 8 && rowEntries.distinct().size == rowEntries.size &&
      (rowEntries.isNotEmpty() || entryMaps.isNotEmpty()))
    rowEntries.forEach { path ->
      check(path.size in 1..3 && android.view.View::class.java.isAssignableFrom(path.first().declaringClass) &&
        path.last().type === p.consumer.parameterTypes.first() &&
        path.all { !Modifier.isStatic(it.modifiers) })
      path.zipWithNext().forEach { (a, b) -> check(b.declaringClass.isAssignableFrom(a.type)) }
    }
    check(packViews.size in 1..8 && packViews.distinct().size == packViews.size &&
      packViews.all { it.declaringClass === p.entryPack.type && !Modifier.isStatic(it.modifiers) &&
        p.drawable.declaringClass.isAssignableFrom(it.type) })
    check(updateEntry.declaringClass === p.consumer.declaringClass &&
      updateEntry.parameterTypes.contentEquals(arrayOf(p.consumer.parameterTypes.first())) &&
      updateEntry.returnType == Void.TYPE)
    check(importantConversation.declaringClass === p.consumer.declaringClass &&
      !Modifier.isStatic(importantConversation.modifiers) &&
      importantConversation.parameterTypes.contentEquals(arrayOf(p.consumer.parameterTypes.first())) &&
      importantConversation.returnType == Boolean::class.javaPrimitiveType)
    check(viewNotification.declaringClass.isAssignableFrom(p.drawable.declaringClass) &&
      entryNotification.declaringClass.isAssignableFrom(p.consumer.parameterTypes.first()) &&
      listOf(viewNotification, entryNotification).all {
        !Modifier.isStatic(it.modifiers) && StatusBarNotification::class.java.isAssignableFrom(it.type)
      })
    check(entryMaps.size <= 4 && entryMaps.all { path ->
      path.size == 3 && path.first().declaringClass === p.consumer.declaringClass &&
        path[0].type.isAssignableFrom(path[1].declaringClass) &&
        path[1].type === path[2].declaringClass && Map::class.java.isAssignableFrom(path.last().type) &&
        path.all { !Modifier.isStatic(it.modifiers) }
    })
    check(stores.size <= 4)
    stores.forEach { it.validate(p, entryMaps) }
  }
}

internal data class SystemUiStatusBarStoreProfile(
  val listeners: Field, val captured: Field, val manager: Field, val collection: Field, val views: Field,
) {
  fun fields() = listOf(listeners, captured, manager, collection, views)
  fun validate(p: SystemUiStatusBarIconProfile, entryMaps: List<List<Field>>) {
    check(fields().all { !Modifier.isStatic(it.modifiers) } &&
      listeners.declaringClass === p.consumer.declaringClass && Set::class.java.isAssignableFrom(listeners.type) &&
      manager.declaringClass === captured.type && manager.type === p.consumer.declaringClass &&
      views.declaringClass === captured.type && Map::class.java.isAssignableFrom(views.type) &&
      collection.declaringClass === captured.type && entryMaps.any {
        it[1].type === collection.type || it.first().type.isAssignableFrom(collection.type)
      })
  }
}
