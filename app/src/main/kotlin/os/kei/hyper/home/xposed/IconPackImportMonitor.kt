package os.kei.hyper.home.xposed

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.UserHandle
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.json.JSONObject
import os.kei.hyper.home.status.IconPackImportProtocol
import os.kei.hyper.home.status.IconPackImportRequest
import os.kei.hyper.home.status.NativeIconImportReport
import os.kei.hyper.home.status.RuntimeStatusInbox

/** Native import can block in Themes, so it has an owned worker separate from color/refresh work. */
internal class IconPackImportMonitor(
  private val preferences: () -> SharedPreferences?,
  private val onFailure: (Throwable) -> Unit,
  private val onConvertedImport: (NativeIconImportReport, ConvertedIconArchive.Proof) -> Unit,
) {
  private val lifecycle = RestartableMonitor<Context>(::Watch, onFailure)
  fun start(context: Context) = lifecycle.start(context)
  fun stop() = lifecycle.stop()
  fun awaitStopped(deadline: Long) = lifecycle.awaitStopped(deadline)

  private inner class Watch(private val base: Context) : MonitorWorker {
    override val thread = HandlerThread("KeiHomeIconImport", android.os.Process.THREAD_PRIORITY_DEFAULT)
    private val handler by lazy { Handler(thread.looper) }
    @Volatile override var closed = false
      private set
    private var prefs: SharedPreferences? = null
    private var handled: IconPackImportRequest? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> request() }

    private fun request() {
      if (closed) return
      handler.removeCallbacks(import)
      handler.postDelayed(import, 100)
    }

    private val import = Runnable {
      if (closed) return@Runnable
      val request = IconPackImportProtocol.read(prefs) ?: return@Runnable
      if (request == handled) return@Runnable
      if (!request.token.matches(Regex("[0-9a-f-]{36}"))) return@Runnable
      var input: File? = null
      var journal: AtomicFile? = null
      var submitted = false
      var retainedStage: String? = null
      var outcome: NativeIconImportReport? = null
      var convertedProof: ConvertedIconArchive.Proof? = null
      val receipt = IconImportReceipt()
      fun persist(report: NativeIconImportReport, mayHaveImported: Boolean, stage: String?) {
        receipt.persist(report, stage, convertedProof) {
          save(checkNotNull(journal), report, mayHaveImported, stage, convertedProof)
        }
      }
      fun forDelivery(report: NativeIconImportReport) =
        receipt.forDelivery(report, onConvertedImport) { failure -> if (!closed) onFailure(failure) }
      try {
        val user = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        if (user != request.userId) return@Runnable
        val context = Context::class.java.getMethod("createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType)
          .invoke(base, UserHandle.getUserHandleForUid(user * 100_000), 0) as Context
        fun deliver(report: NativeIconImportReport) {
          if (closed) return
          val notification = forDelivery(report)
          if (closed) return
          context.contentResolver.call(RuntimeStatusInbox.AUTHORITY, IconPackImportProtocol.REPORT_METHOD,
            null, notification.bundle())
        }
        val directory = File("/data/system/keihome-icon-imports")
        check(directory.isDirectory || directory.mkdir())
        val existingJournal = AtomicFile(File(directory, "${request.token}.json"))
        val recorded = existingJournal.baseFile.exists() || File(existingJournal.baseFile.path + ".bak").exists()
        val previous = if (recorded) runCatching {
          JSONObject(existingJournal.readFully().toString(Charsets.UTF_8))
        }.getOrNull() else null
        val stored = NativeIconImportReport.decode(previous?.optString("report")) ?: if (recorded) {
          NativeIconImportReport(request.token, user, request.sourceId, "UNCERTAIN",
            message = "Import receipt needs reconciliation")
        } else null
        // Do not acquire write/delete ownership until the persisted request identity matches.
        if (stored != null) {
          retainedStage = previous?.optString("stage")?.takeIf { it.isNotBlank() && it != "null" }
          convertedProof = ConvertedImportProofReceipt.decode(previous?.optString("convertedProof"))
          receipt.restore(request, stored, retainedStage, convertedProof)
        }
        input = File(directory, "${request.token}.mtz")
        journal = existingJournal
        if (stored != null) {
          outcome = stored
          if (stored.pending) {
            val result = NativeIconPackManager.reconcile(context, input.path, retainedStage, request.convertedOrigin)
            retainedStage = result.retainedStagePath
            convertedProof = result.convertedProof
            outcome = stored.copy(status = result.status.name, localId = result.localId,
              title = result.title, message = result.message, convertedReady = result.convertedReady)
            persist(outcome, result.mayHaveImported, retainedStage)
          } else if (stored.imported && retainedStage != null) {
            NativeIconPackManager.cleanupConfirmedImport(input.path, retainedStage)
            retainedStage = null
            persist(stored, previous?.optBoolean("mayHaveImported") ?: true, null)
          }
          deliver(outcome)
          handled = request
          return@Runnable
        }
        if (!request.pending(SystemClock.elapsedRealtime())) {
          outcome = NativeIconImportReport(request.token, user, request.sourceId, "FAILED",
            message = "Request expired before native submission")
          persist(outcome, false, null)
          deliver(outcome)
          handled = request
          return@Runnable
        }
        deliver(NativeIconImportReport(request.token, user, request.sourceId, "IMPORTING"))
        val uri = Uri.Builder().scheme("content").authority(RuntimeStatusInbox.AUTHORITY)
          .appendPath("icon-import").appendPath(request.token).build()
        context.contentResolver.openInputStream(uri)!!.use { source ->
          input.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
              if (closed || Thread.currentThread().isInterrupted) throw InterruptedException()
              val count = source.read(buffer)
              if (count < 0) break
              bytes += count
              check(bytes <= 256L * 1024 * 1024) { "Icon handoff exceeds size limit" }
              output.write(buffer, 0, count)
            }
          }
        }
        if (closed || IconPackImportProtocol.read(prefs) != request ||
          !request.pending(SystemClock.elapsedRealtime())) return@Runnable
        // Journal before the native transaction: a replacement queries its outcome and never
        // blindly resubmits a call that Themes may still be processing.
        persist(NativeIconImportReport(request.token, user, request.sourceId, "IMPORTING"), true, null)
        submitted = true
        val result = try {
          NativeIconPackManager.importIcons(context, input.absolutePath, request.convertedOrigin)
        } catch (cancelled: InterruptedException) {
          // The manager throws cancellation only before native submission. Its stage is gone,
          // so this pre-submit marker can be removed and the replacement can copy the handoff.
          receipt.abandonUnsubmitted {
            journal.delete()
            check(!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) {
              "Could not discard the unsubmitted import receipt"
            }
          }
          submitted = false
          throw cancelled
        }
        retainedStage = result.retainedStagePath
        convertedProof = result.convertedProof
        outcome = NativeIconImportReport(request.token, user, request.sourceId, result.status.name,
          result.localId, result.title, result.message, result.convertedReady)
        persist(outcome, result.mayHaveImported, retainedStage)
        deliver(outcome)
        handled = request
      } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (!submitted && outcome == null && (closed || failure is InterruptedException)) return@Runnable
        if (!closed) onFailure(failure)
        // Delivery failure cannot turn a successful native import into FAILED.
        if (outcome == null) {
          outcome = NativeIconImportReport(request.token, request.userId, request.sourceId,
            if (submitted) "UNCERTAIN" else "FAILED", message = failure.message?.take(400))
          journal?.let { runCatching { persist(outcome, submitted, retainedStage) } }
        }
        if (!closed) {
          runCatching {
            val notification = forDelivery(outcome)
            if (!closed) base.contentResolver.call(RuntimeStatusInbox.AUTHORITY,
              IconPackImportProtocol.REPORT_METHOD, null, notification.bundle())
          }
          handled = request
        }
      } finally {
        receipt.releaseInput(input)
      }
    }

    override fun start() {
      thread.start()
      handler.post {
        if (closed) return@post
        try {
          prefs = preferences()
          prefs?.registerOnSharedPreferenceChangeListener(listener)
          request()
        } catch (failure: Exception) { if (!closed) onFailure(failure) }
      }
    }

    override fun stop() {
      if (closed) return
      closed = true
      handler.removeCallbacksAndMessages(null)
      handler.post { prefs?.unregisterOnSharedPreferenceChangeListener(listener); prefs = null }
      thread.interrupt()
      thread.quitSafely()
    }
  }

  private fun save(file: AtomicFile, report: NativeIconImportReport, submitted: Boolean, stage: String?,
    convertedProof: ConvertedIconArchive.Proof?) {
    val bytes = JSONObject().put("report", report.encode()).put("mayHaveImported", submitted)
      .put("stage", stage)
      .put("convertedProof", ConvertedImportProofReceipt.forReport(report, convertedProof)
        ?.let(ConvertedImportProofReceipt::encode))
      .toString().toByteArray(Charsets.UTF_8)
    val stream = file.startWrite()
    try {
      stream.write(bytes)
      stream.fd.sync()
      file.finishWrite(stream)
    } catch (failure: Throwable) { file.failWrite(stream); throw failure }
    // AtomicFile logs sync/rename failures instead of throwing. Verify the committed receipt
    // before permitting native submission or releasing its only reconciliation source.
    if (!file.readFully().contentEquals(bytes)) throw IOException("Import receipt was not committed")
  }
}

