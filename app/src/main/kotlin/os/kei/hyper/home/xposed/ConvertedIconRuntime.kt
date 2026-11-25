package os.kei.hyper.home.xposed

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.status.NativeIconImportReport

/**
 * Keeps the framework's periodic theme validation aware of a completed KeiHome conversion.
 *
 * The validator still owns every normal DRM decision. Only the exact system icons file, and only
 * when its complete SHA-256 is present in a durable receipt written by this module, is treated as
 * legal. This avoids suppressing validation for opaque themes or other files.
 */
internal class ConvertedIconRuntime(
  private val xposed: XposedInterface,
  private val storage: ConvertedIconValidationStorage = ConvertedIconValidationFile(),
  private val hashIcons: (File) -> String = ::convertedIconFileSha256,
  private val appearanceStorage: ConvertedAppearanceVariantStorage = ConvertedAppearanceVariantFile(),
) {
  private val gate = ConvertedIconValidationGate()
  private val registrationLock = Any()
  private val registrations = AtomicReference<Map<String, ConvertedIconValidationRegistration>>(emptyMap())
  private val appearanceVariants = AtomicReference<List<ConvertedAppearanceVariants>>(emptyList())
  private lateinit var legalResult: Any
  private lateinit var method: Method

  fun install(
    loader: ClassLoader,
    oldHooks: List<XposedInterface.HookHandle>,
  ): Pair<XposedInterface.HookHandle, XposedInterface.HookHandle?> {
    resolveContract(loader)
    loadRegistrations()
    loadAppearanceVariants()
    val previous = findMatchingIconHook(oldHooks, HOOK_ID, method)
    val installed = installOrReplaceIconHook(
      xposed, previous, HOOK_ID, method,
      XposedInterface.Hooker { chain ->
        validate(chain.getArg(1) as? File, legalResult) { chain.proceed() }
      },
    )
    require(installed.first.id == HOOK_ID && installed.first.executable == method)
    xposed.log(Log.INFO, TAG, "Installed converted icon DRM validation")
    return installed
  }

  /**
   * Called only after the import receipt and converted proof have been durably committed.
   * An identical token can be replayed, but cannot be rebound to another user or conversion.
   */
  fun register(report: NativeIconImportReport, proof: ConvertedIconArchive.Proof) {
    check(gate.enter()) { "Converted icon validation is retired" }
    try {
      require(report.imported && report.convertedReady == true)
      val registration = ConvertedIconValidationRegistration(
        report.token, report.userId, requireNotNull(report.localId), proof,
      ).also { it.validate() }
      synchronized(registrationLock) {
        val current = registrations.get()
        val previous = current[registration.token]
        require(previous == null || previous == registration) { "Converted import token was rebound" }
        if (previous == registration) return
        val updated = current + (registration.token to registration)
        require(updated.size <= MAX_REGISTRATIONS) { "Too many converted icon registrations" }
        // AtomicFile is not thread safe. Serialize commits, then publish only acknowledged bytes.
        storage.write(updated.values)
        registrations.set(updated)
      }
    } finally {
      gate.leave()
    }
  }

  /**
   * Records the fixed light/dark copies derived from a registered converted archive. They are
   * approved only while their primary digest is itself registered. Persist before Themes may
   * swap either copy into the active icons path.
   */
  fun registerAppearanceVariants(entry: ConvertedAppearanceVariants) {
    check(gate.enter()) { "Converted icon validation is retired" }
    try {
      entry.validate()
      synchronized(registrationLock) {
        val current = appearanceVariants.get()
        if (entry in current) return
        val updated = (current.filterNot { it.primary == entry.primary } + entry)
          .takeLast(MAX_APPEARANCE_VARIANTS)
        appearanceStorage.write(updated)
        appearanceVariants.set(updated)
      }
    } finally {
      gate.leave()
    }
  }

  fun hasAppearanceVariants(): Boolean = appearanceVariants.get().isNotEmpty()

  internal fun loadAppearanceVariants() = synchronized(registrationLock) {
    val loaded = try {
      appearanceStorage.read().onEach { it.validate() }.takeLast(MAX_APPEARANCE_VARIANTS)
    } catch (failure: Exception) {
      if (failure is InterruptedException) Thread.currentThread().interrupt()
      xposed.log(Log.WARN, TAG, "Ignoring corrupt appearance variant receipt", failure)
      emptyList()
    }
    appearanceVariants.set(loaded)
  }

  /** The old hook must stop admitting work before API 102 replaces its executable. */
  fun retire(): Boolean = gate.retire()

  /** Reopen admission if another runtime failed during the same hot-reload transaction. */
  fun resume() = gate.resume()

  internal fun validate(file: File?, success: Any, proceed: () -> Any?): Any? {
    if (!gate.enter()) return proceed()
    try {
      val approved = try {
        file != null && isApproved(file)
      } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        // Failure to inspect our receipt/file must leave the framework's own DRM decision intact.
        false
      }
      // Keep proceed outside the catch: an OEM exception must propagate once, without retry.
      return if (approved) success else proceed()
    } finally {
      gate.leave()
    }
  }

  private fun resolveContract(loader: ClassLoader) {
    val drm = Class.forName("miui.drm.DrmManager", false, loader)
    val resultClass = Class.forName("miui.drm.DrmManager\$DrmResult", false, loader)
    require(resultClass.isEnum)
    legalResult = resultClass.enumConstants!!.single {
      (it as Enum<*>).name == "DRM_SUCCESS"
    }
    method = drm.declaredMethods.single { candidate ->
      candidate.name == "isLegal" &&
        Modifier.isStatic(candidate.modifiers) &&
        candidate.returnType == resultClass &&
        candidate.parameterTypes.contentEquals(
          arrayOf(Context::class.java, File::class.java, File::class.java),
        )
    }.apply { isAccessible = true }
  }

  private fun isApproved(file: File): Boolean {
    if (file.path != ACTIVE_ICONS) return false
    val registered = registrations.get().values.map { it.proof.icons.sha256 }.toSet()
    if (registered.isEmpty()) return false
    // A fixed-appearance copy inherits approval only from its still-registered primary.
    val known = registered + appearanceVariants.get().filter { it.primary in registered }
      .flatMap { listOf(it.light, it.dark) }
    if (file.canonicalPath != ACTIVE_ICONS || !file.isFile ||
      file.length() !in 1..ConvertedIconArchive.MAX_BYTES) return false
    val digest = hashIcons(file)
    return digest in known && file.canonicalPath == ACTIVE_ICONS && file.isFile
  }

  internal fun loadRegistrations() = synchronized(registrationLock) {
    val loaded = try {
      val rows = storage.read()
      require(rows.size <= MAX_REGISTRATIONS)
      buildMap {
        rows.forEach { entry ->
          entry.validate()
          require(put(entry.token, entry) == null) { "Duplicate converted import token" }
        }
      }
    } catch (failure: Exception) {
      if (failure is InterruptedException) Thread.currentThread().interrupt()
      xposed.log(Log.WARN, TAG, "Ignoring corrupt converted icon validation receipt", failure)
      emptyMap()
    }
    registrations.set(loaded)
  }

  companion object {
    const val HOOK_ID = "keihome.system.converted-icons.drm-validation.v1"
    private const val TAG = "KeiHomeIconRefresh"
    private const val ACTIVE_ICONS = "/data/system/theme/icons"
    internal const val MAX_REGISTRATIONS = 700
    internal const val MAX_APPEARANCE_VARIANTS = 16
  }
}

