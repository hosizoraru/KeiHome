package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The current tint policy is based on one inspected launcher snapshot.
 * Never apply it to a different launcher just because the package name matches.
 */
internal object SupportedMonetLauncher {
  private const val PACKAGE_NAME = "com.miui.home"
  private const val DART_LIBRARY = "lib/arm64-v8a/libapp.so"
  private const val SNAPSHOT_SHA256 =
    "fb8b1f91eba2d9c43875f49bbded70cca462be341fc379b9864e7c6136d6e463"
  private const val MAX_LIBRARY_BYTES = 64L * 1024 * 1024

  private data class Identity(
    val path: String,
    val version: Long,
    val length: Long,
    val modified: Long,
  )

  private var cachedIdentity: Identity? = null
  private var cachedResult = false

  @Synchronized
  fun matches(context: Context): Boolean = try {
    val packageInfo = context.packageManager.getPackageInfo(
      PACKAGE_NAME,
      PackageManager.PackageInfoFlags.of(0),
    )
    val path = packageInfo.applicationInfo?.sourceDir ?: return false
    val file = File(path)
    val identity = Identity(path, packageInfo.longVersionCode, file.length(), file.lastModified())
    if (identity == cachedIdentity) return cachedResult
    val digest = ZipFile(file).use { zip ->
      val entry = zip.getEntry(DART_LIBRARY) ?: return false
      if (entry.size <= 0 || entry.size > MAX_LIBRARY_BYTES) return false
      val sha = MessageDigest.getInstance("SHA-256")
      zip.getInputStream(entry).use { input ->
        val buffer = ByteArray(32 * 1024)
        var count = 0L
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          count += read
          if (count > MAX_LIBRARY_BYTES) return false
          sha.update(buffer, 0, read)
        }
        if (count != entry.size) return false
      }
      sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    (digest == SNAPSHOT_SHA256).also {
      cachedIdentity = identity
      cachedResult = it
    }
  } catch (_: Exception) {
    false
  }
}
