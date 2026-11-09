package os.kei.hyper.home.xposed

import android.content.Context
import android.content.Intent
import os.kei.hyper.home.BuildConfig

/** The system-server publisher invalidates the existing host receivers once per palette. */
internal object CrossSurfaceIconEvents {
  const val PALETTE_CHANGED = "${BuildConfig.APPLICATION_ID}.ICON_PALETTE_CHANGED"

  fun publishPaletteChanged(context: Context) {
    for (host in listOf("android", "com.android.systemui")) {
      context.sendBroadcast(
        Intent(PALETTE_CHANGED).setPackage(host)
          .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
      )
    }
  }
}
