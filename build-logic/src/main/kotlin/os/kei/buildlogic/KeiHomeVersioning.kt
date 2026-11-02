package os.kei.buildlogic

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.gradle.api.GradleException
import org.gradle.api.Project

internal data class KeiHomeVersion(val code: Int, val name: String)

internal data class VersionSeries(val major: Int, val minor: Int, val patch: Int) {
  val name = "$major.$minor.$patch"
  val base = major * 100_000_000L + minor * 1_000_000L + patch * 10_000L

  companion object {
    fun parse(raw: String): VersionSeries {
      val match = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(raw)
        ?: throw GradleException("keihome.versionName must have the form major.minor.patch")
      val parts = match.groupValues.drop(1).map { it.toIntOrNull() }
      if (parts.any { it == null } || parts[0]!! !in 0..20 || parts[1]!! !in 0..99 || parts[2]!! !in 0..99) {
        throw GradleException("KeiHome supports major 0..20, minor 0..99 and patch 0..99")
      }
      return VersionSeries(parts[0]!!, parts[1]!!, parts[2]!!)
    }
  }
}

internal object KeiHomeVersioning {
  private const val MAX_CODE = 2_100_000_000L
  private const val MAX_ITERATION = 9_999

  fun resolve(project: Project): KeiHomeVersion {
    val root = project.rootProject.projectDir.toPath()
    val series = VersionSeries.parse(project.providers.gradleProperty("keihome.versionName").orElse("1.0.0").get())
    val revision = git(project, "rev-parse", "--short=12", "HEAD") ?: "unknown"
    val commitCount = git(project, "rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
    val status = git(project, "status", "--porcelain", "--untracked-files=normal")
    val dirty = status != null && status.isNotEmpty()
    val override = project.providers.gradleProperty("keihome.versionCode").orNull
    val code = if (override != null) {
      override.toLongOrNull()?.takeIf { it in 1..MAX_CODE }?.toInt()
        ?: throw GradleException("keihome.versionCode must be between 1 and $MAX_CODE")
    } else {
      allocate(root.resolve(".gradle/keihome-version"), series, commitCount)
    }
    val iteration = (code % 10_000).toString().padStart(4, '0')
    val suffix = if (override == null) "" else ".override"
    return KeiHomeVersion(code, "${series.name}+g$revision.b$iteration$suffix${if (dirty) ".dirty" else ""}")
  }

  private fun git(project: Project, vararg args: String): String? = runCatching {
    val process = ProcessBuilder(listOf("git") + args).directory(project.rootDir)
      .redirectErrorStream(true).apply { environment()["GIT_OPTIONAL_LOCKS"] = "0" }.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    output.takeIf { process.waitFor() == 0 }
  }.getOrNull()

  internal fun nextCode(series: VersionSeries, previous: Int, commitCount: Int): Int {
    val iteration = maxOf(1, previous + 1, commitCount)
    if (iteration > MAX_ITERATION || series.base + iteration > MAX_CODE) {
      throw GradleException("KeiHome ${series.name} iteration slot exhausted; advance the patch version")
    }
    return (series.base + iteration).toInt()
  }

  internal fun allocate(directory: Path, series: VersionSeries, commitCount: Int): Int {
    Files.createDirectories(directory)
    FileChannel.open(directory.resolve("allocation.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
      val deadline = System.nanoTime() + 15_000_000_000L
      var acquired: java.nio.channels.FileLock? = null
      while (acquired == null) {
        acquired = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (acquired != null) break
        if (System.nanoTime() >= deadline) throw GradleException("Timed out allocating KeiHome versionCode")
        Thread.sleep(20)
      }
      requireNotNull(acquired).use {
        val path = directory.resolve("series-${series.name}")
        val previous = if (Files.exists(path)) Files.readString(path).trim().toIntOrNull()
          ?: throw GradleException("Invalid KeiHome version counter: $path") else 0
        val next = nextCode(series, previous, commitCount)
        val tmp = Files.createTempFile(directory, "code-", ".tmp")
        try {
          Files.writeString(tmp, "${next % 10_000}\n", StandardCharsets.UTF_8)
          Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
        return next
      }
    }
  }
}
