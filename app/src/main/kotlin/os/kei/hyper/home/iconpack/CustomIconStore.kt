package os.kei.hyper.home.iconpack

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.data.PreservedIcons

internal data class CustomIconSelection(
  val packageName: String, val appLabel: String, val sourceId: String, val sourceTitle: String,
  val sourceEntry: String, val token: String, val digest: String,
  val bytes: Long,
  val captureRevision: Int = 0,
)

internal data class CustomIconSnapshot(
  val selections: List<CustomIconSelection>, val resources: Map<String, Map<String, ByteArray>>,
) {
  val packages = selections.mapTo(linkedSetOf()) { it.packageName }
  val identity: String? = if (selections.isEmpty()) null else CustomIconStore.digest(
    selections.sortedBy { it.packageName }.joinToString("\n") { "${it.packageName}:${it.digest}" }.toByteArray())
}

/** Durable user choices, separate from disposable pack caches. Publication and reads share one lock. */
internal object CustomIconStore {
  const val CAPTURE_REVISION = 1
  private val lock = Any()
  private val tokenPattern = Regex("[0-9a-f-]{36}")
  private val digestPattern = Regex("[0-9a-f]{64}")
  private val mutableRevision = MutableStateFlow(0L)
  val revision = mutableRevision.asStateFlow()
  private fun prefs(context: Context) = context.getSharedPreferences("custom_icons_v1", Context.MODE_PRIVATE)
  private fun root(context: Context) = File(context.filesDir, "custom-icons")

  fun selections(context: Context): List<CustomIconSelection> = synchronized(lock) {
    val array = JSONArray(prefs(context).getString("selections", "[]"))
    require(array.length() <= 256)
    List(array.length()) { index ->
      val item = array.getJSONObject(index)
      CustomIconSelection(item.getString("package"), item.getString("label"), item.getString("source"),
        item.getString("title"), item.getString("entry"), item.getString("token"), item.getString("digest"),
        item.getLong("bytes"), item.optInt("captureRevision", 0)).also {
        require(PreservedIcons.isValidPackageName(it.packageName))
        require(it.token.matches(tokenPattern) && it.digest.matches(digestPattern))
        require(it.bytes in 1..CustomIconResources.MAX_BYTES.toLong())
        require(it.captureRevision >= 0)
      }
    }.also {
      require(it.map { row -> row.packageName }.distinct().size == it.size)
      require(it.map { row -> row.token }.distinct().size == it.size)
      require(it.sumOf { row -> row.bytes } <= 64L * 1024 * 1024)
    }
  }

  fun identity(context: Context): String? = CustomIconSnapshot(selections(context), emptyMap()).identity

  fun read(context: Context, check: () -> Unit = {}): CustomIconSnapshot = synchronized(lock) {
    val selections = selections(context)
    val resources = selections.associate { item -> item.packageName to verifiedResources(context, item, check) }
    CustomIconSnapshot(selections, resources)
  }

  fun readSelection(context: Context, expected: CustomIconSelection,
    check: () -> Unit = {}): Map<String, ByteArray>? = synchronized(lock) {
    if (selections(context).firstOrNull { it.packageName == expected.packageName } != expected) return null
    verifiedResources(context, expected, check)
  }

  private fun verifiedResources(context: Context, item: CustomIconSelection,
    check: () -> Unit): Map<String, ByteArray> {
    check()
    val file = File(root(context), "${item.token}.zip")
    require(file.isFile && file.length() <= CustomIconResources.MAX_BYTES + 8 * 1024 * 1024 &&
      sha256(file, check) == item.digest) { "A saved custom icon is unavailable" }
    return readResources(file, check)
  }

  fun save(context: Context, packageName: String, appLabel: String, pack: IconPackDescriptor,
    entry: IconPackEntry, resources: Map<String, ByteArray>, check: () -> Unit = {}): CustomIconSelection =
    write(context, packageName, appLabel, pack.id, pack.displayName, entry.key, resources, check)

  /** Compare under the publication lock so migration never overwrites a concurrent user edit. */
  fun refresh(context: Context, expected: CustomIconSelection, resources: Map<String, ByteArray>,
    check: () -> Unit = {}): Boolean = synchronized(lock) {
    if (selections(context).singleOrNull { it.packageName == expected.packageName } != expected)
      return@synchronized false
    write(context, expected.packageName, expected.appLabel, expected.sourceId, expected.sourceTitle,
      expected.sourceEntry, resources, check)
    true
  }

