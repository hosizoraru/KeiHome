package os.kei.hyper.home.xposed

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.iconpack.NativeAppearanceVariants

/**
 * Themes-owned fixed light and dark copies of converted icon archives.
 *
 * HyperOS Home does not read `nightmode/res` for ordinary icons, so a system-following archive
 * needs a MAML selector per icon. When the user opts in, system_server derives two static copies
 * and this store makes the matching one the active icons archive after each appearance change.
 * Only an active archive whose digest equals a registered primary or copy is ever replaced.
 *
 * No global theme configuration change is sent, so foreground apps are not relaunched and Home keeps
 * its engine and page. Home rechecks the archive's modification time on every icon lookup and
 * clears its icon caches when it applies a night-mode change. The opposite copy is therefore
 * pre-staged beside the active archive; system_server renames it into place before the night
 * configuration is dispatched. Runs in Themes, inside the guarded system-UID bridge.
 */
internal class ThemeAppearanceVariants(private val context: Context) {
  data class Record(
    val localId: String,
    val primary: ConvertedIconArchive.Digests,
    val primaryContent: String,
    val light: ConvertedIconArchive.Digests,
    val dark: ConvertedIconArchive.Digests,
    val paletteFlattened: Boolean,
  )

  /** What system_server needs for an atomic, pre-dispatch swap at the next night transition. */
  data class Prepared(
    val activeKey: AppearanceFileKey,
    val activeNight: Boolean,
    val stagedPath: String,
    val stagedKey: AppearanceFileKey,
    val stagedNight: Boolean,
  )

  data class SyncResult(val status: String, val prepared: Prepared?)

  /**
   * Guards the receipt, the copy directories and the theme directory files this store writes.
   * The receipt is read on every use: it holds at most [MAX_RECORDS] rows, and a cached copy
   * could miss a write made after it was loaded.
   */
  private val lock = Any()

  /** Digests of files this store wrote or verified, by identity. A rename keeps the identity. */
  private val known = object : LinkedHashMap<AppearanceFileKey, ConvertedIconArchive.Digests>() {
    override fun removeEldestEntry(
      eldest: MutableMap.MutableEntry<AppearanceFileKey, ConvertedIconArchive.Digests>,
    ) = size > 8
  }

  /** Fixed copies that inherit a primary grant's approval. Empty when unavailable. */
  fun siblings(primary: ConvertedIconArchive.Digests): Set<ConvertedIconArchive.Digests> = try {
    synchronized(lock) { records() }.filter { it.primary == primary }.flatMapTo(HashSet()) { listOf(it.light, it.dark) }
  } catch (failure: Exception) {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    emptySet()
  }

  fun register(
    localId: String,
    primary: ConvertedIconArchive.Digests,
    primaryContent: File,
    lightStage: File,
    light: ConvertedIconArchive.Digests,
    darkStage: File,
    dark: ConvertedIconArchive.Digests,
    paletteFlattened: Boolean,
  ): Unit = synchronized(lock) {
    sweepTemporaries()
    require(localId.length in 1..128 && localId.none(Char::isISOControl)) { "Invalid converted local ID" }
    require(setOf(primary, light, dark).size == 3) { "Appearance copies must differ from the original" }
    require(ConvertedIconArchive.digest(primaryContent) == primary) { "Native Icon bytes do not match conversion" }
    for (stage in listOf(lightStage, darkStage)) requireSystemStage(stage)
    val directory = File(root(), primary.sha256).apply { check(isDirectory || mkdirs()) }
    copyVerified(lightStage, File(directory, LIGHT), light)
    copyVerified(darkStage, File(directory, DARK), dark)
    val record = Record(localId, primary, primaryContent.path, light, dark, paletteFlattened)
    val previous = records()
    val kept = retain(previous, activeRecord(previous), record)
    persist(kept)
    removeUnreferencedCopies(kept)
  }

  /**
   * Stops fixed copies for [primary], for an import made without them. An active copy is first
   * replaced by the primary itself, so the active archive never loses its approval. The status
   * starts with "applied" when the active archive changed.
   */
  fun unregister(primary: ConvertedIconArchive.Digests): SyncResult = synchronized(lock) {
    sweepTemporaries()
    val records = records()
    val record = records.lastOrNull { it.primary == primary } ?: return SyncResult("absent", null)
    val active = File(ACTIVE)
    val before = statKey(active)
    val current = before?.let { currentDigest(active, it) }
    val status = if (current == record.light || current == record.dark) {
      if (!replaceActive(File(record.primaryContent), record.primary, active, checkNotNull(before))) {
        return SyncResult("theme-changed", null)
      }
      "applied primary; unregistered"
    } else "unregistered"
    val kept = records - record
    persist(kept)
    removeUnreferencedCopies(kept)
    // Staged copies belong to the applied record; keep them when that is another import.
    val applied = current == record.primary || current == record.light || current == record.dark
    if (applied) clearStaging(status) else SyncResult(status, null)
  }