/** Keep the handoff until its terminal receipt and staging cleanup have both been committed. */
internal class IconImportReceipt {
  private var owned = false
  private var persisted: NativeIconImportReport? = null
  private var retainedStage: String? = null
  private var convertedProof: ConvertedIconArchive.Proof? = null
  private var notification: NativeIconImportReport? = null

  fun restore(request: IconPackImportRequest, report: NativeIconImportReport, stage: String?,
    proof: ConvertedIconArchive.Proof? = null) {
    check(report.token == request.token && report.sourceId == request.sourceId && report.userId == request.userId)
    owned = true
    persisted = report
    retainedStage = stage
    convertedProof = ConvertedImportProofReceipt.forReport(report, proof)
    notification = null
  }

  fun persist(report: NativeIconImportReport, stage: String?, proof: ConvertedIconArchive.Proof? = null,
    write: () -> Unit) {
    owned = true
    persisted = null
    convertedProof = null
    notification = null
    write()
    persisted = report
    retainedStage = stage
    convertedProof = ConvertedImportProofReceipt.forReport(report, proof)
  }

  fun forDelivery(report: NativeIconImportReport,
    register: (NativeIconImportReport, ConvertedIconArchive.Proof) -> Unit,
    onFailure: (Throwable) -> Unit): NativeIconImportReport {
    if (!report.imported) return report
    if (persisted != report) {
      return report.copy(status = "UNCERTAIN", convertedReady = report.convertedReady?.let { false },
        message = "Import completed; durable receipt needs reconciliation")
    }
    if (report.convertedReady != true) return report
    notification?.let { return it }
    // Keep the native approval in the private receipt for replay; public readiness also needs
    // the system runtime grant. Never register proof that has not survived a receipt commit.
    val delivered = try {
      register(report, checkNotNull(convertedProof) { "Converted import receipt has no validated proof" })
      report
    } catch (failure: Throwable) {
      if (failure is InterruptedException) Thread.currentThread().interrupt()
      onFailure(failure)
      report.copy(convertedReady = false,
        message = "Imported; converted system validation unavailable: ${failure.message}".take(512))
    }
    notification = delivered
    return delivered
  }

