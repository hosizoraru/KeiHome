package os.kei.hyper.home.iconpack

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BuiltinIconPackResolverTest {
  private fun pack(key: String) = IconPackDescriptor(key, key, IconPackFormat.INSTALLED_APK,
    IconPackSource.Installed("org.example.builtin", 1, 1), emptyList(), builtinKey = key)

  @Test fun unopenedCatalogLoadsTheRequestedArtworkWithoutSelectingAnotherStyle() = runBlocking {
    val target = pack("coloros-classic-art-on")
    assertSame(target, resolveBuiltinIconPack(target.builtinKey!!, emptyList()) {
      IconPackScanResult(listOf(pack("coloros-classic-art-off"), target))
    })
    assertNull(resolveBuiltinIconPack("missing", emptyList()) { IconPackScanResult(listOf(target)) })
  }

  @Test fun existingSnapshotIsReusedWithoutReadingTheRepositoryAgain() = runBlocking {
    val target = pack("coloros-classic-art-on")
    assertSame(target, resolveBuiltinIconPack(target.builtinKey!!, listOf(target)) {
      error("An already loaded immutable snapshot should be reused")
    })
  }

  @Test fun cancellationIsNotPresentedAsAnUnavailablePack() = runBlocking {
    val cancellation = CancellationException("cancel owned preparation")
    try {
      resolveBuiltinIconPack("coloros-classic-art-on", emptyList()) { throw cancellation }
      fail("Expected cancellation")
    } catch (actual: CancellationException) { assertSame(cancellation, actual) }
  }
}