  /**
   * Makes the active icons archive the copy for this appearance, then stages the opposite copy.
   * When this call itself replaces the archive, the caller asks Home to reload icons through
   * package callbacks; a pre-dispatch rename needs nothing more than Home's own night handling.
   */
  fun sync(night: Boolean, paletteEnabled: Boolean): SyncResult = synchronized(lock) {
    sweepTemporaries()
    val active = File(ACTIVE)
    val before = statKey(active) ?: return clearStaging("unmanaged")
    val current = currentDigest(active, before)
    val record = records().lastOrNull { current == it.primary || current == it.light || current == it.dark }
      ?: return clearStaging("unmanaged")
    val role = NativeAppearanceVariants.target(night, paletteEnabled, record.paletteFlattened)
    val expected = digestFor(record, role)
    val status = if (current == expected) "unchanged ${role.label}" else {
      val source = sourceFor(record, role)
      if (!source.isFile) return clearStaging("missing ${role.label}")
      if (!replaceActive(source, expected, active, before)) return clearStaging("theme-changed")
      // Themes has just applied the primary and Home reloaded it, unless this store itself
      // restored the primary for the live palette (that file keeps its identity until now).
      if (current == record.primary && before != paletteKey()) "applied ${role.label} $FROM_PRIMARY"
      else "applied ${role.label}"
    }
    val activeKey = statKey(active) ?: return clearStaging("unmanaged")
    known[activeKey] = expected
    // A palette-backed original changes through this slower path only.
    if (role == NativeAppearanceVariants.Role.PRIMARY) {
      savePaletteKey(activeKey)
      return clearStaging(status)
    }
    val next = if (role == NativeAppearanceVariants.Role.DARK) NativeAppearanceVariants.Role.LIGHT
      else NativeAppearanceVariants.Role.DARK
    val stagedKey = try {
      stage(record, next, active)
    } catch (failure: Exception) {
      if (failure is InterruptedException) throw failure
      return clearStaging("$status; staging failed: ${failure.javaClass.simpleName}")
    }
    SyncResult(status, Prepared(activeKey, role == NativeAppearanceVariants.Role.DARK,
      stagedFile(next).path, stagedKey, next == NativeAppearanceVariants.Role.DARK))
  }

  /** Identity of the primary this store placed for the live palette; survives a Themes restart. */
  private fun paletteKey(): AppearanceFileKey? = try {
    AppearanceFileKey.of(File(root(), PALETTE_KEY).readText().split(',').map(String::toLong).toLongArray())
  } catch (_: Exception) { null }

  private fun savePaletteKey(key: AppearanceFileKey) {
    try { File(root(), PALETTE_KEY).writeText(key.toLongArray().joinToString(",")) }
    catch (_: IOException) { /* Only weakens the after-apply distinction. */ }
  }

  private fun currentDigest(active: File, key: AppearanceFileKey): ConvertedIconArchive.Digests =
    known[key] ?: ConvertedIconArchive.digest(active).also { known[key] = it }

  /** The record whose primary or copy is the applied archive; null when that cannot be read. */
  private fun activeRecord(records: List<Record>): Record? {
    val active = File(ACTIVE)
    val current = try {
      statKey(active)?.let { currentDigest(active, it) } ?: return null
    } catch (failure: Exception) {
      if (failure is InterruptedException) throw failure
      return null
    }
    return records.lastOrNull { current == it.primary || current == it.light || current == it.dark }
  }

  /** Renames a verified copy of [source] over the active archive unless Themes replaced it first. */
  private fun replaceActive(
    source: File,
    expected: ConvertedIconArchive.Digests,
    active: File,
    before: AppearanceFileKey,
  ): Boolean {
    val staged = File(active.parentFile, "$STAGED_PREFIX${UUID.randomUUID()}.tmp")
    try {
      copyVerified(source, staged, expected)
      matchActiveMetadata(active, staged)
      // Themes may have applied another archive while this copy was written.
      if (statKey(active) != before) return false
      Os.rename(staged.path, active.path)
    } finally {
      Files.deleteIfExists(staged.toPath())
    }
    statKey(active)?.let { known[it] = expected }
    return true
  }

