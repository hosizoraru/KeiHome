package os.kei.hyper.home.xposed

import android.content.Context
import android.content.Intent
import android.app.BroadcastOptions
import os.kei.hyper.home.BuildConfig

/** The system-server publisher invalidates the existing host receivers once per palette. */
internal object CrossSurfaceIconEvents {
  const val PALETTE_CHANGED = "${BuildConfig.APPLICATION_ID}.ICON_PALETTE_CHANGED"

  fun publishPaletteChanged(context: Context) {
    context.sendBroadcast(
      Intent(PALETTE_CHANGED).setPackage("android")
        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
    )
    // SystemUI, OEM resolver, Sidebar and Settings run outside system_server's process/UID.
    // Their exported receivers require a system-only permission and verify sentFromUid == SYSTEM_UID.
    for (host in listOf(
      "com.android.systemui",
      HyperOsShareProfile.PACKAGE,
      HostAppIconRuntime.SIDEBAR_PACKAGE,
      HostAppIconRuntime.SETTINGS_PACKAGE,
    )) {
      context.sendBroadcast(
        Intent(PALETTE_CHANGED).setPackage(host)
          .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
        null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
      )
    }
  }
}
