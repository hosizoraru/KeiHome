package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.RecommendedIconMappings

class IconGenerationManifestTest {
  @Test fun schemaThirteenPreservesColorOsGeometryProvenance() {
    val value = IconGenerationManifest(schemaVersion = IconGenerationManifest.CURRENT_SCHEMA,
      colorOsPackages = setOf("com.android.settings"))
    assertEquals(value, IconGenerationManifest.decode(value.encode()))
  }

  @Test fun schemasEightAndNineRecordSupportedMappingsInCanonicalMetadataBeforeDecisions() {
    for (schema in listOf(8, 9)) for (bits in 0..(if (schema == 8) 3 else 7)) {
      val value = IconGenerationManifest(
        colorOsPackages = setOf("os.test.app"),
        considered = setOf("os.test.app/os.test.app.Main"),
        nativeAdaptations = mapOf("os.test.app" to "none"),
        installedOnly = true,
        activeLauncherTargets = setOf("os.test.app/os.test.app.Main"),
        preservedIconPackages = setOf("os.test.preserved"),
        schemaVersion = schema,
        recommendedMappings = RecommendedIconMappings.fromBits(bits),
      )
      assertEquals(schema, value.schemaVersion)
      val canonical = ("KeiHomeIconGeneration$schema\nscope\tinstalled\nshape\toriginal\nmask\toriginal\n" +
        "mappings\t$bits\ncoloros\tos.test.app\ntarget\tos.test.app/os.test.app.Main\n" +
        "native\tos.test.app\tnone\nactive\tos.test.app/os.test.app.Main\npreserve\tos.test.preserved\n")
        .toByteArray(Charsets.UTF_8)
      assertArrayEquals(canonical, value.encode())
      assertEquals(value, IconGenerationManifest.decode(canonical))
      assertArrayEquals(canonical, IconGenerationManifest.decode(canonical).encode())
    }
    assertArrayEquals(
      "KeiHomeIconGeneration9\nscope\tall\nshape\toriginal\nmask\toriginal\nmappings\t7\n"
        .toByteArray(Charsets.UTF_8),
      IconGenerationManifest().encode())
  }

  @Test fun schemasOneThroughEightKeepPhoneMappingOffAndRejectEncodingItAsEnabled() {
    val headers = listOf(
      "KeiHomeIconGeneration1\n",
      "KeiHomeIconGeneration2\nscope\tall\n",
      "KeiHomeIconGeneration3\nscope\tall\nshape\toriginal\n",
      "KeiHomeIconGeneration4\nscope\tall\nshape\toriginal\nmask\toriginal\n",
      "KeiHomeIconGeneration5\nscope\tall\nshape\toriginal\nmask\toriginal\n",
      "KeiHomeIconGeneration6\nscope\tall\nshape\toriginal\nmask\toriginal\n",
      "KeiHomeIconGeneration7\nscope\tall\nshape\toriginal\nmask\toriginal\n",
      "KeiHomeIconGeneration8\nscope\tall\nshape\toriginal\nmask\toriginal\nmappings\t3\n",
    )
    for (schema in 1..8) {
      val value = IconGenerationManifest(schemaVersion = schema)
      val bytes = headers[schema - 1].toByteArray(Charsets.UTF_8)
      assertArrayEquals(bytes, value.encode())
      val decoded = IconGenerationManifest.decode(bytes)
      assertEquals(schema, decoded.schemaVersion)
      assertEquals(RecommendedIconMappings.LEGACY, decoded.recommendedMappings)
      assertFalse(decoded.recommendedMappings.phoneCompanions)
      assertArrayEquals(bytes, decoded.encode())
      for (bits in 4..7) {
        assertThrows("schema=$schema bits=$bits", IllegalArgumentException::class.java) {
          value.copy(recommendedMappings = RecommendedIconMappings.fromBits(bits)).encode()
        }
      }
      if (schema < 8) {
        for (bits in 0..2) {
          assertThrows("schema=$schema bits=$bits", IllegalArgumentException::class.java) {
            value.copy(recommendedMappings = RecommendedIconMappings.fromBits(bits)).encode()
          }
        }
        assertThrows("schema=$schema", IllegalArgumentException::class.java) {
          IconGenerationManifest.decode(bytes + "mappings\t3\n".toByteArray(Charsets.UTF_8))
        }
      }
    }
    val previous = ("KeiHomeIconGeneration7\nscope\tall\nshape\toriginal\nmask\toriginal\n" +
      "preserve\tos.test.preserved\n").toByteArray(Charsets.UTF_8)
    assertEquals(7, IconGenerationManifest.decode(previous).schemaVersion)
    assertEquals(setOf("os.test.preserved"), IconGenerationManifest.decode(previous).preservedIconPackages)
    assertArrayEquals(previous, IconGenerationManifest.decode(previous).encode())
  }

