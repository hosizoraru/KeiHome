package os.kei.hyper.home.xposed

import io.github.libxposed.api.XposedInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.status.NativeIconImportReport

class ConvertedIconRuntimeTest {
  private val report = NativeIconImportReport(token(1), 0, "local-conversion", "IMPORTED",
    localId = "icon-local", convertedReady = true)
  private val proof = ConvertedIconArchive.Proof("CONVERTED_APK",
    ConvertedIconArchive.Digests("a".repeat(40), "b".repeat(64)),
    ConvertedIconArchive.Digests("c".repeat(40), "d".repeat(64)))
  private val secondProof = proof.copy(icons = proof.icons.copy(sha256 = "e".repeat(64)))
  private val success = Any()
  private val original = Any()

  @Test fun onlyTheRegisteredIconsSha256ReturnsTheFrameworkSuccessObject() {
    var digest = proof.icons.sha256
    val runtime = runtime(hash = { digest })
    runtime.register(report, proof)
    assertApproved(runtime)
    digest = proof.archive.sha256
    assertOriginal(runtime)
    digest = secondProof.icons.sha256
    assertOriginal(runtime)
    runtime.register(report.copy(token = token(2), userId = 10),
      secondProof.copy(origin = "CONVERTED_CLASSIC_ZIP"))
    assertApproved(runtime)
  }

  @Test fun absentGrantAndEveryOtherPathOrUnboundedFileKeepOriginalValidation() {
    var hashes = 0
    val runtime = runtime(hash = { hashes++; proof.icons.sha256 })
    assertOriginal(runtime)
    runtime.register(report, proof)
    for (file in listOf(null, ActiveFile(path = "/data/system/theme/wallpaper"),
      ActiveFile(path = "/data/system/theme/icons/child"),
      ActiveFile(path = "/data/system/theme/icons.bak"),
      ActiveFile(path = "/data/system/theme/../theme/icons"),
      ActiveFile(canonical = "/some/other/icons"), ActiveFile(regular = false),
      ActiveFile(size = 0), ActiveFile(size = ConvertedIconArchive.MAX_BYTES + 1))) {
      assertOriginal(runtime, file)
    }
    assertEquals(0, hashes)
  }

  @Test fun inspectionExceptionsFallThroughOnceAndReleaseRetirementAdmission() {
    for (failure in listOf(IOException("read failed"), SecurityException("denied"),
      IllegalArgumentException("size changed"), InterruptedException("cancelled"))) {
      val runtime = runtime(hash = { throw failure })
      runtime.register(report, proof)
      try {
        assertOriginal(runtime)
        assertEquals(failure is InterruptedException, Thread.currentThread().isInterrupted)
        assertTrue(runtime.retire())
      } finally {
        Thread.interrupted()
      }
    }
    val runtime = runtime()
    runtime.register(report, proof)
    assertOriginal(runtime, object : ActiveFile() {
      override fun getCanonicalPath(): String = throw IOException("canonical path failed")
    })
    assertTrue(runtime.retire())
  }

  @Test fun originalFrameworkExceptionsPropagateWithoutRetry() {
    val runtime = runtime(hash = { throw IOException("module cannot read") })
    runtime.register(report, proof)
    val failure = IOException("original framework failure")
    var calls = 0
    assertSame(failure, assertThrows(IOException::class.java) {
      runtime.validate(ActiveFile(), success) { calls++; throw failure }
    })
    assertEquals(1, calls)
    assertTrue(runtime.retire())
  }

  @Test fun canonicalPathAndRegularFileAreRecheckedAfterHashing() {
    var canonical = activePath
    var regular = true
    val file = object : ActiveFile() {
      override fun getCanonicalPath(): String = canonical
      override fun isFile(): Boolean = regular
    }
    val runtime = runtime(hash = {
      canonical = "/different/icons"
      proof.icons.sha256
    })
    runtime.register(report, proof)
    assertOriginal(runtime, file)
    canonical = activePath
    val removed = runtime(hash = { regular = false; proof.icons.sha256 })
    removed.register(report, proof)
    assertOriginal(removed, file)
  }