  private fun removeUnreferencedCopies(kept: List<Record>) {
    for (stale in root().listFiles().orEmpty()) {
      if (!stale.isDirectory || !stale.name.matches(HEX_64) || kept.any { it.primary.sha256 == stale.name }) continue
      stale.listFiles().orEmpty().forEach(File::delete)
      stale.delete()
    }
  }

  /**
   * Removes copies a killed process left mid-write. Every writer holds [lock] in this process,
   * so no temporary file is in use when a locked operation starts.
   */
  private fun sweepTemporaries() {
    val theme = File(ACTIVE).parentFile ?: return
    val leftovers = theme.listFiles { _, name ->
      name.endsWith(".tmp") && (name.startsWith(STAGED_PREFIX) || name.startsWith(".$STAGED_PREFIX"))
    }.orEmpty().toMutableList()
    for (folder in root().listFiles().orEmpty()) {
      if (folder.isDirectory && folder.name.matches(HEX_64)) {
        leftovers += folder.listFiles { _, name -> name.startsWith(".") && name.endsWith(".tmp") }.orEmpty()
      }
    }
    for (file in leftovers) {
      try { Files.deleteIfExists(file.toPath()) } catch (_: IOException) { /* Best effort. */ }
    }
  }

  /** Copies [role] next to the active archive with the active archive's owner, mode and label. */
  private fun stage(record: Record, role: NativeAppearanceVariants.Role, active: File): AppearanceFileKey {
    val target = stagedFile(role)
    for (other in NativeAppearanceVariants.Role.entries) {
      if (other != role) Files.deleteIfExists(stagedFile(other).toPath())
    }
    val expected = digestFor(record, role)
    statKey(target)?.let { existing -> if (known[existing] == expected) return existing }
    Files.deleteIfExists(target.toPath())
    val temporary = File(active.parentFile, "$STAGED_PREFIX${UUID.randomUUID()}.tmp")
    try {
      copyVerified(sourceFor(record, role), temporary, expected)
      matchActiveMetadata(active, temporary)
      Os.rename(temporary.path, target.path)
    } finally {
      Files.deleteIfExists(temporary.toPath())
    }
    val key = requireNotNull(statKey(target)) { "Staged appearance copy disappeared" }
    known[key] = expected
    return key
  }

  private fun clearStaging(status: String): SyncResult {
    for (role in NativeAppearanceVariants.Role.entries) {
      try { Files.deleteIfExists(stagedFile(role).toPath()) } catch (_: IOException) { /* Best effort. */ }
    }
    return SyncResult(status, null)
  }

  private val NativeAppearanceVariants.Role.label get() = name.lowercase()

  private fun stagedFile(role: NativeAppearanceVariants.Role) =
    File(File(ACTIVE).parentFile, "$STAGED_PREFIX${role.label}.icons")

  private fun digestFor(record: Record, role: NativeAppearanceVariants.Role) = when (role) {
    NativeAppearanceVariants.Role.PRIMARY -> record.primary
    NativeAppearanceVariants.Role.LIGHT -> record.light
    NativeAppearanceVariants.Role.DARK -> record.dark
  }

  private fun sourceFor(record: Record, role: NativeAppearanceVariants.Role) = when (role) {
    NativeAppearanceVariants.Role.PRIMARY -> File(record.primaryContent)
    NativeAppearanceVariants.Role.LIGHT -> File(File(root(), record.primary.sha256), LIGHT)
    NativeAppearanceVariants.Role.DARK -> File(File(root(), record.primary.sha256), DARK)
  }

  private fun statKey(file: File): AppearanceFileKey? = try {
    Os.lstat(file.path).takeIf { OsConstants.S_ISREG(it.st_mode) }?.let(AppearanceFileKey::of)
  } catch (_: ErrnoException) { null }

  private fun requireSystemStage(stage: File) {
    val folder = requireNotNull(stage.parentFile)
    require(stage.isAbsolute && stage.canonicalFile == stage && stage.name in setOf(LIGHT, DARK) &&
      folder.parentFile == File(STAGE_ROOT) && UUID.fromString(folder.name).toString() == folder.name) {
      "Appearance copy is outside the system-owned staging path"
    }
    for (file in listOf(folder.parentFile!!, folder, stage)) {
      val stat = Os.lstat(file.path)
      require(stat.st_uid == 1000 && stat.st_mode and 0x12 == 0 &&
        if (file == stage) OsConstants.S_ISREG(stat.st_mode) else OsConstants.S_ISDIR(stat.st_mode)) {
        "Appearance copy must remain system-owned without shared write access"
      }
    }
  }

