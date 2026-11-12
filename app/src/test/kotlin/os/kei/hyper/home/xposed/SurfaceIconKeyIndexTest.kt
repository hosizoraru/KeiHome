package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class SurfaceIconKeyIndexTest {
  @Test fun equalNamesReuseKeysWithoutMergingActivities() {
    val keys = SurfaceIconKeyIndex()
    val name = "org.telegram.messenger"
    val bare = keys.retain(name, null)
    val default = keys.retain(name, "$name.DefaultIcon")
    val alternate = keys.retain(name, "$name.AlternateIcon")
    repeat(10_000) {
      assertSame(bare, keys.retain(String(name.toCharArray()), null))
      assertSame(default, keys.find(name, "$name.DefaultIcon"))
      assertSame(alternate, keys.find(name, "$name.AlternateIcon"))
    }
    assertEquals(3, keys.size)
  }

  @Test fun lookupMissesDoNotAdmitUnknownTargets() {
    val keys = SurfaceIconKeyIndex()
    repeat(10_000) { assertNull(keys.find("com.example.app$it", null)) }
    assertEquals(0, keys.size)
  }

  @Test fun evictionDropsOnlyTheExactActivityAndCanReadmitIt() {
    val keys = SurfaceIconKeyIndex()
    val bare = keys.retain("com.example.app", null)
    val activity = keys.retain("com.example.app", "com.example.app.Icon")
    keys.remove(bare)
    keys.remove(bare)
    assertEquals(1, keys.size)
    assertNull(keys.find(bare.name, null))
    assertSame(activity, keys.find(activity.name, activity.activity))
    assertNotSame(bare, keys.retain(bare.name, null))
    keys.clear()
    assertEquals(0, keys.size)
    assertNull(keys.find(activity.name, activity.activity))
  }
}
