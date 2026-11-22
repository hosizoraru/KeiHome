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
  SHIZUKU("moe.shizuku.privileged.api", "Shizuku", 0xff2c3b5b.toInt(), 0xff1b2339.toInt());

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
