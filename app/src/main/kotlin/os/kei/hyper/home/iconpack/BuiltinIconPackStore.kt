package os.kei.hyper.home.iconpack

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import org.json.JSONObject

/** Signed APK assets, shared across profiles. No persistent copy, open FD, or decoded-byte cache.
 * Catalog identity is the original ZIP digest, so existing selections/receipts remain valid. */
internal class BuiltinIconPackStore(
  private val assets: AssetManager,
  private val limits: IconPackLimits = IconPackLimits(),
) {
  private data class ObjectEntry(val digest: String, val offset: Long, val compressed: Int, val bytes: Int)
  private var cached: List<BuiltinIconPack>? = null

  @Synchronized
  fun list(check: () -> Unit = {}): List<BuiltinIconPack> {
    check()
    cached?.let { return it }
    if ("catalog.json" !in assets.list(ROOT).orEmpty()) return emptyList()
    val bytes = assets.open("$ROOT/catalog.json").use { input ->
      ByteArrayOutputStream().also { copyBounded(input, it, 16L * 1024 * 1024, check) }.toByteArray()
    }
    val catalog = JSONObject(bytes.toString(Charsets.UTF_8))
    require(catalog.getInt("schema") == 1) { "Unsupported builtin catalog" }
    val blobBytes = catalog.getLong("blobBytes")
    require(blobBytes in 1..limits.maxStoredBytes)
    require(DIGEST.matches(catalog.getString("blobSha256")))
    assets.openFd("$ROOT/objects.kicons").use { require(it.length == blobBytes) { "Builtin blob size changed" } }
    val encoded = catalog.getJSONObject("objects")
    require(encoded.length() <= limits.maxEntries)
    val objects = linkedMapOf<String, ObjectEntry>()
    for (digest in encoded.keys()) {
      check()
      require(DIGEST.matches(digest))
      val row = encoded.getJSONArray(digest)
      require(row.length() == 3)
      val offset = row.getLong(0)
      val compressed = row.getLong(1)
      val raw = row.getLong(2)
      require(offset >= 0 && compressed in 1..limits.maxEntryBytes && raw in 0..limits.maxEntryBytes &&
        compressed <= blobBytes && offset <= blobBytes - compressed &&
        raw <= compressed * limits.maxCompressionRatio)
      objects[digest] = ObjectEntry(digest, offset, compressed.toInt(), raw.toInt())
    }
    val rows = catalog.getJSONArray("packs")
    require(rows.length() <= limits.maxStoredPacks)
    val keys = hashSetOf<String>()
    val profiles = (0 until rows.length()).map { index ->
      check()
      val row = rows.getJSONObject(index)
      val key = row.getString("key")
      val id = row.getString("id")
      require(KEY.matches(key) && keys.add(key) && DIGEST.matches(id))
      val paths = row.getJSONObject("paths")
      require(paths.length() in 1..limits.maxEntries)
      var expanded = 0L
      val entries = linkedMapOf<String, ObjectEntry>()
      for (path in paths.keys()) {
        check()
        IconPackArchive.validatePath(path)
        val entry = requireNotNull(objects[paths.getString(path)]) { "Missing shared object" }
        expanded += entry.bytes
        require(expanded <= limits.maxExpandedBytes)
        entries[path] = entry
      }
      for (path in entries.keys) {
        var slash = path.indexOf('/')
        while (slash >= 0) {
          require(path.substring(0, slash) !in entries) { "Builtin path collision" }
          slash = path.indexOf('/', slash + 1)
        }
      }
      BuiltinIconPack(key, id, entries.keys.toSet()) { path, maxBytes, cancelled ->
        val entry = entries[path] ?: throw IconPackException("Missing builtin entry: $path")
        read(entry, maxBytes, cancelled)
      }
    }
    check()
    return profiles.also { cached = it }
  }

  private fun read(entry: ObjectEntry, maxBytes: Int, check: () -> Unit): ByteArray {
    check()
    if (entry.bytes > maxBytes) throw IconPackException("Builtin entry size limit exceeded")
    val compressed = ByteArray(entry.compressed)
    // AutoCloseInputStream owns the descriptor; positional reads cannot race other requests.
    assets.openFd("$ROOT/objects.kicons").use { descriptor ->
      descriptor.createInputStream().use { input ->
        val target = ByteBuffer.wrap(compressed)
        val start = descriptor.startOffset + entry.offset
        while (target.hasRemaining()) {
          check()
          val count = input.channel.read(target, start + target.position())
          if (count <= 0) throw IconPackException("Truncated builtin object")
        }
      }
    }
    val inflater = Inflater(true)
    val decoded = try {
      InflaterInputStream(ByteArrayInputStream(compressed), inflater).use { input ->
        val output = ByteArrayOutputStream(minOf(entry.bytes, 64 * 1024))
        copyBounded(input, output, entry.bytes.toLong(), check)
        if (!inflater.finished() || inflater.bytesRead != compressed.size.toLong() || output.size() != entry.bytes) {
          throw IconPackException("Corrupt builtin object")
        }
        output.toByteArray()
      }
    } finally { inflater.end() }
    check()
    if (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded)) != entry.digest) {
      throw IconPackException("Builtin object checksum mismatch")
    }
    return decoded
  }

  private companion object {
    const val ROOT = "builtin-icon-store"
    val DIGEST = Regex("[0-9a-f]{64}")
    val KEY = Regex("[a-z0-9-]{1,128}")
  }
}

internal class BuiltinIconPack(
  val key: String,
  val id: String,
  val paths: Set<String>,
  private val reader: (String, Int, () -> Unit) -> ByteArray,
) {
  fun read(path: String, maxBytes: Int, check: () -> Unit = {}): ByteArray {
    IconPackArchive.validatePath(path)
    return reader(path, maxBytes, check)
  }
}
