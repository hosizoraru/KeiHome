package os.kei.hyper.home.iconpack

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Immutable factory APK metadata only; installed package identities are checked per renderer. */
internal class PreinstalledIconOrigins(roots: List<File>) {
  private val roots = roots.mapNotNull { canonical(it) }.distinct()
  private var catalog: Map<String, List<File>>? = null
  private val certificates = mutableMapOf<File, Set<String>>()

  fun matches(
    packageName: String,
    source: String?,
    readPackage: (File) -> String?,
    readCertificates: (File) -> Set<String>,
    hasCertificate: (String, String) -> Boolean,
  ): Boolean {
    val file = source?.let { canonical(File(it)) }
    if (file != null && roots.any { inside(file, it) } && file.isFile) return true
    return snapshot(readPackage)[packageName].orEmpty().any { archive ->
      val signers = signers(archive, readCertificates)
      signers.isNotEmpty() && signers.all { hasCertificate(packageName, it) }
    }
  }

  @Synchronized private fun snapshot(
    readPackage: (File) -> String?,
  ): Map<String, List<File>> {
    catalog?.let { return it }
    val result = mutableMapOf<String, MutableList<File>>()
    val visited = mutableSetOf<File>()
    var directories = 0
    var archives = 0
    for (root in roots) {
      for (file in root.walkTopDown().maxDepth(4).onEnter { directory ->
        val real = canonical(directory)
        real != null && (real == root || inside(real, root)) &&
          visited.add(real) && ++directories <= 2048
      }) {
        if (archives >= 512) break
        if (!file.isFile || !file.extension.equals("apk", true)) continue
        val real = canonical(file) ?: continue
        if (!inside(real, root) || real.length() !in 1..(2L * 1024 * 1024 * 1024)) continue
        archives++
        val name = safely { readPackage(real) } ?: continue
        if (!validDottedName(name)) continue
        result.getOrPut(name) { mutableListOf() }.add(real)
      }
    }
    return result.mapValues { it.value.toList() }.also { catalog = it }
  }

  @Synchronized private fun signers(file: File, read: (File) -> Set<String>): Set<String> =
    certificates.getOrPut(file) {
      safely { read(file) }?.takeIf { set ->
        set.size in 1..8 && set.all { it.matches(Regex("[0-9a-f]{64}")) }
      }?.toSet().orEmpty()
    }

  private fun <T> safely(read: () -> T): T? = try { read() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (interrupted: InterruptedException) { throw interrupted }
    catch (_: Exception) { null }

  private fun inside(file: File, root: File) = file.path.startsWith(root.path + File.separator)
  private fun canonical(file: File): File? = try { file.canonicalFile } catch (_: Exception) { null }
}

internal object NativePreinstalledIcons {
  private val origins = PreinstalledIconOrigins(
    listOf("/product", "/system", "/system_ext", "/vendor", "/odm").map { File(it, "data-app") }
      .filter { root ->
        // /product commonly resolves to /system/product; a mutable /data symlink is not a factory source.
        val path = runCatching { root.canonicalPath }.getOrNull()
        path != null && listOf("/product/", "/system/", "/system_ext/", "/vendor/", "/odm/")
          .any(path::startsWith)
      },
  )

  fun matches(pm: PackageManager, app: ApplicationInfo): Boolean = origins.matches(
    app.packageName, app.sourceDir,
    readPackage = { file ->
      pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))?.packageName
    },
    readCertificates = { file ->
      val info = pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(
        PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
      ))
      if (info == null || info.packageName != app.packageName) emptySet() else {
        info.signingInfo?.apkContentsSigners.orEmpty().map { certificate ->
          MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
            .joinToString("") { "%02x".format(it) }
        }.toSet()
      }
    },
    hasCertificate = { name, digest ->
      try {
        pm.hasSigningCertificate(name, digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
          PackageManager.CERT_INPUT_SHA256)
      } catch (_: SecurityException) { false }
    },
  )
}
