package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.NativeIconPngFixtures

class PerfectDynamicIconValidatorTest {
  private val quiet = NativeIconPngFixtures.rgba()
  private val ordinary = """<Config><Variable name="layer0" value="0"/></Config>"""
  private val fancyOnly = """<Config><Variables><Variable name="layer0" value="0"/>
    <Variable name="layer_only_fancy0" value="true"/></Variables></Config>"""
  private val quietOnly = """<Config><Variable name="layer0" value="0"/>
    <Variable name="layer_only_quiet0" value="true"/></Config>"""
  private val manifest = """<Icon><Image src="image.png"/></Icon>""".toByteArray()

  @Test fun everyDynamicRootNeedsConfig() {
    rejects(mapOf("0/quiet/quietImage.png" to quiet))
  }

  @Test fun anOrdinaryFancyLayerStillNeedsQuiet() {
    rejects(resources(ordinary, "0/fancy/manifest.xml" to manifest, "0/fancy/image.png" to quiet))
  }

  @Test fun eachDeclaredOrdinaryLayerNeedsQuiet() {
    val config = """<Config><Variable name="layer0" value="0"/><Variable name="layer1" value="1"/></Config>"""
    rejects(resources(config, "0/quiet/quietImage.png" to quiet))
  }

  @Test fun activeFancyDirectoryNeedsItsManifest() {
    rejects(resources(ordinary, "0/quiet/quietImage.png" to quiet, "0/fancy/image.png" to quiet))
  }

  @Test fun explicitFancyOnlyCanOmitEveryQuietImage() {
    assertTrue(validate(resources(fancyOnly, "0/fancy/manifest.xml" to manifest, "0/fancy/image.png" to quiet)))
  }

  @Test fun rootQuietCannotReplaceAFancyOnlyManifest() {
    rejects(resources(fancyOnly, "quiet/quietImage.png" to quiet))
  }

  @Test fun unusedQuietDoesNotInvalidateAnExplicitFancyOnlyLayer() {
    assertTrue(validate(resources(fancyOnly, "quiet/quietImage.png" to byteArrayOf(0),
      "0/quiet/quietImage.png" to byteArrayOf(0), "0/fancy/manifest.xml" to manifest,
      "0/fancy/image.png" to quiet)))
  }

  @Test fun quietOnlyMayKeepUnusedIncompleteFancyResources() {
    assertFalse(validate(resources(quietOnly, "0/quiet/quietImage.png" to quiet,
      "0/fancy/image.png" to byteArrayOf(0))))
  }

  @Test fun legacyRootQuietStillSupportsMultipleDeclaredLayers() {
    val config = """<Config><Variable name="layer0" value="0"/><Variable name="layer1" value="1"/></Config>"""
    assertFalse(validate(resources(config, "quiet/quietImage.png" to quiet)))
  }

  @Test fun rootQuietDoesNotHideACorruptSuppliedLayerQuiet() {
    rejects(resources(ordinary, "quiet/quietImage.png" to quiet, "0/quiet/quietImage.png" to byteArrayOf(0)))
  }

  @Test fun aSuppliedLayerQuietDensityOverrideIsAlsoValidated() {
    rejects(resources(ordinary, "quiet/quietImage.png" to quiet, "0/quiet/quietImage.png" to quiet,
      "0/quiet/den640/quietImage.png" to byteArrayOf(0)))
  }

  @Test fun aDensityOverrideAloneDoesNotReplaceTheRequiredQuietImage() {
    rejects(resources(ordinary, "0/quiet/den640/quietImage.png" to quiet))
  }

  @Test fun directVariablesCannotLoseMissingAttributesDuringNormalization() {
    for (badVariable in listOf("""<Variable name="layer1"/>""", """<Variable value="1"/>""")) {
      rejects(resources(ordinary.replace("</Config>", "$badVariable</Config>"), "0/quiet/quietImage.png" to quiet))
    }
  }

  @Test fun meaningfulConfigTextIsNotDiscarded() {
    for (text in listOf("unexpected", "<![CDATA[unexpected]]>")) {
      rejects(resources(ordinary.replace("</Config>", "$text</Config>"), "0/quiet/quietImage.png" to quiet))
    }
  }

  @Test fun mixedVariableContainersAreRejectedInEitherOrder() {
    val variable = """<Variable name="layer0" value="0"/>"""
    for (body in listOf("$variable<Variables/>", "<Variables>$variable</Variables>$variable")) {
      rejects(resources("<Config>$body</Config>", "0/quiet/quietImage.png" to quiet))
    }
  }

  @Test fun duplicateAndNestedVariableDeclarationsAreRejected() {
    val variable = """<Variable name="layer0" value="0"/>"""
    for (body in listOf("$variable$variable", "<Variables><Variables>$variable</Variables></Variables>",
      """<Variable name="layer0" value="0">$variable</Variable>""")) {
      rejects(resources("<Config>$body</Config>", "0/quiet/quietImage.png" to quiet))
    }
  }

