package os.kei.hyper.home.xposed

import android.graphics.drawable.Icon
import android.os.Bundle

/**
 * Market copies its own application icon into these two slots. Its plain
 * pic_app_icon is the downloaded app's artwork and must survive.
 */
internal object SystemUiIslandPictureBindings {
  fun remember(pictures: Bundle, extras: Bundle, processUser: Int, bind: (Icon, String) -> Unit) {
    val name = extras.getString("miui.pkg.name")
    if (name != "com.xiaomi.market" || !extras.containsKey("miui.user.id") ||
      extras.getInt("miui.user.id") != processUser) return
    for (key in arrayOf(
      "miui.focus.pic_app_icon_small_island",
      "miui.focus.pic_app_icon_big_island",
    )) {
      pictures.getParcelable(key, Icon::class.java)?.let { bind(it, name) }
    }
  }
}
