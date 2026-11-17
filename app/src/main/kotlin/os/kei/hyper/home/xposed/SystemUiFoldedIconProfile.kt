package os.kei.hyper.home.xposed

import android.content.Context
import android.app.Application
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

internal class SystemUiFoldedIconProfile private constructor(
  val producer: Method,
  val send: Method,
  private val controller: Class<*>,
  private val themeChanged: Method,
  private val discover: () -> List<*>,
  val discoversExisting: Boolean,
) {
  @Volatile private var known = WeakReference<Any>(null)

  fun remember(value: Any?) {
    if (value != null && controller.isInstance(value)) known = WeakReference(value)
  }

  /** Called on the main thread, like native configuration-listener dispatch. */
  fun rebuild(): Int {
    val targets = known.get()?.let(::listOf) ?: discover().filterNotNull().filter(controller::isInstance)
    targets.forEach { themeChanged.invoke(it, false); remember(it) }
    return targets.size
  }

  companion object {
    private const val APP = "Lcom/miui/systemui/graphics/AppIconsManager;"
    private const val FOLD = "com.android.systemui.statusbar.notification.history.FoldNotifControllerImpl"
    private const val INNER = "$APP->getAppIconInner(Ljava/lang/String;ILandroid/content/pm/ApplicationInfo;Landroid/content/pm/PackageManager;)Landroid/graphics/drawable/Drawable;"
    private const val LOAD = "$APP->loadAppIcon(Ljava/lang/String;ILandroid/content/pm/ApplicationInfo;Landroid/content/pm/PackageManager;)Landroid/graphics/drawable/Drawable;"
    private const val SEND = "Lcom/android/systemui/statusbar/notification/history/FoldNotifControllerImpl;->sendFoldNotification(Landroid/os/UserHandle;Lcom/android/systemui/statusbar/notification/history/FoldNotifControllerImpl\$CreateFoldNotifReason;)V"

    @OptIn(DexKitExperimentalApi::class)
    @SuppressLint("SoonBlockedPrivateApi") // Injected SystemUI only; ordinary callers use the native-send fallback.
    fun resolve(context: Context, owner: Class<*>): SystemUiFoldedIconProfile {
      val loader = owner.classLoader!!
      val fold = Class.forName(FOLD, false, loader)
      val reason = Class.forName("$FOLD\$CreateFoldNotifReason", false, loader)
      val producer = owner.getDeclaredMethod("getAppIconInner", String::class.java,
        Int::class.javaPrimitiveType, ApplicationInfo::class.java, PackageManager::class.java)
      check(producer.returnType == Drawable::class.java && !Modifier.isStatic(producer.modifiers))
      val send = fold.getDeclaredMethod("sendFoldNotification", UserHandle::class.java, reason)
      val theme = fold.getDeclaredMethod("onMiuiThemeChanged", Boolean::class.javaPrimitiveType)
      check(send.returnType == Void.TYPE && theme.returnType == Void.TYPE)
      val source = context.packageManager.getApplicationInfo("com.android.systemui", 0).sourceDir
      SystemUiMaterialDexKit.open(source, context.codeCacheDir).use { dex ->
        dex.setThreadNum(1)
        val nativeProducer = checkNotNull(dex.getMethodData(INNER))
        val nativeSend = checkNotNull(dex.getMethodData(SEND))
        check(nativeProducer.invokes.any { it.descriptor == LOAD })
        check(nativeSend.invokes.any { it.descriptor == INNER })
        check(nativeProducer.getMethodInstance(loader) == producer &&
          nativeSend.getMethodInstance(loader) == send)
      }
      val configType = Class.forName("com.android.systemui.statusbar.policy.ConfigurationController", false, loader)
      val configImpl = Class.forName("com.android.systemui.statusbar.phone.ConfigurationControllerImpl", false, loader)
      val listeners = configImpl.getDeclaredField("listeners").apply { isAccessible = true }
      check(java.util.List::class.java.isAssignableFrom(listeners.type))
      check(configType.isAssignableFrom(configImpl) && configType.isInstanceClassListener(fold, loader))
      // Read the existing factory/component graph; do not construct a second
      // Dependency or configuration controller during a hot reload.
      val existing: (() -> List<*>)? = try {
        val loadedApk = Application::class.java.getDeclaredField("mLoadedApk").apply { isAccessible = true }
        val appFactory = Class.forName("android.app.LoadedApk").getDeclaredField("mAppComponentFactory")
          .apply { isAccessible = true }
        val initializer = Class.forName("com.android.systemui.SystemUIAppComponentFactoryBase", false, loader)
          .getDeclaredField("systemUIInitializer").apply { isAccessible = true }
        val component = Class.forName("com.android.systemui.SystemUIInitializer", false, loader)
          .getDeclaredField("mSysUIComponent").apply { isAccessible = true }
        val configGetter = Class.forName(
          "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl", false, loader)
          .getDeclaredMethod("getConfigurationController").apply { isAccessible = true }
        check(configType.isAssignableFrom(configGetter.returnType))
        val lookup: () -> List<*> = {
          val apk = loadedApk.get(context.applicationContext)
          val factory = apk?.let(appFactory::get)
          val init = factory?.takeIf(initializer.declaringClass::isInstance)?.let(initializer::get)
          val graph = init?.let(component::get)
          val config = graph?.let { configGetter.invoke(it) }
          if (configImpl.isInstance(config)) (listeners.get(config) as List<*>).toList() else emptyList<Any>()
        }
        lookup
      } catch (_: ReflectiveOperationException) {
        // Private framework members can be hidden to an ordinary test process,
        // or absent on another build. Native send calls still capture ownership.
        null
      }
      return SystemUiFoldedIconProfile(producer.apply { isAccessible = true },
        send.apply { isAccessible = true }, fold, theme.apply { isAccessible = true },
        existing ?: { emptyList<Any>() }, existing != null)
    }

    private fun Class<*>.isInstanceClassListener(fold: Class<*>, loader: ClassLoader): Boolean =
      Class.forName("$name\$ConfigurationListener", false, loader).isAssignableFrom(fold)
  }
}