/** SHA-256 identities of one converted icons archive and its fixed light and dark copies. */
internal data class ConvertedAppearanceVariants(val primary: String, val light: String, val dark: String) {
  fun validate() {
    val hex = Regex("[0-9a-f]{64}")
    require(listOf(primary, light, dark).all(hex::matches) && setOf(primary, light, dark).size == 3) {
      "Invalid appearance variant digests"
    }
  }
}

internal interface ConvertedAppearanceVariantStorage {
  fun read(): List<ConvertedAppearanceVariants>
  /** Return only after the entire collection has been durably committed and verified. */
  fun write(values: List<ConvertedAppearanceVariants>)
}

private class ConvertedAppearanceVariantFile : ConvertedAppearanceVariantStorage {
  // Constructed lazily: this receipt is separate from, and never rewrites, converted-drm-v1.json.
  private val storage by lazy {
    AtomicFile(File("/data/system/keihome-icon-imports/appearance-variants-v1.json"))
  }

  override fun read(): List<ConvertedAppearanceVariants> {
    if (!storage.baseFile.exists() && !File("${storage.baseFile.path}.bak").exists()) return emptyList()
    val root = JSONObject(String(storage.openRead().use(::readConvertedIconValidationReceipt), Charsets.UTF_8))
    require(root.get("version") == 1)
    val rows = root.getJSONArray("variants")
    require(rows.length() <= ConvertedIconRuntime.MAX_APPEARANCE_VARIANTS)
    return List(rows.length()) { index ->
      val row = rows.getJSONObject(index)
      ConvertedAppearanceVariants(row.getString("primary"), row.getString("light"), row.getString("dark"))
    }
  }

