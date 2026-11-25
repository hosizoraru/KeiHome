package os.kei.hyper.home.xposed

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
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
  private val profile: ThemeImportProfile,
) {
  private val gate = ThemeIconImportGate()
  private val approvalWarningLogged = AtomicBoolean()
  private val generation = System.identityHashCode(this).toString(16)
  private val grants = ConvertedIconGrants(classLoader, profile) { stage, nanos ->
    xposed.log(Log.INFO, TAG,
      "Converted $stage hash generation=$generation tid=${Process.myTid()} elapsedMs=${nanos / 1_000_000}")
  }
  private val initializationScheduled = AtomicBoolean()
  @Volatile private var initializationThread: Thread? = null
  @Volatile private var grantsReady = false
  @Volatile private var convertedGuard: ThemeConvertedGuard? = null
  @Volatile private var appearanceVariants: ThemeAppearanceVariants? = null

  private fun appearance(context: Context): ThemeAppearanceVariants =
    appearanceVariants ?: synchronized(this) {
      appearanceVariants ?: ThemeAppearanceVariants(context).also { store ->
        appearanceVariants = store
        grants.appearanceSiblings = store::siblings
        convertedGuard?.appearanceSiblings = store::siblings
      }
    }

  fun install(oldHooks: List<XposedInterface.HookHandle> = emptyList()) {
    val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
    val service = Class.forName(SERVICE_CLASS, false, classLoader)
    val method = service.getDeclaredMethod("onBind", Intent::class.java)
    require(method.returnType == IBinder::class.java) { "Unexpected Themes onBind contract" }
    fun native(name: String) = Class.forName("$PACKAGE_NAME.$name", false, classLoader)
    val resource = native("basemodule.resource.model.Resource")
    val rights = profile["rights"]
    val drmResult = Class.forName("miui.drm.DrmManager\$DrmResult", false, classLoader)
    require(rights.returnType == drmResult && Modifier.isStatic(rights.modifiers))
    val drmResults = requireNotNull(drmResult.enumConstants)
    val success = drmResults.single { (it as Enum<*>).name == "DRM_SUCCESS" }
    val unknown = drmResults.single { (it as Enum<*>).name == "DRM_ERROR_UNKNOWN" }
    val downloadRights = profile["downloadRights"]
    val downloadResponse = downloadRights.returnType.getConstructor(Int::class.javaPrimitiveType)
    // Resolve the host's LiveData from its signature. Class.forName with a full AndroidX
    // name is rewritten by R8 to KeiHome's own obfuscated class, absent from the host loader.
    val apply = profile["apply"].also { candidate ->
      val p = candidate.parameterTypes
      require(p.size == 11 && p[0] == Activity::class.java &&
        p[1] == String::class.java && p[2] == resource &&
        p[3] == Int::class.javaPrimitiveType && p[4] == Int::class.javaPrimitiveType &&
        p[6].simpleName == "MutableLiveData" &&
        listOf(5, 7, 8, 9, 10).all { p[it] == Boolean::class.javaPrimitiveType })
    }
    require(apply.returnType == Void.TYPE && Modifier.isStatic(apply.modifiers))
    val detailReady = profile["detailReady"]
    val automatic = NativeIconAutoApply(apply)
    val validate = profile["validate"]
    require(validate.returnType == Pair::class.java && !Modifier.isStatic(validate.modifiers))
    // Resolve the complete installed-DEX contract before changing hooks. Registration stays unavailable
    // until every approval callback is installed in this generation.
    installHook(attach, ATTACH_HOOK_ID, XposedInterface.Hooker { chain ->
      if (!gate.enter()) null else try {
        val result = chain.proceed()
        try {
          // ApplicationInfo belongs to the actual attached instance, not a guessed Themes
          // application class or a heap-enumerated object. Run after native onCreate returns.
          initialize(requireNotNull(chain.thisObject) as Application, afterAttach = true)
        } catch (failure: Exception) {
          warnApproval(failure)
        }
        result
      } finally { gate.leave() }
    }, oldHooks)
    val hooker = XposedInterface.Hooker { chain ->
      // Old callbacks can arrive during replacement. Once retired, they touch neither Xposed
      // chain APIs nor the native service. The verified original onBind also returns null.
      if (!gate.enter()) null else try {
        if ((chain.getArg(0) as? Intent)?.action != PRIVATE_ACTION) {
          chain.proceed()
        } else {
          try {
            val host = requireNotNull(chain.thisObject)
            val context = (host as Context).applicationContext
            (context as? Application)?.let { initialize(it) }
            guardedBinder(resolveNativeBinder(host), context)
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
        if (approved {
            val hostContext = requireNotNull(chain.getArg(0))
            val supplied = requireNotNull(chain.getArg(3))
            convertedGuard?.approve(supplied, hostContext) ?: grants.eligible(hostContext, supplied)
          }) success
        else chain.proceed()
      } finally { gate.leave() }
    }, oldHooks)
    installHook(downloadRights, DOWNLOAD_RIGHTS_HOOK_ID, XposedInterface.Hooker { chain ->
      if (!gate.enter()) downloadResponse.newInstance(100001) else try {
        ThemeConvertedApproval.dispatch(
          approval = {
            if (approved { convertedGuard?.approve(requireNotNull(chain.getArg(0)), null) == true }) {
              xposed.log(Log.INFO, TAG,
                "Converted preapproved standard rights generation=$generation pid=${Process.myPid()}")
              downloadResponse.newInstance(ThemeConvertedGuard.RIGHTS_SUCCESS)
            } else null
          },
          original = { chain.proceed() },
          failed = ::warnApproval,
        )
      } finally { gate.leave() }
    }, oldHooks)
    installHook(apply, APPLY_HOOK_ID, XposedInterface.Hooker { chain ->
      if (!gate.enter()) null else try {
        if (chain.getArg(1) == "icons" && approvedScope {
            val supplied = requireNotNull(chain.getArg(2))
            convertedGuard?.iconsOnly(supplied) == true || grants.iconsOnly(supplied)
          }) {
          val args = chain.args.toTypedArray()
          args[8] = false // withRelateResource; native icons otherwise also relates wallpaper.
          chain.proceed(args)
        } else chain.proceed()
      } finally { gate.leave() }
    }, oldHooks)
    installHook(validate, VALIDATE_HOOK_ID, XposedInterface.Hooker { chain ->
      if (!gate.enter()) Pair(false, unknown) else try {
        if (approved {
            convertedGuard?.validates(requireNotNull(chain.thisObject), chain.getArg(1) as String)
              ?: grants.validates(requireNotNull(chain.thisObject), chain.getArg(1) as String,
                chain.getArg(0) as Context)
          }) Pair(true, success)
        else chain.proceed()
      } finally { gate.leave() }
    }, oldHooks)
    installHook(detailReady, AUTO_APPLY_HOOK_ID, XposedInterface.Hooker { chain ->
      if (!gate.enter()) null else try {
        val result = chain.proceed()
        if (chain.getArg(2) == true) {
          val activity = chain.thisObject as Activity
          val readyResource = requireNotNull(chain.getArg(0))
          val code = chain.getArg(1) as String
          // The local resource arrives during onCreate. Let the native activity reach
          // STARTED before applying, so lifecycle observers receive every status.
          activity.window.decorView.post {
            if (gate.enter()) try {
              try { automatic.resourceReady(activity, readyResource, code) }
              catch (failure: Exception) {
                xposed.log(Log.WARN, TAG, "Automatic icons apply unavailable; native detail remains available", failure)
              }
            } finally { gate.leave() }
          }
        }
        result
      } finally { gate.leave() }
    }, oldHooks)
    grantsReady = true
    // API 102 does not replay attach on reload. This is the framework's live application,
    // and only its host Context crosses into our new generation.
    try {
      val application = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
        .invoke(null) as? Application
      application?.let { initialize(it) }
    } catch (failure: Exception) {
      warnApproval(failure)
    }
    xposed.log(Log.INFO, TAG, "Installed private Themes import and converted-icon approval hooks")
  }

  private fun initialize(application: Application, afterAttach: Boolean = false) {
    if (!grantsReady) return
    val info = application.applicationInfo
    if (info.packageName != PACKAGE_NAME || info.uid != Process.myUid()) return
    if (convertedGuard == null) convertedGuard = ThemeConvertedGuard(application, classLoader, profile)
    appearance(application).let { store -> convertedGuard?.appearanceSiblings = store::siblings }
    if (initializationThread?.isAlive == true) return
    if (!initializationScheduled.compareAndSet(false, true)) return
    if (!gate.enter()) { initializationScheduled.set(false); return }
    val start = Runnable {
      try {
        val worker = Thread({
          try { grants.initialize(application) }
          catch (failure: Exception) {
            initializationScheduled.set(false)
            warnApproval(failure)
          } finally { gate.leave() }
        }, "KeiHomeConvertedGrants").apply { isDaemon = true }
        initializationThread = worker
        worker.start()
      }
      catch (failure: Throwable) {
        initializationScheduled.set(false)
        gate.leave()
        warnApproval(failure)
      }
    }
    if (afterAttach) {
      // Admission includes the queued callback, so retirement cannot leave an old closure
      // on the main Looper or start an old-generation worker after a successful state save.
      val posted = try { Handler(Looper.getMainLooper()).post(start) }
        catch (failure: Exception) { warnApproval(failure); false }
      if (!posted) {
        initializationScheduled.set(false)
        gate.leave()
      }
    } else start.run()
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

  private fun approved(check: () -> Boolean): Boolean = try {
      (convertedGuard != null || grantsReady) && Looper.myLooper() != Looper.getMainLooper() && check()
    }
    catch (failure: Exception) {
      warnApproval(failure)
      false
    }

  private fun warnApproval(failure: Throwable) {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    if (approvalWarningLogged.compareAndSet(false, true)) {
      xposed.log(Log.WARN, TAG, "Converted approval unavailable; retaining native rights behavior", failure)
    }
  }

  private fun approvedScope(check: () -> Boolean): Boolean = try { (convertedGuard != null || grantsReady) && check() }
    catch (failure: Exception) { warnApproval(failure); false }

  /** Never wait on an import from a lifecycle callback. Refuse reload until active calls finish. */
  fun retire(saveState: () -> Unit): Boolean = gate.retire(
    workersStopped = { initializationThread?.isAlive != true },
  ) {
    saveState()
    grants.retire()
    grantsReady = false
    convertedGuard = null
  }

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
        if (code == AUTHORIZE_AUTO_APPLY) {
          require(flags and IBinder.FLAG_ONEWAY == 0 && reply != null)
          data.enforceInterface(GRANT_DESCRIPTOR)
          val token = requireNotNull(data.readString())
          val local = requireNotNull(data.readString())
          require(data.dataAvail() == 0)
          val accepted = NativeIconAutoApply.authorize(context, token, local)
          reply.writeNoException()
          reply.writeInt(if (accepted) 1 else 0)
          return true
        }
        if (code == REGISTER_APPEARANCE_VARIANTS) {
          require(flags and IBinder.FLAG_ONEWAY == 0 && reply != null)
          data.enforceInterface(GRANT_DESCRIPTOR)
          fun read() = requireNotNull(data.readString()).also { require(it.length in 1..4096) }
          val localId = read()
          val primary = ConvertedIconArchive.Digests(read(), read())
          val lightPath = read()
          val light = ConvertedIconArchive.Digests(read(), read())
          val darkPath = read()
          val dark = ConvertedIconArchive.Digests(read(), read())
          val paletteFlattened = data.readInt() == 1
          require(data.dataAvail() == 0)
          try {
            check(grantsReady) { "Converted approval hooks unavailable" }
            val content = grants.registeredContent(context, localId, primary)
            appearance(context).register(localId, primary, content, java.io.File(lightPath), light,
              java.io.File(darkPath), dark, paletteFlattened)
            xposed.log(Log.INFO, TAG, "Registered fixed-appearance icons for $localId")
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
        if (code == SYNC_APPEARANCE_VARIANT) {
          require(flags and IBinder.FLAG_ONEWAY == 0 && reply != null)
          data.enforceInterface(GRANT_DESCRIPTOR)
          val night = data.readInt() == 1
          val palette = data.readInt() == 1
          require(data.dataAvail() == 0)
          val result = try {
            appearance(context).sync(night, palette)
          } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            xposed.log(Log.WARN, TAG, "Fixed-appearance icon swap failed", failure)
            ThemeAppearanceVariants.SyncResult("failed ${failure.javaClass.simpleName}", null)
          }
          reply.writeNoException()
          reply.writeString(result.status)
          val prepared = result.prepared
          reply.writeInt(if (prepared == null) 0 else 1)
          if (prepared != null) {
            reply.writeInt(if (prepared.activeNight) 1 else 0)
            reply.writeLongArray(prepared.activeKey.toLongArray())
            reply.writeString(prepared.stagedPath)
            reply.writeInt(if (prepared.stagedNight) 1 else 0)
            reply.writeLongArray(prepared.stagedKey.toLongArray())
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
    private const val ATTACH_HOOK_ID = "keihome.theme.converted_icon.attach.v1"
    const val GRANT_DESCRIPTOR = "os.kei.hyper.home.IConvertedIconGrant.v1"
    const val REGISTER_CONVERTED = 0x004b4801
    const val AUTHORIZE_AUTO_APPLY = 0x004b4802
    const val REGISTER_APPEARANCE_VARIANTS = 0x004b4803
    const val SYNC_APPEARANCE_VARIANT = 0x004b4804
    private const val AUTO_APPLY_HOOK_ID = "keihome.theme.icon.auto_apply.v1"
    private const val RIGHTS_HOOK_ID = "keihome.theme.converted_icon.rights.v1"
    private const val DOWNLOAD_RIGHTS_HOOK_ID = "keihome.theme.converted_icon.download_rights.v1"
    private const val APPLY_HOOK_ID = "keihome.theme.converted_icon.apply.v1"
    private const val VALIDATE_HOOK_ID = "keihome.theme.converted_icon.validate.v1"
    private const val TAG = "KeiHomeThemeImport"
  }
}

/** Counts hook callbacks and delegated Binder calls; commit closes admission atomically. */
internal class ThemeIconImportGate {
  private val lifecycle = ThemeCompatibilityLifecycle()

  fun enter(): Boolean = lifecycle.enter()

  fun leave() = lifecycle.leave()

  fun retire(workersStopped: () -> Boolean = { true }, saveState: () -> Unit): Boolean =
    lifecycle.retire(workersStopped, saveState)
}