  @Test fun schemasEightAndNineRejectMissingInvalidDuplicateAndNoncanonicalMappingMetadata() {
    for (schema in listOf(8, 9)) {
      val header = "KeiHomeIconGeneration$schema\nscope\tall\nshape\toriginal\nmask\toriginal\n"
      val outOfRange = if (schema == 8) listOf("4", "5", "6", "7", "8") else listOf("8", "9", "15")
      val malformedRows = listOf("", "mappings\t3\nmappings\t3\n", "mappings\t1\nmappings\t2\n",
        "mappings\t3\textra\n", "mappings\t3\t\n") +
        (outOfRange + listOf("", "-1", "255", "2147483648", "+1", "01", " 1", "1 ", "1.0", "true"))
          .map { "mappings\t$it\n" }
      for (rows in malformedRows) {
        assertThrows("schema=$schema rows=$rows", IllegalArgumentException::class.java) {
          IconGenerationManifest.decode((header + rows).toByteArray(Charsets.UTF_8))
        }
      }
      for (text in listOf(
        "KeiHomeIconGeneration$schema\nmappings\t3\nscope\tall\nshape\toriginal\nmask\toriginal\n",
        "${header}target\tos.test.app\nmappings\t3\n",
      )) {
        assertThrows(text, IllegalArgumentException::class.java) {
          IconGenerationManifest.decode(text.toByteArray(Charsets.UTF_8))
        }
      }
    }
  }

  @Test fun upgradingSchemaEightRetainsBothOldChoicesAndDoesNotSilentlyEnablePhoneMapping() {
    for (bits in 0..3) {
      val bytes = ("KeiHomeIconGeneration8\nscope\tinstalled\nshape\toriginal\nmask\toriginal\n" +
        "mappings\t$bits\npreserve\tos.test.app\n").toByteArray(Charsets.UTF_8)
      val old = IconGenerationManifest.decode(bytes)
      assertFalse(old.recommendedMappings.phoneCompanions)
      assertEquals(bits, old.recommendedMappings.bits)
      assertArrayEquals(bytes, old.encode())
      val upgraded = old.copy(schemaVersion = 9)
      assertEquals(old.recommendedMappings, upgraded.recommendedMappings)
      assertEquals(upgraded, IconGenerationManifest.decode(upgraded.encode()))
      assertFalse(upgraded.recommendedMappings.phoneCompanions)
      assertEquals(setOf("os.test.app"), upgraded.preservedIconPackages)
    }
    assertTrue(IconGenerationManifest().recommendedMappings.phoneCompanions)
  }