  fun abandonUnsubmitted(removeReceipt: () -> Unit) {
    removeReceipt()
    owned = false
    persisted = null
    retainedStage = null
    convertedProof = null
    notification = null
  }

  fun releaseInput(input: File?) {
    if (!owned || (persisted?.pending == false && retainedStage == null)) input?.delete()
  }
}

/** Private receipt evidence from successful PNG conversion approval, replayable after MTZ cleanup. */
internal object ConvertedImportProofReceipt {
  fun forReport(report: NativeIconImportReport, proof: ConvertedIconArchive.Proof?): ConvertedIconArchive.Proof? =
    proof?.takeIf { report.imported && report.convertedReady == true && valid(it) }

  fun encode(proof: ConvertedIconArchive.Proof): String {
    require(valid(proof))
    return listOf("1", proof.origin, proof.archive.sha1, proof.archive.sha256,
      proof.icons.sha1, proof.icons.sha256).joinToString("|")
  }

  fun decode(raw: String?): ConvertedIconArchive.Proof? {
    val parts = raw?.split('|') ?: return null
    if (parts.size != 6 || parts[0] != "1") return null
    return ConvertedIconArchive.Proof(parts[1], ConvertedIconArchive.Digests(parts[2], parts[3]),
      ConvertedIconArchive.Digests(parts[4], parts[5])).takeIf(::valid)
  }

  private fun valid(proof: ConvertedIconArchive.Proof): Boolean =
    proof.origin in setOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP") &&
      listOf(proof.archive, proof.icons).all {
        it.sha1.matches(Regex("[0-9a-f]{40}")) && it.sha256.matches(Regex("[0-9a-f]{64}"))
      }
}
