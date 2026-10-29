package os.kei.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** AGP's generated instrumentation manifest does not inherit the app version. */
@CacheableTask
abstract class TestApkVersionTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val inputManifest: RegularFileProperty

  @get:OutputFile
  abstract val outputManifest: RegularFileProperty

  @get:Input
  abstract val versionCode: Property<Int>

  @TaskAction
  fun stampVersion() {
    val source = inputManifest.get().asFile.readText()
    val version = "android:versionCode=\"${versionCode.get()}\""
    val attribute = Regex("android:versionCode=\"[^\"]*\"")
    val stamped = if (attribute.containsMatchIn(source)) {
      attribute.replace(source, version)
    } else {
      check(source.contains("<manifest"))
      source.replaceFirst("<manifest", "<manifest $version")
    }
    outputManifest.get().asFile.apply {
      parentFile.mkdirs()
      writeText(stamped)
    }
  }
}