  @Test fun preservedPackagesRoundTripWithoutChangingLegacySchemaMeaning() {
    val value = IconGenerationManifest(preservedIconPackages = setOf("android", "org.telegram.messenger.web", "os.kei"))
    assertEquals(value, IconGenerationManifest.decode(value.encode()))
    assertTrue(value.encode().toString(Charsets.UTF_8).contains("preserve\tos.kei\n"))
    assertEquals(emptySet<String>(), IconGenerationManifest.decode(
      IconGenerationManifest(schemaVersion = 6).encode()).preservedIconPackages)
    assertThrows(IllegalArgumentException::class.java) { value.copy(schemaVersion = 6).encode() }
  }
  @Test fun recordsCustomMaskIdentityAndRejectsMissingOrInconsistentIdentity() {
    val custom = IconGenerationManifest(completeSystemIconConfig = true, iconMaskIdentity = "custom-${"a".repeat(64)}")
    assertEquals(custom, IconGenerationManifest.decode(custom.encode()))
    assertFalse(custom.encode().contentEquals(IconGenerationManifest(completeSystemIconConfig = true).encode()))
    assertThrows(IllegalArgumentException::class.java) {
      IconGenerationManifest(completeSystemIconConfig = false, iconMaskIdentity = custom.iconMaskIdentity).encode()
    }
    assertThrows(IllegalArgumentException::class.java) {
      IconGenerationManifest.decode(custom.encode().toString(Charsets.UTF_8)
        .lineSequence().filterNot { it.startsWith("mask\t") }.joinToString("\n").toByteArray())
    }
    val old = "KeiHomeIconGeneration3\nscope\tall\nshape\tcomplete\n".toByteArray()
    assertArrayEquals(old, IconGenerationManifest.decode(old).encode())
  }
  @Test fun shapeCompletionPolicyRoundTripsAndLegacySchemasKeepTheirMeaning() {
    for (enabled in listOf(false, true)) {
      val value = IconGenerationManifest(completeSystemIconConfig = enabled)
      assertEquals(9, value.schemaVersion)
      assertEquals(value, IconGenerationManifest.decode(value.encode()))
    }
    val old = "KeiHomeIconGeneration2\nscope\tall\n".toByteArray()
    assertFalse(IconGenerationManifest.decode(old).completeSystemIconConfig)
    assertArrayEquals(old, IconGenerationManifest.decode(old).encode())
    for (text in listOf("KeiHomeIconGeneration3\nscope\tall\n",
      "KeiHomeIconGeneration3\nscope\tall\nshape\tunknown\n",
      "KeiHomeIconGeneration3\nscope\tall\nshape\tcomplete\nshape\toriginal\n",
      "KeiHomeIconGeneration2\nscope\tall\nshape\tcomplete\n")) {
      assertTrue(runCatching { IconGenerationManifest.decode(text.toByteArray()) }.isFailure)
    }
  }

  @Test fun previousMaskSchemaRemainsByteCompatibleAfterComponentFilenameUpgrade() {
    val previous = IconGenerationManifest(schemaVersion = 4, completeSystemIconConfig = true)
    assertEquals(previous, IconGenerationManifest.decode(previous.encode()))
    assertEquals(4, IconGenerationManifest.decode(previous.encode()).schemaVersion)
    assertFalse(previous.encode().contentEquals(previous.copy(schemaVersion = 5).encode()))
  }

  @Test fun activeLauncherSnapshotIsCanonicalAndIndependentOfConsideredTargets() {
    val first = "os.test.app/os.foreign.LauncherAndroid"
    val second = "os.test.app/os.foreign.LauncherApple"
    val value = IconGenerationManifest(
      schemaVersion = 6, considered = setOf("os.test.app", second),
      activeLauncherTargets = linkedSetOf(second, first),
    )
    val bytes = ("KeiHomeIconGeneration6\nscope\tall\nshape\toriginal\nmask\toriginal\n" +
      "target\tos.test.app\ntarget\t$second\nactive\t$first\nactive\t$second\n").toByteArray()
    assertArrayEquals(bytes, value.encode())
    assertEquals(value, IconGenerationManifest.decode(bytes))
    assertArrayEquals(bytes, value.copy(activeLauncherTargets = linkedSetOf(first, second)).encode())
    assertTrue(IconGenerationManifest.decode(IconGenerationManifest().encode()).activeLauncherTargets.isEmpty())
  }

