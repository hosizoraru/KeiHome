package os.kei.hyper.home.xposed

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HyperOsShareIdentityTest {
  private fun resolveInfo(
    pkg: String,
    activityName: String,
    appIcon: Int,
    activityIcon: Int,
    uid: Int = 10_123,
  ): ResolveInfo = ResolveInfo().apply {
    activityInfo = ActivityInfo().apply {
      packageName = pkg
      name = activityName
      icon = activityIcon
      applicationInfo = ApplicationInfo().apply {
        packageName = pkg
        icon = appIcon
        this.uid = uid
      }
    }
  }

  @Test fun singleCustomActionTargetIsPreservedWhileGroupedAppUsesAppIcon() {
    val appleShare = resolveInfo(
      pkg = "com.miui.mishare.connectivity",
      activityName = "com.xiaomi.sharop.AirDropShareTrampolineActivity",
      appIcon = 0x7f0801bc,
      activityIcon = 0x7f080609,
    )
    assertNull(
      "Single custom-icon share action must retain its native artwork",
      shareAppIdentity(appleShare, user = 0, groupedApp = false),
    )

    val wechatGroup = resolveInfo(
      pkg = "com.tencent.mm",
      activityName = "com.tencent.mm.ui.tools.ShareImgUI",
      appIcon = 0x7f0d0001,
      activityIcon = 0x7f080df5,
    )
    assertNull(
      "Single custom-icon WeChat sub-action preserves its own icon",
      shareAppIdentity(wechatGroup, user = 0, groupedApp = false),
    )
    assertEquals(
      "Grouped MultiDisplayResolveInfo resolves to package-level app icon",
      "com.tencent.mm" to "",
      shareAppIdentity(wechatGroup, user = 0, groupedApp = true),
    )
  }

  @Test fun workProfileTargetsRemainNativeEvenWhenGrouped() {
    val workWechat = resolveInfo(
      pkg = "com.tencent.mm",
      activityName = "com.tencent.mm.ui.tools.ShareImgUI",
      appIcon = 0x7f0d0001,
      activityIcon = 0x7f080df5,
      uid = 10_10_123,
    )
    assertNull(shareAppIdentity(workWechat, user = 0, groupedApp = true))
  }
}
