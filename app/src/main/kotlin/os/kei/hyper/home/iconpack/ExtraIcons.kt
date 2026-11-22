package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppDisplayStorage

/** Paint choices for the artwork in the installed APK. */
internal enum class ExtraIcon(
  val packageName: String,
  val label: String,
  val top: Int,
  val bottom: Int,
  val tint: Int? = null,
  val activity: String? = null,
  @param:StringRes val labelRes: Int = 0,
  val flatArtwork: ExtraFlatArtwork? = null,
  val applicationIconOnly: Boolean = false,
) {
  SCENE("com.omarea.vtools", "Scene", 0xff29333a.toInt(), 0xff171c24.toInt(),
    activity = "com.omarea.vtools.activities.ActivityMain"),
  MT("bin.mt.plus", "MT Manager", 0xff303743.toInt(), 0xff1b2029.toInt(),
    labelRes = R.string.extra_icons_mt),
  LIB_CHECKER("com.absinthe.libchecker", "LibChecker", 0xff3c2c57.toInt(), 0xff241a38.toInt()),
  KERNEL_SU("me.weishu.kernelsu", "KernelSU", 0xff34363a.toInt(), 0xff1d1e22.toInt(),
    tint = 0xffe2e4e8.toInt()),
  UPDATER("top.yukonga.updater.kmp", "Updater", 0xff264674.toInt(), 0xff17263e.toInt(),
    tint = 0xffd7e9ff.toInt()),
  MEILOX("com.neoruaa.meilox", "MeiloX", 0xff423039.toInt(), 0xff291e27.toInt()),
  DOWNLOADS("com.android.providers.downloads.ui", "Downloads", 0xff2f4226.toInt(), 0xff19251a.toInt(),
    tint = 0xffd8f7ae.toInt(), labelRes = R.string.extra_icons_downloads,
    flatArtwork = ExtraFlatArtwork.LIGHT_MARK),
  DOWNLOAD_PROVIDER("com.android.providers.downloads", "Download Manager", 0xff283c33.toInt(),
    0xff162820.toInt(), tint = 0xffc8f3ac.toInt(), labelRes = R.string.extra_icons_download_provider,
    flatArtwork = ExtraFlatArtwork.LIGHT_MARK, applicationIconOnly = true),
  WALLET("com.mipay.wallet", "Wallet", 0xff363146.toInt(), 0xff201c2d.toInt(),
    labelRes = R.string.extra_icons_wallet, flatArtwork = ExtraFlatArtwork.WALLET_CARD),
  XIAOMI_COMMUNITY("com.xiaomi.vipaccount", "Xiaomi Community", 0xff502c29.toInt(), 0xff2c1b1d.toInt(),
    tint = 0xffffeee8.toInt(), labelRes = R.string.extra_icons_xiaomi_community,
    flatArtwork = ExtraFlatArtwork.LIGHT_MARK),
  SERVICES_FEEDBACK("com.miui.miservice", "Services & feedback", 0xff24394c.toInt(), 0xff17232e.toInt(),
    labelRes = R.string.extra_icons_services_feedback, flatArtwork = ExtraFlatArtwork.BLUE_BUBBLE),
  IT_HOME("com.ruanmei.ithome", "IT Home", 0xff512c33.toInt(), 0xff2d1d25.toInt(),
    labelRes = R.string.extra_icons_it_home),
  SHIZUKU("moe.shizuku.privileged.api", "Shizuku", 0xff2c3b5b.toInt(), 0xff1b2339.toInt()),
  KEYGUARD("com.artemchep.keyguard", "Keyguard", 0xff363758.toInt(), 0xff202135.toInt()),
  KEY_ATTESTATION("io.github.vvb2060.keyattestation", "Key Attestation",
    0xff34434e.toInt(), 0xff1e2833.toInt(), tint = 0xffd9e9f0.toInt(),
    labelRes = R.string.extra_icons_key_attestation),
  VPN_HOTSPOT("be.mygod.vpnhotspot", "VPN Hotspot", 0xff304551.toInt(), 0xff192832.toInt(),
    labelRes = R.string.extra_icons_vpn_hotspot),
  MUSIC("com.miui.player", "Music", 0xff243e3f.toInt(), 0xff162729.toInt(),
    labelRes = R.string.extra_icons_music, flatArtwork = ExtraFlatArtwork.MUSIC_MARK),
  SALT_PLAYER("com.salt.music", "Salt Player", 0xff40364c.toInt(), 0xff261f32.toInt()),
  ANYWHERE("com.absinthe.anywhere_", "Anywhere-", 0xff343c4e.toInt(), 0xff1d2433.toInt(),
    activity = "com.absinthe.anywhere_.MainAliasActivity"),
  LOW_LEVEL_DETECTOR("net.imknown.android.forefrontinfo", "LowLevelDetector",
    0xff304237.toInt(), 0xff192920.toInt(), labelRes = R.string.extra_icons_low_level_detector),
  FILEYAN("com.mgenware.fileyan", "Fileyan", 0xff354355.toInt(), 0xff1e2939.toInt(),
    labelRes = R.string.extra_icons_fileyan),
  DEV_CHECK("flar2.devcheck", "DevCheck", 0xff32433c.toInt(), 0xff1b2926.toInt()),
  UU_REMOTE("com.netease.uuremote", "UU Remote", 0xff352c51.toInt(), 0xff211a36.toInt(),
    labelRes = R.string.extra_icons_uu_remote),
  OPEN_DISPLAY("com.peetzweg.opendisplay", "OpenDisplay", 0xff35394b.toInt(), 0xff212535.toInt(),
    flatArtwork = ExtraFlatArtwork.OPEN_DISPLAY);

  fun accepts(target: IconPackComponent): Boolean =
    target.packageName == packageName &&
      (activity == null || target.className == null || activity == target.className)
}

internal object ExtraIcons {
  const val KEY = "extra_icon_packages"
  private val supported = ExtraIcon.entries.mapTo(hashSetOf()) { it.packageName }

  fun normalize(packages: Set<String>): Set<String> = packages.intersect(supported)

  fun read(preferences: SharedPreferences): Set<String> =
    normalize(runCatching { preferences.getStringSet(KEY, null) }.getOrNull().orEmpty())

  fun snapshot(context: Context): Set<String> = read(AppDisplayStorage.open(context))
}
