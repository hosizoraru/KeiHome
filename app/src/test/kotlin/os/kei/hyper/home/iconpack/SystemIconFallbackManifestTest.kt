package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.SystemIconFallback

class SystemIconFallbackManifestTest {
  private val hash = "0123456789abcdef".repeat(4)
  private val styles = listOf("HyperOS4", "Material You", "iOS")

  @Test fun legacyHeadersKeepExactBytesAndFlagsForNativeAndPerfectStyles() {
    for (style in SystemIconFallback.entries + styles.map { SystemIconFallback.perfect(it) }) {
      for (nameMatching in listOf(false, true)) for (colorOs in listOf(false, true)) {
        val header = 1 + (if (nameMatching) 1 else 0) + (if (colorOs) 2 else 0)
        val text = "KeiHomeSystemFallback$header\n${style.name}\ncom.a\ncom.z\n"
        val manifest = SystemIconFallbackManifest(style, setOf("com.z", "com.a"), nameMatching, colorOs)
        assertEquals(text, manifest.encode().toString(Charsets.UTF_8))
        assertEquals(manifest, SystemIconFallbackManifest.decode(text.toByteArray(Charsets.UTF_8)))
        assertNull(manifest.perfectSource)
      }
    }
  }

  @Test fun sourceHeadersStoreFullArchiveHashAfterStyleAndPreserveBothFlags() {
    for (style in styles.map { SystemIconFallback.perfect(it) }) {
      for (nameMatching in listOf(false, true)) for (colorOs in listOf(false, true)) {
        val header = 5 + (if (nameMatching) 1 else 0) + (if (colorOs) 2 else 0)
        val text = "KeiHomeSystemFallback$header\n${style.name}\n$hash\ncom.a\ncom.z\n"
        val manifest = SystemIconFallbackManifest(style, setOf("com.z", "com.a"), nameMatching, colorOs, hash)
        assertEquals(text, manifest.encode().toString(Charsets.UTF_8))
        assertEquals(manifest, SystemIconFallbackManifest.decode(text.toByteArray(Charsets.UTF_8)))
      }
    }
  }

  @Test fun emptyInventoriesRoundTripWithAndWithoutSourceMetadata() {
    for (source in listOf(null, hash)) {
      val manifest = SystemIconFallbackManifest(SystemIconFallback.perfect("HyperOS4"), emptySet(),
        perfectSource = source)
      assertEquals(manifest, SystemIconFallbackManifest.decode(manifest.encode()))
    }
  }

  @Test fun sourceMetadataRequiresPerfectStyleAndCanonicalFullSha256() {
    for (style in SystemIconFallback.entries) {
      assertThrows(IllegalArgumentException::class.java) {
        SystemIconFallbackManifest(style, emptySet(), perfectSource = hash)
      }
      reject("KeiHomeSystemFallback5\n${style.name}\n$hash\n")
    }
    for (invalid in listOf("", hash.take(40), hash.dropLast(1), hash + "0",
        hash.uppercase(), "g".repeat(64), " $hash", "$hash ", "$hash\n../bad")) {
      assertThrows(IllegalArgumentException::class.java) {
        SystemIconFallbackManifest(SystemIconFallback.perfect("HyperOS4"), emptySet(), perfectSource = invalid)
      }
      reject("KeiHomeSystemFallback5\nPERFECT:HyperOS4\n$invalid\n")
    }
  }

  @Test fun malformedAndNoncanonicalSourceHeadersAreRejected() {
    for (header in 5..8) {
      val prefix = "KeiHomeSystemFallback$header\nPERFECT:HyperOS4\n"
      for (text in listOf(prefix, "${prefix}com.a\n", "$prefix$hash",
          "$prefix$hash\n../foo\n", "$prefix$hash\ncom.a\ncom.a\n",
          "$prefix$hash\ncom.z\ncom.a\n", "$prefix$hash\n\n",
          "$prefix$hash\n$hash\n")) {
        reject(text)
      }
    }
    for (header in listOf("0", "9", "05", "-1")) {
      reject("KeiHomeSystemFallback$header\nPERFECT:HyperOS4\n$hash\n")
    }
    reject("KeiHomeSystemFallback1\nPERFECT:HyperOS4\n$hash\n")
    reject("KeiHomeSystemFallback5\nPERFECT:\n$hash\n")
    reject("KeiHomeSystemFallback5\nPERFECT:../bad\n$hash\n")
    reject("KeiHomeSystemFallback5\nUNKNOWN\n$hash\n")
  }

  private fun reject(text: String) {
    assertThrows(IllegalArgumentException::class.java) {
      SystemIconFallbackManifest.decode(text.toByteArray(Charsets.UTF_8))
    }
  }


  @Test fun provenanceAcceptsQualifiedNativeNamesWithoutAcceptingPathsOrExtraSeparators() {
    val manifest = SystemIconFallbackManifest(SystemIconFallback.entries.first(),
      setOf("com.example.debug#com.example.Main"))
    assertEquals(manifest, SystemIconFallbackManifest.decode(manifest.encode()))
    for (invalid in listOf("com.example#../Main", "com.example#com.other#Main", "#com.example.Main", "com.example#")) {
      reject("KeiHomeSystemFallback1\n${manifest.style.name}\n$invalid\n")
    }
  }
}
