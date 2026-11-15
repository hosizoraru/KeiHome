package os.kei.hyper.home.xposed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.NativeIconPngFixtures

class ThemeConvertedApprovalTest {
  @Test fun consumedParentPlaceholderStillRequiresFreshRegisteredChildContent() = fixture { root, grant, subject ->
    val icons = java.util.zip.ZipFile(File(root, "package.mtz")).use {
      it.getInputStream(it.getEntry("icons")).use { input -> input.readBytes() }
    }
    var payload = icons
    val content = ThemeConvertedContent(File(root, "cache")) { uri ->
      ByteArrayInputStream(if (uri.endsWith("parent")) byteArrayOf() else payload)
    }
    val split = subject.copy(parentContent = "content://themes/parent", iconContent = "content://themes/icons")
    val approvals = ThemeConvertedApproval({ mapOf(grant.localId to grant) }, content::inspect)
    assertTrue(approvals.approve(split))
    payload = icons.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
    assertFalse(approvals.approve(split))
    assertFalse(approvals.iconsOnly(split))
    assertTrue(File(root, "cache").listFiles().orEmpty().isEmpty())
  }

  @Test fun anotherProcessCanRegisterAfterInitializationAndRevokeAnEarlierGrant() = fixture { _, grant, subject ->
    var disk = emptyMap<String, ConvertedIconGrant>()
    var reads = 0
    val approvals = ThemeConvertedApproval({ reads++; disk }) { _, _ -> true }
    assertFalse(approvals.approve(subject))
    disk = mapOf(grant.localId to grant)
    assertTrue(approvals.approve(subject))
    disk = emptyMap()
    assertFalse(approvals.approve(subject))
    assertFalse(approvals.iconsOnly(subject))
    assertEquals(3, reads)
  }

  @Test fun wrongIdentityAndStoreResourcesCannotReachContentInspection() = fixture { _, grant, subject ->
    var inspections = 0
    val approvals = ThemeConvertedApproval({ mapOf(grant.localId to grant) }) { _, _ -> inspections++; true }
    for (wrong in listOf(
      subject.copy(icon = subject.icon.copy(hash = "0".repeat(40))),
      subject.copy(parent = subject.parent.copy(localId = "other-parent")),
      subject.copy(icon = subject.icon.copy(onlineId = "store-id")),
      subject.copy(parent = subject.parent.copy(productId = "paid-theme")),
    )) assertFalse(approvals.approve(wrong))
    assertEquals(0, inspections)
  }

  @Test fun contentFailureCannotLeaveAnEarlierApprovalPublished() = fixture { _, grant, subject ->
    var intact = true
    val approvals = ThemeConvertedApproval({ mapOf(grant.localId to grant) }) { _, _ -> intact }
    assertTrue(approvals.approve(subject))
    intact = false
    assertFalse(approvals.approve(subject))
    assertFalse(approvals.iconsOnly(subject))
  }

  @Test fun providerWindowsAndNestedIconsUseExactBytesAndCloseTheirStreams() = fixture { root, grant, subject ->
    val outer = File(root, "package.mtz").readBytes()
    val icons = java.util.zip.ZipFile(File(root, "package.mtz")).use {
      it.getInputStream(it.getEntry("icons")).use { input -> input.readBytes() }
    }
    var closes = 0
    val content = ThemeConvertedContent(File(root, "cache")) { uri ->
      val window = if (uri.endsWith("parent")) outer else icons
      val prefix = "outside-window".toByteArray()
      val backing = prefix + window + "also-outside".toByteArray()
      object : ByteArrayInputStream(backing, prefix.size, window.size) {
        override fun close() { closes++; super.close() }
      }
    }
    assertTrue(content.inspect(subject.copy(parentContent = "content://themes/parent",
      iconContent = "content://themes/icons"), grant))
    assertEquals(2, closes)
    assertTrue(File(root, "cache").listFiles().orEmpty().isEmpty())
    assertTrue(content.inspect(subject, grant))
  }

