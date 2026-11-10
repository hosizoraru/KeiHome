package os.kei.hyper.home.iconpack

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreinstalledIconOriginsTest {
  @get:Rule val temporary = TemporaryFolder()
  private val signer = "ab".repeat(32)
  private val other = "cd".repeat(32)
  private fun archive(root: File, name: String = "App") =
    File(root, "$name/$name.apk").apply { parentFile.mkdirs(); writeText("fixture") }
  private fun origin(file: File) = "os.test.${file.nameWithoutExtension}"

  @Test fun updatedPreloadRequiresExactPackageAndFactoryCertificate() {
    val root = temporary.newFolder("factory")
    archive(root)
    val catalog = PreinstalledIconOrigins(listOf(root))
    assertTrue(catalog.matches("os.test.App", "/data/app/base.apk", ::origin, { setOf(signer) }) { name, certificate ->
      name == "os.test.App" && certificate == signer
    })
    assertFalse(catalog.matches("os.test.Other", null, ::origin, { setOf(signer) }) { _, _ -> true })
    assertFalse(catalog.matches("os.test.App", null, ::origin, { setOf(signer) }) { _, _ -> false })
  }

  @Test fun installedFactoryPathNeedsNoArchiveScanOrCertificateBinderCall() {
    val root = temporary.newFolder("factory")
    val apk = archive(root)
    assertTrue(PreinstalledIconOrigins(listOf(root)).matches("os.test.App", apk.path,
      { error("No archive scan for an installed immutable factory APK") },
      { error("No certificate read for an installed immutable factory APK") },
      { _, _ -> error("No certificate Binder call for an installed immutable factory APK") }))
  }

  @Test fun metadataIsReusedButInstalledCertificatesAreRechecked() {
    val root = temporary.newFolder("factory")
    archive(root)
    val catalog = PreinstalledIconOrigins(listOf(root, root))
    var reads = 0
    var certificateReads = 0
    var trusted = true
    val read = { file: File -> reads++; origin(file) }
    val certificates = { _: File -> certificateReads++; setOf(signer) }
    assertTrue(catalog.matches("os.test.App", null, read, certificates) { _, _ -> trusted })
    trusted = false
    assertFalse(catalog.matches("os.test.App", null, read, certificates) { _, _ -> trusted })
    assertEquals(1, reads)
    assertEquals(1, certificateReads)
  }

  @Test fun allFactorySignersAreRequiredAndMalformedRecordsAreRejected() {
    val root = temporary.newFolder("factory")
    archive(root)
    fun match(signers: Set<String>, known: Set<String>) =
      PreinstalledIconOrigins(listOf(root)).matches("os.test.App", null,
        ::origin, { signers }, { _, digest -> digest in known })
    assertFalse(match(setOf(signer, other), setOf(signer)))
    assertTrue(match(setOf(signer, other), setOf(signer, other)))
    assertFalse(match(emptySet(), emptySet()))
    assertFalse(match(setOf("invalid"), setOf("invalid")))
  }

  @Test fun siblingDirectoriesAndSymlinksCannotImpersonateFactoryOrigins() {
    val root = temporary.newFolder("factory")
    val outside = archive(temporary.newFolder("factory-other"))
    Files.createSymbolicLink(File(root, "escape.apk").toPath(), outside.toPath())
    val directory = File(root, "directory").toPath()
    Files.createSymbolicLink(directory, outside.parentFile.toPath())
    var reads = 0
    assertFalse(PreinstalledIconOrigins(listOf(root)).matches("os.test.App", outside.path,
      { reads++; origin(it) }, { setOf(signer) }, { _, _ -> true }))
    assertEquals(0, reads)
    assertFalse(PreinstalledIconOrigins(listOf(root)).matches("os.test.App",
      File(root, "escape.apk").path, { reads++; origin(it) }, { setOf(signer) }, { _, _ -> true }))
  }

  @Test fun damagedArchiveDoesNotHideOtherPreloads() {
    val root = temporary.newFolder("factory")
    archive(root, "Broken")
    archive(root)
    assertTrue(PreinstalledIconOrigins(listOf(root)).matches("os.test.App", null,
      { if (it.name.startsWith("Broken")) throw IllegalArgumentException("damaged APK") else origin(it) },
      { setOf(signer) },
      { _, _ -> true }))
  }

  @Test fun cancellationDoesNotPublishAnIncompleteCatalog() {
    val root = temporary.newFolder("factory")
    archive(root)
    val catalog = PreinstalledIconOrigins(listOf(root))
    try {
      catalog.matches("os.test.App", null, { throw CancellationException() }, { setOf(signer) },
        { _, _ -> true })
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
    assertTrue(catalog.matches("os.test.App", null, ::origin, { setOf(signer) }) { _, _ -> true })
  }

  @Test fun unrelatedFactoryApksNeverNeedCertificateParsing() {
    val root = temporary.newFolder("factory")
    val app = archive(root)
    archive(root, "LargeUnrelated")
    assertTrue(PreinstalledIconOrigins(listOf(root)).matches("os.test.App", null, ::origin,
      { file ->
        assertEquals(app.canonicalFile, file)
        setOf(signer)
      }, { _, _ -> true }))
  }
}