  @Test fun activeRowsRejectDuplicatesRelativeComponentsAndNoncanonicalOrdering() {
    val header = "KeiHomeIconGeneration6\nscope\tall\nshape\toriginal\nmask\toriginal\n"
    val first = "os.test.app/os.foreign.A"
    val second = "os.test.app/os.foreign.B"
    for (rows in listOf(
      "active\t$first\nactive\t$first\n",
      "active\t$second\nactive\t$first\n",
      "active\tos.test.app\n",
      "active\tos.test.app/\n",
      "active\tos.test.app/.Launcher\n",
      "active\tComponentInfo{$first}\n",
      "active\t $first\n",
      "active\t../outside/os.test.Main\n",
      "active\t$first\textra\n",
      "active\t$first\ntarget\t$first\n",
    )) {
      assertThrows(rows, IllegalArgumentException::class.java) {
        IconGenerationManifest.decode((header + rows).toByteArray())
      }
    }
    for (target in listOf("os.test.app", "os.test.app/.Launcher", "os.test.app/")) {
      assertThrows(IllegalArgumentException::class.java) {
        IconGenerationManifest(activeLauncherTargets = setOf(target)).encode()
      }
    }
  }

  @Test fun schemasOneThroughFiveRemainByteCompatibleAndRejectActiveRows() {
    val headers = listOf(
      "KeiHomeIconGeneration1\n",
      "KeiHomeIconGeneration2\nscope\tall\n",
      "KeiHomeIconGeneration3\nscope\tall\nshape\toriginal\n",
      "KeiHomeIconGeneration4\nscope\tall\nshape\toriginal\nmask\toriginal\n",
      "KeiHomeIconGeneration5\nscope\tall\nshape\toriginal\nmask\toriginal\n",
    )
    val target = "os.test.app/os.foreign.Launcher"
    headers.forEachIndexed { index, header ->
      val bytes = (header + "target\t$target\n").toByteArray()
      val value = IconGenerationManifest.decode(bytes)
      assertEquals(index + 1, value.schemaVersion)
      assertTrue(value.activeLauncherTargets.isEmpty())
      assertArrayEquals(bytes, value.encode())
      assertThrows(IllegalArgumentException::class.java) {
        IconGenerationManifest.decode((header + "active\t$target\n").toByteArray())
      }
      assertThrows(IllegalArgumentException::class.java) {
        value.copy(activeLauncherTargets = setOf(target)).encode()
      }
    }
  }

  @Test fun activeRowsHaveRoomBeyondTheLegacyParserRowLimit() {
    val targets = (0..15000).mapTo(linkedSetOf()) { "os.test.app/os.test.Launcher$it" }
    val value = IconGenerationManifest(considered = targets, activeLauncherTargets = targets)
    assertEquals(value, IconGenerationManifest.decode(value.encode()))
    val legacy = value.copy(schemaVersion = 5, activeLauncherTargets = emptySet(),
      recommendedMappings = RecommendedIconMappings.LEGACY)
    assertEquals(legacy, IconGenerationManifest.decode(legacy.encode()))
  }

  @Test fun preservesSourceCountsDecisionsAndIndependentNativeFingerprints() {
    val value = IconGenerationManifest(setOf("mark.via"), setOf("mark.via", "com.apple.android.music.Main"),
      mapOf("mark.via" to "none", "com.apple.android.music" to "a".repeat(64)))
    assertEquals(value, IconGenerationManifest.decode(value.encode()))
    assertEquals(IconGenerationManifest(), IconGenerationManifest.decode(IconGenerationManifest().encode()))
  }

  @Test fun canonicalLegacyManifestRemainsReadableWithoutChangingItsSchemaOrBytes() {
    val bytes = ("KeiHomeIconGeneration1\n" +
      "coloros\tos.test.app\n" +
      "target\tos.test.app/os.test.app.Main\n" +
      "native\tos.test.app/os.test.app.Main\tnone\n").toByteArray(Charsets.UTF_8)
    val decoded = IconGenerationManifest.decode(bytes)
    assertEquals(1, decoded.schemaVersion)
    assertFalse(decoded.installedOnly)
    assertEquals(setOf("os.test.app"), decoded.colorOsPackages)
    assertEquals(setOf("os.test.app/os.test.app.Main"), decoded.considered)
    assertEquals(mapOf("os.test.app/os.test.app.Main" to "none"), decoded.nativeAdaptations)
    assertArrayEquals(bytes, decoded.encode())

    val empty = "KeiHomeIconGeneration1\n".toByteArray(Charsets.UTF_8)
    assertEquals(IconGenerationManifest(schemaVersion = 1), IconGenerationManifest.decode(empty))
    assertArrayEquals(empty, IconGenerationManifest.decode(empty).encode())
  }

