package os.kei.hyper.home.iconpack

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal enum class GeneratedPackProducer(val archiveName: Regex) {
  PERFECT(Regex("perfect-[0-9a-f]{12}-[0-9a-f]{24}\\.zip")),
  APPLE(Regex("[0-9a-f]{64}\\.zip")),
}

/** Called only after the new archive and its publication pointer have been committed. */
internal fun pruneGeneratedArchives(directory: File, latest: File, producer: GeneratedPackProducer): List<File> {
  require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
  require(latest.canonicalFile.parentFile == directory.canonicalFile &&
    Files.isRegularFile(latest.toPath(), LinkOption.NOFOLLOW_LINKS) && latest.length() > 0 &&
    producer.archiveName.matches(latest.name))
  val failures = mutableListOf<File>()
  for (file in directory.listFiles().orEmpty()) {
    if (file.name == latest.name || !producer.archiveName.matches(file.name) ||
      !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
    try { Files.delete(file.toPath())
    } catch (_: IOException) { failures += file }
  }
  return failures
}
