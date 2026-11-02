package os.kei.hyper.home.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Worker-only bridge for authenticated, explicit App requests in system_server. The caller owns
 * request authorization, the private-provider handoff, validation of the inner icons module, and
 * worker lifetime. Do not call from a configuration hook, Binder ingress, or the main thread.
 *
 * Verified against installed Themes 11.5.4.0 (11540), APK SHA-256
 * 089429477c7b33784e306a419e8684651bcad86f480e060cfb9bb8ed389a8659:
 * ThemeRuntimeDataProvider / ThemeProvider; ThemePCService / IThemePCService;
 * ThemeImportService; model.ResourceResolver; ResourceHelper.wvg (SHA-1).
 * The PC service imports into the native library; it does not apply the imported resource.
 *
 * Native importResource can block for 600,000 ms. There is deliberately no detached executor,
 * speculative retry, active-theme file write, configuration broadcast, or launcher restart here.
 */
internal object NativeIconPackManager {
  data class Identity(val title: String?, val localId: String?, val default: Boolean, val hash: String?)

  enum class ImportStatus { IMPORTED, ALREADY_IMPORTED, BUSY, UNSUPPORTED, INVALID_ARCHIVE, FAILED, UNCERTAIN }

  data class ImportResult(
    val status: ImportStatus,
    val localId: String? = null,
    val title: String? = null,
    val message: String? = null,
    /** A submitted request may have succeeded even if its reply or resource lookup failed. */
    val mayHaveImported: Boolean = false,
    /** Staging copy not removed; do not remove/reuse it or retry while completion is unknown. */
    val retainedStagePath: String? = null,
    /** Null for native imports; converted imports keep their success even if approval fails. */
    val convertedReady: Boolean? = null,
    /** Present only after static-PNG validation and successful native converted registration. */
    val convertedProof: ConvertedIconArchive.Proof? = null,
  )

  private const val THEMES = "com.android.thememanager"
  private const val RUNTIME_URI = "content://com.android.thememanager.provider/icons"
  private const val PROVIDER_URI = "content://com.android.thememanager.theme_provider"
  private const val PC_DESCRIPTOR = "com.android.thememanager.service.IThemePCService"
  private const val THEME_CONTEXT = "u:object_r:theme_data_file:s0"
  private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
  private const val MAX_METADATA_BYTES = 32L * 1024 * 1024
  private val stageRoot = File("/data/system/theme/.keihome-import")
  private val importing = AtomicBoolean()
  private val projection = arrayOf("local_id", "resource_name", "resource_path", "resource_hash", "online_id")

  /** Null means unavailable or changing; it must not be displayed as proof of the default theme. */
  fun identity(context: Context): Identity? = try {
    asSystemWorker {
      repeat(2) {
        val before = readIdentityRow(context)
        val provider = Uri.parse(PROVIDER_URI)
        val title = context.contentResolver.call(provider, "getUsingIconInfo", null, null)
          ?.getString("iconTitle")
        val defaults = context.contentResolver.call(provider, "isDefaultIcon", null, null)
          ?: return@asSystemWorker null
        if (!defaults.containsKey("defaultIcon")) return@asSystemWorker null
        val componentId = NativeIconComponentIdentity.fromProvider(before[2], before[3])
        if (before == readIdentityRow(context)) {
          return@asSystemWorker Identity(title.nonBlank() ?: before[1], componentId,
            defaults.getBoolean("defaultIcon"), before[3])
        }
      }
      null
    }
  } catch (e: Exception) {
    if (e is InterruptedException) Thread.currentThread().interrupt()
    null
  }