  @Test fun badWindowOrMissingProviderDoesNotApproveAndCleansTemporaryArchive() = fixture { root, grant, subject ->
    val content = ThemeConvertedContent(File(root, "cache")) { ByteArrayInputStream("bad-window".toByteArray()) }
    assertThrows(Exception::class.java) {
      content.inspect(subject.copy(parentContent = "content://themes/parent"), grant)
    }
    assertTrue(File(root, "cache").listFiles().orEmpty().isEmpty())
    assertFalse(content.matchesIcons("content://themes/icons", grant))
    val missing = ThemeConvertedContent(File(root, "cache")) { null }
    assertThrows(IllegalArgumentException::class.java) { missing.open("content://themes/missing") }
  }

  @Test fun traversalRelativeAndMissingEntriesRemainNativeFailures() = fixture { root, _, _ ->
    val content = ThemeConvertedContent(File(root, "cache")) { null }
    for (path in listOf("${root.path}/package.mtz/../icons", "relative/icons", "${root.path}/package.mtz/missing"))
      assertThrows(IllegalArgumentException::class.java) { content.open(path) }
  }

  @Test fun nativeExceptionPropagatesExactlyOnceWithoutApprovalRetry() {
    var originals = 0
    var warnings = 0
    val failure = IOException("native failure")
    val thrown = assertThrows(IOException::class.java) {
      ThemeConvertedApproval.dispatch<String>({ null }, { originals++; throw failure }, { warnings++ })
    }
    assertSame(failure, thrown)
    assertEquals(1, originals)
    assertEquals(0, warnings)
  }

  @Test fun approvalFailureFallsBackOnceAndApprovedRightsNeverCallNative() {
    var originals = 0
    var warnings = 0
    assertEquals("native", ThemeConvertedApproval.dispatch(
      { throw IOException("provider unavailable") }, { originals++; "native" }, { warnings++ }))
    assertEquals("approved", ThemeConvertedApproval.dispatch(
      { "approved" }, { originals++; "native" }, { warnings++ }))
    assertEquals(1, originals)
    assertEquals(1, warnings)
  }

  @Test fun retiredWorkerDoesNotKeepAStagedArchive() = fixture { root, grant, subject ->
    val content = ThemeConvertedContent(File(root, "cache")) { null }
    try {
      Thread.currentThread().interrupt()
      assertThrows(InterruptedException::class.java) { content.inspect(subject, grant) }
    } finally { Thread.interrupted() }
    assertTrue(File(root, "cache").listFiles().orEmpty().isEmpty())
  }

  private fun fixture(block: (File, ConvertedIconGrant, ThemeConvertedSubject) -> Unit) {
    val root = Files.createTempDirectory("keihome-theme-rights-test-").toFile()
    try {
      val icons = zip(
        "res/drawable-xxhdpi/com.example.png" to NativeIconPngFixtures.rgba(),
        "nightmode/res/drawable-xxhdpi/com.example.png" to NativeIconPngFixtures.rgba())
      val archive = File(root, "package.mtz").apply {
        writeBytes(zip("description.xml" to
          ("<MIUI-Theme><title>Converted</title><author>Author</author>" +
            "<designer>Author</designer><version>1.0</version><uiVersion>13</uiVersion>" +
            "<description>Local icon import by KeiHome.</description></MIUI-Theme>").toByteArray(),
          "icons" to icons, "preview/preview_icons_0.png" to NativeIconPngFixtures.rgba()))
      }
      val grant = ConvertedIconGrant(ConvertedIconArchive.inspect(archive, "CONVERTED_APK"), "icon-id", "parent-id")
      block(root, grant, ThemeConvertedSubject(
        ConvertedIconResource(grant.localId, grant.proof.icons.sha1, null, null, null),
        ConvertedIconResource(grant.parentLocalId, grant.proof.archive.sha1, null, null, null),
        "${archive.path}/icons", archive.path))
    } finally { root.deleteRecursively() }
  }

  private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
      entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
    }
  }.toByteArray()
}
