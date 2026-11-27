package os.kei.hyper.home.iconpack.perfect

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal class InMemoryPerfectIconSettings : PerfectIconSettings {
  val values = HashMap<String, String>()
  override fun read(key: String) = values[key]
  override fun write(key: String, value: String) { values[key] = value }
}

/** Shared disk-backed fake Git backend for PerfectIconRepository JVM unit tests. */
internal class FakePerfectGitBackend(
  val root: File,
  initialFiles: Map<String, ByteArray> = emptyMap(),
  var revision: String = "1".repeat(40),
  var committedAt: Long = 1_800_000_000_000L,
  var subject: String = "Resource update",
  var availableStylesOverride: List<String>? = null,
) : PerfectIconGitBackend {
  val files = LinkedHashMap<String, ByteArray>(initialFiles)
  var checks = 0
  var fetches = 0
  var opens = 0
  var closes = 0
  var stamps = 0
  var lastRequestedStyles = emptySet<String>()
  var lastRepair = false
  var activeStore: String = "test-store"
  var failure: Exception? = null
  var checkFailure: Exception? = null
  var beforeFetch: (suspend () -> Unit)? = null
  var compactOnMaintenance = false
  var filterBySelection = true
  private var retainSeq = 0
  private var fetchSeq = 0

  fun storeDir(store: String = activeStore): File = File(root, "git/$store")

  fun availableStyles(): List<String> = availableStylesOverride ?: buildSet {
    add("default")
    files.keys.forEach { path ->
      if (path.startsWith("themes/")) {
        val style = path.removePrefix("themes/").substringBefore('/')
        if (style != "common" && style.isNotEmpty()) add(style)
      }
    }
  }.sorted()

  override suspend fun check(progress: (PerfectIconProgress) -> Unit): PerfectIconRemote {
    checks++
    progress(PerfectIconProgress(0, 0, "source.check"))
    (checkFailure ?: failure)?.let { throw it }
    return PerfectIconRemote(revision, committedAt, subject, committedAt + 1_000, availableStyles())
  }

  override suspend fun fetch(
    currentStore: String?,
    repair: Boolean,
    styles: Set<String>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitSnapshot {
    fetches++
    lastRequestedStyles = styles
    lastRepair = repair
    progress(PerfectIconProgress(0, 0, "source.check"))
    beforeFetch?.invoke()
    failure?.let { throw it }
    val available = availableStyles()
    val downloaded = styles.intersect(available.toSet()).ifEmpty { available.toSet() }.sorted()
    val store = if (repair || currentStore == null || !exists(currentStore)) {
      "test-store-${++fetchSeq}"
    } else currentStore
    activeStore = store
    val dir = storeDir(store).apply { mkdirs() }
    val selectedPaths = if (filterBySelection) {
      PerfectIconSelection.paths(files.keys.toList(), downloaded.toSet())
    } else files.keys
    val entries = files.filterKeys { it in selectedPaths }.map { (path, bytes) ->
      val sha = PerfectIconFormat.sha(bytes)
      File(dir, "$sha.pack").writeBytes(bytes)
      PerfectIconSourceEntry(path, sha, bytes.size.toLong(), 0)
    }
    File(dir, "tip").writeText("$revision\n")
    progress(PerfectIconProgress(entries.size, entries.size, "source.download"))
    val storedBytes = dir.listFiles().orEmpty().sumOf { it.length() }
    return PerfectGitSnapshot(
      store = store,
      remote = PerfectIconRemote(revision, committedAt, subject, committedAt + 1_000, available),
      entries = entries,
      storedBytes = storedBytes,
      transferredBytes = storedBytes,
      availableStyles = available,
      downloadedStyles = downloaded,
    )
  }

  override fun open(store: String): PerfectGitReader {
    opens++
    val dir = storeDir(store)
    return object : PerfectGitReader {
      override fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray {
        check()
        val file = File(dir, "${entry.sha}.pack")
        require(file.isFile) { "Missing blob ${entry.sha}" }
        return file.readBytes().also {
          require(it.size.toLong() == entry.size && PerfectIconFormat.sha(it) == entry.sha) {
            "Corrupt blob ${entry.sha}"
          }
        }
      }
      override fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit): Boolean =
        entries.all {
          check()
          val file = File(dir, "${it.sha}.pack")
          file.isFile && file.length() == it.size
        }
      override fun close() { closes++ }
    }
  }

  override fun exists(store: String): Boolean {
    val dir = storeDir(store)
    return dir.isDirectory && File(dir, "tip").isFile
  }

  override fun stamp(store: String): String {
    stamps++
    val dir = storeDir(store)
    return dir.listFiles().orEmpty().sortedBy { it.name }
      .joinToString("\n") { "${it.name}:${it.length()}:${it.lastModified()}" }
  }

  override fun maintenanceNeeded(store: String, liveBytes: Long): Boolean = compactOnMaintenance

  override suspend fun retain(
    store: String,
    entries: List<PerfectIconSourceEntry>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitRetained {
    failure?.let { throw it }
    val next = "retained-store-${++retainSeq}"
    val src = storeDir(store)
    val dst = storeDir(next).apply { mkdirs() }
    entries.distinctBy { it.sha }.forEachIndexed { index, entry ->
      File(src, "${entry.sha}.pack").copyTo(File(dst, "${entry.sha}.pack"), overwrite = true)
      progress(PerfectIconProgress(index + 1, entries.size, "source.verify"))
    }
    File(src, "tip").takeIf { it.isFile }?.copyTo(File(dst, "tip"), overwrite = true)
    return PerfectGitRetained(next, dst.listFiles().orEmpty().sumOf { it.length() })
  }

  override fun published(store: String, revision: String) {
    activeStore = store
    File(root, "git").listFiles().orEmpty()
      .filter { it.isDirectory && it.name != store }
      .forEach { it.deleteRecursively() }
  }
}

internal fun testPng(tint: Int): ByteArray {
  val output = ByteArrayOutputStream()
  DataOutputStream(output).use { data ->
    data.writeLong(0x89504e470d0a1a0aUL.toLong())
    fun chunk(type: String, bytes: ByteArray) {
      val name = type.toByteArray(Charsets.US_ASCII)
      data.writeInt(bytes.size)
      data.write(name)
      data.write(bytes)
      data.writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
    }
    val header = ByteArrayOutputStream().apply {
      DataOutputStream(this).apply { writeInt(1); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) }
    }.toByteArray()
    val compressed = ByteArrayOutputStream().apply {
      DeflaterOutputStream(this).use { it.write(byteArrayOf(0, tint.toByte(), 20, 30, -1)) }
    }.toByteArray()
    chunk("IHDR", header)
    chunk("IDAT", compressed)
    chunk("IEND", byteArrayOf())
  }
  return output.toByteArray()
}
