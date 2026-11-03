package os.kei.hyper.home.xposed

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Uses the resolved native apply task, with an import-bound, single-use authorization. */
internal class NativeIconAutoApply(private val apply: Method) {
  fun resourceReady(activity: Activity, resource: Any, code: String) {
    val token = activity.intent.getStringExtra(EXTRA) ?: return
    if (code != "icons" || activity.isFinishing || activity.isDestroyed) return
    val id = resource.javaClass.getMethod("getLocalId").invoke(resource) as? String ?: return
    val store = activity.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    if (!validAutoApplyTicket(token, id, store.getString("token", null), store.getString("local", null),
        store.getLong("at", -1), SystemClock.elapsedRealtime(), store.getBoolean("used", true))) return
    check(store.edit().putBoolean("used", true).commit())
    android.util.Log.i("KeiHomeAutoApply", "Starting native icons apply token=$token component=$id")
    activity.intent.removeExtra(EXTRA)
    // ApplyThemeTask mutates the existing ThemeStatus and emits nothing for a null value.
    val statusClass = Class.forName("com.android.thememanager.router.app.entity.ThemeStatus", false,
      resource.javaClass.classLoader)
    val initial = statusClass.getConstructor(String::class.java).newInstance(token)
    val live = apply.parameterTypes[6].getDeclaredConstructor(Any::class.java).newInstance(initial)
    val observe = live.javaClass.methods.single {
      it.parameterCount == 2 && it.parameterTypes[0].isInstance(activity) && it.parameterTypes[1].isInterface
    }
    val observerType = observe.parameterTypes[1]
    val change = observerType.methods.single { it.parameterCount == 1 && it.returnType == Void.TYPE }.name
    val observer = Proxy.newProxyInstance(observerType.classLoader, arrayOf(observerType)) { proxy, method, args ->
      when (method.name) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        "toString" -> "KeiHomeNativeApplyObserver"
        else -> {
          if (method.name == change) {
            val status = args?.firstOrNull()
            if (status != null && status.javaClass.getField("status").getInt(status) == 98 &&
              !activity.isFinishing && !activity.isDestroyed) {
              android.util.Log.i("KeiHomeAutoApply", "Native icons apply task completed token=$token component=$id")
              activity.window.decorView.post {
                if (!activity.isDestroyed && !activity.isFinishing) {
                  activity.setResult(Activity.RESULT_OK)
                  activity.finish()
                }
              }
            }
          }
          null
        }
      }
    }
    // Lifecycle-bound observation is removed by the host on destroy. The host owns the
    // asynchronous apply task and its errors; there is no polling or delayed UI click.
    observe.invoke(live, activity, observer)
    apply.invoke(null, activity, "icons", resource, 1, 63, false, live, false, false, true, true)
  }

  companion object {
    private const val STORE = "keihome_auto_apply"
    private const val EXTRA = "keihome.AUTO_APPLY_TOKEN"
    fun authorize(context: Context, token: String, id: String): Boolean {
      require(token.matches(Regex("[0-9a-f-]{36}")) && id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
      val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
      if (store.getString("token", null) == token)
        return store.getString("local", null) == id && !store.getBoolean("used", true)
      return store.edit().putString("token", token).putString("local", id)
        .putLong("at", SystemClock.elapsedRealtime()).putBoolean("used", false).commit()
    }
  }
}

internal fun validAutoApplyTicket(token: String, id: String, expectedToken: String?, expectedId: String?,
  at: Long, now: Long, used: Boolean): Boolean =
  !used && token == expectedToken && id == expectedId && at >= 0 && now >= at && now - at <= 600_000
