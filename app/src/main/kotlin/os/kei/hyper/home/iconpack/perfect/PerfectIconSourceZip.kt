package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PerfectIconZipLimits(
  val compressed: Long = 1024L * 1024 * 1024,
  val expanded: Long = 2L * 1024 * 1024 * 1024,
  val entries: Int = 40_000,
  val ratio: Long = 200,
)

internal data class PerfectIconSourceEntry(
  val path: String,
  val sha: String,
  val size: Long,
  val crc: Long,
)

internal data class PerfectIconSourceIndex(
  val revision: String,
  val hash: String,
  val entries: List<PerfectIconSourceEntry>,
  val styles: List<String>,
  val iconCount: Int,
)

/** Validate in place, never extract the repository or execute any repository resource. */
internal object PerfectIconSourceZip {
  const val PREFIX = "Perfect-Icons-Completion-Project-main/"
  private val commit = Regex("[0-9a-f]{40}")

  suspend fun inspect(
    file: File,
    limits: PerfectIconZipLimits,
    progress: (PerfectIconProgress) -> Unit = {},
  ): PerfectIconSourceIndex {
    require(file.length() in 22..limits.compressed) { "Perfect Icons source ZIP size limit exceeded" }
    val coroutine = currentCoroutineContext()
    val reporter = PerfectIconProgressReporter("source.verify", progress)
    reporter.update(0, 0, force = true)
    centralDirectory(file, limits) { coroutine.ensureActive() }
    val archiveDigest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        coroutine.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        archiveDigest.update(buffer, 0, count)
      }
    }
    val entries = ArrayList<PerfectIconSourceEntry>()
    val styles = sortedSetOf<String>()
    var expanded = 0L
    val paths = HashSet<String>()
    val revision = ZipFile(file, Charsets.UTF_8).use { zip ->
      require(zip.size() <= limits.entries) { "Perfect Icons source ZIP entry limit exceeded" }
      val total = zip.size().toLong()
      reporter.update(0, total, force = true)
      zip.entries().asSequence().forEachIndexed { index, entry ->
        coroutine.ensureActive()
        require(paths.add(entry.name)) { "Duplicate Perfect Icons ZIP path" }
        val name = entry.name.removeSuffix("/")
        PerfectIconFormat.safePath(name)
        require(entry.name == PREFIX || entry.name.startsWith(PREFIX)) { "Unexpected source ZIP root" }
        val relative = entry.name.removePrefix(PREFIX).removeSuffix("/")
        if (relative.isNotEmpty()) PerfectIconFormat.safePath(relative)
        require(entry.method in setOf(0, 8) && entry.size >= 0 && entry.compressedSize >= 0 && entry.crc >= 0)
        require(entry.size <= limits.expanded - expanded) { "Perfect Icons source ZIP expanded size limit exceeded" }
        require(entry.size <= maxOf(1024L * 1024, entry.compressedSize * limits.ratio)) { "Unsafe source ZIP compression ratio" }
        expanded += entry.size
        require(expanded <= file.length() * limits.ratio + 1024L * 1024) { "Unsafe source ZIP compression ratio" }
        if (relative.startsWith("themes/")) {
          val style = relative.removePrefix("themes/").substringBefore('/')
          if (style != "common") {
            PerfectIconFormat.style(style)
            if (style.isNotEmpty()) styles += style
          }
        }
        if (entry.isDirectory) {
          require(entry.size == 0L)
          reporter.update(index + 1L, total)
          return@forEachIndexed
        }
        require(relative.isNotEmpty())
        val sha = MessageDigest.getInstance("SHA-1").apply {
          update("blob ${entry.size}\u0000".toByteArray(Charsets.US_ASCII))
        }
        val crc = CRC32()
        var actual = 0L
        zip.getInputStream(entry).use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            coroutine.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            actual += count
            require(actual <= entry.size) { "Source ZIP entry exceeded declared size" }
            sha.update(buffer, 0, count)
            crc.update(buffer, 0, count)
          }
        }
        require(actual == entry.size && crc.value == entry.crc) { "Source ZIP CRC/size verification failed" }
        entries += PerfectIconSourceEntry(relative, hex(sha.digest()), actual, crc.value)
        reporter.update(index + 1L, total)
      }
      reporter.update(total, total, force = true)
      // git archive stores the resolved commit in the ZIP comment. An absent comment is
      // represented honestly as an unknown commit, with the archive hash stored separately.
      zip.comment?.takeIf { commit.matches(it) }.orEmpty()
    }
    val base = entries.filter { it.path.startsWith("icons/") }.map {
      PerfectIconBlob(it.path.removePrefix("icons/"), it.sha, it.size, it.path)
    }
    val count = PerfectIconFormat.staticGroups(base).size
    require(count > 0 && styles.size <= 256 && "default" in styles &&
      entries.any { it.path.startsWith("themes/default/icons/") || it.path.startsWith("themes/default/layer_animating_icons/") }) {
      "Source ZIP lacks usable Perfect Icons base/default resources"
    }
    return PerfectIconSourceIndex(revision, hex(archiveDigest.digest()), entries, styles.toList(), count)
  }

  /**
   * ZipFile does not expose Unix modes or encryption flags. Check the bounded central
   * directory first, including local-name agreement; ZIP64/multipart archives are unnecessary
   * within the configured 1 GiB / 40k-entry limits and are rejected.
   */
  private fun centralDirectory(file: File, limits: PerfectIconZipLimits, checkpoint: () -> Unit) {
    RandomAccessFile(file, "r").use { input ->
      val tailSize = minOf(file.length(), 65_557).toInt()
      val tail = ByteArray(tailSize)
      input.seek(file.length() - tailSize)
      input.readFully(tail)
      val bytes = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
      val end = (tailSize - 22 downTo 0).firstOrNull {
        bytes.getInt(it) == 0x06054b50 && it + 22 + (bytes.getShort(it + 20).toInt() and 65535) == tailSize
      } ?: error("Missing source ZIP central directory")
      fun short(offset: Int) = bytes.getShort(end + offset).toInt() and 65535
      fun uint(offset: Int) = bytes.getInt(end + offset).toLong() and 0xffffffffL
      val count = short(10)
      require(short(4) == 0 && short(6) == 0 && short(8) == count && count in 1..limits.entries)
      val centralSize = uint(12)
      val centralStart = uint(16)
      val endPosition = file.length() - tailSize + end
      require(centralSize <= 64L * 1024 * 1024 && centralStart + centralSize == endPosition)
      var position = centralStart
      val ranges = ArrayList<Pair<Long, Long>>()
      repeat(count) {
        checkpoint()
        require(position + 46 <= endPosition)
        input.seek(position)
        val header = ByteArray(46)
        input.readFully(header)
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(offset: Int) = h.getShort(offset).toInt() and 65535
        fun u32(offset: Int) = h.getInt(offset).toLong() and 0xffffffffL
        require(h.int == 0x02014b50 && u16(34) == 0)
        val flags = u16(8)
        val method = u16(10)
        require(flags and 0x2041 == 0 && method in setOf(0, 8)) { "Encrypted/unsupported source ZIP entry" }
        val mode = (u32(38) shr 16).toInt() and 0xf000
        require(mode in setOf(0, 0x4000, 0x8000)) { "Source ZIP links/special files are forbidden" }
        val nameSize = u16(28)
        require(nameSize in 1..4096)
        val name = ByteArray(nameSize)
        input.readFully(name)
        val next = position + 46 + nameSize + u16(30) + u16(32)
        require(next <= endPosition)
        val local = u32(42)
        require(local + 30 <= centralStart)
        input.seek(local)
        val localHeader = ByteArray(30)
        input.readFully(localHeader)
        val lh = ByteBuffer.wrap(localHeader).order(ByteOrder.LITTLE_ENDIAN)
        require(lh.int == 0x04034b50 && lh.getShort(6).toInt() and 65535 == flags &&
          lh.getShort(8).toInt() and 65535 == method)
        val localNameSize = lh.getShort(26).toInt() and 65535
        require(localNameSize == nameSize)
        val localName = ByteArray(localNameSize)
        input.readFully(localName)
        require(localName.contentEquals(name)) { "Source ZIP local/central names differ" }
        val dataEnd = local + 30 + localNameSize + (lh.getShort(28).toInt() and 65535) + u32(20)
        require(dataEnd <= centralStart)
        ranges += local to dataEnd
        position = next
      }
      require(position == endPosition)
      var previous = 0L
      ranges.sortedBy { it.first }.forEach { (start, endOffset) ->
        require(start >= previous) { "Overlapping source ZIP entries" }
        previous = endOffset
      }
    }
  }

  private fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    return CharArray(bytes.size * 2).also { result ->
      bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 255
        result[index * 2] = digits[value ushr 4]
        result[index * 2 + 1] = digits[value and 15]
      }
    }.concatToString()
  }
}