  override fun write(values: List<ConvertedAppearanceVariants>) {
    val rows = JSONArray()
    values.forEach { rows.put(JSONObject().put("primary", it.primary).put("light", it.light).put("dark", it.dark)) }
    val bytes = JSONObject().put("version", 1).put("variants", rows).toString().toByteArray(Charsets.UTF_8)
    val stream = storage.startWrite()
    try {
      stream.write(bytes)
      stream.fd.sync()
      storage.finishWrite(stream)
    } catch (failure: Throwable) {
      storage.failWrite(stream)
      throw failure
    }
    require(storage.openRead().use(::readConvertedIconValidationReceipt).contentEquals(bytes)) {
      "Appearance variant receipt was not committed"
    }
  }
}

internal interface ConvertedIconValidationStorage {
  fun read(): List<ConvertedIconValidationRegistration>
  /** Return only after the entire collection has been durably committed and verified. */
  fun write(values: Collection<ConvertedIconValidationRegistration>)
}

private class ConvertedIconValidationFile : ConvertedIconValidationStorage {
  private val storage = AtomicFile(File("/data/system/keihome-icon-imports/converted-drm-v1.json"))

  override fun read(): List<ConvertedIconValidationRegistration> {
    if (!storage.baseFile.exists() && !File("${storage.baseFile.path}.bak").exists()) return emptyList()
    val bytes = storage.openRead().use(::readConvertedIconValidationReceipt)
    val root = JSONObject(String(bytes, Charsets.UTF_8))
    require(root.get("version") == 1)
    val rows = root.getJSONArray("registrations")
    require(rows.length() <= ConvertedIconRuntime.MAX_REGISTRATIONS)
    return List(rows.length()) { index ->
      val row = rows.getJSONObject(index)
      val proof = ConvertedIconArchive.Proof(
        row.getString("origin"),
        ConvertedIconArchive.Digests(row.getString("archiveSha1"), row.getString("archiveSha256")),
        ConvertedIconArchive.Digests(row.getString("iconsSha1"), row.getString("iconsSha256")),
      )
      ConvertedIconValidationRegistration(
        row.getString("token"), requireNotNull(row.get("user") as? Int), row.getString("localId"), proof,
      )
    }
  }