  @Test fun failedCommitNeverPublishesItsGrantAndPreservesThePreviousReceipt() {
    val store = Store()
    var digest = proof.icons.sha256
    val runtime = runtime(store) { digest }
    runtime.register(report, proof)
    store.beforeWrite = {
      // Validation remains usable while a receipt is being committed.
      assertApproved(runtime)
      digest = secondProof.icons.sha256
      assertOriginal(runtime)
      assertFalse(runtime.retire())
      throw IOException("commit or readback failed")
    }
    assertThrows(IOException::class.java) {
      runtime.register(report.copy(token = token(2)), secondProof)
    }
    assertOriginal(runtime)
    digest = proof.icons.sha256
    assertApproved(runtime)
    assertEquals(listOf(registration()), store.rows)
    assertTrue(runtime.retire())
    runtime.resume()
    store.beforeWrite = {}
    runtime.register(report.copy(token = token(2)), secondProof)
    digest = secondProof.icons.sha256
    assertApproved(runtime)
  }

  @Test fun concurrentCommitsSerializeAndReloadRetainsBothApprovals() {
    val store = Store()
    val runtime = runtime(store)
    val firstWriting = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val secondWriting = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    store.beforeWrite = { values ->
      if (values.size == 1) {
        firstWriting.countDown()
        check(releaseFirst.await(5, TimeUnit.SECONDS))
      } else {
        secondWriting.countDown()
      }
    }
    val first = thread {
      try { runtime.register(report, proof) } catch (error: Throwable) { failure.set(error) }
    }
    var second: Thread? = null
    try {
      assertTrue(firstWriting.await(5, TimeUnit.SECONDS))
      second = thread {
        secondStarted.countDown()
        try { runtime.register(report.copy(token = token(2)), secondProof) }
        catch (error: Throwable) { failure.set(error) }
      }
      assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
      assertFalse(secondWriting.await(100, TimeUnit.MILLISECONDS))
      assertOriginal(runtime)
      assertFalse(runtime.retire())
    } finally {
      releaseFirst.countDown()
      first.join(5_000)
      second?.join(5_000)
    }
    assertFalse(first.isAlive)
    assertFalse(second.isAlive)
    assertNull(failure.get())
    assertEquals(setOf(token(1), token(2)), store.rows.map { it.token }.toSet())
    assertTrue(runtime.retire())
    var digest = proof.icons.sha256
    val replacement = runtime(store) { digest }
    replacement.loadRegistrations()
    assertApproved(replacement)
    digest = secondProof.icons.sha256
    assertApproved(replacement)
  }

  @Test fun retiredRuntimeRejectsRegistrationAndResumeKeepsCommittedApprovals() {
    val store = Store()
    var hashes = 0
    val runtime = runtime(store) { hashes++; proof.icons.sha256 }
    runtime.register(report, proof)
    assertTrue(runtime.retire())
    assertTrue(runtime.retire())
    assertThrows(IllegalStateException::class.java) {
      runtime.register(report.copy(token = token(2)), secondProof)
    }
    assertOriginal(runtime)
    assertEquals(0, hashes)
    assertEquals(1, store.writes.get())
    runtime.resume()
    assertApproved(runtime)
    runtime.register(report.copy(token = token(2)), secondProof)
    assertEquals(2, store.rows.size)
  }

  @Test fun activeValidationRejectsRetirementWithoutDisablingTheOldOwner() {
    lateinit var runtime: ConvertedIconRuntime
    runtime = runtime(hash = {
      assertFalse(runtime.retire())
      proof.icons.sha256
    })
    runtime.register(report, proof)
    assertApproved(runtime)
    assertApproved(runtime)
    assertTrue(runtime.retire())
  }

  @Test fun onlyCompletedConversionsWithStrictIdentityAndDigestsCanRegister() {
    val badReports = listOf(
      report.copy(token = "-".repeat(36)), report.copy(token = "a".repeat(36)),
      report.copy(token = token(1).replace('0', 'A')), report.copy(token = "1-1-1-1-1"),
      report.copy(userId = -1), report.copy(userId = Int.MAX_VALUE),
      report.copy(userId = Int.MAX_VALUE / 100_000 + 1),
      report.copy(localId = null), report.copy(localId = ""), report.copy(localId = "  "),
      report.copy(localId = "bad\nid"), report.copy(localId = "x".repeat(129)),
      report.copy(status = "IMPORTING"), report.copy(status = "FAILED"),
      report.copy(convertedReady = null), report.copy(convertedReady = false),
    )
    val badProofs = listOf(
      proof.copy(origin = "MIUI_MTZ"), proof.copy(origin = "NATIVE"),
      proof.copy(archive = proof.archive.copy(sha1 = "a".repeat(39))),
      proof.copy(archive = proof.archive.copy(sha256 = "B".repeat(64))),
      proof.copy(icons = proof.icons.copy(sha1 = "g".repeat(40))),
      proof.copy(icons = proof.icons.copy(sha256 = "d".repeat(65))),
    )
    for ((candidate, candidateProof) in badReports.map { it to proof } +
      badProofs.map { report to it }) {
      val store = Store()
      val runtime = runtime(store)
      assertThrows(IllegalArgumentException::class.java) { runtime.register(candidate, candidateProof) }
      assertEquals(0, store.writes.get())
      assertOriginal(runtime)
      assertTrue(runtime.retire())
    }
  }

