package os.kei.hyper.home.xposed

import android.content.res.Configuration

/**
 * Tells only HyperOS Home that its icon theme changed, so it reopens `/data/system/theme/icons`.
 *
 * Home's Dart `ThemeChangeSignals.handleConfigEvent` calls `ThemeResourcesSystem.poke` only for a
 * theme configuration event. Only that path rechecks the icons package; ordinary lookups and
 * night-mode handling keep the already open archive. A *global* theme change cannot be used:
 * ActivityTaskManager relaunches every activity that does not handle CONFIG_THEME (0x400000),
 * observed for the Launcher and the foreground app whatever the MIUI flags. A process-level
 * ConfigurationChangeItem is not turned into an activity relaunch by the server.
 *
 * The flag is outside MiuiConfiguration's restart set (framework | icon | font | launcher |
 * third-party app), so Home's own restart policy does not rebuild the Launcher either.
 * Home's process sequence then runs ahead of the global one; [poke] always increments past
 * both, and the caller repeats it after a real icon-theme change ([ahead]) so Home still sees that.
 */
internal object LauncherThemePoke {
  const val LAUNCHER = "com.miui.home"
  /** MiuiConfiguration.THEME_FLAG_FREE_HOME_DEPRECATED: no component restarts for it. */
  private const val NO_RESTART_FLAG = 2_097_152L

  /** JVM-wide, so a hot-reloaded module generation continues from the same sequence. */
  private const val DELIVERED_KEY = "keihome.launcher.theme.sequence.v1"

  /**
   * Highest sequence delivered to Home. Global dispatches reset the server's last-reported copy
   * to the global value, while Home keeps the higher one, so the server copy cannot be trusted.
   * A reload must not restart from zero: Home would ignore a sequence it has already passed.
   */
  private var delivered: Int
    get() = System.getProperty(DELIVERED_KEY)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    set(value) { System.setProperty(DELIVERED_KEY, value.toString()) }

  /** A poke reached Home in this system_server, so Home's sequence may be ahead of the global one. */
  val ahead: Boolean get() = delivered > 0

  /** Takes the WM global lock. A caller may hold the UiModeManager lock (UiMode -> WM), no WM lock. */
  fun poke(launcherUid: Int): String {
    if (launcherUid < 0) return "no-launcher-uid"
    val atms = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
      .invoke(null, "activity_task") ?: return "no-atms"
    val atmsClass = ancestor(atms.javaClass, "com.android.server.wm.ActivityTaskManagerService")
    val lock = atmsClass.getDeclaredField("mGlobalLock").apply { isAccessible = true }.get(atms)!!
    val lookup = atmsClass.getDeclaredMethod("getProcessController", String::class.java, Int::class.javaPrimitiveType)
      .apply { isAccessible = true }
    synchronized(lock) {
      val process = lookup.invoke(atms, LAUNCHER, launcherUid) ?: return "no-process"
      val processClass = ancestor(process.javaClass, "com.android.server.wm.WindowProcessController")
      val config = Configuration(process.javaClass.getMethod("getConfiguration").invoke(process) as Configuration)
      val reported = processClass.getDeclaredField("mLastReportedConfiguration")
        .apply { isAccessible = true }.get(process) as Configuration
      val extraField = Configuration::class.java.getField("extraConfig")
      val extra = extraField.get(config) ?: return "no-miui-config"
      val sequence = extra.javaClass.getField("themeChanged")
      val flags = extra.javaClass.getField("themeChangedFlags")
      val last = synchronized(reported) { extraField.get(reported)?.let(sequence::getInt) } ?: 0
      val next = maxOf(sequence.getInt(extra), last, delivered) + 1
      sequence.setInt(extra, next)
      flags.setLong(extra, NO_RESTART_FLAG)
      processClass.getDeclaredMethod("dispatchConfiguration", Configuration::class.java)
        .apply { isAccessible = true }.invoke(process, config)
      delivered = next
      val state = runCatching {
        processClass.getDeclaredMethod("getReportedProcState").apply { isAccessible = true }.invoke(process)
      }.getOrNull()
      return "sent theme=$next procState=$state"
    }
  }

  /**
   * Ends Home's process for [userId] so the system starts a new one, whose engine reads every
   * icon from the current archive ([LauncherRebuildSchedule]). Uses
   * `ActivityManagerInternal.killProcess`, which records the exit and allows the restart.
   * Takes the ActivityManager lock; call it from a worker that holds no system lock.
   */
  fun restart(launcherUid: Int, userId: Int, reason: String): String {
    if (launcherUid < 0) return "no-launcher-uid"
    val uid = userId * PER_USER_RANGE + launcherUid % PER_USER_RANGE
    val ams = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
      .invoke(null, "activity") ?: return "no-ams"
    val loader = ams.javaClass.classLoader
    val internal = Class.forName("android.app.ActivityManagerInternal", false, loader)
    val service = Class.forName("com.android.server.LocalServices", false, loader)
      .getMethod("getService", Class::class.java).invoke(null, internal) ?: return "no-ami"
    internal.getMethod("killProcess", String::class.java, Int::class.javaPrimitiveType, String::class.java)
      .invoke(service, LAUNCHER, uid, reason)
    return "restarted uid=$uid"
  }

  private const val PER_USER_RANGE = 100_000

  private fun ancestor(type: Class<*>, name: String): Class<*> =
    generateSequence(type) { it.superclass }.firstOrNull { it.name == name }
      ?: error("Expected $name, found ${type.name}")
}
