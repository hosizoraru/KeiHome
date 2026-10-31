package os.kei.hyper.home.xposed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.util.Pair
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Private transport to the installed native PC importer; all actual import work stays in Themes. */
internal class ThemeIconImportRuntime(
  private val xposed: XposedInterface,
  val classLoader: ClassLoader,
) {
  private val gate = ThemeIconImportGate()
  private val approvalWarningLogged = AtomicBoolean()
  private val grants by lazy { ConvertedIconGrants(classLoader) }
  @Volatile private var grantsReady = false

  fun install(oldHooks: List<XposedInterface.HookHandle> = emptyList()) {
    val service = Class.forName(SERVICE_CLASS, false, classLoader)
    val method = service.getDeclaredMethod("onBind", Intent::class.java)
    require(method.returnType == IBinder::class.java) { "Unexpected Themes onBind contract" }
    fun native(name: String) = Class.forName("$PACKAGE_NAME.$name", false, classLoader)
    val resource = native("basemodule.resource.model.Resource")
    val rights = native("module.detail.util.ResourceRightsHelper").getDeclaredMethod("k",
      native("ResourceContext"), native("controller.ThemeController"), native("controller.online.DrmService"), resource)
    val drmResult = Class.forName("miui.drm.DrmManager\$DrmResult", false, classLoader)
    require(rights.returnType == drmResult && Modifier.isStatic(rights.modifiers))
    val drmResults = requireNotNull(drmResult.enumConstants)
    val success = drmResults.single { (it as Enum<*>).name == "DRM_SUCCESS" }
    val unknown = drmResults.single { (it as Enum<*>).name == "DRM_ERROR_UNKNOWN" }
    // Resolve the host's LiveData from its signature. Class.forName with a full AndroidX
    // name is rewritten by R8 to KeiHome's own obfuscated class, absent from the host loader.
    val apply = native("module.detail.util.ResourceApplyHelper").declaredMethods.single { candidate ->
      val p = candidate.parameterTypes
      candidate.name == "toq" && p.size == 11 && p[0] == Activity::class.java &&
        p[1] == String::class.java && p[2] == resource &&
        p[3] == Int::class.javaPrimitiveType && p[4] == Int::class.javaPrimitiveType &&
        p[6].simpleName == "MutableLiveData" &&
        listOf(5, 7, 8, 9, 10).all { p[it] == Boolean::class.javaPrimitiveType }
    }
    require(apply.returnType == Void.TYPE && Modifier.isStatic(apply.modifiers))
    val validate = native("util.ApplyValidateThemeTask").getDeclaredMethod("n", Context::class.java,
      String::class.java, String::class.java)
    require(validate.returnType == Pair::class.java && !Modifier.isStatic(validate.modifiers))
    // Resolve all exact 11540 contracts before changing hooks. Registration stays unavailable
    // until every approval callback is installed in this generation.
    grants
    val hooker = XposedInterface.Hooker { chain ->
      // Old callbacks can arrive during replacement. Once retired, they touch neither Xposed
      // chain APIs nor the native service. The verified original onBind also returns null.
      if (!gate.enter()) null else try {
        if ((chain.getArg(0) as? Intent)?.action != PRIVATE_ACTION) {
          chain.proceed()
        } else {
          try {
            val host = requireNotNull(chain.thisObject)
            guardedBinder(resolveNativeBinder(host), (host as Context).applicationContext)
          } catch (failure: Exception) {
            xposed.log(Log.WARN, TAG, "Could not resolve the native PC import Binder", failure)
            null
          }
        }
      } finally { gate.leave() }
    }
    installHook(method, HOOK_ID, hooker, oldHooks)
    installHook(rights, RIGHTS_HOOK_ID, XposedInterface.Hooker { chain ->
      if (!gate.enter()) unknown else try {
        if (approved { grants.eligible(requireNotNull(chain.getArg(0)), requireNotNull(chain.getArg(3))) }) success
        else chain.proceed()
      } finally { gate.leave() }
    }, oldHooks)
    installHook(apply, APPLY_HOOK_ID, XposedInterface.Hooker { chain ->
      if (!gate.enter()) null else try {
        if (chain.getArg(1) == "icons" && approved { grants.iconsOnly(requireNotNull(chain.getArg(2))) }) {
          val args = chain.args.toTypedArray()
          args[8] = false // withRelateResource; native icons otherwise also relates wallpaper.
          chain.proceed(args)
        } else chain.proceed()
      } finally { gate.leave() }
    }, oldHooks)
    installHook(validate, VALIDATE_HOOK_ID, XposedInterface.Hooker { chain ->
      if (!gate.enter()) Pair(false, unknown) else try {
        if (approved { grants.validates(requireNotNull(chain.thisObject), chain.getArg(1) as String,
            chain.getArg(0) as Context) }) Pair(true, success)
        else chain.proceed()
      } finally { gate.leave() }
    }, oldHooks)
    grantsReady = true
    xposed.log(Log.INFO, TAG, "Installed private Themes import and converted-icon approval hooks")
  }

  private fun installHook(method: Method, id: String, hooker: XposedInterface.Hooker,
    oldHooks: List<XposedInterface.HookHandle>) {
    val previous = oldHooks.filter { it.id == id && it.executable == method }
    require(previous.size <= 1) { "Multiple Themes hooks for $id" }
    val old = previous.singleOrNull()
    val installed = if (old == null) xposed.hook(method).setId(id).intercept(hooker) else old.replaceHook(hooker)
    require(installed.id == id && installed.executable == method)
    oldHooks.filter { it !== old && it.id == id }.forEach { it.unhook() }
  }

  private fun approved(check: () -> Boolean): Boolean = try { grantsReady && check() }
    catch (failure: Exception) {
      if (failure is InterruptedException) Thread.currentThread().interrupt()
      if (approvalWarningLogged.compareAndSet(false, true)) {
        xposed.log(Log.WARN, TAG, "Converted approval unavailable; retaining native rights behavior", failure)
      }
      false
    }

  /** Never wait on an import from a lifecycle callback. Refuse reload until active calls finish. */
  fun retire(saveState: () -> Unit): Boolean = gate.retire(saveState)

  private fun resolveNativeBinder(service: Any): IBinder {
    val candidates = service.javaClass.declaredFields.filter {
      !Modifier.isStatic(it.modifiers) && IBinder::class.java.isAssignableFrom(it.type)
    }.mapNotNull { field ->
      field.isAccessible = true
      (field.get(service) as? IBinder)?.takeIf { it.isBinderAlive && it.interfaceDescriptor == PC_DESCRIPTOR }
    }
    return candidates.singleOrNull() ?: error("Expected one live native PC Binder field, found ${candidates.size}")
  }

  private fun guardedBinder(native: IBinder, context: Context): IBinder = object : Binder() {
    init { attachInterface(null, PC_DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
      if (!gate.enter()) throw SecurityException("Themes import bridge retired")
      try {
        // onBind runs on the service main thread and cannot authenticate its client. Enforce
        // ownership at every transaction, including descriptor queries, before exposing data.
        if (getCallingUid() != 1000) throw SecurityException("Themes import bridge requires system UID")
        if (code == IBinder.INTERFACE_TRANSACTION) {
          requireNotNull(reply).writeString(PC_DESCRIPTOR)
          return true
        }
        if (code == REGISTER_CONVERTED) {
          require(flags and IBinder.FLAG_ONEWAY == 0 && reply != null) {
            "Converted registration requires a synchronous reply"
          }
          data.enforceInterface(GRANT_DESCRIPTOR)
          fun read() = requireNotNull(data.readString()).also { require(it.length in 1..4096) }
          val origin = read()
          val path = read()
          val localId = read()
          val proof = ConvertedIconArchive.Proof(origin,
            ConvertedIconArchive.Digests(read(), read()), ConvertedIconArchive.Digests(read(), read()))
          require(data.dataAvail() == 0)
          try {
            check(grantsReady) { "Converted approval hooks unavailable" }
            grants.register(context, path, localId, proof)
            reply.writeNoException()
            reply.writeInt(1)
          } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            reply.writeNoException()
            reply.writeInt(0)
            reply.writeString("${failure.javaClass.simpleName}: ${failure.message}".take(512))
          }
          return true
        }
        if (code != 2 && code != 5) throw SecurityException("Unsupported Themes import bridge transaction")
        return native.transact(code, data, reply, flags)
      } finally { gate.leave() }
    }
  }

  companion object {
    const val PACKAGE_NAME = "com.android.thememanager"
    const val SERVICE_CLASS = "$PACKAGE_NAME.service.ThemePCService"
    const val PRIVATE_ACTION = "os.kei.hyper.home.action.BIND_NATIVE_ICON_IMPORT"
    const val PC_DESCRIPTOR = "$PACKAGE_NAME.service.IThemePCService"
    const val HOOK_ID = "keihome.theme.icon_import.bind.v1"
    const val GRANT_DESCRIPTOR = "os.kei.hyper.home.IConvertedIconGrant.v1"
    const val REGISTER_CONVERTED = 0x004b4801
    private const val RIGHTS_HOOK_ID = "keihome.theme.converted_icon.rights.v1"
    private const val APPLY_HOOK_ID = "keihome.theme.converted_icon.apply.v1"
    private const val VALIDATE_HOOK_ID = "keihome.theme.converted_icon.validate.v1"
    private const val TAG = "KeiHomeThemeImport"
  }
}

/** Counts hook callbacks and delegated Binder calls; commit closes admission atomically. */
internal class ThemeIconImportGate {
  private val lock = Any()
  private var active = 0
  private var retiring = false
  private var retired = false

  fun enter(): Boolean = synchronized(lock) {
    if (retiring || retired) false else { active++; true }
  }

  fun leave() = synchronized(lock) { check(active > 0); active-- }

  fun retire(saveState: () -> Unit): Boolean = synchronized(lock) {
    if (retired) return@synchronized true
    if (retiring || active != 0) return@synchronized false
    retiring = true
    try {
      saveState()
      retired = true
      true
    } catch (_: Throwable) {
      false
    } finally { retiring = false }
  }
}
