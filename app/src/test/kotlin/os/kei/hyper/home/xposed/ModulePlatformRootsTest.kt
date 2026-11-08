package os.kei.hyper.home.xposed

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ModulePlatformRootsTest {
  private open class StoredTrace(@JvmField var stored: Any?) : RuntimeException()
  private class OverriddenTrace(stored: Any?) : StoredTrace(stored) {
    var invoked = false
    override fun getStackTrace(): Array<StackTraceElement> {
      invoked = true
      error("Do not invoke a host override")
    }
  }
  @Test fun inspectionKeepsHostAndOldLoaderBucketsAvailableForInflightDecoders() {
    val old = object : ClassLoader(null) {}
    val host = ClassLoader.getSystemClassLoader()
    val oldBucket = mutableMapOf("type" to Any())
    val paired = linkedMapOf<ClassLoader?, Any?>(old to oldBucket, host to Any(), null to Any())
    val legacy = linkedMapOf<ClassLoader?, Any?>(old to oldBucket, host to Any())
    assertEquals(2, countOwnedParcelBuckets(paired, legacy, old))
    assertSame(oldBucket, paired[old])
    assertSame(oldBucket, legacy[old])
    assertEquals(3, paired.size)
    assertEquals(2, legacy.size)
  }

  @Test fun unknownLoaderDoesNotClaimForeignBuckets() {
    assertEquals(0, countOwnedParcelBuckets(
      mapOf(ClassLoader.getSystemClassLoader() to Any()), emptyMap<Any, Any>(), object : ClassLoader(null) {},
    ))
  }

  @Test fun onlyActualClassFramesWithExactLoaderIdentityMatch() {
    val owner = object : ClassLoader(null) {}
    val other = object : ClassLoader(null) {}
    val type = Proxy.newProxyInstance(owner, arrayOf(Runnable::class.java)) { _, _, _ -> null }.javaClass
    assertTrue(ownsBacktrace(arrayOf(longArrayOf(1), type), owner))
    assertFalse(ownsBacktrace(arrayOf(longArrayOf(1), type), other))
    assertFalse(ownsBacktrace(arrayOf(owner, "not a frame", String::class.java), owner))
    assertFalse(ownsBacktrace(null, owner))
    assertFalse(ownsBacktrace(longArrayOf(1, 2), owner))
  }

  @Test fun retainedBacktraceIsNotReportedAsReleased() {
    val owner = object : ClassLoader(null) {}
    val type = Proxy.newProxyInstance(owner, arrayOf(Runnable::class.java)) { _, _, _ -> null }.javaClass
    val trace = StoredTrace(arrayOf(type))
    val field = StoredTrace::class.java.getDeclaredField("stored").apply { isAccessible = true }
    assertEquals(-1, materializeOwnedTrace(trace, owner, field))
    assertTrue(ownsBacktrace(trace.stored, owner))
  }

  @Test fun hostOverridesAndUnrelatedTracesAreLeftAlone() {
    val owner = object : ClassLoader(null) {}
    val type = Proxy.newProxyInstance(owner, arrayOf(Runnable::class.java)) { _, _, _ -> null }.javaClass
    val field = StoredTrace::class.java.getDeclaredField("stored").apply { isAccessible = true }
    val overridden = OverriddenTrace(arrayOf(type))
    assertEquals(-1, materializeOwnedTrace(overridden, owner, field))
    assertFalse(overridden.invoked)
    val unrelated = StoredTrace(arrayOf(String::class.java))
    val before = unrelated.stored
    assertEquals(0, materializeOwnedTrace(unrelated, owner, field))
    assertSame(before, unrelated.stored)
  }
}
