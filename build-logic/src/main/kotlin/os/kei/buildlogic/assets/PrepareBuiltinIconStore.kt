package os.kei.buildlogic.assets

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class PrepareBuiltinIconStore @Inject constructor(
  private val execution: ExecOperations,
  private val fileSystem: FileSystemOperations,
) : DefaultTask() {
  @get:Input
  abstract val requirePacks: Property<Boolean>
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packs: ConfigurableFileCollection
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val otherAssets: ConfigurableFileCollection
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val generator: RegularFileProperty
  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun prepare() {
    check(!requirePacks.get() || !packs.isEmpty) {
      "keihome.generatedAssetsDir must contain builtin-icon-packs/*.zip"
    }
    fileSystem.sync { spec ->
      spec.from(otherAssets.asFileTree)
      spec.into(outputDirectory)
    }
    if (packs.isEmpty) return // Public builds without optional OEM inputs do not need Python.
    execution.exec { spec ->
      spec.commandLine(listOf("python3", generator.get().asFile.absolutePath, "--output",
        outputDirectory.dir("builtin-icon-store").get().asFile.absolutePath) +
        packs.files.sortedBy { it.name }.flatMap { listOf("--pack", it.absolutePath) })
    }
  }
}
