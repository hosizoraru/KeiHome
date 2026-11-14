package os.kei.hyper.home.ui.main

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconPackStore
import os.kei.hyper.home.iconpack.StoredIconPack
import os.kei.hyper.home.status.SystemIconThemeReport

class StatusIconPackDestinationTest {
  @Test fun currentAndHistoricalFallbackReceiptsRouteToTheOriginalPack() {
    val id = "a".repeat(64)
    val record = (imported(id).source as IconPackSource.Imported).record
    for (version in 1..4) {
      val metadata = statusIconPackMetadata(
        listOf(record), emptyMap(), mapOf("$id:system-fallback-v$version:DEFAULT" to "applied"),
        sources = emptyMap(), perfectPackId = id)
      assertEquals("perfect", statusIconPackDestination(theme, metadata))
    }
  }
  @get:Rule val temporary = TemporaryFolder()
  private val theme = SystemIconThemeReport(SystemIconThemeReport.Source.THEME, "Untrusted label",
    null, 1, 0, true, "revision", localId = "applied")
  private val nativeHash = "a".repeat(64)
  private val generated = IconPackSource.Generated(File("not-read.zip"), "b".repeat(64), 0)

  @Test fun routesAppliedStoredPackToEachSupportedSection() {
    val cases = listOf(
      imported("perfect-record") to "perfect",
      pack("apple", generated) to "apple",
      imported("coloros-record").copy(builtinKey = "coloros-classic-art-off") to "coloros",
      imported("builtin-record").copy(builtinKey = "other-native") to "builtin",
      pack("installed:example.pack", IconPackSource.Installed("example.pack", 1, 0)) to "installed",
      imported("document-record") to "imported",
    )
    for ((pack, section) in cases) {
      assertEquals(section, statusIconPackDestination(theme, applied(pack), "perfect-record"))
    }
  }

  @Test fun appliedHistoryTakesPrecedenceOverTheLatestAssociation() {
    val current = imported("current")
    val previous = imported("previous").copy(builtinKey = "other-native")
    val state = catalog(previous, current).copy(
      nativeSources = mapOf("applied" to previous.id),
      nativeAssociations = mapOf(current.id to "applied"),
    )
    assertEquals("builtin", statusIconPackDestination(theme, state))
  }

  @Test fun importedApkFormatsBelongToInstalledRatherThanImportedArchives() {
    for (format in listOf(IconPackFormat.APK, IconPackFormat.INSTALLED_APK)) {
      val original = imported("apk")
      val source = original.source as IconPackSource.Imported
      val pack = original.copy(format = format,
        source = source.copy(record = source.record.copy(format = format)))
      assertEquals("installed", statusIconPackDestination(theme, applied(pack)))
    }
  }

  @Test fun appliedPerfectReceiptResolvesBeforeTheCatalogScanFlagIsSet() {
    val pack = imported("published-perfect")
    assertEquals("perfect", statusIconPackDestination(theme,
      applied(pack).copy(scanned = false), perfectPackId = pack.id))
  }

  @Test fun initialEmptyCatalogCannotGuessPerfectFromTheGeneratedRecordId() {
    assertNull(statusIconPackDestination(theme.copy(title = "Perfect Icons"), IconPacksUiState(),
      perfectPackId = "published-perfect"))
  }

  @Test fun coldMetadataResolvesPerfectEvenWhenTheStoredPayloadCannotBeInspectedAsAnArchive() {
    val root = temporary.newFolder("store")
    val id = "c".repeat(64)
    val folder = File(root, id).also { it.mkdir() }
    val payload = File(folder, "original.zip").apply { writeText("not an archive") }
    File(folder, "metadata.properties").outputStream().use { output ->
      Properties().apply {
        setProperty("schema", "1")
        setProperty("originalName", "Not an identity")
        setProperty("format", "MIUI_ICONS")
        setProperty("storedBytes", payload.length().toString())
      }.store(output, null)
    }
    val listing = IconPackStore(root).list()
    assertEquals(1, listing.records.size)
    val metadata = statusIconPackMetadata(listing.records, emptyMap(), emptyMap(),
      mapOf("applied" to "$id:system-fallback-v1:DEFAULT"), perfectPackId = id)
    assertEquals("perfect", statusIconPackDestination(theme, metadata))
  }

