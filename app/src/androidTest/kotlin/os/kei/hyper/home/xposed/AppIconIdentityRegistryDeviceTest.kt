package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AppIconIdentityRegistryDeviceTest {
  @Test fun sharedPlaceholderDoesNotAcquireTheLastApplicationsIdentity() {
    val identities = AppIconIdentityRegistry()
    val shared = ColorDrawable(Color.WHITE)
    identities.remember(shared, "com.example.calendar")
    identities.remember(shared, "com.example.clock")
    assertNull(identities.find(shared, null))
    identities.remember(shared, "com.example.clock")
    assertNull(identities.find(shared, null))
  }

  @Test fun mutatedOrAmbiguousBitmapIsNotUsedAsAnApplicationHint() {
    val identities = AppIconIdentityRegistry()
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    identities.remember(bitmap, "com.example.first")
    assertEquals("com.example.first", identities.find(null, bitmap))
    identities.remember(bitmap, "com.example.second")
    assertNull(identities.find(null, bitmap))
    bitmap.eraseColor(Color.RED)
    assertNull(identities.find(null, bitmap))
    identities.remember(bitmap, "com.example.third")
    assertEquals("com.example.third", identities.find(null, bitmap))
  }

  @Test fun independentWrappersRetainIdentityWhileSharedUnownedPixelsDoNot() {
    val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    val pixels = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val first = BitmapDrawable(resources, pixels)
    val second = BitmapDrawable(resources, pixels)
    val identities = AppIconIdentityRegistry()
    identities.remember(first, "com.example.first")
    identities.remember(second, "com.example.second")
    assertEquals("com.example.first", identities.find(first, null))
    assertEquals("com.example.second", identities.find(second, null))
    assertNull(identities.find(null, pixels))
    identities.clear()
    assertNull(identities.find(first, null))
  }
}
