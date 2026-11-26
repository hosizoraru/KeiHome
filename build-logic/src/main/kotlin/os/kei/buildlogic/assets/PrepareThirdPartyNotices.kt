package os.kei.buildlogic.assets

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class PrepareThirdPartyNotices : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val notice: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val apacheLicense: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun prepare() {
    val output = outputDirectory.file("THIRD_PARTY_NOTICES.md").get().asFile
    output.parentFile.mkdirs()
    notice.get().asFile.copyTo(output, overwrite = true)
    outputDirectory.file("licenses/Apache-2.0.txt").get().asFile.apply {
      parentFile.mkdirs()
      apacheLicense.get().asFile.copyTo(this, overwrite = true)
    }
  }
}
