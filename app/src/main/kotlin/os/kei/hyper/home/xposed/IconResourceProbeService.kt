package os.kei.hyper.home.xposed

import android.app.Service
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.DisplayMetrics
import android.util.TypedValue
import org.xmlpull.v1.XmlPullParser

internal object IconProbeProtocol {
  const val ACTIVITY_INFOS = "activities"
  const val CONFIGURATION = "configuration"
  const val REQUIRES_REFRESH = "requires_refresh"
}

/**
 * Examines other apps' drawable resources outside both system_server and the launcher.
 * A failure is always reported as needing a refresh.
 */
class IconResourceProbeService : Service() {
  private lateinit var thread: HandlerThread
  private lateinit var messenger: Messenger

  override fun onCreate() {
    super.onCreate()
    thread = HandlerThread("KeiHomeIconProbe").also { it.start() }
    messenger = Messenger(Handler(thread.looper) { message ->
      if (message.sendingUid != Process.SYSTEM_UID || message.replyTo == null) {
        return@Handler true
      }
      val refresh = try {
        val activities = message.data.getParcelableArrayList(
          IconProbeProtocol.ACTIVITY_INFOS, ActivityInfo::class.java,
        )
        val config = message.data.getParcelable(
          IconProbeProtocol.CONFIGURATION, Configuration::class.java,
        )
        if (activities.isNullOrEmpty() || config == null) {
          true
        } else {
          IconResourceInspector(packageManager).requiresRefresh(activities, config)
        }
      } catch (_: Throwable) {
        true
      }
      try {
        message.replyTo.send(Message.obtain().apply {
          arg1 = message.arg1
          data.putBoolean(IconProbeProtocol.REQUIRES_REFRESH, refresh)
        })
      } catch (_: Throwable) {
      }
      true
    })
  }

  override fun onBind(intent: Intent?): IBinder = messenger.binder

  override fun onDestroy() {
    thread.quitSafely()
    super.onDestroy()
  }
}

internal class IconResourceInspector(private val packageManager: PackageManager) {
  fun requiresRefresh(activities: List<ActivityInfo>, base: Configuration): Boolean {
    for (activity in activities) {
      val app = activity.applicationInfo ?: return true
      val iconIds = setOf(activity.iconResource, app.icon).filter { it != 0 }
      if (iconIds.isEmpty()) return true
      for (iconId in iconIds) {
        val light = Configuration(base).apply {
          uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_NO
        }
        val dark = Configuration(base).apply {
          uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_YES
        }
        val lightResources = packageManager.getResourcesForApplication(app, light)
        val darkResources = packageManager.getResourcesForApplication(app, dark)
        if (lightResources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
          Configuration.UI_MODE_NIGHT_NO ||
          darkResources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
          Configuration.UI_MODE_NIGHT_YES
        ) return true
        val lightValue = TypedValue().also { lightResources.getValue(iconId, it, true) }
        val darkValue = TypedValue().also { darkResources.getValue(iconId, it, true) }
        val lightIcon = lightResources.getDrawableForDensity(
          iconId, DisplayMetrics.DENSITY_DEFAULT, null,
        ) ?: return true
        val darkIcon = darkResources.getDrawableForDensity(
          iconId, DisplayMetrics.DENSITY_DEFAULT, null,
        ) ?: return true
        val changing = lightValue.changingConfigurations or darkValue.changingConfigurations or
          lightIcon.changingConfigurations or darkIcon.changingConfigurations
        if (changing and ActivityInfo.CONFIG_UI_MODE != 0 ||
          lightIcon.canApplyTheme() || darkIcon.canApplyTheme() ||
          lightIcon is Animatable || darkIcon is Animatable
        ) return true
        if (!StableIconResourceGraph(lightResources, darkResources).isStable(iconId)) return true
        val first = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        try {
          lightIcon.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
          darkIcon.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
          lightIcon.draw(Canvas(first))
          darkIcon.draw(Canvas(second))
          if (!first.sameAs(second)) return true
        } finally {
          first.recycle()
          second.recycle()
        }
      }
    }
    return false
  }

  private companion object {
    const val ICON_SIZE = 96
  }
}

/**
 * Only skips resources whose supported XML dependency graph is completely stable.
 * Unknown drawable types, theme attributes, resource cycles and limits fail open.
 */
internal class StableIconResourceGraph(
  private val light: Resources,
  private val dark: Resources,
) {
  private val visited = mutableSetOf<Int>()
  private val active = mutableSetOf<Int>()

  fun isStable(id: Int): Boolean = inspect(id, 0)

  private fun inspect(id: Int, depth: Int): Boolean {
    if (id == 0 || depth > MAX_DEPTH || active.contains(id)) return false
    if (visited.contains(id)) return true
    if (visited.size >= MAX_RESOURCES) return false
    active.add(id)
    val stable = try {
      val first = TypedValue().also { light.getValue(id, it, true) }
      val second = TypedValue().also { dark.getValue(id, it, true) }
      if ((first.changingConfigurations or second.changingConfigurations) and
        ActivityInfo.CONFIG_UI_MODE != 0 ||
        first.type != second.type || first.data != second.data ||
        first.assetCookie != second.assetCookie || first.string != second.string
      ) {
        false
      } else when (first.type) {
        TypedValue.TYPE_STRING -> {
          val path = first.string?.toString() ?: return false
          when {
            path.endsWith(".xml") -> inspectXml(id, depth + 1)
            IMAGE_SUFFIXES.any { path.endsWith(it) } -> true
            else -> false
          }
        }
        in TypedValue.TYPE_FIRST_INT..TypedValue.TYPE_LAST_INT,
        TypedValue.TYPE_FLOAT,
        TypedValue.TYPE_DIMENSION,
        TypedValue.TYPE_FRACTION -> true
        else -> false
      }
    } catch (_: Throwable) {
      false
    } finally {
      active.remove(id)
    }
    if (stable) visited.add(id)
    return stable
  }

  private fun inspectXml(id: Int, depth: Int): Boolean {
    light.getXml(id).use { parser ->
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
          if (parser.name !in SUPPORTED_TAGS) return false
          for (index in 0 until parser.attributeCount) {
            val value = parser.getAttributeValue(index) ?: return false
            if (value.startsWith("?")) return false
            if (value.startsWith("@") && value != "@null") {
              val dependency = parser.getAttributeResourceValue(index, 0)
              if (dependency == 0 || !inspect(dependency, depth)) return false
            }
          }
        }
        event = parser.next()
      }
    }
    return true
  }

  private companion object {
    const val MAX_DEPTH = 16
    const val MAX_RESOURCES = 128
    val IMAGE_SUFFIXES = listOf(".png", ".webp", ".jpg", ".jpeg")
    val SUPPORTED_TAGS = setOf(
      "adaptive-icon", "background", "foreground", "monochrome",
      "vector", "group", "path", "clip-path",
    )
  }
}
