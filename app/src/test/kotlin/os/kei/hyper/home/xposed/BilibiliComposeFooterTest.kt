package os.kei.hyper.home.xposed

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
}
