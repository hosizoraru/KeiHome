package os.kei.hyper.home.data

import org.junit.Assert.*
import org.junit.Test

class SystemIconFallbackTest {
  @Test fun existingNamesAndSourceIdsRemainCompatible() {
    SystemIconFallback.entries.forEach {
      assertEquals(it, SystemIconFallback.valueOf(it.name))
      assertEquals("pack:system-fallback-v6:${it.name}", it.sourceId("pack"))
    }
  }

  @Test fun oldPublicationsKeepTheirSourceAssociationButRequireTheNewRenderingContract() {
    for (version in listOf(1, 2, 3, 4, 5)) {
      val old = "pack:system-fallback-v$version:DEFAULT"
      assertEquals("pack", SystemIconFallback.baseSourceId(old))
      assertEquals("pack:system-fallback-v6:DEFAULT", SystemIconFallback.DEFAULT.sourceId(old))
      assertNotEquals(old, SystemIconFallback.DEFAULT.sourceId(old))
      assertEquals(SystemIconFallback.DEFAULT.sourceId(old),
        SystemIconFallback.DEFAULT.sourceId(SystemIconFallback.DEFAULT.sourceId(old)))
    }
  }

  @Test fun downloadedStylesPersistWithIndependentIdentities() {
    val hyper = SystemIconFallback.perfect("HyperOS4")
    val other = SystemIconFallback.perfect("OriginOS")
    assertEquals("HyperOS4", hyper.perfectStyle)
    assertEquals(hyper, SystemIconFallback.valueOf(hyper.name))
    assertNotEquals(hyper.sourceId("pack"), other.sourceId("pack"))
    assertEquals("pack", SystemIconFallback.baseSourceId(other.sourceId(hyper.sourceId("pack"))))
  }

  @Test fun invalidStyleNamesCannotInjectManifestLinesOrPaths() {
    listOf("", " ", ".", "..", "../icons", "x\\icons", "x\nDEFAULT", "x\u0000", "x".repeat(129)).forEach {
      assertTrue(it, runCatching { SystemIconFallback.perfect(it) }.isFailure)
    }
    assertTrue(runCatching { SystemIconFallback.valueOf("unknown") }.isFailure)
    assertNull(SystemIconFallback.DEFAULT.perfectStyle)
  }
}
