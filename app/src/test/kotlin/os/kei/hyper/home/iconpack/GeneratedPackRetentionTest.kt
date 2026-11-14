package os.kei.hyper.home.iconpack

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedPackRetentionTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun publicationsStayIndependentAndManualImportsSurvive() {
    val store = IconPackStore(temporary.newFolder())
    val manual = import(store, "manual")
    val oldPerfect = import(store, "perfectOld", GeneratedPackProducer.PERFECT)
    val apple = import(store, "apple", GeneratedPackProducer.APPLE)
    val latest = import(store, "perfectNew", GeneratedPackProducer.PERFECT)
    assertEquals(GeneratedPackProducer.PERFECT, store.read(latest.id).generatedBy)
    assertEquals(listOf(oldPerfect.id), store.pruneGenerated(GeneratedPackProducer.PERFECT, latest.id))
    assertEquals(setOf(manual.id, apple.id, latest.id), store.list().records.map { it.id }.toSet())
    assertTrue(manual.originalFile.isFile)
  }

  @Test fun generationDoesNotPruneUntilItsPublicationIsCommitted() {
    val store = IconPackStore(temporary.newFolder())
    val old = import(store, "old", GeneratedPackProducer.PERFECT)
    val fresh = import(store, "fresh", GeneratedPackProducer.PERFECT)
    assertEquals(2, store.list().records.size)
    assertTrue(old.originalFile.exists())
    store.pruneGenerated(GeneratedPackProducer.PERFECT, fresh.id)
    assertFalse(old.originalFile.exists())
    assertTrue(fresh.originalFile.exists())
  }

  @Test fun failedImportAndCorruptReplacementLeaveExistingPublicationIntact() {
    val store = IconPackStore(temporary.newFolder())
    val old = import(store, "old", GeneratedPackProducer.PERFECT)
    assertThrows(Exception::class.java) {
      store.import("bad archive".byteInputStream(), "broken.zip", generatedBy = GeneratedPackProducer.PERFECT)
    }
    val fresh = import(store, "fresh", GeneratedPackProducer.PERFECT)
    fresh.originalFile.appendText("changed")
    assertThrows(IconPackException::class.java) { store.pruneGenerated(GeneratedPackProducer.PERFECT, fresh.id) }
    assertTrue(old.originalFile.isFile)
  }

  @Test fun sameContentAsAManualImportDoesNotAcquireGeneratedOwnership() {
    val store = IconPackStore(temporary.newFolder())
    val manual = import(store, "same")
    val repeated = import(store, "same", GeneratedPackProducer.PERFECT)
    assertEquals(manual.id, repeated.id)
    assertNull(store.read(manual.id).generatedBy)
    val fresh = import(store, "fresh", GeneratedPackProducer.PERFECT)
    store.pruneGenerated(GeneratedPackProducer.PERFECT, fresh.id)
    assertTrue(manual.originalFile.isFile)
  }

  @Test fun explicitManualImportProtectsAnExistingGeneratedCopy() {
    val store = IconPackStore(temporary.newFolder())
    val generated = import(store, "claimed", GeneratedPackProducer.PERFECT)
    import(store, "claimed")
    assertNull(store.read(generated.id).generatedBy)
    val latest = import(store, "latest", GeneratedPackProducer.PERFECT)
    store.pruneGenerated(GeneratedPackProducer.PERFECT, latest.id)
    assertTrue(generated.originalFile.isFile)
  }

  @Test fun legacyCopiesWithoutProvenanceSurviveEvenWhenPrivateOriginalsAndTimesMatch() {
    val root = temporary.newFolder()
    val store = IconPackStore(root)
    val legacy = import(store, "legacy")
    val manual = import(store, "manual")
    val metadata = File(File(root, legacy.id), "metadata.properties")
    val properties = Properties().apply { metadata.inputStream().use(::load); remove("userImported") }
    metadata.outputStream().use { properties.store(it, "legacy") }
    val originals = temporary.newFolder()
    fun original(record: StoredIconPack, letter: String) =
      File(originals, "perfect-${letter.repeat(12)}-${letter.repeat(24)}.zip").also {
        record.originalFile.copyTo(it)
        assertTrue(it.setLastModified(record.importedAt))
      }
    original(legacy, "a")
    original(manual, "b")
    val latest = import(store, "latest", GeneratedPackProducer.PERFECT)
    store.pruneGenerated(GeneratedPackProducer.PERFECT, latest.id)
    assertTrue(legacy.originalFile.isFile)
    assertTrue(manual.originalFile.isFile)
    assertNull(store.read(legacy.id).generatedBy)
    assertNull(store.read(manual.id).generatedBy)
  }

  @Test fun optedInReplacementWorksAtTheCatalogLimitWithoutDeletingFirst() {
    val root = temporary.newFolder()
    val store = IconPackStore(root, IconPackLimits(maxStoredPacks = 1))
    val old = import(store, "old", GeneratedPackProducer.PERFECT)
    assertThrows(IconPackException::class.java) { import(store, "new", GeneratedPackProducer.PERFECT) }
    val fresh = import(store, "new", GeneratedPackProducer.PERFECT, replacing = true)
    assertTrue(old.originalFile.exists())
    store.pruneGenerated(GeneratedPackProducer.PERFECT, fresh.id)
    assertEquals(listOf(fresh.id), store.list().records.map { it.id })
  }

  @Test fun archiveCleanupKeepsLatestAndDoesNotTouchOtherFilesOrSymlinks() {
    val root = temporary.newFolder()
    val latest = File(root, "${"a".repeat(64)}.zip").apply { writeText("new") }
    val old = File(root, "${"b".repeat(64)}.zip").apply { writeText("old") }
    val manual = File(root, "manual.zip").apply { writeText("manual") }
    val otherProducer = File(root, "perfect-${"c".repeat(12)}-${"d".repeat(24)}.zip").apply { writeText("perfect") }
    val target = temporary.newFile().apply { writeText("external") }
    val link = File(root, "${"e".repeat(64)}.zip")
    Files.createSymbolicLink(link.toPath(), target.toPath())
    assertTrue(pruneGeneratedArchives(root, latest, GeneratedPackProducer.APPLE).isEmpty())
    assertFalse(old.exists())
    assertTrue(listOf(latest, manual, otherProducer, target, link).all { it.exists() })
  }

  @Test fun invalidLatestArchiveCannotStartCleanup() {
    val root = temporary.newFolder()
    val old = File(root, "perfect-${"a".repeat(12)}-${"b".repeat(24)}.zip").apply { writeText("old") }
    val outside = temporary.newFile().apply { writeText("elsewhere") }
    assertThrows(IllegalArgumentException::class.java) {
      pruneGeneratedArchives(root, outside, GeneratedPackProducer.PERFECT)
    }
    assertTrue(old.isFile)
    assertTrue(outside.isFile)
  }

  private fun import(store: IconPackStore, name: String, producer: GeneratedPackProducer? = null,
    replacing: Boolean = false): StoredIconPack =
    store.import(archive(name).inputStream(), "$name.zip", generatedBy = producer,
      replacingGenerated = replacing).record

  private fun archive(name: String): ByteArray = ByteArrayOutputStream().also { buffer ->
    ZipOutputStream(buffer).use { zip ->
      zip.putNextEntry(ZipEntry("res/drawable-xxhdpi/org.example.$name.png"))
      zip.write(Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH2kAAAAASUVORK5CYII="))
      zip.closeEntry()
    }
  }.toByteArray()
}