  /**
   * [validatedMtzPath] must name a stable, system-owned regular file copied through the caller's
   * authenticated private-provider handoff. App-supplied raw paths are not this API's input.
   * The input is left alone. Only a generated staging copy is offered to Themes.
   *
   * Native ThemeImportService extracts under ResourceResolver.getDownloadFolder(), independently
   * of the input's parent, and ignores failure to delete its input. Our 0755/0644 staging therefore
   * allows Themes UID 6101 (queried at runtime) to read without granting write access or chown.
   * restorecon verifies theme_data_file:s0, not the category-labelled .runtime directory.
   *
   * Success returns the Icon component's localId, not its Compound parent's ID. The caller opens
   * ThemeDetailActivity with ViewLocalResource://view.local.resource#<localId> and String extra
   * REQUEST_RESOURCE_CODE=icons. Native UI owns application and confirmation.
   * InterruptedException escapes only before native submission, allowing the caller to discard
   * its pre-submit receipt. After submission, interruption returns an owned UNCERTAIN result.
   */
  fun importIcons(context: Context, validatedMtzPath: String, convertedOrigin: String? = null): ImportResult {
    if (!importing.compareAndSet(false, true)) return ImportResult(ImportStatus.BUSY)
    var stage: File? = null
    var submitted = false
    var completed = false
    try {
      return asSystemWorker {
        verifyInstalledContract(context)
        stage = copyToStage(File(validatedMtzPath))
        val archive = inspectArchive(checkNotNull(stage))
        withNativeImporter(context) { pc ->
          val before = localIcons(pc)
          val existing = matches(before, archive)
          if (existing.size > 1) throw IOException("Multiple native Icon records match this archive")
          existing.singleOrNull()?.let {
            return@withNativeImporter approveConverted(pc, checkNotNull(stage), archive, convertedOrigin,
              ImportResult(ImportStatus.ALREADY_IMPORTED, it.localId, it.name))
          }
          checkInterrupted()
          // Set before transact: transport failure can occur after the native worker accepted it.
          submitted = true
          val reply = pcRequest(pc, 2, checkNotNull(stage).path)
          if (reply.opt("code") != "1") throw IOException("Native import rejected or timed out: $reply")
          completed = true
          val found = matches(localIcons(pc), archive)
          val oldIds = before.mapTo(HashSet()) { it.localId }
          val added = found.filter { it.localId !in oldIds }
          val imported = added.singleOrNull() ?: found.singleOrNull()
            ?: throw IOException("Native import completed but its Icon localId is not uniquely observable")
          approveConverted(pc, checkNotNull(stage), archive, convertedOrigin,
            ImportResult(ImportStatus.IMPORTED, imported.localId, imported.name, mayHaveImported = true))
        }
      }
    } catch (e: Exception) {
      if (e is InterruptedException) {
        Thread.currentThread().interrupt()
        if (!submitted) throw e
      }
      val retained = stage?.path?.takeIf { submitted && !completed }
      return ImportResult(
        status = when {
          submitted -> ImportStatus.UNCERTAIN
          e is UnsupportedContract -> ImportStatus.UNSUPPORTED
          e is InvalidArchive -> ImportStatus.INVALID_ARCHIVE
          else -> ImportStatus.FAILED
        },
        message = "${e.javaClass.simpleName}: ${e.message}".take(512),
        mayHaveImported = submitted,
        retainedStagePath = retained,
      )
    } finally {
      if (!submitted || completed) stage?.let(::removeOwnStage)
      importing.set(false)
    }
  }

