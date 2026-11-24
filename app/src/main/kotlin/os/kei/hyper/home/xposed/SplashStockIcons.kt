package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** HyperOS default-theme icon producer, shared with the framework package manager. */
internal object SplashStockIcons {
  private val getIcon by lazy {
    try {
      Class.forName("miui.content.res.IconCustomizer").getMethod(
        "getIcon", Context::class.java, String::class.java, String::class.java,
        Int::class.javaPrimitiveType, ApplicationInfo::class.java, Boolean::class.javaPrimitiveType,
      ).takeIf { Modifier.isStatic(it.modifiers) && it.returnType == Drawable::class.java }
    } catch (_: ReflectiveOperationException) {
      null
    } catch (_: LinkageError) {
      null
    }
  }

  fun load(context: Context, activity: ActivityInfo): Drawable? {
    return try {
      getIcon?.invoke(null, context, activity.packageName, activity.name,
        activity.iconResource, activity.applicationInfo, false) as? Drawable
    } catch (failure: InvocationTargetException) {
      val cause = failure.targetException
      if (cause !is Exception && cause !is LinkageError) throw cause
      null
    } catch (_: ReflectiveOperationException) {
      null
    }
  }
}
