package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.util.zip.ZipFile

/** Lightweight fake Git backend for Android device tests. */
internal class FakePerfectGitBackend(
  val root: File,
  initialFiles: Map<String, ByteArray> = emptyMap(),
  var revision: String = "1".repeat(40),
) : PerfectIconGitBackend {
  val files = LinkedHashMap<String, ByteArray>(initialFiles)
  var fetches = 0
  private var seq = 0

  private fun availableStyles(): List<String> = buildSet {
    add("default")
    files.keys.forEach { path ->
      if (path.startsWith("themes/")) {
        val style = path.removePrefix("themes/").substringBefore('/')
        if (style != "common" && style.isNotEmpty()) add(style)
      }
    }
  }.sorted()

  override suspend fun check(progress: (PerfectIconProgress) -> Unit): PerfectIconRemote =
    PerfectIconRemote(revision, 0, "", 1, availableStyles())

  override suspend fun fetch(
    currentStore: String?,
    repair: Boolean,
    styles: Set<String>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitSnapshot {
    fetches++
    val available = availableStyles()
    val downloaded = styles.intersect(available.toSet()).ifEmpty { available.toSet() }.sorted()
    val store = "device-store-${++seq}"
    val dir = File(root, "git/$store").apply { mkdirs() }
    val selected = PerfectIconSelection.paths(files.keys.toList(), downloaded.toSet())
    val entries = files.filterKeys { it in selected }.map { (path, bytes) ->
      val sha = PerfectIconFormat.sha(bytes)
      File(dir, "$sha.pack").writeBytes(bytes)
      PerfectIconSourceEntry(path, sha, bytes.size.toLong(), 0)
    }
    File(dir, "tip").writeText("$revision\n")
    val bytes = dir.listFiles().orEmpty().sumOf { it.length() }
    return PerfectGitSnapshot(store, PerfectIconRemote(revision, 0, "", 1, available),
      entries, bytes, bytes, available, downloaded)
  }

  override fun open(store: String): PerfectGitReader {
    val dir = File(root, "git/$store")
    return object : PerfectGitReader {
      override fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray {
        check()
        return File(dir, "${entry.sha}.pack").readBytes()
      }
      override fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit): Boolean =
        entries.all { check(); File(dir, "${it.sha}.pack").isFile }
      override fun close() = Unit
    }
  }

  override fun exists(store: String): Boolean {
    val dir = File(root, "git/$store")
    return dir.isDirectory && File(dir, "tip").isFile
  }

  override fun stamp(store: String): String {
    val dir = File(root, "git/$store")
    return dir.listFiles().orEmpty().sortedBy { it.name }
      .joinToString("\n") { "${it.name}:${it.length()}:${it.lastModified()}" }
  }

  override suspend fun retain(
    store: String,
    entries: List<PerfectIconSourceEntry>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitRetained = error("retain unused in device fixture")

  override fun published(store: String, revision: String) {
    File(root, "git").listFiles().orEmpty()
      .filter { it.isDirectory && it.name != store }
      .forEach { it.deleteRecursively() }
  }
}

/** Exposes a local upstream repository ZIP archive via PerfectIconGitBackend for offline device acceptance tests. */
internal class ZipBackedPerfectGitBackend(
  val root: File,
  private val sourceZip: File,
  private val prefix: String = "Perfect-Icons-Completion-Project-main/",
) : PerfectIconGitBackend {
  var fetches = 0
  private var revision: String = "1".repeat(40)
  private var indexedPaths: Map<String, String> = emptyMap()

  private fun scan(): Pair<String, Map<String, String>> {
    ZipFile(sourceZip, Charsets.UTF_8).use { zip ->
      val comment = zip.comment?.trim().orEmpty()
      val rev = if (comment.matches(Regex("[0-9a-f]{40}"))) comment else "1".repeat(40)
      val paths = linkedMapOf<String, String>()
      zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }.forEach { entry ->
        val rel = entry.name.removePrefix(prefix)
        if (rel.startsWith("icons/") || rel.startsWith("themes/")) paths[rel] = entry.name
      }
      return rev to paths
    }
  }

  override suspend fun check(progress: (PerfectIconProgress) -> Unit): PerfectIconRemote {
    val (rev, paths) = scan()
    val styles = paths.keys.mapNotNull {
      if (it.startsWith("themes/")) it.removePrefix("themes/").substringBefore('/').takeUnless { s -> s == "common" }
      else null
    }.distinct().sorted()
    return PerfectIconRemote(rev, 0, "", 1, styles)
  }

  override suspend fun fetch(
    currentStore: String?,
    repair: Boolean,
    styles: Set<String>,
    progress: (PerfectIconProgress) -> Unit,
  ): PerfectGitSnapshot {
    fetches++
    val (rev, paths) = scan()
    revision = rev
    indexedPaths = paths
    val available = paths.keys.mapNotNull {
      if (it.startsWith("themes/")) it.removePrefix("themes/").substringBefore('/').takeUnless { s -> s == "common" }
      else null
    }.distinct().sorted()
    val downloaded = styles.intersect(available.toSet()).ifEmpty { available.toSet() }.sorted()
    val store = "zip-fixture-store"
    val dir = File(root, "git/$store").apply { mkdirs() }
    val selected = PerfectIconSelection.paths(paths.keys.toList(), downloaded.toSet())
    val entries = ArrayList<PerfectIconSourceEntry>()
    ZipFile(sourceZip, Charsets.UTF_8).use { zip ->
      selected.forEach { rel ->
        val full = paths.getValue(rel)
        val entry = requireNotNull(zip.getEntry(full))
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val sha = PerfectIconFormat.sha(bytes)
        entries += PerfectIconSourceEntry(rel, sha, bytes.size.toLong(), entry.crc)
      }
    }
    File(dir, "tip").writeText("$rev\n")
    return PerfectGitSnapshot(store, PerfectIconRemote(rev, 0, "", 1, available),
      entries, sourceZip.length(), sourceZip.length(), available, downloaded)
  }

  override fun open(store: String): PerfectGitReader {
    val zip = ZipFile(sourceZip, Charsets.UTF_8)
    return object : PerfectGitReader {
      override fun read(entry: PerfectIconSourceEntry, check: () -> Unit): ByteArray {
        check()
        val zipEntry = requireNotNull(zip.getEntry(prefix + entry.path))
        return zip.getInputStream(zipEntry).use { it.readBytes() }
      }
      override fun healthy(entries: List<PerfectIconSourceEntry>, check: () -> Unit): Boolean =
        entries.all { check(); zip.getEntry(prefix + it.path) != null }
      override fun close() = zip.close()
    }
  }

  override fun exists(store: String): Boolean = File(root, "git/$store/tip").isFile
  override fun stamp(store: String): String = "${sourceZip.length()}:${sourceZip.lastModified()}"
  override suspend fun retain(store: String, entries: List<PerfectIconSourceEntry>,
    progress: (PerfectIconProgress) -> Unit): PerfectGitRetained = error("retain unused")
  override fun published(store: String, revision: String) = Unit
}