  override fun write(values: Collection<ConvertedIconValidationRegistration>) {
    val rows = JSONArray()
    values.forEach { entry ->
      rows.put(JSONObject()
        .put("token", entry.token)
        .put("user", entry.userId)
        .put("localId", entry.localId)
        .put("origin", entry.proof.origin)
        .put("archiveSha1", entry.proof.archive.sha1)
        .put("archiveSha256", entry.proof.archive.sha256)
        .put("iconsSha1", entry.proof.icons.sha1)
        .put("iconsSha256", entry.proof.icons.sha256))
    }
    val bytes = JSONObject().put("version", 1).put("registrations", rows)
      .toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_RECEIPT_BYTES) { "Converted validation receipt size limit" }
    val stream = storage.startWrite()
    try {
      stream.write(bytes)
      stream.fd.sync()
      storage.finishWrite(stream)
    } catch (failure: Throwable) {
      storage.failWrite(stream)
      throw failure
    }
    require(storage.openRead().use(::readConvertedIconValidationReceipt).contentEquals(bytes)) {
      "Converted validation receipt was not committed"
    }
  }
}

private const val MAX_RECEIPT_BYTES = 1024 * 1024

internal fun readConvertedIconValidationReceipt(input: InputStream): ByteArray {
  val output = ByteArrayOutputStream()
  val buffer = ByteArray(8192)
  while (true) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    val count = input.read(buffer)
    if (count < 0) break
    require(output.size() + count <= MAX_RECEIPT_BYTES) { "Converted validation receipt size limit" }
    output.write(buffer, 0, count)
  }
  return output.toByteArray()
}

internal fun convertedIconFileSha256(file: File): String {
  val length = file.length()
  require(file.isFile && length in 1..ConvertedIconArchive.MAX_BYTES)
  val path = file.toPath()
  val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
  val identity = requireNotNull(before.fileKey()) { "Converted icons have no file identity" }
  require(before.isRegularFile && before.size() == length)
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
      if (Thread.currentThread().isInterrupted) throw InterruptedException()
      val count = input.read(buffer)
      if (count < 0) break
      total += count
      require(total <= length) { "Converted icons grew during validation" }
      digest.update(buffer, 0, count)
    }
    require(total == length && file.length() == length) { "Converted icons changed during validation" }
    // A rename can leave this stream on the old inode while the active path has equal-sized bytes.
    val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(after.isRegularFile && after.fileKey() == identity && after.size() == length &&
      after.lastModifiedTime() == before.lastModifiedTime() &&
      after.creationTime() == before.creationTime()) { "Converted icons were replaced during validation" }
  }
  return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class ConvertedIconValidationRegistration(
  val token: String,
  val userId: Int,
  val localId: String,
  val proof: ConvertedIconArchive.Proof,
) {
  fun validate() {
    require(TOKEN.matches(token)) { "Invalid converted import token" }
    require(userId in 0..(Int.MAX_VALUE / 100_000)) { "Invalid converted import user" }
    require(localId.length in 1..128 && localId.isNotBlank() && localId.none(Char::isISOControl)) {
      "Invalid converted local ID"
    }
    require(ConvertedIconArchive.supportsOrigin(proof.origin))
    require(listOf(proof.archive, proof.icons).all {
      HEX_40.matches(it.sha1) && HEX_64.matches(it.sha256)
    }) { "Invalid converted import digests" }
  }

  companion object {
    private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val HEX_40 = Regex("[0-9a-f]{40}")
    private val HEX_64 = Regex("[0-9a-f]{64}")
  }
}

internal class ConvertedIconValidationGate {
  private val lock = Any()
  private var active = 0
  private var retiring = false
  private var retired = false

  fun enter(): Boolean = synchronized(lock) {
    if (retiring || retired) false else {
      active += 1
      true
    }
  }

  fun leave() = synchronized(lock) {
    check(active > 0)
    active -= 1
  }

  fun retire(): Boolean = synchronized(lock) {
    if (retired) return@synchronized true
    if (retiring || active != 0) return@synchronized false
    retiring = true
    retired = true
    retiring = false
    true
  }

  fun resume() = synchronized(lock) {
    if (retired) {
      retired = false
    }
    retiring = false
  }
}
