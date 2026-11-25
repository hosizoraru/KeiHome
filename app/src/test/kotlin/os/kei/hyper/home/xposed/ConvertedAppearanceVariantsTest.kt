package os.kei.hyper.home.xposed

import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.status.NativeIconImportReport

class ConvertedAppearanceVariantsTest {
  private val report = NativeIconImportReport("00000000-0000-0000-0000-000000000001", 0, "local-conversion",
    "IMPORTED", localId = "icon-local", convertedReady = true)
  private val proof = ConvertedIconArchive.Proof("CONVERTED_APK",
    ConvertedIconArchive.Digests("a".repeat(40), "b".repeat(64)),
    ConvertedIconArchive.Digests("c".repeat(40), "d".repeat(64)))
  private val variants = ConvertedAppearanceVariants("d".repeat(64), "1".repeat(64), "2".repeat(64))
  private val success = Any()
  private val original = Any()

  private class Registrations : ConvertedIconValidationStorage {
    var rows: List<ConvertedIconValidationRegistration> = emptyList()
    override fun read() = rows
    override fun write(values: Collection<ConvertedIconValidationRegistration>) { rows = values.toList() }
  }

  private class Variants : ConvertedAppearanceVariantStorage {
    var rows: List<ConvertedAppearanceVariants> = emptyList()
    var writes = 0
    override fun read() = rows
    override fun write(values: List<ConvertedAppearanceVariants>) { writes++; rows = values }
  }

  private class ActiveFile : File("/data/system/theme/icons") {
    override fun getCanonicalPath(): String = "/data/system/theme/icons"
    override fun isFile(): Boolean = true
    override fun length(): Long = 10
  }

  private fun runtime(variants: Variants, hash: () -> String) = ConvertedIconRuntime(
    Proxy.newProxyInstance(XposedInterface::class.java.classLoader, arrayOf(XposedInterface::class.java)) { _, _, _ ->
      null
    } as XposedInterface, Registrations(), { hash() }, variants)

  private fun validate(runtime: ConvertedIconRuntime) = runtime.validate(ActiveFile(), success) { original }

  @Test fun fixedCopiesInheritApprovalOnlyFromARegisteredPrimary() {
    var digest = variants.dark
    val store = Variants()
    val runtime = runtime(store) { digest }
    runtime.registerAppearanceVariants(variants)
    assertTrue(runtime.hasAppearanceVariants())
    // Without the converted primary registration, a copy is not approved.
    assertSame(original, validate(runtime))
    runtime.register(report, proof)
    for (candidate in listOf(variants.primary, variants.light, variants.dark)) {
      digest = candidate
      assertSame(success, validate(runtime))
    }
    digest = "3".repeat(64)
    assertSame(original, validate(runtime))
  }

  @Test fun reloadsPersistedCopiesAndReplacesAnEarlierSetForTheSamePrimary() {
    val store = Variants()
    val first = runtime(store) { variants.light }
    first.registerAppearanceVariants(variants)
    first.registerAppearanceVariants(variants)
    assertEquals(1, store.writes)
    val replaced = variants.copy(light = "4".repeat(64))
    first.registerAppearanceVariants(replaced)
    assertEquals(listOf(replaced), store.rows)

    val reloaded = runtime(store) { replaced.light }
    assertFalse(reloaded.hasAppearanceVariants())
    reloaded.loadAppearanceVariants()
    assertTrue(reloaded.hasAppearanceVariants())
    reloaded.register(report, proof)
    assertSame(success, validate(reloaded))
  }

  @Test fun rejectsMalformedOrDegenerateDigests() {
    val runtime = runtime(Variants()) { variants.primary }
    for (bad in listOf(variants.copy(light = "XYZ"), variants.copy(dark = variants.light),
      variants.copy(primary = variants.dark))) {
      assertThrows(IllegalArgumentException::class.java) { runtime.registerAppearanceVariants(bad) }
    }
    assertFalse(runtime.hasAppearanceVariants())
  }
}
