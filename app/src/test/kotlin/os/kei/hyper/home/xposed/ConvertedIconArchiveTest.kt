package os.kei.hyper.home.xposed

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertedIconArchiveTest {
  @Test fun acceptsBothAuthenticatedConversionOriginsAndPreservesNativeDigests() {
    val icons = icons()
    withArchive(icons) { file ->
      for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP")) {
        val proof = ConvertedIconArchive.inspect(file, origin)
        assertEquals(hash(icons, "SHA-1"), proof.icons.sha1)
        assertEquals(hash(icons, "SHA-256"), proof.icons.sha256)
        assertEquals(hash(file.readBytes(), "SHA-256"), proof.archive.sha256)
      }
      assertEquals(listOf(file.name), requireNotNull(file.parentFile).list()!!.toList()) // No leaked proof copy.
    }
  }

  @Test fun acceptsUnicodeDottedComponents() {
    withArchive(icons("com.示例.活动")) { ConvertedIconArchive.inspect(it, "CONVERTED_APK") }
  }

  @Test fun unchangedNativeOrUnrecognizedOriginCannotRegister() {
    withArchive(icons()) { file ->
      listOf("MIUI_MTZ", "NATIVE", "converted_apk", "").forEach { origin ->
        assertThrows(IllegalArgumentException::class.java) { ConvertedIconArchive.inspect(file, origin) }
      }
    }
  }

  @Test fun rejectsMamlOtherComponentsAndTraversalEvenWhenOuterEnvelopeLooksConverted() {
    for (name in listOf("fancy_icons/com.example/manifest.xml", "../icons.png", "wallpaper/default.png")) {
      rejects(zip(day to png(), night to png(), name to "<Root/>".toByteArray()))
    }
  }

  @Test fun requiresBothNativeDayAndNightTrees() {
    rejects(zip(day to png()))
    rejects(zip("res/drawable-night-xxhdpi/com.example.png" to png(), day to png()))
  }

  @Test fun rejectsStoreMetadataAndXmlDeclarations() {
    for (extra in listOf("<productId>store-id</productId>", "<rights>anything</rights>")) {
      withArchive(icons(), description.replace("</MIUI-Theme>", "$extra</MIUI-Theme>")) { file ->
        assertThrows(IllegalArgumentException::class.java) { ConvertedIconArchive.inspect(file, "CONVERTED_APK") }
      }
    }
    withArchive(icons(), "<!DOCTYPE MIUI-Theme [<!ENTITY x 'untrusted'>]>$description") { file ->
      assertThrows(IllegalArgumentException::class.java) { ConvertedIconArchive.inspect(file, "CONVERTED_APK") }
    }
  }

  @Test fun rejectsNonPngCorruptPngAndOversizedDimensions() {
    rejects(zip(day to "not a PNG".toByteArray(), night to png()))
    val corrupt = png().also { it[29] = (it[29].toInt() xor 1).toByte() }
    rejects(zip(day to corrupt, night to png()))
    rejects(zip(day to png(513), night to png()))
  }

  @Test fun usesCentralDirectoryNamesAndRejectsDuplicates() {
    val centralMismatch = icons()
    val offset = lastIndexOf(centralMismatch, day.toByteArray())
    "xml".toByteArray().copyInto(centralMismatch, offset + day.length - 3)
    rejects(centralMismatch)
    val second = "res/drawable-xxhdpi/com.examp1e.png"
    val duplicate = zip(day to png(), second to png(), night to png())
    var index = lastIndexOf(duplicate, second.toByteArray())
    while (index >= 0) {
      day.toByteArray().copyInto(duplicate, index)
      index = lastIndexOf(duplicate, second.toByteArray())
    }
    rejects(duplicate)
  }

  @Test fun previewChangeKeepsIconIdentityButChangesParentIdentity() {
    var first: ConvertedIconArchive.Proof? = null
    withArchive(icons()) { first = ConvertedIconArchive.inspect(it, "CONVERTED_APK") }
    withArchive(icons(), preview = png(2)) {
      val second = ConvertedIconArchive.inspect(it, "CONVERTED_APK")
      assertEquals(first!!.icons, second.icons)
      assertNotEquals(first.archive, second.archive)
    }
  }

  @Test fun approvalRequiresExactLocalAndParentAssociationsAndNoStoreIdentifiers() {
    val proof = ConvertedIconArchive.Proof("CONVERTED_APK",
      ConvertedIconArchive.Digests("parent-hash", "parent-sha256"),
      ConvertedIconArchive.Digests("icon-hash", "icon-sha256"))
    val grant = ConvertedIconGrant(proof, "icon-local", "parent-local")
    val icon = ConvertedIconResource("icon-local", "icon-hash", null, null, null)
    val parent = ConvertedIconResource("parent-local", "parent-hash", null, null, null)
    assertTrue(grant.matches(icon, parent))
    assertFalse(grant.matches(icon.copy(localId = "other"), parent))
    assertFalse(grant.matches(icon.copy(hash = "other"), parent))
    assertFalse(grant.matches(icon, parent.copy(localId = "other")))
    assertFalse(grant.matches(icon, parent.copy(hash = "other")))
    for (store in listOf(icon.copy(onlineId = "store"), icon.copy(productId = "store"), icon.copy(assemblyId = "store"))) {
      assertFalse(grant.matches(store, parent))
    }
    assertFalse(grant.matches(icon, parent.copy(productId = "store")))
  }

  private fun rejects(icons: ByteArray) = withArchive(icons) { file ->
    assertThrows(Exception::class.java) { ConvertedIconArchive.inspect(file, "CONVERTED_APK") }
  }

  private fun withArchive(icons: ByteArray, metadata: String = description, preview: ByteArray = png(),
    block: (java.io.File) -> Unit) {
    val directory = Files.createTempDirectory("keihome-converted-test-").toFile()
    try {
      val file = java.io.File(directory, "icons.mtz")
      file.writeBytes(zip("description.xml" to metadata.toByteArray(), "icons" to icons,
        "preview/preview_icons_0.png" to preview))
      block(file)
    } finally { directory.deleteRecursively() }
  }

  private fun icons(component: String = "com.example") = zip(
    "res/drawable-xxhdpi/$component.png" to png(), "nightmode/res/drawable-xxhdpi/$component.png" to png())

  private fun zip(vararg entries: kotlin.Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip -> entries.forEach { (name, content) ->
      zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(content); zip.closeEntry()
    } }
  }.toByteArray()

  private fun png(width: Int = 1): ByteArray = ByteArrayOutputStream().also { bytes ->
    val output = DataOutputStream(bytes)
    output.writeLong(-8552249625308161526L)
    fun chunk(name: String, data: ByteArray) {
      val type = name.toByteArray(Charsets.US_ASCII)
      output.writeInt(data.size); output.write(type); output.write(data)
      output.writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
    }
    chunk("IHDR", ByteArrayOutputStream().also { header -> DataOutputStream(header).apply {
      writeInt(width); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0))
    } }.toByteArray())
    chunk("IDAT", ByteArrayOutputStream().also { pixels ->
      DeflaterOutputStream(pixels).use { it.write(ByteArray(1 + width * 4)) }
    }.toByteArray())
    chunk("IEND", byteArrayOf())
  }.toByteArray()

  private fun hash(bytes: ByteArray, algorithm: String) = MessageDigest.getInstance(algorithm).digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

  private fun lastIndexOf(bytes: ByteArray, value: ByteArray): Int =
    (bytes.size - value.size downTo 0).firstOrNull { start -> value.indices.all { bytes[start + it] == value[it] } } ?: -1

  companion object {
    private const val day = "res/drawable-xxhdpi/com.example.png"
    private const val night = "nightmode/res/drawable-xxhdpi/com.example.png"
    private const val description = "<MIUI-Theme><title>Community</title><author>Author</author>" +
      "<designer>Author</designer><version>1.0</version><uiVersion>13</uiVersion>" +
      "<description>Local icon import by KeiHome.</description></MIUI-Theme>"
  }
}
