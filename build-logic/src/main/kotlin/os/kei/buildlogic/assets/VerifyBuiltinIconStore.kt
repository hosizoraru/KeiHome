package os.kei.buildlogic.assets

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Checks APK metadata without producing output")
abstract class VerifyBuiltinIconStore : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val apkDirectory: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val generatedAssets: DirectoryProperty

  @TaskAction
  fun verify() {
    val expected = generatedAssets.file("$STORE/objects.kicons").get().asFile
    val apks = apkDirectory.get().asFile.listFiles { file -> file.extension == "apk" }.orEmpty()
    check(apks.isNotEmpty()) { "No APK available to verify at ${apkDirectory.get()}" }
    for (apk in apks) {
      ZipFile(apk).use { zip ->
        val catalog = zip.getEntry("assets/$STORE/catalog.json")
        val objects = zip.getEntry("assets/$STORE/objects.kicons")
        if (!expected.isFile && catalog == null && objects == null) return@use
        check(catalog != null && objects != null) { "${apk.name} is missing built-in icon store assets" }
        check(expected.isFile && objects.method == ZipEntry.STORED && objects.size == expected.length()) {
          "${apk.name}: assets/$STORE/objects.kicons must be stored uncompressed at its generated size"
        }
      }
    }
  }

  private companion object {
    const val STORE = "builtin-icon-store"
  }
}