  private fun copyVerified(source: File, target: File, expected: ConvertedIconArchive.Digests) {
    val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
      source.inputStream().use { input ->
        temporary.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          var copied = 0L
          while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= ConvertedIconArchive.MAX_BYTES) { "Appearance copy size limit" }
            output.write(buffer, 0, count)
          }
          output.fd.sync()
        }
      }
      require(ConvertedIconArchive.digest(temporary) == expected) { "Appearance copy digest mismatch" }
      Os.rename(temporary.path, target.path)
    } finally {
      Files.deleteIfExists(temporary.toPath())
    }
  }

  /** Same owner, mode and SELinux label as the archive Themes applied. */
  private fun matchActiveMetadata(active: File, staged: File) {
    val selinux = Class.forName("android.os.SELinux")
    val fileContext = selinux.getMethod("getFileContext", String::class.java)
    val wanted = fileContext.invoke(null, active.path) as? String
      ?: throw IOException("Active icons archive has no SELinux label")
    if (fileContext.invoke(null, staged.path) != wanted) {
      Class.forName("miui.content.res.ThemeNativeUtils")
        .getMethod("updateFilePermissionWithThemeContext", String::class.java).invoke(null, staged.path)
    }
    val current = Os.stat(active.path)
    val replacement = Os.stat(staged.path)
    if (current.st_uid != replacement.st_uid || current.st_gid != replacement.st_gid ||
      fileContext.invoke(null, staged.path) != wanted) {
      throw IOException("Could not match the active icons archive owner and label")
    }
    Os.chmod(staged.path, current.st_mode and 0x1ff)
  }

  private fun root(): File = context.getDir(DIRECTORY, Context.MODE_PRIVATE)

  private fun storage(): AtomicFile = AtomicFile(File(root(), RECORDS))

  private fun records(): List<Record> {
    val file = storage()
    val result = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyList() else {
      val bytes = file.openRead().use(::readConvertedIconValidationReceipt)
      val root = JSONObject(String(bytes, Charsets.UTF_8))
      require(root.getInt("version") == 1)
      val rows = root.getJSONArray("records")
      require(rows.length() <= MAX_RECORDS)
      List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        fun digests(prefix: String) = ConvertedIconArchive.Digests(
          row.getString("${prefix}Sha1").also { require(it.matches(HEX_40)) },
          row.getString("${prefix}Sha256").also { require(it.matches(HEX_64)) })
        Record(row.getString("localId"), digests("primary"), row.getString("primaryContent"),
          digests("light"), digests("dark"), row.getBoolean("paletteFlattened"))
      }
    }
    return result
  }

  private fun persist(records: List<Record>) {
    val rows = JSONArray()
    for (record in records) {
      val row = JSONObject().put("localId", record.localId).put("primaryContent", record.primaryContent)
        .put("paletteFlattened", record.paletteFlattened)
      for ((prefix, digests) in listOf("primary" to record.primary, "light" to record.light, "dark" to record.dark)) {
        row.put("${prefix}Sha1", digests.sha1).put("${prefix}Sha256", digests.sha256)
      }
      rows.put(row)
    }
    val bytes = JSONObject().put("version", 1).put("records", rows).toString().toByteArray(Charsets.UTF_8)
    val file = storage()
    val stream = file.startWrite()
    try {
      stream.write(bytes)
      file.finishWrite(stream)
    } catch (failure: Throwable) {
      file.failWrite(stream)
      throw failure
    }
  }

  companion object {
    /**
     * The newest records within [MAX_RECORDS], replacing any earlier record of the same primary.
     * The applied archive may be a copy of an older import; its record outlives newer imports.
     */
    internal fun retain(previous: List<Record>, applied: Record?, added: Record): List<Record> {
      val others = previous.filter { it.primary != added.primary }
      val pinned = applied?.takeIf { it in others }
      val room = MAX_RECORDS - 1 - (if (pinned == null) 0 else 1)
      return others.filter { it != pinned }.takeLast(room) + listOfNotNull(pinned) + added
    }

    const val ACTIVE = "/data/system/theme/icons"
    /** Sync status marker: the copy replaced the original that Themes had just applied. */
    const val FROM_PRIMARY = "after apply"
    const val STAGED_PREFIX = ".keihome-appearance-"
    private const val STAGE_ROOT = "/data/system/theme/.keihome-import"
    private const val DIRECTORY = "keihome_appearance_variants"
    private const val RECORDS = "variants-v1.json"
    private const val PALETTE_KEY = "palette-primary-key"
    private const val LIGHT = "light.icons"
    private const val DARK = "dark.icons"
    private const val MAX_RECORDS = 2
    private val HEX_40 = Regex("[0-9a-f]{40}")
    private val HEX_64 = Regex("[0-9a-f]{64}")
  }
}
