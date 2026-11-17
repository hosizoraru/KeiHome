package os.kei.hyper.home.xposed

import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemUiIslandPictureBindingsDeviceTest {
  @Test fun onlyVerifiedIssuerIdentitySlotsEnterTheSharedBindings() {
    val small = Icon.createWithResource("android", android.R.drawable.sym_def_app_icon)
    val big = Icon.createWithResource("android", android.R.drawable.sym_def_app_icon)
    val content = Icon.createWithResource("android", android.R.drawable.ic_delete)
    val pictures = Bundle().apply {
      putParcelable("miui.focus.pic_app_icon_small_island", small)
      putParcelable("miui.focus.pic_app_icon_big_island", big)
      putParcelable("miui.focus.pic_app_icon", content)
      putParcelable("miui.focus.pic_install_success_small_icon", content)
      putParcelable("miui.focus.pic_app_icon_small_aod", content)
    }
    val extras = Bundle().apply {
      putString("miui.pkg.name", "com.xiaomi.market")
      putInt("miui.user.id", 0)
    }
    val owned = mutableListOf<Icon>()
    fun collect() = SystemUiIslandPictureBindings.remember(pictures, extras, 0) { icon, name ->
      assertEquals("com.xiaomi.market", name)
      owned.add(icon)
    }
    collect()
    assertEquals(listOf(small, big), owned)
    assertSame(content, pictures.getParcelable("miui.focus.pic_app_icon", Icon::class.java))
    owned.clear()
    extras.putInt("miui.user.id", 10)
    collect()
    assertTrue(owned.isEmpty())
    extras.remove("miui.user.id")
    collect()
    assertTrue(owned.isEmpty())
    extras.putInt("miui.user.id", 0)
    extras.putString("miui.pkg.name", "another.app")
    collect()
    assertTrue(owned.isEmpty())
  }
}