  private fun write(context: Context, packageName: String, appLabel: String, sourceId: String,
    sourceTitle: String, sourceEntry: String, resources: Map<String, ByteArray>,
    check: () -> Unit): CustomIconSelection =
    synchronized(lock) {
      require(PreservedIcons.isValidPackageName(packageName))
      require(resources.isNotEmpty() && resources.keys.all { CustomIconResources.owner(it) == "custom.icon" })
      require(resources.size <= 4096 && resources.values.sumOf { it.size.toLong() } <= CustomIconResources.MAX_BYTES)
      val previous = selections(context)
      require(previous.size < 256 || previous.any { it.packageName == packageName })
      val bytes = resources.values.sumOf { it.size.toLong() }
      require(previous.filterNot { it.packageName == packageName }.sumOf { it.bytes } + bytes <= 64L * 1024 * 1024) {
        "Custom icon storage limit reached"
      }
      val directory = root(context).apply { require(isDirectory || mkdirs()) }
      val token = UUID.randomUUID().toString()
      val file = File(directory, "$token.zip")
      var committed = false
      try {
        ZipOutputStream(file.outputStream()).use { zip ->
          resources.toSortedMap().forEach { (path, bytes) ->
            check()
            zip.putNextEntry(ZipEntry(path).apply { time = 0 })
            zip.write(bytes); zip.closeEntry()
          }
        }
        val item = CustomIconSelection(packageName, appLabel, sourceId, sourceTitle, sourceEntry, token,
          sha256(file, check), bytes, CAPTURE_REVISION)
        check()
        publish(context, previous.filterNot { it.packageName == packageName } + item)
        committed = true
        previous.filter { it.packageName == packageName }.forEach { File(directory, "${it.token}.zip").delete() }
        mutableRevision.value++
        item
      } finally { if (!committed) file.delete() }
    }

  fun remove(context: Context, packageName: String) = synchronized(lock) {
    val previous = selections(context)
    publish(context, previous.filterNot { it.packageName == packageName })
    previous.filter { it.packageName == packageName }.forEach { File(root(context), "${it.token}.zip").delete() }
    mutableRevision.value++
  }

  private fun publish(context: Context, selections: List<CustomIconSelection>) {
    val array = JSONArray()
    selections.sortedBy { it.packageName }.forEach { item -> array.put(JSONObject().apply {
      put("package", item.packageName); put("label", item.appLabel); put("source", item.sourceId)
      put("title", item.sourceTitle); put("entry", item.sourceEntry); put("token", item.token); put("digest", item.digest)
      put("bytes", item.bytes)
      put("captureRevision", item.captureRevision)
    }) }
    val preferences = prefs(context)
    val previous = preferences.getString("selections", "[]")
    if (!preferences.edit().putString("selections", array.toString()).commit()) {
      // SharedPreferences changes memory even when the disk commit fails.
      preferences.edit().putString("selections", previous).commit()
      error("Cannot save custom icon choices")
    }
  }

  fun readResources(file: File, check: () -> Unit = {}): Map<String, ByteArray> = ZipFile(file).use { zip ->
    val result = linkedMapOf<String, ByteArray>()
    var total = 0L
    zip.entries().asSequence().filterNot { it.isDirectory }.forEach { item ->
      check()
      require(result.size < 4096 && CustomIconResources.owner(item.name) == "custom.icon")
      val bytes = zip.getInputStream(item).use { it.readNBytes(CustomIconResources.MAX_BYTES + 1) }
      total += bytes.size
      require(total <= CustomIconResources.MAX_BYTES && result.put(item.name, bytes) == null)
    }
    require(result.isNotEmpty())
    result
  }

  internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

  fun preview(context: Context, item: CustomIconSelection, dark: Boolean): ByteArray? = synchronized(lock) {
    val file = File(root(context), "${item.token}.zip")
    if (!file.isFile) return@synchronized null
    ZipFile(file).use { zip ->
      // Saved pairs and dynamic snapshots need the same completed geometry as applied surfaces.
      val bitmap = os.kei.hyper.home.xposed.SurfaceIconSnapshotRenderer.render(
        zip, "custom.icon", dark, java.util.Locale.getDefault(), System.currentTimeMillis())
      if (bitmap != null) {
        try {
          return@synchronized java.io.ByteArrayOutputStream().also {
            require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
          }.toByteArray()
        } finally { bitmap.recycle() }
      }
      val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
      val preferred = if (dark) listOf("fancy_icons/custom.icon/night.png", "fancy_icons/custom.icon/day.png")
        else listOf("fancy_icons/custom.icon/day.png")
      val entry = preferred.firstNotNullOfOrNull(zip::getEntry)
        ?: entries.firstOrNull { it.name.startsWith(if (dark) "nightmode/res/" else "res/") && it.name.endsWith("/custom.icon.png") }
        ?: entries.firstOrNull { it.name.startsWith("res/") && it.name.endsWith("/custom.icon.png") }
        ?: return@synchronized null
      zip.getInputStream(entry).use { it.readNBytes(4 * 1024 * 1024 + 1) }.takeIf { it.size <= 4 * 1024 * 1024 }
    }
  }
}