  @Test fun legacyDecodingStillRejectsNoncanonicalDecisionOrder() {
    val bytes = ("KeiHomeIconGeneration1\n" +
      "target\tos.test.app\n" +
      "coloros\tos.test.app\n").toByteArray(Charsets.UTF_8)
    assertTrue(runCatching { IconGenerationManifest.decode(bytes) }.isFailure)
  }

  @Test fun versionTwoRoundTripsBothScopesWithCanonicalBytes() {
    for (installedOnly in listOf(false, true)) {
      val value = IconGenerationManifest(
        colorOsPackages = setOf("os.test.other", "os.test.app"),
        considered = setOf("os.test.other", "os.test.app/os.test.app.Main"),
        nativeAdaptations = mapOf("os.test.other" to "a".repeat(64), "os.test.app" to "none"),
        installedOnly = installedOnly,
        schemaVersion = 2,
      )
      val scope = if (installedOnly) "installed" else "all"
      val bytes = ("KeiHomeIconGeneration2\nscope\t$scope\n" +
        "coloros\tos.test.app\ncoloros\tos.test.other\n" +
        "target\tos.test.app/os.test.app.Main\ntarget\tos.test.other\n" +
        "native\tos.test.app\tnone\nnative\tos.test.other\t${"a".repeat(64)}\n")
        .toByteArray(Charsets.UTF_8)
      assertArrayEquals(bytes, value.encode())
      assertEquals(value, IconGenerationManifest.decode(bytes))
      val empty = IconGenerationManifest(installedOnly = installedOnly)
      assertEquals(empty, IconGenerationManifest.decode(empty.encode()))
    }
  }

  @Test fun versionTwoRejectsDuplicateAndConflictingScopeRows() {
    for (first in listOf("all", "installed")) {
      for (second in listOf("all", "installed")) {
        val text = "KeiHomeIconGeneration2\nscope\t$first\nscope\t$second\n"
        assertTrue(text, runCatching { IconGenerationManifest.decode(text.toByteArray()) }.isFailure)
      }
    }
  }

  @Test fun versionTwoRequiresScopeEvenWhenThereAreNoDecisions() {
    listOf("KeiHomeIconGeneration2\n", "KeiHomeIconGeneration2\ntarget\tos.test.app\n").forEach {
      assertTrue(it, runCatching { IconGenerationManifest.decode(it.toByteArray()) }.isFailure)
    }
  }

  @Test fun scopeMustBeKnownCanonicalAndOnlyPresentInVersionTwo() {
    listOf(
      "KeiHomeIconGeneration2\nscope\tunknown\n",
      "KeiHomeIconGeneration2\nscope\tinstalled\textra\n",
      "KeiHomeIconGeneration2\ntarget\tos.test.app\nscope\tinstalled\n",
      "KeiHomeIconGeneration1\nscope\tall\n",
    ).forEach {
      assertTrue(it, runCatching { IconGenerationManifest.decode(it.toByteArray()) }.isFailure)
    }
  }

  @Test fun rejectsDuplicateNoncanonicalAndForgedDecisions() {
    listOf("KeiHomeIconGeneration1\ncoloros\t../outside\n", "KeiHomeIconGeneration1\ntarget\tos.test.app\ntarget\tos.test.app\n",
      "KeiHomeIconGeneration1\nnative\tos.test.app\tunbounded\n", "KeiHomeIconGeneration1").forEach {
      assertTrue(runCatching { IconGenerationManifest.decode(it.toByteArray()) }.isFailure)
    }
  }
}