  @Test fun legacyEnvelopeCannotNormalizeAwayNamespaceAliases() {
    val config = """<c:Config xmlns:c="urn:other"><c:Variable name="layer0" value="0"/></c:Config>"""
    rejects(resources(config, "0/quiet/quietImage.png" to quiet))
  }

  @Test fun validDirectAndWrappedSourceBytesAreNeverRewritten() {
    for (config in listOf(
      """<?xml version="1.0"?><Config><!-- retained --><Variable value="0" name="layer0" /></Config>""",
      """<Config>
        <Variables><Variable name="layer0" type="string" value="0"/></Variables>
      </Config>""",
    )) {
      val input = resources(config, "0/quiet/quietImage.png" to quiet,
        "0/fancy/manifest.xml" to manifest, "0/fancy/image.png" to quiet)
      val originals = input.mapValues { it.value.copyOf() }
      assertTrue(validate(input))
      assertEquals(originals.keys, input.keys)
      originals.forEach { (path, bytes) -> assertArrayEquals(path, bytes, input.getValue(path)) }
    }
  }

  @Test fun retainedSourceRejectsOnlyKnownIncompleteRoots() {
    val source = sequenceOf(
      File(".artifacts/research/perfect-icons-20261108/source/repository-complete.zip"),
      File("../.artifacts/research/perfect-icons-20261108/source/repository-complete.zip"),
    ).firstOrNull(File::isFile)
    assumeTrue("Optional retained pzcn snapshot unavailable", source != null)
    ZipFile(source!!).use { zip ->
      val groups = zip.entries().asSequence().filter {
        !it.isDirectory && "/layer_animating_icons/" in it.name
      }.groupBy { it.name.substringBefore("/layer_animating_icons/") + "/layer_animating_icons/" +
        it.name.substringAfter("/layer_animating_icons/").substringBefore('/') + "/" }
      assertEquals(162, groups.size)
      val rejected = linkedMapOf<String, String>()
      for ((prefix, entries) in groups) {
        val blobs = entries.associate {
          val path = it.name.removePrefix(prefix)
          path to PerfectIconBlob(path, it.name, it.size, it.name)
        }
        try {
          PerfectDynamicIconValidator.validate(blobs) { blob ->
            zip.getInputStream(zip.getEntry(blob.sha)).use { it.readBytes() }
          }
        } catch (failure: Exception) {
          rejected[prefix.substringAfter("/themes/")] = failure.message.orEmpty()
        }
      }
      // These are defects in the pinned source, not validator compatibility exceptions:
      // two flags target an undeclared layer, one manifest and fifteen dependencies are absent.
      val undeclaredFlags = setOf("Hyper", "HyperOSClassic").map {
        "$it/layer_animating_icons/com.miui.weather2/"
      }.toSet()
      val missingManifest = setOf("lrone/layer_animating_icons/com.miui.weather2/")
      val missingDependencies = setOf(
        "com.android.browser", "com.android.calendar", "com.android.fileexplorer",
        "com.android.providers.downloads.ui", "com.hengye.share", "com.mipay.wallet",
        "com.miui.compass", "com.miui.gallery", "com.miui.notes", "com.miui.securitycenter",
        "com.tencent.mm", "com.tencent.mobileqq", "com.xiaomi.market", "com.xiaomi.shop", "tv.danmaku.bili",
      ).map { "luck7/layer_animating_icons/$it/" }.toSet()
      assertEquals(undeclaredFlags + missingManifest + missingDependencies, rejected.keys)
      rejected.forEach { (path, message) ->
        val expected = when (path) {
          in undeclaredFlags -> "Invalid native layer declarations"
          in missingManifest -> "Missing fancy manifest"
          else -> "Missing fancy dependency"
        }
        assertTrue("$path: $message", message.contains(expected))
      }
    }
  }

  private fun resources(config: String, vararg files: Pair<String, ByteArray>) =
    linkedMapOf("config.xml" to config.toByteArray()).apply { putAll(files) }

  private fun validate(input: Map<String, ByteArray>): Boolean {
    val blobs = input.mapValues { (path, bytes) ->
      PerfectIconBlob(path, path, bytes.size.toLong(), "validator-fixture/$path")
    }
    return PerfectDynamicIconValidator.validate(blobs) { input.getValue(it.sha) }
  }

  private fun rejects(input: Map<String, ByteArray>) {
    try {
      validate(input)
      fail("Incomplete or malformed dynamic resources were admitted")
    } catch (_: IllegalArgumentException) {
    } catch (_: IllegalStateException) {
    } catch (_: java.io.IOException) {
    }
  }
}
