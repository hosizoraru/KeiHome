package os.kei.buildlogic

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.gradle.api.GradleException

class KeiHomeVersioningTest {
  @Test fun concurrentAllocationsAreDistinctAndFollowTheVersionSeries() {
    val dir = Files.createTempDirectory("keihome-version-test")
    val executor = Executors.newFixedThreadPool(8)
    try {
      val series = VersionSeries.parse("1.2.3")
      val results = executor.invokeAll((0 until 20).map {
        Callable { KeiHomeVersioning.allocate(dir, series, 106) }
      }).map { it.get() }.sorted()
      assertEquals(20, results.distinct().size)
      assertEquals((results.first()..results.last()).toList(), results)
      assertEquals(102_030_106, results.first())
      assertEquals(results.last() + 1, KeiHomeVersioning.allocate(dir, series, 4))
      assertEquals(102_040_106, KeiHomeVersioning.allocate(dir, VersionSeries.parse("1.2.4"), 106))
      assertTrue(results.first() < 2_100_000_000)
    } finally {
      executor.shutdownNow()
      Files.walk(dir).sorted(Comparator.reverseOrder()).use { paths -> paths.forEach(Files::deleteIfExists) }
    }
  }

  @Test fun invalidSeriesAndAnExhaustedSlotFailInsteadOfWrapping() {
    assertThrows(GradleException::class.java) { VersionSeries.parse("21.0.0") }
    assertThrows(GradleException::class.java) { VersionSeries.parse("1.100.0") }
    assertThrows(GradleException::class.java) { KeiHomeVersioning.nextCode(VersionSeries.parse("1.2.3"), 9_999, 1) }
  }
}
