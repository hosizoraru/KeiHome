package os.kei.buildlogic.assets

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class PrepareDependencyVersions : DefaultTask() {
  @get:Input
  abstract val modules: ListProperty<String>

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun prepare() {
    val output = outputDirectory.file("third-party-dependencies.tsv").get().asFile
    output.parentFile.mkdirs()
    output.writeText(modules.get().distinct().sorted().joinToString("\n", postfix = "\n"))
  }
}
