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
import android.util.Log
import android.util.TypedValue
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

internal object IconProbeProtocol {
  const val ACTIVITY_INFOS = "activities"
  const val CONFIGURATION = "configuration"
  const val REQUIRES_REFRESH = "requires_refresh"
}

internal enum class IconSupportStatus {
  DETECTED,
  NOT_DETECTED,
  UNKNOWN,
}

internal data class IconResourceAssessment(
  val nightMode: IconSupportStatus,
  val monochrome: IconSupportStatus,
)

private fun IconSupportStatus.merge(other: IconSupportStatus): IconSupportStatus =
  when {
    this == IconSupportStatus.DETECTED || other == IconSupportStatus.DETECTED ->
      IconSupportStatus.DETECTED
    this == IconSupportStatus.UNKNOWN || other == IconSupportStatus.UNKNOWN ->
      IconSupportStatus.UNKNOWN
    else -> IconSupportStatus.NOT_DETECTED
  }

/**
 * Examines other apps' drawable resources outside both system_server and the launcher.
 * A failure is always reported as needing a refresh.
 */
class IconResourceProbeService : Service() {
  private lateinit var thread: HandlerThread
  private lateinit var messenger: Messenger
  private val executor = Executors.newFixedThreadPool(PROBE_THREADS, ProbeThreadFactory())

  override fun onCreate() {
    super.onCreate()
    thread = HandlerThread("KeiHomeIconProbe").also { it.start() }
    messenger = Messenger(Handler(thread.looper) { message ->
      if (message.sendingUid != Process.SYSTEM_UID || message.replyTo == null) {
        return@Handler true
      }
      val activities = try {
        val activities = message.data.getParcelableArrayList(
          IconProbeProtocol.ACTIVITY_INFOS, ActivityInfo::class.java,
        )
        activities
      } catch (_: Throwable) {
        null
      }
      val config = try {
        message.data.getParcelable(
          IconProbeProtocol.CONFIGURATION, Configuration::class.java,
        )
      } catch (_: Throwable) {
        null
      }
      val reply = message.replyTo
      if (activities == null || config == null || reply == null) {
        sendResult(reply, message.arg1, true)
        return@Handler true
      }
      executor.execute {
        val refresh = try {
          IconResourceInspector(packageManager).requiresRefresh(activities, config)
        } catch (failure: Throwable) {
          Log.w(TAG, "icon probe failed", failure)
          true
        }
        sendResult(reply, message.arg1, refresh)
      }
      true
    })
  }

  override fun onBind(intent: Intent?): IBinder = messenger.binder

  override fun onDestroy() {
    executor.shutdownNow()
    thread.quitSafely()
    super.onDestroy()
  }

  private fun sendResult(reply: Messenger?, request: Int, refresh: Boolean) {
    if (reply == null) return
    try {
      reply.send(Message.obtain().apply {
        arg1 = request
        data.putBoolean(IconProbeProtocol.REQUIRES_REFRESH, refresh)
      })
    } catch (_: Throwable) {
    }
  }

  private companion object {
    const val TAG = "KeiHomeIconProbe"
    const val PROBE_THREADS = 2
  }
}

internal class IconResourceInspector(private val packageManager: PackageManager) {
  fun requiresRefresh(activities: List<ActivityInfo>, base: Configuration): Boolean {
    return assess(activities, base).nightMode != IconSupportStatus.NOT_DETECTED
  }