  @Test fun identicalReplayIsIdempotentAndCannotRebindTokenUserLocalIdOrProof() {
    val store = Store()
    val runtime = runtime(store)
    runtime.register(report, proof)
    runtime.register(report.copy(status = "ALREADY_IMPORTED"), proof)
    assertEquals(1, store.writes.get())
    for ((candidate, candidateProof) in listOf(report.copy(userId = 10) to proof,
      report.copy(localId = "another-icon") to proof, report to secondProof,
      report to proof.copy(archive = proof.archive.copy(sha256 = "f".repeat(64))),
      report to proof.copy(origin = "CONVERTED_CLASSIC_ZIP"))) {
      assertThrows(IllegalArgumentException::class.java) { runtime.register(candidate, candidateProof) }
    }
    assertEquals(1, store.writes.get())
    assertApproved(runtime)
  }

  @Test fun corruptDuplicateOrOversizedReceiptsCannotPublishPartialApprovals() {
    val valid = registration()
    val corrupt = listOf(
      listOf(valid, valid),
      listOf(valid, valid.copy(token = token(2), userId = -1)),
      listOf(valid.copy(token = "-".repeat(36))),
      listOf(valid.copy(proof = proof.copy(origin = "MIUI_MTZ"))),
      listOf(valid.copy(proof = proof.copy(icons = proof.icons.copy(sha256 = "bad")))),
      List(ConvertedIconRuntime.MAX_REGISTRATIONS + 1) { valid.copy(token = token(it)) },
    )
    for (rows in corrupt) {
      val store = Store().apply { this.rows = rows }
      val runtime = runtime(store)
      runtime.loadRegistrations()
      assertOriginal(runtime)
      assertEquals(0, store.writes.get())
    }
    val store = Store().apply { readFailure = IOException("cannot read receipt") }
    val runtime = runtime(store)
    runtime.loadRegistrations()
    assertOriginal(runtime)
  }

  @Test fun receiptCapacityRejectsNewTokenButPermitsReplayWithoutRewriting() {
    val store = Store().apply {
      rows = List(ConvertedIconRuntime.MAX_REGISTRATIONS) { registration().copy(token = token(it)) }
    }
    val runtime = runtime(store)
    runtime.loadRegistrations()
    runtime.register(report, proof)
    assertThrows(IllegalArgumentException::class.java) {
      runtime.register(report.copy(token = token(ConvertedIconRuntime.MAX_REGISTRATIONS)), proof)
    }
    assertEquals(0, store.writes.get())
    assertApproved(runtime)
    assertTrue(runtime.retire())
  }

