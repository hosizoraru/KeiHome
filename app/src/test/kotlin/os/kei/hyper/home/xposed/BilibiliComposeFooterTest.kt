package os.kei.hyper.home.xposed

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliComposeFooterTest {

  private class PlainScope {
    var count = 0
    fun invalidate() { count++ }
  }

  private class ObfuscatedScope {
    var count = 0
    @Suppress("unused")
    fun a() { count++ }
  }

  private class AmbiguousScope {
    @Suppress("unused") fun a() = Unit
    @Suppress("unused") fun b() = Unit
  }

  @Test fun parsesBilibili9210ThirteenParamAbiAndClearsExactComposeBits() {
    val params = listOf(
      "kntr.base.imageloader.ImmutableImageRequest",
      "androidx.compose.ui.Modifier",
      "java.lang.String",
      "androidx.lifecycle.Lifecycle",
      "kotlin.jvm.functions.Function1",
      "kotlin.jvm.functions.Function1",
      "kotlin.jvm.functions.Function1",
      "kotlin.jvm.functions.Function1",
      "kotlin.jvm.functions.Function2",
      "kotlin.jvm.functions.Function4",
      "androidx.compose.runtime.Composer",
      "int",
      "int",
    )
    val abi = requireNotNull(parseBiliImageAbi(params))
    assertEquals(0, abi.requestIndex)
    assertEquals(1, abi.modifierIndex)
    assertEquals(4, abi.onStateIndex)
    assertEquals(10, abi.composerIndex)
    assertEquals(11, abi.changedStartIndex)
    assertEquals(1, abi.changedCount)
    assertEquals(12, abi.defaultIndex)

    val args = arrayOfNulls<Any?>(13)
    args[11] = 0x7fffffff
    args[12] = 0x3fc // Bilibili 9.21.0 actual $default mask (bits 2..9 set)
    assertFalse(abi.hasDefaultFlag(args, abi.modifierIndex))
    assertTrue(abi.hasDefaultFlag(args, abi.onStateIndex))

    abi.clearDirtyBits(args, abi.modifierIndex)
    abi.clearDirtyBits(args, abi.onStateIndex)
    abi.clearDefaultBit(args, abi.onStateIndex)

    assertEquals(0x7fffffff and (0x70 or 0xe000).inv(), args[11])
    assertEquals(0x3fc and (1 shl 4).inv(), args[12])
  }

  @Test fun parsesFutureFifteenParamAbiWithSecondChangedInt() {
    // Adding 1 optional parameter to BiliImage crosses Compose's 10-param $changed boundary.
    val params = listOf(
      "kntr.base.imageloader.ImmutableImageRequest",
      "androidx.compose.ui.Modifier",
      "java.lang.String",
      "androidx.lifecycle.Lifecycle",
      "kotlin.jvm.functions.Function1",
      "kotlin.jvm.functions.Function1",
      "kotlin.jvm.functions.Function1",
      "kotlin.jvm.functions.Function1",
      "kotlin.jvm.functions.Function2",
      "kotlin.jvm.functions.Function4",
      "java.lang.Object", // future 11th value parameter
      "androidx.compose.runtime.Composer",
      "int", // $changed0
      "int", // $changed1
      "int", // $default
    )
    val abi = requireNotNull(parseBiliImageAbi(params))
    assertEquals(4, abi.onStateIndex)
    assertEquals(11, abi.composerIndex)
    assertEquals(12, abi.changedStartIndex)
    assertEquals(2, abi.changedCount)
    assertEquals(14, abi.defaultIndex)

    val args = arrayOfNulls<Any?>(15)
    args[12] = -1
    args[13] = -1
    args[14] = 0x7fc
    abi.clearDirtyBits(args, abi.modifierIndex)
    abi.clearDirtyBits(args, abi.onStateIndex)
    abi.clearDefaultBit(args, abi.onStateIndex)

    assertEquals(-1 and (0x70 or 0xe000).inv(), args[12])
    assertEquals(-1, args[13])
    assertEquals(0x7fc and (1 shl 4).inv(), args[14])
  }

  @Test fun clearsDirtyBitsInSecondChangedSlotWhenParamIndexExceedsNine() {
    val abi = BiliImageAbi(
      requestIndex = 0,
      modifierIndex = 1,
      onStateIndex = 10,
      composerIndex = 11,
      changedStartIndex = 12,
      changedCount = 2,
      defaultIndex = 14,
    )
    val args = arrayOfNulls<Any?>(15)
    args[12] = -1
    args[13] = -1
    args[14] = 1 shl 10
    abi.clearDirtyBits(args, abi.onStateIndex)
    abi.clearDefaultBit(args, abi.onStateIndex)

    assertEquals(-1, args[12])
    assertEquals(-1 and 0xe.inv(), args[13])
    assertEquals(0, args[14])
  }

  @Test fun rejectsUnrecognizedBiliImageSignatures() {
    assertNull(parseBiliImageAbi(listOf("java.lang.String", "androidx.compose.ui.Modifier")))
    assertNull(
      parseBiliImageAbi(
        listOf(
          "kntr.base.imageloader.ImmutableImageRequest",
          "androidx.compose.ui.Modifier",
          "java.lang.String",
          "androidx.lifecycle.Lifecycle",
          "java.lang.String", // missing Function1
          "androidx.compose.runtime.Composer",
          "int",
          "int",
        ),
      ),
    )
  }

  @Test fun resolvesPlainAndSingleMethodObfuscatedRecomposeScopeInvalidate() {
    val plain = PlainScope()
    val plainMethod = requireNotNull(resolveInvalidateMethod(PlainScope::class.java))
    plainMethod.invoke(plain)
    assertEquals(1, plain.count)

    val obfuscated = ObfuscatedScope()
    val obfMethod = requireNotNull(resolveInvalidateMethod(ObfuscatedScope::class.java))
    obfMethod.invoke(obfuscated)
    assertEquals(1, obfuscated.count)

    assertNull(resolveInvalidateMethod(AmbiguousScope::class.java))
  }

  @Test fun footerAlignmentToleratesSubPixelRoundingAndRejectsRealMotion() {
    assertTrue(isNearIntegerPixel(2400f))
    assertTrue(isNearIntegerPixel(2400.02f))
    assertTrue(isNearIntegerPixel(2399.97f))
    assertFalse(isNearIntegerPixel(2400.35f))
    assertFalse(isNearIntegerPixel(Float.NaN))
    assertFalse(isNearIntegerPixel(Float.POSITIVE_INFINITY))

    assertTrue(isFooterAlignedToInset(0, 1220, 2591, decorWidth = 1220, insetTop = 2591))
    assertTrue(isFooterAlignedToInset(1, 1219, 2590, decorWidth = 1220, insetTop = 2591))
    assertTrue(isFooterAlignedToInset(-1, 1221, 2592, decorWidth = 1220, insetTop = 2591))
    assertFalse(isFooterAlignedToInset(0, 1220, 2595, decorWidth = 1220, insetTop = 2591))
    assertFalse(isFooterAlignedToInset(4, 1220, 2591, decorWidth = 1220, insetTop = 2591))
    assertNotNull(BilibiliHomeFooterRuntime.findLifecycleResume(android.app.Activity::class.java))
  }

  @Test fun profileCacheRoundTripsComposeAndLegacyProfilesAndInvalidatesOnApkChange() {
    val compose = CachedBilibiliProfile(
      homeClass = "tv.danmaku.bili.MainActivityV2",
      footerClass = "llp0",
      footerMethod = "invoke",
      imageClass = "kntr.base.imageloader.BiliImageKt",
      imageParamTypes = listOf(
        "kntr.base.imageloader.ImmutableImageRequest",
        "androidx.compose.ui.Modifier",
        "int",
        "int",
      ),
    )
    val serialized = BilibiliFooterProfileCache.serialize("/data/app/bili/base.apk", 253_000_000L, 1795800000L, compose)
    assertEquals(
      compose,
      BilibiliFooterProfileCache.parse(serialized, "/data/app/bili/base.apk", 253_000_000L, 1795800000L),
    )
    assertNull(BilibiliFooterProfileCache.parse(serialized, "/data/app/bili/other.apk", 253_000_000L, 1795800000L))
    assertNull(BilibiliFooterProfileCache.parse(serialized, "/data/app/bili/base.apk", 253_000_001L, 1795800000L))
    assertNull(BilibiliFooterProfileCache.parse(serialized, "/data/app/bili/base.apk", 253_000_000L, 1795800001L))
    assertNull(BilibiliFooterProfileCache.parse("corrupt", "/data/app/bili/base.apk", 253_000_000L, 1795800000L))

    val legacyOnly = CachedBilibiliProfile("tv.danmaku.bili.MainActivityV2", null, null, null, emptyList())
    val legacyText = BilibiliFooterProfileCache.serialize("/data/app/bili/base.apk", 100L, 200L, legacyOnly)
    assertEquals(legacyOnly, BilibiliFooterProfileCache.parse(legacyText, "/data/app/bili/base.apk", 100L, 200L))
    assertFalse(legacyOnly.hasCompose)
    assertTrue(compose.hasCompose)

    assertEquals(Int::class.javaPrimitiveType, BilibiliFooterProfileCache.loadParamClass(javaClass.classLoader!!, "int"))
    assertEquals(String::class.java, BilibiliFooterProfileCache.loadParamClass(javaClass.classLoader!!, "java.lang.String"))
  }

  @Test fun profileCacheWritesAndReadsAtomicallyInCodeCacheDir() {
    val root = Files.createTempDirectory("keihome-bili-cache-test").toFile()
    try {
      val apk = File(root, "base.apk").apply { writeText("fake-apk-bytes") }
      val cacheDir = File(root, "code_cache")
      val profile = CachedBilibiliProfile(
        homeClass = "tv.danmaku.bili.MainActivityV2",
        footerClass = "llp0",
        footerMethod = "invoke",
        imageClass = "kntr.base.imageloader.BiliImageKt",
        imageParamTypes = listOf("java.lang.String", "int"),
      )
      assertTrue(BilibiliFooterProfileCache.write(cacheDir, apk.absolutePath, profile))
      assertEquals(profile, BilibiliFooterProfileCache.read(cacheDir, apk.absolutePath))

      // Mutating APK size invalidates the cached profile immediately.
      apk.appendText("-updated")
      assertNull(BilibiliFooterProfileCache.read(cacheDir, apk.absolutePath))
    } finally {
      root.deleteRecursively()
    }
  }
}
