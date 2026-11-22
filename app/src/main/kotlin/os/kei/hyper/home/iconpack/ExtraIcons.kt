package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.SharedPreferences
import os.kei.hyper.home.data.AppDisplayStorage

/** Paint choices for the adaptive layers in the installed APK. */
internal enum class ExtraIcon(
  val packageName: String,
  val label: String,
  val top: Int,
  val bottom: Int,
  val tint: Int? = null,
  val activity: String? = null,
) {
  SCENE("com.omarea.vtools", "Scene", 0xff29333a.toInt(), 0xff171c24.toInt(),
    activity = "com.omarea.vtools.activities.ActivityMain"),
  MT("bin.mt.plus", "MT Manager", 0xff303743.toInt(), 0xff1b2029.toInt()),
  LIB_CHECKER("com.absinthe.libchecker", "LibChecker", 0xff3c2c57.toInt(), 0xff241a38.toInt()),
  KERNEL_SU("me.weishu.kernelsu", "KernelSU", 0xff34363a.toInt(), 0xff1d1e22.toInt(),
    tint = 0xffe2e4e8.toInt()),
  UPDATER("top.yukonga.updater.kmp", "Updater", 0xff264674.toInt(), 0xff17263e.toInt(),
    tint = 0xffd7e9ff.toInt()),
  MEILOX("com.neoruaa.meilox", "MeiloX", 0xff423039.toInt(), 0xff291e27.toInt());

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