  @Test fun receiptReadIsBoundedAndDoesNotClearInterruption() {
    val max = ByteArray(1024 * 1024) { 1 }
    assertArrayEquals(max, readConvertedIconValidationReceipt(ByteArrayInputStream(max)))
    assertThrows(IllegalArgumentException::class.java) {
      readConvertedIconValidationReceipt(ByteArrayInputStream(max + byteArrayOf(1)))
    }
    try {
      Thread.currentThread().interrupt()
      assertThrows(InterruptedException::class.java) {
        readConvertedIconValidationReceipt(ByteArrayInputStream(byteArrayOf(1)))
      }
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test fun fileDigestUsesAllBytesAndRejectsEmptyOversizedOrNonRegularContent() = withFile { file ->
    assertThrows(IllegalArgumentException::class.java) { convertedIconFileSha256(file) }
    assertThrows(IllegalArgumentException::class.java) { convertedIconFileSha256(file.parentFile!!) }
    val bytes = ByteArray(64 * 1024 + 7) { (it % 251).toByte() }
    file.writeBytes(bytes)
    val hash = convertedIconFileSha256(file)
    assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it.toInt() and 255) }, hash)
    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
    file.writeBytes(bytes)
    assertNotEquals(hash, convertedIconFileSha256(file))
    RandomAccessFile(file, "rw").use { it.setLength(ConvertedIconArchive.MAX_BYTES + 1) }
    assertThrows(IllegalArgumentException::class.java) { convertedIconFileSha256(file) }
  }

  @Test fun changingLengthAndInterruptionNeverProduceAFileDigest() = withFile { file ->
    file.writeText("complete icon bytes")
    val length = file.length()
    for (reported in listOf(length - 1, length + 1)) {
      val changed = object : File(file.path) {
        override fun length(): Long = reported
      }
      assertThrows(IllegalArgumentException::class.java) { convertedIconFileSha256(changed) }
    }
    var lengthCalls = 0
    val changedAfterRead = object : File(file.path) {
      override fun length(): Long = if (++lengthCalls == 1) length else length + 1
    }
    assertThrows(IllegalArgumentException::class.java) { convertedIconFileSha256(changedAfterRead) }
    try {
      Thread.currentThread().interrupt()
      assertThrows(InterruptedException::class.java) { convertedIconFileSha256(file) }
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test fun sameSizeAtomicReplacementCannotApproveTheOldOpenFileDigest() = withFile { file ->
    file.writeText("approved PNG archive")
    val approvedProof = proof.copy(icons = proof.icons.copy(sha256 = convertedIconFileSha256(file)))
    val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    val replacement = File(file.parentFile, "replacement").apply {
      writeBytes(ByteArray(file.length().toInt()) { 1 })
      Files.setLastModifiedTime(toPath(), before.lastModifiedTime())
    }
    var lengthCalls = 0
    val racingFile = object : File(file.path) {
      override fun length(): Long {
        if (++lengthCalls == 2) {
          // At EOF the old descriptor is still open; preserve size and mtime across the rename.
          Files.move(replacement.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        }
        return super.length()
      }
    }
    val runtime = runtime(hash = { convertedIconFileSha256(racingFile) })
    runtime.register(report, approvedProof)
    assertOriginal(runtime)
    val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    assertEquals(2, lengthCalls)
    assertEquals(before.size(), after.size())
    assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
    assertNotEquals(before.fileKey(), after.fileKey())
    assertTrue(runtime.retire())
  }

  private fun runtime(store: Store = Store(), hash: (File) -> String = { proof.icons.sha256 }) =
    ConvertedIconRuntime(Proxy.newProxyInstance(XposedInterface::class.java.classLoader,
      arrayOf(XposedInterface::class.java)) { _, method, _ ->
      check(method.name == "log")
      null
    } as XposedInterface, store, hash)

  private fun registration() =
    ConvertedIconValidationRegistration(report.token, report.userId, report.localId!!, proof)

  private fun assertApproved(runtime: ConvertedIconRuntime) {
    assertSame(success, runtime.validate(ActiveFile(), success) {
      error("Registered icon bytes must return the framework success object")
    })
  }

  private fun assertOriginal(runtime: ConvertedIconRuntime, file: File? = ActiveFile()) {
    var calls = 0
    assertSame(original, runtime.validate(file, success) { calls++; original })
    assertEquals(1, calls)
  }

  private fun withFile(action: (File) -> Unit) {
    val directory = Files.createTempDirectory("keihome-runtime-validation-test-").toFile()
    try {
      action(File(directory, "icons").apply { createNewFile() })
    } finally {
      directory.deleteRecursively()
    }
  }

  private class Store : ConvertedIconValidationStorage {
    @Volatile var rows = emptyList<ConvertedIconValidationRegistration>()
    var beforeWrite: (Collection<ConvertedIconValidationRegistration>) -> Unit = {}
    var readFailure: Exception? = null
    val writes = AtomicInteger()

    override fun read(): List<ConvertedIconValidationRegistration> {
      readFailure?.let { throw it }
      return rows
    }

    override fun write(values: Collection<ConvertedIconValidationRegistration>) {
      writes.incrementAndGet()
      beforeWrite(values)
      rows = values.toList()
    }
  }

  private open class ActiveFile(
    path: String = activePath,
    private val canonical: String = activePath,
    private val regular: Boolean = true,
    private val size: Long = 10,
  ) : File(path) {
    override fun getCanonicalPath(): String = canonical
    override fun isFile(): Boolean = regular
    override fun length(): Long = size
  }

  companion object {
    private const val activePath = "/data/system/theme/icons"
    private fun token(value: Int) = "00000000-0000-0000-0000-${value.toString(16).padStart(12, '0')}"
  }
}
