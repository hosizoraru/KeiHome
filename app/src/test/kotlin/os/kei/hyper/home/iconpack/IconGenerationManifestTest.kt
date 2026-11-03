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
  @Test fun rejectsDuplicateNoncanonicalAndForgedDecisions() {
    listOf("KeiHomeIconGeneration1\ncoloros\t../outside\n", "KeiHomeIconGeneration1\ntarget\tos.test.app\ntarget\tos.test.app\n",
      "KeiHomeIconGeneration1\nnative\tos.test.app\tunbounded\n", "KeiHomeIconGeneration1").forEach {
      assertTrue(runCatching { IconGenerationManifest.decode(it.toByteArray()) }.isFailure)
    }
  }
}
