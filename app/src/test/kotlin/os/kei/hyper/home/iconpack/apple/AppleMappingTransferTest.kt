package os.kei.hyper.home.iconpack.apple

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppleMappingTransferTest {
  private fun choice(pkg: String = "uninstalled.app", platform: ApplePlatform = ApplePlatform.IOS,
    country: String = "jp", id: String = "1108187390") = AppleTransferredMapping(pkg, platform, country,
    AppleAppMatch(id, "Music \"Live\" \uD83C\uDFB5", "Publisher", "com.apple.Music", country))

  private fun rejected(block: () -> Unit) {
    try { block() } catch (_: Exception) { return }
    fail("Expected invalid transfer to be rejected")
  }

  private fun document(rows: String, schema: String = "1") =
    """{"format":"keihome.apple-mappings","schema":$schema,"mappings":[$rows]}""".toByteArray()

  private val row = """{"packageName":"some.app","platform":"IOS","country":"jp","app":{"id":"1108187390","name":"Music","publisher":"Apple","bundleId":"com.apple.Music"}}"""

  @Test fun allPlatformsExclusionsUninstalledAppsAndStorefrontsRoundTrip() {
    val choices = listOf(choice(), choice(platform = ApplePlatform.IPADOS, country = "hk"),
      choice(platform = ApplePlatform.MACOS, country = "us"),
      choice("excluded.app").copy(app = null), choice("system.settings", id = "system_settings"))
    val bytes = AppleMappingTransfer.encode(choices)
    assertEquals(choices.toSet(), AppleMappingTransfer.decode(bytes).toSet())
    assertEquals(bytes.toList(), AppleMappingTransfer.encode(choices.reversed()).toList())
    assertTrue(String(bytes).contains("\"country\":\"hk\""))
    assertFalse(String(bytes).contains("artwork"))
    assertFalse(String(bytes).contains("options"))
  }

  @Test fun emptyTransferIsValidAndDoesNotClearSavedChoices() {
    val existing = listOf(choice())
    assertTrue(AppleMappingTransfer.decode(AppleMappingTransfer.encode(emptyList())).isEmpty())
    assertEquals(existing, AppleMappingTransfer.merge(existing, emptyList()))
  }

  @Test fun mergeReplacesOnlyIdenticalPackageAndPlatformIncludingExclusions() {
    val ios = choice()
    val ipad = choice(platform = ApplePlatform.IPADOS)
    val unrelated = choice("other.app")
    val incoming = ios.copy(country = "us", app = null)
    assertEquals(setOf(incoming, ipad, unrelated), AppleMappingTransfer.merge(
      listOf(ios, ipad, unrelated), listOf(incoming)).toSet())
    assertEquals(setOf(ios, ipad, unrelated), AppleMappingTransfer.merge(
      listOf(incoming, ipad, unrelated), listOf(ios)).toSet())
  }

  @Test fun savedPreferencesIncludeOnlyManualKeysAcrossPlatforms() {
    val prefs = mapOf("options" to "private", "generated" to "private",
      "mapping.IOS.uninstalled.app" to AppleMappingTransfer.savedValue(choice()),
      "mapping.IPADOS.excluded.app" to "-",
      "mapping.MACOS.legacy.app" to """{"id":"1108187390","name":"Music","publisher":"Apple","bundleId":""}""")
    val choices = AppleMappingTransfer.savedChoices(prefs.keys.toTypedArray(), prefs::get, "sg")
    assertEquals(3, choices.size)
    assertEquals("jp", choices.first { it.platform == ApplePlatform.IOS }.country)
    assertEquals("sg", choices.first { it.platform == ApplePlatform.MACOS }.app?.country)
    assertEquals(null, choices.first { it.platform == ApplePlatform.IPADOS }.app)
    assertFalse(String(AppleMappingTransfer.encode(choices)).contains("private"))
  }

  @Test fun unsupportedFormatsSchemasAndNonIntegralSchemasAreRejected() {
    listOf("0", "2", "1.0", "\"1\"", "null", "true").forEach { schema ->
      rejected { AppleMappingTransfer.decode(document(row, schema)) }
    }
    rejected { AppleMappingTransfer.decode(String(document(row)).replace("keihome.apple-mappings", "other").toByteArray()) }
  }

  @Test fun namespaceSnapshotsExportAllChoicesAndImportOneMergedValuePerPlatform() {
    val prefs = mutableMapOf(
      "matches_IOS" to """{"uninstalled.app":{"id":"1108187390","name":"Music","publisher":"Apple","bundleId":"com.apple.Music","country":"jp"},"other.app":"-"}""",
      "matches_IPADOS" to """{"uninstalled.app":"-"}""",
      "matches_MACOS" to """{"mac.app":{"id":"1108187390","name":"Music","publisher":"Apple","bundleId":"","country":"hk"}}""",
      "options" to "private", "cache" to "private")
    val saved = AppleMappingTransfer.savedChoices(prefs.keys.toTypedArray(), prefs::get, "sg")
    assertEquals(4, saved.size)
    val incoming = listOf(choice().copy(app = null), choice("new.app", ApplePlatform.MACOS, "us"))
    val plan = AppleMappingTransfer.importWrites(AppleMappingTransfer.encode(incoming),
      prefs.keys.toTypedArray(), prefs::get, "sg")
    assertEquals(2, plan.count)
    assertEquals(setOf("matches_IOS"), plan.writes.getValue(ApplePlatform.IOS).keys)
    assertEquals(setOf("matches_MACOS"), plan.writes.getValue(ApplePlatform.MACOS).keys)
    plan.writes.values.forEach(prefs::putAll)
    assertEquals(AppleMappingTransfer.merge(saved, incoming).map {
      if (it.app == null) it.copy(country = "sg") else it
    }.toSet(),
      AppleMappingTransfer.savedChoices(prefs.keys.toTypedArray(), prefs::get, "sg").toSet())
    assertEquals("private", prefs["options"])
    assertEquals("private", prefs["cache"])
    assertEquals("""{"uninstalled.app":"-"}""", prefs["matches_IPADOS"])
  }

  @Test fun stringValuedLowercaseNamespaceSnapshotsRetainTheirEncoding() {
    val raw = AppleMappingTransfer.savedValue(choice())
      .replace("\\", "\\\\").replace("\"", "\\\"")
    val prefs = mapOf("matches_ios" to """{"uninstalled.app":"$raw"}""")
    val plan = AppleMappingTransfer.importWrites(AppleMappingTransfer.encode(listOf(choice("new.app"))),
      prefs.keys.toTypedArray(), prefs::get, "us")
    val value = plan.writes.getValue(ApplePlatform.IOS).getValue("matches_ios")
    assertTrue(value.contains("\"new.app\":\"{"))
    assertEquals(2, AppleMappingTransfer.savedChoices(arrayOf("matches_ios"), { value }, "us").size)
  }

  @Test fun legacyImportPlanTouchesOnlyMatchingManualKeysAndPreservesUnrelatedChoices() {
    val prefs = mutableMapOf("mapping.IOS.other.app" to "-", "mapping.IPADOS.uninstalled.app" to "-",
      "options" to "private")
    val incoming = listOf(choice())
    val plan = AppleMappingTransfer.importWrites(AppleMappingTransfer.encode(incoming),
      prefs.keys.toTypedArray(), prefs::get, "us")
    assertEquals(setOf("mapping.IOS.uninstalled.app"), plan.writes.getValue(ApplePlatform.IOS).keys)
    plan.writes.values.forEach(prefs::putAll)
    assertEquals("-", prefs["mapping.IOS.other.app"])
    assertEquals("-", prefs["mapping.IPADOS.uninstalled.app"])
    assertEquals("private", prefs["options"])
  }

  @Test fun invalidLastEntryProducesNoImportPlanAndDoesNotWritePreferences() {
    val prefs = mutableMapOf("matches_IOS" to """{"existing.app":"-"}""")
    val before = prefs.toMap()
    rejected {
      val plan = AppleMappingTransfer.importWrites(document("$row,${row.replace("IOS", "INVALID")}"),
        prefs.keys.toTypedArray(), prefs::get, "us")
      plan.writes.values.forEach(prefs::putAll)
    }
    assertEquals(before, prefs)
  }

  @Test fun invalidEntriesRejectTheEntireDocumentEvenAfterAValidEntry() {
    listOf(row.replace("some.app", "../bad"), row.replace("IOS", "TVOS"),
      row.replace("\"jp\"", "\"JP\""), row.replace("1108187390", "not-an-id"),
      row.replace("\"Music\"", "\"\""), row.replace("\"publisher\":\"Apple\"", "\"publisher\":null"),
      row.replace("\"bundleId\":\"com.apple.Music\"", "\"bundleId\":12"),
      row.replace("\"country\":\"jp\",", ""),
      row.replace("\"Music\"", "\"bad\\nname\""),
      row.replace("\"Music\"", "\"${"x".repeat(513)}\""),
      row.replace("\"app\":", "\"private\":null,\"app\":")).forEach { invalid ->
      rejected { AppleMappingTransfer.decode(document("$row,$invalid")) }
    }
  }

  @Test fun duplicateAssignmentsAndJsonKeysAreRejected() {
    rejected { AppleMappingTransfer.decode(document("$row,$row")) }
    rejected { AppleMappingTransfer.encode(listOf(choice(), choice())) }
    rejected { AppleMappingTransfer.decode(document(row.replace("\"country\":\"jp\"", "\"country\":\"jp\",\"country\":\"us\""))) }
    rejected { AppleMappingTransfer.decode("""{"format":"keihome.apple-mappings","schema":1,"schema":1,"mappings":[]}""".toByteArray()) }
  }

  @Test fun strictJsonRejectsTrailingInputTrailingCommasInvalidEscapesAndDeepNesting() {
    val valid = String(document(row))
    listOf("$valid garbage", valid.dropLast(1), valid.replace("]}", ",]}"),
      valid.replace("\"Music\"", "\"bad\\x\""),
      valid.replace("\"Music\"", "\"bad\\uD800\""),
      valid.replace("\"Music\"", "\"bad\\uDC00\""),
      valid.replace("\"app\":{", "\"app\":[[[[[{"),
      valid.replace("\"packageName\"", "packageName")).forEach { invalid ->
      rejected { AppleMappingTransfer.decode(invalid.toByteArray()) }
    }
  }

  @Test fun validEscapedUnicodeAndWhitespaceAreAccepted() {
    val bytes = document(row.replace("\"Music\"", "\"\\u97F3\\u4E50 \\uD83C\\uDFB5\""))
    assertEquals("\u97F3\u4E50 \uD83C\uDFB5", AppleMappingTransfer.decode(bytes).single().app?.name)
    assertEquals(1, AppleMappingTransfer.decode((" \r\n" + String(bytes) + "\t ").toByteArray()).size)
  }

  @Test fun malformedUtf8AndOverLimitDocumentsAreRejected() {
    rejected { AppleMappingTransfer.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    rejected { AppleMappingTransfer.decode(ByteArray(AppleMappingTransfer.MAX_BYTES + 1)) }
    rejected { AppleMappingTransfer.encode(List(AppleMappingTransfer.MAX_MAPPINGS + 1) { choice("app.p$it") }) }
  }

  @Test fun safInputStopsAtTheByteLimitWithoutReadingTheWholeStream() {
    var count = 0
    val endless = object : InputStream() {
      override fun read(): Int { count++; return 32 }
    }
    rejected { AppleMappingTransfer.read(endless) }
    assertEquals(AppleMappingTransfer.MAX_BYTES + 1, count)
    assertEquals(AppleMappingTransfer.MAX_BYTES,
      AppleMappingTransfer.read(ByteArrayInputStream(ByteArray(AppleMappingTransfer.MAX_BYTES))).size)
  }
}
