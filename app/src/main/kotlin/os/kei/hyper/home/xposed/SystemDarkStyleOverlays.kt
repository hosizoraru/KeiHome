package os.kei.hyper.home.xposed

import android.content.Context
import android.os.IBinder
import android.util.TypedValue
import os.kei.hyper.home.data.SystemDarkStyle

/** Runtime resource overlays owned by the platform. Their night qualifier leaves day resources intact. */
internal class SystemDarkStyleOverlays(private val context: Context) {
  private enum class Target(val packageName: String) {
    FRAMEWORK("android"),
    SETTINGS("com.android.settings"),
    SYSTEM_UI("com.android.systemui"),
  }

  private val profiles = listOf(SystemDarkStyle.MEDIUM, SystemDarkStyle.GENTLE)
  private val identifier = Class.forName("android.content.om.OverlayIdentifier")
  private val overlay = Class.forName("android.content.om.FabricatedOverlay")
  private val overlayBuilder = Class.forName("${overlay.name}\$Builder")
  private val transaction = Class.forName("android.content.om.OverlayManagerTransaction")
  private val transactionBuilder = Class.forName("${transaction.name}\$Builder")
  private val manager = Class.forName("android.content.om.IOverlayManager")
  private val server = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
    .invoke(null, "overlay") as IBinder
  private val service = Class.forName("${manager.name}\$Stub").getMethod("asInterface", IBinder::class.java)
    .invoke(null, server)
  private val overlayInfo = manager.getMethod("getOverlayInfoByIdentifier", identifier, Int::class.javaPrimitiveType)
  private val isEnabled = Class.forName("android.content.om.OverlayInfo").getMethod("isEnabled")
  private val sets = overlayBuilder.getMethod("setResourceValue", String::class.java,
    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)

  fun read(): SystemDarkStyle {
    val current = inventory()
    return when {
      current.isEmpty() -> SystemDarkStyle.ENHANCED
      else -> checkNotNull(profiles.singleOrNull { matches(it, current) }) { "Incomplete dark style overlays" }
    }
  }

  fun restore() = apply(SystemDarkStyle.ENHANCED)

  fun apply(style: SystemDarkStyle) {
    val current = inventory()
    if (matches(style, current)) return
    update(style, current)
    if (runCatching { read() }.getOrNull() == style) return

    val prior = profiles.firstOrNull { matches(it, current) } ?: SystemDarkStyle.ENHANCED
    update(prior, inventory())
    error("Dark style overlays could not be enabled")
  }

  private fun inventory(): Map<Pair<SystemDarkStyle, Target>, Boolean> = buildMap {
    for (profile in profiles) for (target in Target.entries) {
      val info = overlayInfo.invoke(service, id(profile, target), OWNER_USER) ?: continue
      put(profile to target, isEnabled.invoke(info) == true)
    }
  }

  private fun matches(style: SystemDarkStyle, current: Map<Pair<SystemDarkStyle, Target>, Boolean>): Boolean =
    if (style == SystemDarkStyle.ENHANCED) current.isEmpty()
    else current.size == Target.entries.size && Target.entries.all { current[style to it] == true }

  private fun update(style: SystemDarkStyle, current: Map<Pair<SystemDarkStyle, Target>, Boolean>) {
    val builder = transactionBuilder.getConstructor().newInstance()
    for ((profile, target) in current.keys) {
      if (profile != style) transactionBuilder.getMethod("unregisterFabricatedOverlay", identifier)
        .invoke(builder, id(profile, target))
    }
    if (style != SystemDarkStyle.ENHANCED) for (target in Target.entries) {
      val fabricated = fabricate(style, target)
      transactionBuilder.getMethod("registerFabricatedOverlay", overlay).invoke(builder, fabricated)
      transactionBuilder.getMethod("setEnabled", identifier, Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType).invoke(builder, id(style, target), true, OWNER_USER)
    }
    manager.getMethod("commit", transaction).invoke(service, transactionBuilder.getMethod("build").invoke(builder))
  }

  private fun fabricate(style: SystemDarkStyle, target: Target): Any {
    val packageName = target.packageName
    val builder = overlayBuilder.getConstructor(String::class.java, String::class.java, String::class.java)
      .newInstance("android", name(style, target), packageName)
    val resources = context.packageManager.getResourcesForApplication(packageName)
    for ((entry, color) in colors(style, target)) {
      val qualifiedName = "$packageName:$entry"
      check(resources.getIdentifier(qualifiedName, null, null) != 0) { "Missing dark resource $qualifiedName" }
      sets.invoke(builder, qualifiedName, TypedValue.TYPE_INT_COLOR_ARGB8, color, "night")
    }
    return checkNotNull(overlayBuilder.getMethod("build").invoke(builder))
  }

  private fun id(style: SystemDarkStyle, target: Target): Any =
    identifier.getConstructor(String::class.java, String::class.java).newInstance("android", name(style, target))

  private fun name(style: SystemDarkStyle, target: Target): String = "KeiHomeDarkStyle${style.name}${target.name}"

  private fun colors(style: SystemDarkStyle, target: Target): Map<String, Int> {
    // The 8 and 20 L* ColorOS backgrounds correspond to approximately #181818 and #303030 in sRGB.
    val base = if (style == SystemDarkStyle.MEDIUM) 0x18 else 0x30
    fun gray(level: Int, alpha: Int = 0xff): Int = (alpha shl 24) or (level * 0x010101)
    return when (target) {
      Target.FRAMEWORK -> mapOf(
        "color/background_dark" to gray(base),
        "color/system_background_dark" to gray(base),
        "color/system_surface_dark" to gray(base),
        "color/system_surface_container_lowest_dark" to gray(base),
        "color/system_surface_container_low_dark" to gray(base + 4),
        "color/system_surface_container_dark" to gray(base + 8),
        "color/system_surface_container_high_dark" to gray(base + 12),
        "color/system_surface_container_highest_dark" to gray(base + 16),
      )
      Target.SETTINGS -> mapOf(
        "color/settings_background_color" to gray(base),
        "color/miuix_default_color_surface_dark" to gray(base),
        "color/miuix_default_color_surface_low_dark" to gray(base),
        "color/miuix_default_color_surface_container_dark" to gray(base + 8),
        "color/miuix_preference_card_group_background_color_dark" to gray(base + 16),
        "drawable/miuix_appcompat_settings_window_bg_dark" to gray(base),
      )
      Target.SYSTEM_UI -> mapOf(
        "color/notification_bg_color" to gray(base + 4, 0xf7),
        "color/notification_focus_bg_color" to gray(base + 8),
        "color/miuix_default_color_surface_dark" to gray(base),
      )
    }
  }

  companion object {
    const val OWNER_USER = 0
  }
}
