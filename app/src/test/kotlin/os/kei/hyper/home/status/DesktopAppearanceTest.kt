package os.kei.hyper.home.status

import org.junit.Assert.*
import org.junit.Test

class DesktopAppearanceTest {
  private class Port(var canonical: Int = 0x21) : DesktopAppearanceRefresh.Port {
    var current = canonical
    val writes = mutableListOf<Int>()
    var duringPulse: () -> Unit = {}
    var failFirstWrite = false
    var failRestore = false
    override fun canonicalMode() = canonical
    override fun currentMode() = current
    override fun applyMode(mode: Int) {
      writes += mode
      if (failRestore && writes.size > 1) error("restore unavailable")
      current = mode
      if (failFirstWrite && writes.size == 1) error("reply lost after configuration changed")
    }
    override fun settle() = duringPulse()
  }

  @Test fun pulseRestoresBothNightStatesWithoutASelectedModeSetter() {
    for (original in listOf(0x11, 0x21, 0x13, 0x23)) {
      val port = Port(original)
      assertEquals(AppearanceRefreshResult.REFRESHED, DesktopAppearanceRefresh.run(port) { true })
      assertEquals(original, port.current)
      assertEquals(original xor 0x30, port.writes.first())
    }
  }

  @Test fun restoreFollowsScheduledOrUserChangeDuringPulse() {
    val port = Port()
    port.duringPulse = { port.canonical = 0x13 }
    assertEquals(AppearanceRefreshResult.REFRESHED, DesktopAppearanceRefresh.run(port) { true })
    assertEquals(0x13, port.current)
  }

  @Test fun restoresEvenWhenFirstMutationThrowsAfterTakingEffect() {
    val port = Port().apply { failFirstWrite = true }
    assertEquals(AppearanceRefreshResult.FAILED, DesktopAppearanceRefresh.run(port) { true })
    assertEquals(port.canonical, port.current)
  }

  @Test fun interruptsStillRestoreAndRetainInterruption() {
    val port = Port().apply { duringPulse = { throw InterruptedException() } }
    try {
      assertEquals(AppearanceRefreshResult.FAILED, DesktopAppearanceRefresh.run(port) { true })
      assertEquals(port.canonical, port.current)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally { Thread.interrupted() }
  }

  @Test fun failedRestoreIsNotASuccessReceipt() {
    val port = Port().apply { failRestore = true }
    assertEquals(AppearanceRefreshResult.RESTORE_FAILED, DesktopAppearanceRefresh.run(port) { true })
  }

  @Test fun unknownModesAndSupersededRequestsCannotMutate() {
    val unknown = Port(1)
    assertEquals(AppearanceRefreshResult.UNSUPPORTED, DesktopAppearanceRefresh.run(unknown) { true })
    assertTrue(unknown.writes.isEmpty())
    val stale = Port()
    assertEquals(AppearanceRefreshResult.FAILED, DesktopAppearanceRefresh.run(stale) { false })
    assertTrue(stale.writes.isEmpty())
  }

  @Test fun wireRoundTripAndFreshness() {
    val report = DesktopAppearanceReport("request-1", 0, 100, 0, true, true, false, false, 801029259)
    assertEquals(report, DesktopAppearanceReport.decode(report.encode()))
    val request = RuntimeStatusRequest("request-1", 0, 90, desktopAction = DesktopAppearanceAction.INSPECT)
    assertTrue(report.matches(request, 101))
    assertFalse(report.matches(request.copy(id = "old"), 101))
    assertFalse(report.matches(request.copy(userId = 10), 101))
    assertFalse(report.matches(request, 99))
    assertFalse(report.matches(request, 20_000))
    assertNull(DesktopAppearanceReport.decode(report.encode().replace("|0|1|", "|0|bad|")))
  }

  private fun wallpaper(listeners: String, count: Int = listeners.lines().count { it.isNotBlank() }) = """
    SERVICE com.miui.miwallpaper/.server.MiuiWallPaperControllerService 1 pid=100 user=0
      miuiWallpaperData = MiuiWallpaperData{which=1, partIsDeep ={status_bar_rect=1, clock_style_rect=1}}
      mWallpaperChangeListeners.size=$count
    $listeners
  """.trimIndent()

  @Test fun readsRegionalDataAndLiveHomeSubscriptionSeparately() {
    val absent = wallpaper("listener: WallpaperRemoteCallback{which=3, packageName=com.android.systemui, callback=x}, binderAlive=true")
    assertEquals(true to false, DesktopAppearanceDiagnostics.wallpaper(absent, 0, "com.miui.home"))
    val found = wallpaper("listener: WallpaperRemoteCallback{which=1, packageName=com.miui.home, callback=x}, binderAlive=true")
    assertEquals(true to true, DesktopAppearanceDiagnostics.wallpaper(found, 0, "com.miui.home"))
    assertEquals(null to null, DesktopAppearanceDiagnostics.wallpaper(found, 10, "com.miui.home"))
    assertNull(DesktopAppearanceDiagnostics.wallpaper(wallpaper("", count = 2), 0, "com.miui.home").second)
  }

  @Test fun minimizedActualDumpsDistinguishBothLauncherVersions() {
    fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream(
      "/desktop-appearance/$name.txt")).bufferedReader().use { it.readText() }
    for ((name, subscribed) in listOf("hyperos-9259" to true, "hyperos-8602" to false)) {
      assertEquals(true to subscribed,
        DesktopAppearanceDiagnostics.wallpaper(fixture("$name-wallpaper"), 0, "com.miui.home"))
      assertEquals(!subscribed,
        DesktopAppearanceDiagnostics.homeDarkIcons(fixture("$name-window"), 0, "com.miui.home"))
    }
  }

  @Test fun deadOrLockscreenOnlySubscriptionsDoNotCount() {
    for (listener in listOf(
      "listener: WallpaperRemoteCallback{which=1, packageName=com.miui.home, callback=x}, binderAlive=false",
      "listener: WallpaperRemoteCallback{which=2, packageName=com.miui.home, callback=x}, binderAlive=true",
      "listener: WallpaperRemoteCallback{which=1, packageName=com.miui.home.other, callback=x}, binderAlive=true",
    )) assertEquals(false, DesktopAppearanceDiagnostics.wallpaper(wallpaper(listener), 0, "com.miui.home").second)
  }

  @Test fun homeWindowRequestDoesNotUseTheForegroundSettingsWindow() {
    fun window(pkg: String, appearance: String) = """
  Window #1 Window{abc u0 $pkg/.Main}:
    mDisplayId=0
    mAttrs={(0,0) ty=BASE_APPLICATION
      $appearance}
""".trimIndent().prependIndent("  ")
    val home = window("com.miui.home", "")
    val settings = window("com.android.settings", "apr=LIGHT_STATUS_BARS")
    assertEquals(false, DesktopAppearanceDiagnostics.homeDarkIcons(home + settings, 0, "com.miui.home"))
    assertEquals(true, DesktopAppearanceDiagnostics.homeDarkIcons(
      window("com.miui.home", "apr=LIGHT_STATUS_BARS"), 0, "com.miui.home"))
    assertNull(DesktopAppearanceDiagnostics.homeDarkIcons(settings, 0, "com.miui.home"))
    assertNull(DesktopAppearanceDiagnostics.homeDarkIcons(home, 10, "com.miui.home"))
  }
}
