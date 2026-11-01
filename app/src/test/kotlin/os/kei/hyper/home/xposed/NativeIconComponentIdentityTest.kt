package os.kei.hyper.home.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeIconComponentIdentityTest {
  private val child = "9a25f784-7cc5-45d7-8068-6fd3c877c63f"
  private val hash = "7e6e775172d1fb5621cb990dea3e23243e5f0dea"
  private val path = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data/meta/icons/$child.mrm"

  @Test fun appliedCompoundResolvesToItsImportedIconChild() {
    // The same observed row's local_id is the different Compound parent b7625ba6-....
    assertEquals(child, NativeIconComponentIdentity.fromProvider(path, hash))
  }

  @Test fun alternateStorageMountDoesNotChangeComponentIdentity() {
    assertEquals(child, NativeIconComponentIdentity.fromProvider(
      path.replace("/storage/emulated/0", "/sdcard"), hash))
  }

  @Test fun missingHashOrANonIconPathCannotEstablishAssociation() {
    assertNull(NativeIconComponentIdentity.fromProvider(path, null))
    assertNull(NativeIconComponentIdentity.fromProvider(path.replace("/icons/", "/wallpaper/"), hash))
  }

  @Test fun arbitraryTitlesOrInvalidMetadataNamesAreNotComponentIds() {
    assertNull(NativeIconComponentIdentity.fromProvider(path.replace(child, "ColorOS Classic"), hash))
    assertNull(NativeIconComponentIdentity.fromProvider(path.replace(".mrm", ".png"), hash))
  }
}
