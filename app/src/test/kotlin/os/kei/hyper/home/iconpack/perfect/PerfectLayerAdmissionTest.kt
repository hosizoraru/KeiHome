package os.kei.hyper.home.iconpack.perfect

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.xposed.LocalNativeIconValidator

class PerfectLayerAdmissionTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun cachedHyperOs4GenerationKeepsNativeAdmissionForClockAndCalendar() {
    val source = sequenceOf(
      File(".artifacts/research/perfect-icons-20261108/source/repository-complete.zip"),
      File("../.artifacts/research/perfect-icons-20261108/source/repository-complete.zip"),
    ).firstOrNull(File::isFile)
    assumeTrue("Optional cached pzcn snapshot unavailable", source != null)
    ZipFile(source!!).use { zip ->
      val root = "Perfect-Icons-Completion-Project-main/"
      val entries = zip.entries().asSequence().filter { !it.isDirectory &&
        (it.name.startsWith(root + "themes/common/layer_animating_icons/") ||
          it.name.startsWith(root + "themes/HyperOS4/layer_animating_icons/")) }
        .map { PerfectIconBlob(it.name.removePrefix(root), it.name, it.size) }.toList()
      val plan = PerfectIconFormat.plan(emptyList(), emptyList(),
        entries.filter { it.path.startsWith("themes/common/") }
          .map { it.copy(path = it.path.removePrefix("themes/common/layer_animating_icons/")) },
        entries.filter { it.path.startsWith("themes/HyperOS4/") }
          .map { it.copy(path = it.path.removePrefix("themes/HyperOS4/layer_animating_icons/")) },
        PerfectIconOptions("HyperOS4", installedOnly = false), emptySet())
      fun read(blob: PerfectIconBlob) = zip.getInputStream(zip.getEntry(blob.sha)).use { it.readBytes() }
      val validated = PerfectIconFormat.validate(plan, ::read)
      assertEquals(20, validated.dynamicIds.size)
      val admission = validated.dynamicIds.sorted().associateWith { id ->
        val module = temporary.newFile("$id.zip")
        ZipOutputStream(module.outputStream()).use { output ->
          output.putNextEntry(ZipEntry("transform_config.xml"))
          output.write(HyperOs4IconModule.transform(true, null))
          output.closeEntry()
          validated.files.filterKeys { it.startsWith("layer_animating_icons/$id/") }
            .forEach { (path, blob) ->
              output.putNextEntry(ZipEntry(path)); output.write(read(blob)); output.closeEntry()
            }
        }
        try {
          LocalNativeIconValidator.validate(module)
          "admitted"
        } catch (failure: Exception) {
          generateSequence<Throwable>(failure) { it.cause }
            .joinToString(" -> ") { it.message ?: it.javaClass.simpleName }
        }
      }
      admission.forEach { (id, result) -> println("Native admission $id: $result") }
      for (id in listOf("com.android.calendar", "com.android.deskclock")) {
        assertTrue("$id: ${admission[id]}", admission[id] == "admitted")
      }
    }
  }
}
