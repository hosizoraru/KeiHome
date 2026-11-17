package os.kei.hyper.home.xposed

/** Caller owns synchronization. Eviction releases references, never live drawable bitmaps. */
internal class SplashSnapshotCache<K, V>(
  private val maxEntries: Int = 16,
  private val maxBytes: Int = 2 * 1024 * 1024,
) {
  data class Snapshot<V>(val value: V?)
  private data class Entry<V>(
    val snapshot: Snapshot<V>, val bytes: Int, val expires: Long, val minute: Long?,
  )
  private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
  var bytes: Int = 0
    private set

  init { require(maxEntries > 0 && maxBytes > 0) }

  fun get(key: K, now: Long, minute: Long): Snapshot<V>? {
    val entry = entries[key] ?: return null
    if (now >= entry.expires || entry.minute != null && minute != entry.minute) {
      remove(key)
      return null
    }
    return entry.snapshot
  }

  fun put(key: K, value: V?, weight: Int, expires: Long, minute: Long? = null) {
    require(weight >= 0)
    remove(key)
    if (weight > maxBytes) return
    entries[key] = Entry(Snapshot(value), weight, expires, minute)
    bytes += weight
    while (entries.size > maxEntries || bytes > maxBytes) remove(entries.keys.first())
  }

  private fun remove(key: K) {
    entries.remove(key)?.let { bytes -= it.bytes }
  }

  fun clear() {
    entries.clear()
    bytes = 0
  }
}