  @Test fun coldBuiltinIndexResolvesColorOsWithoutAParsedDescriptorOrBrowserState() {
    val metadata = statusIconPackMetadata(emptyList(), mapOf("asset" to "coloros-classic-art-off"),
      emptyMap(), mapOf("applied" to "coloros:classic:SYSTEM:asset"), perfectPackId = null)
    assertEquals("coloros", statusIconPackDestination(theme, metadata))
    val builtin = metadata.copy(sections = mapOf("asset" to "builtin"),
      nativeSources = mapOf("applied" to "asset"))
    assertEquals("builtin", statusIconPackDestination(theme, builtin))
  }

  @Test fun coldStoredMetadataSeparatesApksAndOrdinaryImportedArchives() {
    for ((format, section) in listOf(IconPackFormat.APK to "installed",
      IconPackFormat.CLASSIC_ZIP to "imported", IconPackFormat.MIUI_MTZ to "imported")) {
      val record = (imported("document").source as IconPackSource.Imported).record.copy(format = format)
      val metadata = statusIconPackMetadata(listOf(record), emptyMap(), emptyMap(),
        mapOf("applied" to record.id), perfectPackId = null)
      assertEquals(section, statusIconPackDestination(theme, metadata))
    }
  }

  @Test fun coldDeletedPublicationCannotResolveFromThePersistedPerfectIdAlone() {
    val metadata = statusIconPackMetadata(emptyList(), emptyMap(), emptyMap(),
      mapOf("applied" to "deleted"), perfectPackId = "deleted")
    assertNull(statusIconPackDestination(theme, metadata))
  }

  @Test fun currentAssociationWorksWhenHistoricalReceiptIsAbsent() {
    val pack = imported("document")
    assertEquals("imported", statusIconPackDestination(theme,
      catalog(pack).copy(nativeAssociations = mapOf(pack.id to "applied"))))
  }

  @Test fun fallbackRenderingStyleDoesNotBecomeThePackDestination() {
    val pack = imported("document")
    for (style in listOf("PERFECT", "PERFECT_HYPEROS4", "COLOROS", "DEFAULT")) {
      assertEquals("imported", statusIconPackDestination(theme,
        applied(pack, "${pack.id}:system-fallback-v1:$style")))
    }
  }

  @Test fun colorOsReceiptResolvesTheStoredAssetAcrossAppearanceAndFallbackVariants() {
    val pack = imported("asset").copy(builtinKey = "coloros-classic-art-on")
    for (mode in listOf("SYSTEM", "LIGHT", "DARK", "DIM_AT_NIGHT")) {
      assertEquals("coloros", statusIconPackDestination(theme,
        applied(pack, "coloros:classic:$mode:${pack.id}:system-fallback-v1:DEFAULT")))
    }
  }

  @Test fun malformedOrUnknownColorOsReceiptDoesNotGuessAnAsset() {
    val pack = imported("asset").copy(builtinKey = "coloros-classic-art-on")
    for (source in listOf("coloros:classic:SYSTEM", "coloros:unknown:SYSTEM:asset",
      "coloros:classic:unknown:asset", "coloros:classic:SYSTEM:")) {
      assertNull(source, statusIconPackDestination(theme, applied(pack, source)))
    }
  }

  @Test fun exactNativeHashMayResolveAnImportedPackWithoutAnAppReceipt() {
    val pack = imported("document")
    val report = theme.copy(localId = null, nativeHash = nativeHash)
    assertEquals("imported", statusIconPackDestination(report, catalog(pack)))
    assertEquals("imported", statusIconPackDestination(report.copy(localId = "external"), catalog(pack)))
  }