  /**
   * Reconcile a persisted operation without submitting another import. The original handoff must
   * remain stable and system-owned until the caller records a terminal result. Native library
   * lookup uses transaction 5 over an owned private-action service binding. Converted outputs
   * additionally register approval; reconciliation never submits native import transaction 2.
   * Missing/ambiguous records are not proof of failure (the native inventory is capped at 700).
   * Cleanup is confined to a matching, system-owned .keihome-import/<UUID>/icons.mtz copy.
   */
  fun reconcile(context: Context, systemOwnedOriginalPath: String, retainedStagePath: String?,
    convertedOrigin: String? = null): ImportResult {
    fun uncertain(message: String) = ImportResult(ImportStatus.UNCERTAIN, message = message.take(512),
      mayHaveImported = true, retainedStagePath = retainedStagePath)
    if (!importing.compareAndSet(false, true)) return uncertain("Native icon operation still running")
    try {
      return asSystemWorker {
        verifyInstalledContract(context)
        val original = File(systemOwnedOriginalPath)
        requireOwnedFile(original)
        val archive = inspectArchive(original)
        val confirmed = withNativeImporter(context) { pc ->
          val found = matches(localIcons(pc), archive)
          val icon = found.singleOrNull() ?: return@withNativeImporter uncertain(
            if (found.isEmpty()) "Imported Icon is not yet observable in the native library"
            else "Multiple native Icon records match this archive")
          val result = ImportResult(ImportStatus.IMPORTED, icon.localId, icon.name, mayHaveImported = true)
          if (convertedOrigin == null) result else {
            // The private original is not Themes-readable. Use a fresh owned proof copy; a
            // retained import stage remains untouched until its association has been confirmed.
            var proof: File? = null
            try {
              proof = copyToStage(original)
              approveConverted(pc, proof, archive, convertedOrigin, result)
            } catch (failure: Exception) {
              grantUnavailable(result, failure)
            } finally { proof?.let(::removeOwnStage) }
          }
        }
        if (confirmed.status != ImportStatus.IMPORTED) return@asSystemWorker confirmed
        var retained = retainedStagePath
        var cleanupMessage: String? = null
        if (retained != null) {
          try {
            cleanupReconciledStage(original, archive, retained)
            retained = null
          } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            cleanupMessage = "Icon import confirmed; staging cleanup pending: ${failure.message}".take(512)
          }
        }
        confirmed.copy(message = listOfNotNull(confirmed.message, cleanupMessage).joinToString("; ")
          .takeIf { it.isNotEmpty() }?.take(512), retainedStagePath = retained)
      }
    } catch (failure: Exception) {
      if (failure is InterruptedException) Thread.currentThread().interrupt()
      return uncertain("${failure.javaClass.simpleName}: ${failure.message}")
    } finally {
      importing.set(false)
    }
  }

  /** A persisted success needs only owned-file cleanup, never another query or grant registration. */
  fun cleanupConfirmedImport(systemOwnedOriginalPath: String, retainedStagePath: String) = asSystemWorker {
    val original = File(systemOwnedOriginalPath)
    requireOwnedFile(original)
    cleanupReconciledStage(original, inspectArchive(original), retainedStagePath)
  }

  fun authorizeAutoApply(context: Context, token: String, localId: String): Boolean = asSystemWorker {
    verifyInstalledContract(context)
    withNativeImporter(context) { pc ->
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
        data.writeInterfaceToken(ThemeIconImportRuntime.GRANT_DESCRIPTOR)
        data.writeString(token); data.writeString(localId)
        check(pc.transact(ThemeIconImportRuntime.AUTHORIZE_AUTO_APPLY, data, reply, 0))
        reply.readException()
        reply.readInt() == 1
      } finally { data.recycle(); reply.recycle() }
    }
  }

  private fun approveConverted(pc: IBinder, stage: File, archive: Archive, origin: String?,
    result: ImportResult): ImportResult {
    if (origin == null) return result
    return try {
      val proof = ConvertedIconArchive.inspect(stage, origin)
      require(proof.archive.sha1 == archive.hash && proof.icons.sha1 == archive.iconHash) {
        "Converted proof no longer matches the imported archive"
      }
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
        data.writeInterfaceToken(ThemeIconImportRuntime.GRANT_DESCRIPTOR)
        listOf(origin, stage.path, requireNotNull(result.localId), proof.archive.sha1,
          proof.archive.sha256, proof.icons.sha1, proof.icons.sha256).forEach(data::writeString)
        if (!pc.transact(ThemeIconImportRuntime.REGISTER_CONVERTED, data, reply, 0)) {
          throw UnsupportedContract("Converted icon eligibility bridge unavailable")
        }
        reply.readException()
        if (reply.readInt() != 1) throw IOException(reply.readString() ?: "Converted approval rejected")
        result.copy(convertedReady = true, convertedProof = proof)
      } finally { data.recycle(); reply.recycle() }
    } catch (failure: Exception) { grantUnavailable(result, failure) }
  }

  private fun grantUnavailable(result: ImportResult, failure: Exception): ImportResult {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    return result.copy(convertedReady = false,
      message = "Imported; converted local apply unavailable: ${failure.message}".take(512))
  }

  private fun requireOwnedFile(file: File) {
    if (!file.isAbsolute || file.canonicalFile != file.absoluteFile) throw IOException("Noncanonical operation path")
    val stat = Os.lstat(file.path)
    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() ||
      stat.st_mode and 0x12 != 0 || stat.st_size !in 1..MAX_ARCHIVE_BYTES) {
      throw IOException("Expected bounded system-owned operation file without shared write access")
    }
  }

  private fun cleanupReconciledStage(original: File, archive: Archive, path: String) {
    checkInterrupted()
    val stage = File(path)
    val folder = stage.parentFile ?: throw IOException("Invalid retained stage path")
    if (!stage.isAbsolute || stage.name != "icons.mtz" || folder.parentFile != stageRoot ||
      UUID.fromString(folder.name).toString() != folder.name || stage.canonicalFile != stage.absoluteFile ||
      stage.canonicalFile == original.canonicalFile) throw IOException("Retained stage is outside the owned import path")
    for (directory in listOf(stageRoot, folder)) {
      val stat = try { Os.lstat(directory.path) } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) return // Already cleaned by an earlier reconciliation.
        throw failure
      }
      if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() ||
        stat.st_mode and 0x12 != 0 || directory.canonicalFile != directory) {
        throw IOException("Unsafe retained stage directory")
      }
    }
    val exists = try { Os.lstat(stage.path); true } catch (failure: ErrnoException) {
      if (failure.errno != OsConstants.ENOENT) throw failure
      false
    }
    if (exists) {
      requireOwnedFile(stage)
      if (inspectArchive(stage) != archive) throw IOException("Retained stage does not match the original MTZ")
      checkInterrupted()
      if (!stage.delete()) throw IOException("Could not remove the confirmed staging copy")
    }
    // Never recursively remove contents or change permissions/labels during reconciliation.
    if (!folder.delete()) throw IOException("Could not remove the staging directory")
  }

  private fun readIdentityRow(context: Context): List<String?> {
    checkInterrupted()
    return context.contentResolver.query(Uri.parse(RUNTIME_URI), projection, null, null, null)?.use { cursor ->
      if (cursor.count != 1 || !cursor.moveToFirst()) throw IOException("Missing native icon identity")
      projection.map { cursor.getString(cursor.getColumnIndexOrThrow(it)).nonBlank() }
    } ?: throw IOException("Native icon identity provider unavailable")
  }

  private fun verifyInstalledContract(context: Context) {
    val info = context.packageManager.getPackageInfo(THEMES, 0)
    val app = info.applicationInfo ?: throw UnsupportedContract("Themes application info unavailable")
    if (info.longVersionCode != 11540L || info.versionName != "11.5.4.0" || app.uid < 0 ||
      app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
      throw UnsupportedContract("Unverified Themes version ${info.versionName}/${info.longVersionCode}")
    }
    val service = context.packageManager.getServiceInfo(ComponentName(THEMES, "$THEMES.service.ThemePCService"), 0)
    if (!service.exported || service.applicationInfo.uid != app.uid) {
      throw UnsupportedContract("Native PC service identity mismatch")
    }
  }

  private fun <T> withNativeImporter(context: Context, action: (IBinder) -> T): T {
    val lock = Any()
    val ready = CountDownLatch(1)
    var closed = false
    var remote: IBinder? = null
    var failure = "Themes import bridge unavailable; check the Themes module scope"
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, service: IBinder) = synchronized(lock) {
        if (!closed) { remote = service; ready.countDown() }
      }
      override fun onServiceDisconnected(name: ComponentName) = synchronized(lock) {
        if (!closed) {
          remote = null
          failure = "Themes import bridge disconnected"
          ready.countDown()
        }
      }
      override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
      override fun onNullBinding(name: ComponentName) = synchronized(lock) {
        if (!closed) { remote = null; ready.countDown() }
      }
    }
    // Intent data participates in Android's binding cache key. A new request must not reuse a
    // guarded Binder from a retired Themes module generation.
    val intent = Intent(ThemeIconImportRuntime.PRIVATE_ACTION)
      .setComponent(ComponentName(THEMES, "$THEMES.service.ThemePCService"))
      .setData(Uri.parse("keihome-icon-import://bridge/${UUID.randomUUID()}"))
    var attempted = false
    try {
      checkInterrupted()
      attempted = true
      // Direct callbacks do only locked state publication. No reply thread or main-looper work
      // is created; closing below makes late framework callbacks inert before worker retirement.
      if (!context.bindService(intent, Context.BIND_AUTO_CREATE, Executor { it.run() }, connection)) {
        throw UnsupportedContract("Could not bind the Themes import bridge")
      }
      if (!ready.await(10, TimeUnit.SECONDS)) throw UnsupportedContract("Themes import bridge bind timed out")
      checkInterrupted()
      val binder = synchronized(lock) { remote ?: throw UnsupportedContract(failure) }
      return action(verifyPcBinder(binder))
    } finally {
      synchronized(lock) { closed = true; remote = null }
      if (attempted) {
        try { context.unbindService(connection) } catch (_: IllegalArgumentException) { /* No binding was established. */ }
      }
    }
  }

  private fun verifyPcBinder(binder: IBinder): IBinder {
    if (!binder.isBinderAlive || binder.interfaceDescriptor != PC_DESCRIPTOR) {
      throw UnsupportedContract("Native PC importer ABI mismatch")
    }
    return binder
  }

  private fun pcRequest(binder: IBinder, code: Int, value: String): JSONObject {
    checkInterrupted()
    val json = transact(binder, PC_DESCRIPTOR, code, value) { it.readString() }
      ?: throw IOException("Empty native PC reply")
    return JSONObject(json)
  }

  private fun <T> transact(binder: IBinder, descriptor: String, code: Int, value: String, read: (Parcel) -> T): T {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
      data.writeInterfaceToken(descriptor)
      data.writeString(value)
      if (!binder.transact(code, data, reply, 0)) throw UnsupportedContract("Unsupported Binder transaction $code")
      reply.readException()
      return read(reply)
    } finally {
      reply.recycle()
      data.recycle()
    }
  }

  private data class NativeIcon(val localId: String, val name: String?, val hash: String?, val parentHash: String?)
  private data class Archive(val hash: String, val iconHash: String)

  private fun localIcons(binder: IBinder): List<NativeIcon> {
    val reply = pcRequest(binder, 5, "Icon") // Case-sensitive component stamp, not "icons" or "Compound".
    if (reply.opt("code") != "1") throw IOException("Native Icon inventory failed")
    val rows = reply.getJSONObject("value").getJSONArray("localResources")
    if (rows.length() > 700) throw UnsupportedContract("Unexpected native Icon inventory contract")
    return List(rows.length()) { index ->
      val row = rows.getJSONObject(index)
      NativeIcon(row.string("localId") ?: throw IOException("Native Icon has no localId"),
        row.string("name"), row.string("hash"), row.string("parentHash"))
    }.also { rowsList ->
      if (rowsList.map { it.localId }.toSet().size != rowsList.size) throw IOException("Duplicate native Icon localId")
    }
  }

  private fun matches(icons: List<NativeIcon>, archive: Archive): List<NativeIcon> = icons.filter {
    it.hash.equals(archive.iconHash, ignoreCase = true) && it.parentHash.equals(archive.hash, ignoreCase = true)
  }

  private fun copyToStage(source: File): File {
    if (!source.isAbsolute || source.canonicalFile != source.absoluteFile) throw InvalidArchive("Noncanonical handoff path")
    withOwnedFd(Os.open(source.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)) { fd ->
      FileInputStream(fd).use { input ->
        val stat = Os.fstat(fd)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() ||
          stat.st_size !in 1..MAX_ARCHIVE_BYTES) throw InvalidArchive("Expected bounded system-owned handoff file")
        ensureStageRoot()
        val folder = File(stageRoot, UUID.randomUUID().toString())
        Os.mkdir(folder.path, 0x1c0) // 0700 until both the copy and its label are ready.
        val target = File(folder, "icons.mtz")
        try {
          restoreThemeLabel(folder)
          withOwnedFd(Os.open(target.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
            OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180)) { outFd ->
            FileOutputStream(outFd).use { output ->
              val buffer = ByteArray(64 * 1024)
              var copied = 0L
              while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > stat.st_size) throw InvalidArchive("Handoff file changed while staging")
                output.write(buffer, 0, count)
              }
              if (copied != stat.st_size) throw InvalidArchive("Incomplete handoff file")
              output.fd.sync()
            }
          }
          restoreThemeLabel(target)
          Os.chmod(target.path, 0x1a4) // 0644: Themes is a different UID; no world/group write.
          Os.chmod(folder.path, 0x1ed) // 0755
          return target
        } catch (e: Exception) {
          removeOwnStage(target)
          throw e
        }
      }
    }
  }

  // Android's FileInput/OutputStream(FileDescriptor) do not own/close their supplied descriptor.
  private inline fun <T> withOwnedFd(fd: FileDescriptor, action: (FileDescriptor) -> T): T =
    AutoCloseable { Os.close(fd) }.use { action(fd) }

  private fun ensureStageRoot() {
    try {
      Os.mkdir(stageRoot.path, 0x1ed)
    } catch (e: ErrnoException) {
      if (e.errno != OsConstants.EEXIST) throw e
    }
    val stat = Os.lstat(stageRoot.path)
    if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() ||
      stageRoot.canonicalFile != stageRoot) throw IOException("Unsafe native import staging directory")
    Os.chmod(stageRoot.path, 0x1ed)
    restoreThemeLabel(stageRoot)
  }

  private fun restoreThemeLabel(file: File) {
    val selinux = Class.forName("android.os.SELinux")
    if (selinux.getMethod("restorecon", String::class.java).invoke(null, file.path) != true ||
      selinux.getMethod("getFileContext", String::class.java).invoke(null, file.path) != THEME_CONTEXT) {
      throw IOException("Native staging path lacks theme_data_file:s0")
    }
  }

  /** Recheck the outer envelope only. Never unpack/re-encode the validated native icons/MAML module. */
  private fun inspectArchive(file: File): Archive {
    if (file.length() !in 1..MAX_ARCHIVE_BYTES) throw InvalidArchive("MTZ size limit")
    val hash = file.inputStream().use { digest(it, MAX_ARCHIVE_BYTES) }
    var iconHash: String? = null
    try {
      ZipFile(file).use { zip ->
        if (zip.size() !in 2..1024) throw InvalidArchive("MTZ entry limit")
        val seen = HashSet<String>()
        var metadataBytes = 0L
        zip.entries().asSequence().forEach { entry ->
          checkInterrupted()
          val name = entry.name
          val allowed = name == "description.xml" || name == "icons" ||
            (name == "preview/" && entry.isDirectory) ||
            Regex("preview/[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(png|jpg|jpeg|webp)").matches(name)
          if (!allowed || !seen.add(name)) throw InvalidArchive("Unexpected or duplicate MTZ entry: $name")
          val limit = if (name == "icons") MAX_ARCHIVE_BYTES else MAX_METADATA_BYTES
          if (entry.size !in 0..limit || (!entry.isDirectory && entry.size == 0L)) throw InvalidArchive("Invalid MTZ entry size")
          if (name != "icons") {
            metadataBytes += entry.size
            if (metadataBytes > MAX_METADATA_BYTES) throw InvalidArchive("MTZ metadata size limit")
          }
          val crc = CRC32()
          val entryHash = zip.getInputStream(entry).use { digest(it, entry.size, crc) }
          if (crc.value != entry.crc) throw InvalidArchive("MTZ entry CRC mismatch")
          if (name == "icons") iconHash = entryHash
        }
        if ("description.xml" !in seen || iconHash == null) throw InvalidArchive("Missing icons-only MTZ envelope")
      }
    } catch (e: IOException) {
      throw InvalidArchive(e.message ?: "Unreadable MTZ")
    }
    return Archive(hash, checkNotNull(iconHash))
  }

  private fun digest(input: java.io.InputStream, limit: Long, crc: CRC32? = null): String {
    val sha1 = MessageDigest.getInstance("SHA-1") // Exact native content identity, not a trust/signature check.
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
      checkInterrupted()
      val size = input.read(buffer)
      if (size < 0) break
      count += size
      if (count > limit) throw InvalidArchive("MTZ expanded size limit")
      sha1.update(buffer, 0, size)
      crc?.update(buffer, 0, size)
    }
    if (crc != null && count != limit) throw InvalidArchive("MTZ entry length mismatch")
    return sha1.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun removeOwnStage(file: File) {
    // Only paths created by this invocation reach here; never recursively delete user/native data.
    runCatching { file.delete() }
    runCatching { file.parentFile?.delete() }
  }

  private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Native icon request interrupted")
  }

  private inline fun <T> asSystemWorker(action: () -> T): T {
    if (Process.myUid() != 1000) throw UnsupportedContract("Requires the existing system module")
    if (Looper.myLooper() == Looper.getMainLooper()) throw IllegalStateException("Requires a worker thread")
    checkInterrupted()
    val token = Binder.clearCallingIdentity()
    try { return action() } finally { Binder.restoreCallingIdentity(token) }
  }

  private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
  private fun JSONObject.string(key: String): String? = (opt(key) as? String).nonBlank()
  private class UnsupportedContract(message: String) : IOException(message)
  private class InvalidArchive(message: String) : IOException(message)
}