  fun assess(activities: List<ActivityInfo>, base: Configuration): IconResourceAssessment {
    if (activities.isEmpty()) {
      return IconResourceAssessment(
        IconSupportStatus.UNKNOWN,
        IconSupportStatus.UNKNOWN,
      )
    }
    var nightMode = IconSupportStatus.NOT_DETECTED
    var monochrome = IconSupportStatus.NOT_DETECTED
    val light = Configuration(base).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        Configuration.UI_MODE_NIGHT_NO
    }
    val dark = Configuration(base).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        Configuration.UI_MODE_NIGHT_YES
    }
    val resourcesBySource = mutableMapOf<String, Pair<Resources, Resources>>()
    for (activity in activities) {
      val app = activity.applicationInfo ?: return unknownAssessment()
      val iconIds = setOf(activity.iconResource).filter { it != 0 }
      if (iconIds.isEmpty()) return unknownAssessment()
      val resources = try {
        resourcesBySource.getOrPut(app.sourceDir) {
          packageManager.getResourcesForApplication(app, light) to
            packageManager.getResourcesForApplication(app, dark)
        }
      } catch (_: Throwable) {
        return unknownAssessment()
      }
      for (iconId in iconIds) {
        val result = assessIcon(iconId, resources.first, resources.second)
        nightMode = nightMode.merge(result.nightMode)
        monochrome = monochrome.merge(result.monochrome)
        if (nightMode == IconSupportStatus.DETECTED &&
          monochrome == IconSupportStatus.DETECTED
        ) {
          return IconResourceAssessment(nightMode, monochrome)
        }
      }
    }
    return IconResourceAssessment(nightMode, monochrome)
  }

  private fun assessIcon(
    iconId: Int,
    lightResources: Resources,
    darkResources: Resources,
  ): IconResourceAssessment {
    return try {
      if (lightResources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
        Configuration.UI_MODE_NIGHT_NO ||
        darkResources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
          Configuration.UI_MODE_NIGHT_YES
      ) return unknownAssessment()
      val lightValue = TypedValue().also { lightResources.getValue(iconId, it, true) }
      val darkValue = TypedValue().also { darkResources.getValue(iconId, it, true) }
      val lightIcon = lightResources.getDrawableForDensity(
        iconId, lightResources.displayMetrics.densityDpi, null,
      ) ?: return unknownAssessment()
      val darkIcon = darkResources.getDrawableForDensity(
        iconId, darkResources.displayMetrics.densityDpi, null,
      ) ?: return unknownAssessment()
      val changing = lightValue.changingConfigurations or darkValue.changingConfigurations or
        lightIcon.changingConfigurations or darkIcon.changingConfigurations
      val graph = StableIconResourceGraph(lightResources, darkResources)
      val monochrome = graph.monochromeStatus(iconId)
      val night = when {
        changing and ActivityInfo.CONFIG_UI_MODE != 0 -> IconSupportStatus.DETECTED
        lightIcon.canApplyTheme() || darkIcon.canApplyTheme() ||
          lightIcon is Animatable || darkIcon is Animatable -> IconSupportStatus.UNKNOWN
        !graph.isStable(iconId) -> {
          val first = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
          val second = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
          try {
            lightIcon.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            darkIcon.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            lightIcon.draw(Canvas(first))
            darkIcon.draw(Canvas(second))
            if (!first.sameAs(second)) {
              IconSupportStatus.DETECTED
            } else {
              IconSupportStatus.UNKNOWN
            }
          } finally {
            first.recycle()
            second.recycle()
          }
        }
        else -> IconSupportStatus.NOT_DETECTED
      }
      IconResourceAssessment(night, monochrome)
    } catch (_: Throwable) {
      unknownAssessment()
    }
  }

  private fun unknownAssessment() = IconResourceAssessment(
    IconSupportStatus.UNKNOWN,
    IconSupportStatus.UNKNOWN,
  )

  private companion object {
    const val ICON_SIZE = 96
  }
}

private class ProbeThreadFactory : ThreadFactory {
  private var nextId = 0

  override fun newThread(task: Runnable): Thread =
    Thread(task, "KeiHomeIconProbe-${nextId++}").apply {
      isDaemon = true
      priority = Thread.NORM_PRIORITY - 1
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

  fun monochromeStatus(id: Int): IconSupportStatus = try {
    when (containsTag(id, "monochrome", 0, mutableSetOf())) {
      true -> IconSupportStatus.DETECTED
      false -> IconSupportStatus.NOT_DETECTED
      null -> IconSupportStatus.UNKNOWN
    }
  } catch (_: Throwable) {
    IconSupportStatus.UNKNOWN
  }

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

  private fun containsTag(
    id: Int,
    target: String,
    depth: Int,
    visitedIds: MutableSet<Int>,
  ): Boolean? {
    if (id == 0 || depth > MAX_DEPTH || !visitedIds.add(id)) return false
    val value = TypedValue()
    light.getValue(id, value, true)
    if (value.type != TypedValue.TYPE_STRING || value.string == null ||
      !value.string.toString().endsWith(".xml")
    ) return false
    light.getXml(id).use { parser ->
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
          if (parser.name == target) return true
          for (index in 0 until parser.attributeCount) {
            val dependency = parser.getAttributeResourceValue(index, 0)
            if (dependency != 0) {
              val result = containsTag(dependency, target, depth + 1, visitedIds)
              if (result == true) return true
              if (result == null) return null
            }
          }
        }
        event = parser.next()
      }
    }
    return false
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
