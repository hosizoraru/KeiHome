package os.kei.hyper.home.xposed

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.iconpack.NativeIconPngFixtures
import os.kei.hyper.home.iconpack.NativePaletteIcon

class LocalNativeIconValidatorTest {
  @get:Rule val temporary = TemporaryFolder()
  private val png = NativeIconPngFixtures.rgba(4)
  private val root = "fancy_icons/com.example/"
  private val selector = "<Icon frameRate='0'><Image src='common.png' visibility='!#__darkmode'/>" +
    "<Image src='dark.png' visibility='#__darkmode'/></Icon>"

  @Test fun preservesNativeAnimationCalendarAndRenamedAssets() {
    val xml = "<Icon useVariableUpdater='DateTime'><VariableBinders>" +
      "<BroadcastBinder action='android.intent.action.DATE_CHANGED'/></VariableBinders>" +
      "<ExternalCommands><Trigger action='back_home_finish'><FrameRateCommand rate='120'/>" +
      "<AnimationCommand target='progress' command='play(0,800)'/>" +
      "<VariableCommand name='progress' expression='0' delay='200'/>" +
      "<FrameRateCommand rate='0' delay='800'/></Trigger></ExternalCommands>" +
      "<Var name='progress'><VariableAnimation><AniFrame time='0' value='0'/></VariableAnimation></Var>" +
      "<Image src='common.png' visibility='!#__darkmode'/><Image src='dark.png' visibility='#__darkmode'/></Icon>"
    val file = archive(module(xml))
    val before = file.readBytes()
    val proof = ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
    assertEquals(ConvertedIconArchive.LOCAL_NATIVE, proof.origin)
    assertArrayEquals(before, file.readBytes())
  }

  @Test fun generatedOriginsStillRejectArbitraryNativeMaml() {
    val file = archive(module())
    for (origin in listOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP", "UNKNOWN")) {
      assertThrows(Exception::class.java) { ConvertedIconArchive.inspect(file, origin) }
    }
  }

  @Test fun rejectsNonvisualCommandsBindersAndExternalReferences() {
    for (body in listOf("<IntentCommand action='android.intent.action.VIEW'/>",
      "<MethodCommand method='exec'/>", "<FileBinder/>", "<WebServiceBinder/>",
      "<ContentProviderBinder uri='content://settings/system'/>",
      "<BroadcastBinder action='android.intent.action.PHONE_STATE'/>",
      "<Image src='../outside.png'/>", "<Image src='file:/data/secret'/>",
      "<Image src='https://example.com/icon.png'/>", "<Include src='other.xml'/>")) {
      val file = archive(module("<Icon>$body</Icon>"))
      assertThrows("Must reject $body", Exception::class.java) {
        ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun rejectsStoreMetadataOrOtherComponentsInsideModule() {
    for (path in listOf("rights.xml", "description.xml", "lockscreen/manifest.xml", "wallpaper/a.png")) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module() + (path to png)), ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun rejectsIndirectExternalPathsAndUnverifiedImageVariables() {
    for (body in listOf(
      "<Var name='asset' type='string' expression=\"'/data/secret'\"/><Image srcExp='@asset'/>",
      "<Var name='asset' type='string' expression=\"substr('relative/file.png',8)\"/><Image srcExp='@asset'/>",
      "<Var name='asset' type='string' expression='eval(#value)'/><Image srcExp='@asset'/>",
      "<Var name='asset' type='string' expression='@external'/><Image srcExp='@asset'/>",
      "<Var name='asset' type='string' expression=\"'common.png'\" value=\"'/data/secret'\"/><Image srcExp='@asset'/>",
      "<Image srcExp='@unverified'/>",
      "<VariableCommand name='asset' expression=\"'/data/secret'\"/>",
      "<VariableCommand name='asset' expression='0' value=\"'/data/secret'\"/>",
      "<Image src=\"'/data/secret'\"/>",
    )) {
      assertThrows("Must reject $body", Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module("<Icon>$body</Icon>")),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun rejectsMalformedImagesAndOversizedCanvas() {
    for (bad in listOf(byteArrayOf(1, 2, 3), png.copyOf(png.size - 1),
      png.clone().also { it[29] = (it[29].toInt() xor 1).toByte() },
      NativeIconPngFixtures.png(2049, 8, 6, byteArrayOf(0)))) {
      assertThrows(Exception::class.java) {
        ConvertedIconArchive.inspect(archive(module() + ("${root}dark.png" to bad)),
          ConvertedIconArchive.LOCAL_NATIVE)
      }
    }
  }

  @Test fun acceptsOnlyCanonicalGeneratedSettingsBindersAlongsideNativeContent() {
    val pair = NativePaletteIcon.entries("com.generated", png, png,
      NativeIconPngFixtures.mask(4), NativeIconPngFixtures.mask(4))
    val file = archive(module() + pair)
    ConvertedIconArchive.inspect(file, ConvertedIconArchive.LOCAL_NATIVE)
    val manifestPath = "fancy_icons/com.generated/manifest.xml"
    val altered = pair + (manifestPath to String(pair.getValue(manifestPath))
      .replace("settings/system", "settings/secure").toByteArray())
    assertThrows(Exception::class.java) {
      ConvertedIconArchive.inspect(archive(module() + altered), ConvertedIconArchive.LOCAL_NATIVE)
    }
  }

  @Test fun acceptsVerifiedNativeOriginInReceiptAndRuntimeAssociation() {
    val proof = ConvertedIconArchive.inspect(archive(module()), ConvertedIconArchive.LOCAL_NATIVE)
    assertEquals(proof, ConvertedImportProofReceipt.decode(ConvertedImportProofReceipt.encode(proof)))
    ConvertedIconValidationRegistration("12345678-1234-1234-1234-123456789012", 0, "local-id", proof).validate()
  }

  @Test fun suppliedModulePassesIndependentLocalValidation() {
    val path = System.getenv("KEIHOME_TEST_ICON_ARCHIVE")
    assumeTrue(path != null)
    val file = File(requireNotNull(path))
    val hash = ConvertedIconArchive.digest(file)
    LocalNativeIconValidator.validate(file)
    val outer = archiveBytes(file.readBytes())
    val proof = ConvertedIconArchive.inspect(outer, ConvertedIconArchive.LOCAL_NATIVE)
    assertEquals(hash, proof.icons)
    assertEquals(hash, ConvertedIconArchive.digest(file))
  }

  private fun module(xml: String = selector) = linkedMapOf(
    "res/drawable-xxhdpi/com.example.png" to png,
    "${root}manifest.xml" to xml.toByteArray(), "${root}common.png" to png, "${root}dark.png" to png,
    "transform_config.xml" to "<IconTransform><ScaleX>1</ScaleX></IconTransform>".toByteArray())

  private fun archive(entries: Map<String, ByteArray>) = archiveBytes(zip(entries))
  private fun archiveBytes(module: ByteArray) = temporary.newFile().apply {
    writeBytes(zip(linkedMapOf("description.xml" to
      ("<MIUI-Theme><title>Local icons</title><author>Local author</author><designer>Local author</designer>" +
        "<version>1</version><uiVersion>13</uiVersion><description>Local icon import</description></MIUI-Theme>").toByteArray(),
      "icons" to module, "preview/preview_icons_0.png" to png)))
  }
  private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
      entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() }
    }
  }.toByteArray()
}
