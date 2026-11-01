package os.kei.hyper.home.status

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object RuntimeStatusInbox {
  const val AUTHORITY = "os.kei.hyper.home.runtime"
  const val REPORT_METHOD = "report"
  private val latest = MutableStateFlow<RuntimeStatusReport?>(null)
  val reports = latest.asStateFlow()

  fun accept(raw: String?): Boolean {
    val report = RuntimeStatusReport.decode(raw) ?: return false
    latest.value = report
    return true
  }
}

/** System UID may reach an unexported provider; other apps cannot publish a health receipt. */
class RuntimeStatusProvider : ContentProvider() {
  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    check(Binder.getCallingUid() == Process.SYSTEM_UID) { "Runtime status requires the system owner" }
    return Bundle().apply {
      putBoolean("accepted", when (method) {
        "preview_exists" -> CurrentIconSnapshot.file(requireNotNull(context), requireNotNull(arg)).isFile
          && java.io.File(CurrentIconSnapshot.file(requireNotNull(context), arg).parentFile, "$arg.ready").exists()
        "preview_ready" -> {
          val file = CurrentIconSnapshot.file(requireNotNull(context), requireNotNull(arg))
          java.io.File(file.parentFile, "$arg.ready").writeText("ready")
          file.parentFile?.listFiles()?.filter { it.name != file.name && it.name != "$arg.ready" }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
          true
        }
        RuntimeStatusInbox.REPORT_METHOD -> RuntimeStatusInbox.accept(arg)
        IconPackImportProtocol.REPORT_METHOD -> IconPackImportProtocol.accept(requireNotNull(context), extras)
        else -> false
      })
    }
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    check(Binder.getCallingUid() == Process.SYSTEM_UID) { "Icon handoff requires the system owner" }
    val path = uri.pathSegments
    if (mode == "w" && path.size == 2 && path[0] == "current-icons") {
      val file = CurrentIconSnapshot.file(requireNotNull(context), path[1])
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or
        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    require(mode == "r" && path.size == 2 && path[0] == "icon-import")
    return ParcelFileDescriptor.open(IconPackHandoffs.file(requireNotNull(context), path[1]),
      ParcelFileDescriptor.MODE_READ_ONLY)
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
    selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
