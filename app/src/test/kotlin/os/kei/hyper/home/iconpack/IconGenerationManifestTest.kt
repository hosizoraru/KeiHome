package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class IconGenerationManifestTest {
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
