package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class CustomIconGenerationManifestTest {
  private fun manifest() = IconGenerationManifest()

  @Test fun unchangedPacksKeepTheExistingV9WireFormat() {
    val original = manifest()
    assertTrue(original.encode().toString(Charsets.UTF_8).startsWith("KeiHomeIconGeneration9\n"))
    assertEquals(original, IconGenerationManifest.decode(original.encode()))
  }

  @Test fun roundTripsTheExactCustomizationRevision() {
    val manifest = manifest().copy(schemaVersion = 10, customIconIdentity = "ab".repeat(32))
    assertEquals(manifest, IconGenerationManifest.decode(manifest.encode()))
  }

  @Test(expected = IllegalArgumentException::class) fun olderSchemasCannotClaimToIncludeCustomIcons() {
    manifest().copy(customIconIdentity = "ab".repeat(32)).encode()
  }

  @Test fun customizationIdentityIgnoresOrderingButTracksAppAndArtwork() {
    fun row(pkg: String, digest: String) = CustomIconSelection(pkg, pkg, "source", "Source", "entry",
      "token", digest, 1)
    val first = row("com.first", "a")
    val second = row("com.second", "b")
    fun identity(rows: List<CustomIconSelection>) = CustomIconSnapshot(rows, emptyMap()).identity
    assertNull(identity(emptyList()))
    assertEquals(identity(listOf(first, second)), identity(listOf(second, first)))
    assertNotEquals(identity(listOf(first)), identity(listOf(first.copy(digest = "c"))))
    assertNotEquals(identity(listOf(first)), identity(listOf(first.copy(packageName = "com.third"))))
  }
}
