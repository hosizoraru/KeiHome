package os.kei.hyper.home.status

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.BILIBILI_PACKAGE

internal data class BilibiliInstallation(val name: String?, val version: Long, val updatedAt: Long) {
  companion object {
    fun read(context: Context): BilibiliInstallation? = try {
      val info = context.packageManager.getPackageInfo(BILIBILI_PACKAGE, PackageManager.PackageInfoFlags.of(0))
      BilibiliInstallation(info.versionName, info.longVersionCode, info.lastUpdateTime)
    } catch (_: PackageManager.NameNotFoundException) { null }
  }
}

internal enum class BilibiliArtworkOutcome { WAITING, DRAWN, FALLBACK, UNSUPPORTED }

internal data class BilibiliArtworkReceipt(
  val version: Long,
  val updatedAt: Long,
  val outcome: BilibiliArtworkOutcome,
) {
  fun matches(installation: BilibiliInstallation) =
    version == installation.version && updatedAt == installation.updatedAt
}

internal object BilibiliArtworkInbox {
  val uri: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.bilibili-artwork")
  const val METHOD = "report"
  private val latest = MutableStateFlow<BilibiliArtworkReceipt?>(null)
  val status = latest.asStateFlow()
  private var owner: IBinder? = null
  private var death: IBinder.DeathRecipient? = null

  @Synchronized
  fun accept(token: IBinder, receipt: BilibiliArtworkReceipt) {
    if (!token.isBinderAlive) return
    if (owner != token) {
      val recipient = IBinder.DeathRecipient { onDeath(token) }
      try { token.linkToDeath(recipient, 0) } catch (_: RemoteException) { return }
      death?.let { owner?.unlinkToDeath(it, 0) }
      owner = token
      death = recipient
    }
    latest.value = receipt
  }

  @Synchronized
  private fun onDeath(token: IBinder) {
    if (owner != token) return
    owner = null
    death = null
    latest.value = null
  }
}

/** Accepts only an ephemeral artwork receipt from the installed host for this Android user. */
class BilibiliArtworkStatusProvider : ContentProvider() {
  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    val host = requireNotNull(context)
    if (method != BilibiliArtworkInbox.METHOD || callingPackage != BILIBILI_PACKAGE || extras == null ||
      extras.getInt("module") != BuildConfig.VERSION_CODE) return null
    val uid = try { host.packageManager.getPackageUid(BILIBILI_PACKAGE, 0) }
    catch (_: PackageManager.NameNotFoundException) { return null }
    if (Binder.getCallingUid() != uid) return null
    val installation = BilibiliInstallation.read(host) ?: return null
    val token = extras.getBinder("process") ?: return null
    val outcome = BilibiliArtworkOutcome.entries.firstOrNull { it.name == extras.getString("outcome") } ?: return null
    val receipt = BilibiliArtworkReceipt(extras.getLong("version"), extras.getLong("updated"), outcome)
    if (!receipt.matches(installation)) return null
    BilibiliArtworkInbox.accept(token, receipt)
    return null
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
    selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