  @Test fun missingOrAmbiguousHashDoesNotPickAnArbitraryPack() {
    val state = catalog(imported("one"), imported("two"))
    assertNull(statusIconPackDestination(theme.copy(localId = null, nativeHash = nativeHash), state))
    assertNull(statusIconPackDestination(theme.copy(localId = null), state))
    assertNull(statusIconPackDestination(theme.copy(localId = null, nativeHash = "not-a-hash"), state))
  }

  @Test fun ambiguousAssociationsNeedAnAppliedHistoryReceipt() {
    val first = imported("first")
    val second = imported("second")
    val state = catalog(first, second).copy(
      nativeAssociations = mapOf(first.id to "applied", second.id to "applied"))
    assertNull(statusIconPackDestination(theme.copy(nativeHash = nativeHash), state))
    assertEquals("imported", statusIconPackDestination(theme,
      state.copy(nativeSources = mapOf("applied" to second.id))))
  }

  @Test fun deletedAppliedSourceDoesNotFallBackToMatchingHashOrAnotherAssociation() {
    val remaining = imported("remaining")
    val state = applied(remaining, "deleted").copy(
      nativeAssociations = mapOf(remaining.id to "applied"))
    assertNull(statusIconPackDestination(theme.copy(nativeHash = nativeHash), state))
    assertNull(statusIconPackDestination(theme, applied(remaining, "")))
  }

  @Test fun defaultUnavailableIncompleteAndMissingReportsKeepTheRoot() {
    val state = applied(imported("document"))
    assertNull(statusIconPackDestination(null, state))
    assertNull(statusIconPackDestination(theme.copy(source = SystemIconThemeReport.Source.DEFAULT), state))
    assertNull(statusIconPackDestination(theme.copy(source = SystemIconThemeReport.Source.UNAVAILABLE), state))
    assertNull(statusIconPackDestination(theme.copy(complete = false), state))
    assertNull(statusIconPackDestination(theme, IconPacksUiState()))
  }

  @Test fun labelsAndArchivePathsCannotSupplyIdentityOrPerfectProvenance() {
    val document = imported("document").copy(displayName = "Perfect Icons")
    assertEquals("imported", statusIconPackDestination(theme.copy(title = "Apple"),
      applied(document), perfectPackId = "different-record"))
    assertNull(statusIconPackDestination(theme.copy(title = "Perfect Icons",
      archivePath = "/perfect-icons/generated/perfect-output.zip", localId = null),
      catalog(document, pack("apple", generated))))
  }

  @Test fun previewAndUnknownGeneratedPacksNeverBecomePublishedDestinations() {
    for (id in listOf("perfect-preview:hash", "other-generated")) {
      val pack = pack(id, generated)
      assertNull(statusIconPackDestination(theme, applied(pack), perfectPackId = id))
    }
    assertEquals("imported", statusIconPackDestination(theme, applied(imported("older-perfect")),
      perfectPackId = "current-perfect"))
  }

  private fun imported(id: String) = pack(id, IconPackSource.Imported(StoredIconPack(
    id, "Untrusted filename.zip", 0, IconPackFormat.MIUI_ICONS, File("not-read.zip"),
    File("not-read-icons.zip"), 1, nativeHash,
  )))

  private fun pack(id: String, source: IconPackSource) = IconPackDescriptor(
    id, "Untrusted display name", IconPackFormat.MIUI_ICONS, source, emptyList())

  private fun catalog(vararg packs: IconPackDescriptor) = IconPacksUiState(
    packs = packs.map { IconPackCardData(it, 0, emptyList()) }, scanned = true)

  private fun applied(pack: IconPackDescriptor, source: String = pack.id) =
    catalog(pack).copy(nativeSources = mapOf("applied" to source))
}
