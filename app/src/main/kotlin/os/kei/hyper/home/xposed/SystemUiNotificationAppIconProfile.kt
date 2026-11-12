package os.kei.hyper.home.xposed

import android.content.Context
import android.app.Notification
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.widget.ImageView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.FieldUsingType
import os.kei.hyper.home.iconpack.validDottedName

internal fun notificationAppIconPackage(
  name: String?, appUid: Int, notificationUser: Int, currentUser: Int,
): String? {
  if (currentUser < 0 || notificationUser != currentUser || appUid < 1000 ||
    appUid / 100000 != currentUser || appUid % 100000 < 1000) return null
  if (name == null || name.length > 240 || !validDottedName(name) ||
    name.any { it != '.' && it != '_' && it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' }) return null
  return name
}

/** Resolve on the startup worker; hot binding reads only authenticated native fields. */
internal class SystemUiNotificationAppIconProfile private constructor(
  val method: Method,
  val customMethod: Method?,
  private val owner: Class<*>,
  private val packageField: Field,
  private val uidField: Field,
  private val iconField: Field,
) {
  fun identity(sbn: Any, currentUser: Int): String? {
    if (!owner.isInstance(sbn) || sbn !is StatusBarNotification) return null
    return try {
      notificationAppIconPackage(packageField.get(sbn) as? String, uidField.getInt(sbn),
        sbn.userId, currentUser)
    } catch (_: ReflectiveOperationException) { null }
  }

  fun cached(sbn: Any): Drawable? {
    if (!owner.isInstance(sbn)) return null
    return try { iconField.get(sbn) as? Drawable }
    catch (_: ReflectiveOperationException) { null }
  }

  fun customIdentity(value: Any, currentUser: Int): String? {
    val name = identity(value, currentUser) ?: return null
    val sbn = value as StatusBarNotification
    val extras = sbn.notification.extras
    if (sbn.opPkg == "com.miui.hybrid" ||
      (sbn.packageName == "com.os.callservice" && !extras.getString("miui.category").isNullOrEmpty()) ||
      extras.getString("miui.opPkg") != name) return null
    return name
  }

  companion object {
    private const val UTILITY = "com.android.systemui.statusbar.notification.utils.NotifImageUtil"
    private const val EXPANDED = "com.android.systemui.statusbar.notification.ExpandedNotification"
    private const val INITIALIZER =
      "com.android.systemui.statusbar.notification.row.NotificationRowContentBinderInjector\$Companion"
    private const val FIELD_OWNER = "Lcom/android/systemui/statusbar/notification/ExpandedNotification;"
    private const val PACKAGE = "$FIELD_OWNER->mPkgName:Ljava/lang/String;"
    private const val UID = "$FIELD_OWNER->mAppUid:I"
    private const val ICON = "$FIELD_OWNER->mAppIcon:Landroid/graphics/drawable/Drawable;"
    private const val SETTER =
      "Landroid/widget/ImageView;->setImageDrawable(Landroid/graphics/drawable/Drawable;)V"
    private const val LOAD =
      "Lcom/miui/systemui/graphics/AppIconsManager;->getAppIconInner(Ljava/lang/String;ILandroid/content/pm/ApplicationInfo;Landroid/content/pm/PackageManager;)Landroid/graphics/drawable/Drawable;"

    @OptIn(DexKitExperimentalApi::class)
    fun resolve(context: Context, loader: ClassLoader): SystemUiNotificationAppIconProfile {
      val application = context.applicationContext ?: context
      val hostLoader = application.classLoader
      fun existing(name: String, candidate: ClassLoader): Class<*>? =
        try { Class.forName(name, false, candidate) } catch (_: ClassNotFoundException) { null }
      fun hostClass(name: String): Class<*> {
        val host = existing(name, hostLoader)
        val supplied = if (loader === hostLoader) host else existing(name, loader)
        check(host == null || supplied == null || host === supplied) {
          "Notification app-icon class identity differs between existing host loaders"
        }
        return checkNotNull(host ?: supplied) { "Notification app-icon host class unavailable: $name" }
      }
      val utility = hostClass(UTILITY)
      val expanded = hostClass(EXPANDED)
      check(StatusBarNotification::class.java.isAssignableFrom(expanded))
      val actualLoader = checkNotNull(utility.classLoader)
      check(Class.forName(EXPANDED, false, actualLoader) === expanded)
      val info = application.applicationInfo
      check(info.packageName == "com.android.systemui") {
        "Notification app-icon profile requires the existing SystemUI application context"
      }
      return SystemUiMaterialDexKit.open(info.sourceDir, context.codeCacheDir).use { dex ->
        dex.setThreadNum(1)
        val seeds = dex.findMethod {
          matcher {
            declaredClass = UTILITY
            returnType = "void"
            paramTypes("android.content.Context", EXPANDED, "android.widget.ImageView", "boolean")
          }
        }
        check(seeds.size <= 16) { "Notification app-icon candidate budget exceeded" }
        val candidate = checkNotNull(seeds.filter {
          val reads = it.usingFields.filter { use -> use.usingType == FieldUsingType.Read }
            .map { use -> use.field.descriptor }.toSet()
          Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) &&
            !Modifier.isAbstract(it.modifiers) && !Modifier.isNative(it.modifiers) &&
            reads.containsAll(setOf(PACKAGE, ICON)) && it.invokes.any { call -> call.descriptor == SETTER }
        }.singleOrNull()) { "Notification app-icon cached Drawable consumer is absent or ambiguous" }

        // Authenticate the UID's role through the native app-info producer, not its type/name alone.
        val writers = dex.findMethod { matcher { declaredClass = INITIALIZER; returnType = "void" } }
        check(writers.size <= 64) { "Notification app-info producer budget exceeded" }
        val writer = checkNotNull(writers.filter {
          val writes = it.usingFields.filter { use -> use.usingType == FieldUsingType.Write }
            .map { use -> use.field.descriptor }.toSet()
          writes.containsAll(setOf(UID, ICON)) && it.invokes.any { call -> call.descriptor == LOAD } &&
            it.usingFields.any { use -> use.usingType == FieldUsingType.Read &&
              use.field.descriptor == "Landroid/content/pm/ApplicationInfo;->uid:I" }
        }.singleOrNull()) { "Notification app-icon UID producer is absent or ambiguous" }
        fun field(descriptor: String, write: Boolean = false): Field {
          val uses = if (write) writer.usingFields else candidate.usingFields
          val type = if (write) FieldUsingType.Write else FieldUsingType.Read
          val reflected = uses.first { it.usingType == type && it.field.descriptor == descriptor }
            .field.getFieldInstance(actualLoader)
          check(reflected.declaringClass === expanded && !Modifier.isStatic(reflected.modifiers))
          return reflected.apply { isAccessible = true }
        }
        val pkg = field(PACKAGE).also { check(it.type == String::class.java) }
        val uid = field(UID, write = true).also { check(it.type == Int::class.javaPrimitiveType) }
        val icon = field(ICON).also { check(it.type == Drawable::class.java) }
        val method = candidate.getMethodInstance(actualLoader)
        check(method.declaringClass === utility && method.returnType == Void.TYPE &&
          method.parameterTypes.contentEquals(arrayOf(Context::class.java, expanded,
            ImageView::class.java, Boolean::class.javaPrimitiveType)))
        method.isAccessible = true
        val custom = candidate.invokes.filter {
          it.returnTypeName == Drawable::class.java.name &&
            it.paramTypeNames == listOf(Notification::class.java.name, Context::class.java.name) &&
            it.usingStrings.containsAll(listOf("miui.opPkg", "miui.appIcon"))
        }.singleOrNull()?.getMethodInstance(actualLoader)?.also {
          check(it.declaringClass === utility && Modifier.isStatic(it.modifiers))
          it.isAccessible = true
        }
        SystemUiNotificationAppIconProfile(method, custom, expanded, pkg, uid, icon)
      }
    }
  }
}
