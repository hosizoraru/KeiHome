package os.kei.hyper.home.iconpack

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

internal data class IconPackExportSkip(val component: IconPackComponent, val reason: String)

/** The caller owns [archive] and should delete it after the bridge has consumed it. */
internal data class IconPackExportResult(
  val archive: File,
  val sha256: String,
  val exportedTargets: List<IconPackComponent>,
  val skippedTargets: List<IconPackExportSkip>,
  val aliasCount: Int,
  val darkMode: Boolean,
  val preservesNativeResources: Boolean = false,
)

internal class IconPackExporter(private val context: Context, private val limits: IconPackLimits) {
  fun export(
    pack: IconPackDescriptor, targets: List<IconPackComponent>, darkMode: Boolean,
    sizePx: Int, check: () -> Unit,
  ): IconPackExportResult {
    require(sizePx in 1..512)
    if (targets.size > minOf(5000, limits.maxEntries / 2)) throw IconPackException("Export target limit exceeded")
    val normalized = targets.map { target ->
      IconPackComponent.parse(target.flattened) ?: throw IconPackException("Invalid launcher component")
    }.distinct()
    if (pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS) {
      return exportNativeModule(pack, normalized, darkMode, check)
    }
    val componentEntries = pack.entries.mapNotNull { it.component?.let { component -> component to it } }.toMap()
    val nativeEntries = pack.entries.mapNotNull { it.nativeName?.let { name -> name to it } }.toMap()
    fun match(target: IconPackComponent) = componentEntries[target]
      ?: componentEntries[IconPackComponent(target.packageName)]
      ?: target.className?.let(nativeEntries::get) ?: nativeEntries[target.packageName]
    val directory = File(context.cacheDir, "icon-pack-exports")
    if (!directory.isDirectory && !directory.mkdirs()) throw IconPackException("Cannot create export staging directory")
    val archive = File.createTempFile("icons-", ".zip", directory)
    val nested = File.createTempFile("verify-", ".zip", directory)
    var success = false
    try {
      val exported = mutableListOf<IconPackComponent>()
      val skipped = mutableListOf<IconPackExportSkip>()
      val names = linkedMapOf<String, String>()
      IconPackRenderer(context).open(pack, darkMode).use { renderer ->
        ZipOutputStream(LimitedOutput(archive.outputStream(), limits.maxInputBytes)).use { zip ->
          fun write(name: String, png: ByteArray) {
            if (!validDottedName(name) || name.toByteArray(Charsets.UTF_8).size > 240) {
              throw IconPackException("Component cannot be represented by a native icon filename")
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
            val previous = names[name]
            if (previous != null && previous != hash) throw IconPackException("Native icon filename collision: $name")
            if (previous != null) return
            names[name] = hash
            zip.putNextEntry(ZipEntry("res/drawable-xxhdpi/$name.png").apply { time = 0 })
            zip.write(png)
            zip.closeEntry()
          }
          for ((pkg, group) in normalized.groupBy { it.packageName }) {
            check()
            val matches = group.associateWith(::match)
            // A package fallback is only unambiguous when ALL supplied launcher activities
            // resolve to the same entry. Different activity artwork gets activity aliases only.
            val common = matches.values.takeIf { values -> values.all { it != null } }
              ?.map { it!!.key }?.distinct()?.singleOrNull()
            var packageWritten = false
            for ((target, entry) in matches) {
              check()
              if (entry == null) {
                skipped += IconPackExportSkip(target, "No component mapping")
                continue
              }
              val png = try {
                val bitmap = renderer.render(entry, sizePx, check)
                if (bitmap == null) null else try {
                  ByteArrayOutputStream().also {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IconPackException("PNG encoding failed")
                  }.toByteArray()
                } finally { bitmap.recycle() }
              } catch (cancelled: CancellationException) { throw cancelled
              } catch (failure: Exception) {
                skipped += IconPackExportSkip(target, failure.message ?: "Artwork cannot be rendered")
                continue
              }
              if (png == null) {
                skipped += IconPackExportSkip(target, "No static artwork for this mode")
                continue
              }
              target.className?.let { write(it, png) }
              if (common != null && !packageWritten) { write(pkg, png); packageWritten = true }
              if (target.className == null && !packageWritten) write(pkg, png)
              exported += target
            }
          }
        }
      }
      if (exported.isEmpty()) throw IconPackException("No selected launcher icons could be exported")
      val verified = IconPackArchive(limits).inspect(archive, nested, checkCancelled = check)
      if (verified.format != IconPackFormat.MIUI_ICONS || verified.entries.size != names.size) {
        throw IconPackException("Native icon archive validation failed")
      }
      val hash = verified.nativeIconsSha256 ?: throw IconPackException("Missing exported archive checksum")
      check()
      success = true
      return IconPackExportResult(archive, hash, exported, skipped, names.size, darkMode)
    } finally {
      nested.delete()
      if (!success) archive.delete()
    }
  }

  /** Native modules already carry their own rendering, masking, animation and mode contracts.
   * Keep every byte and resource qualifier; targets report known exact names, not an allowlist. */
  private fun exportNativeModule(
    pack: IconPackDescriptor, targets: List<IconPackComponent>, darkMode: Boolean, check: () -> Unit,
  ): IconPackExportResult {
    val record = (pack.source as? IconPackSource.Imported)?.record ?: throw IconPackException("Missing native icon import")
    val directory = File(context.cacheDir, "icon-pack-exports")
    if (!directory.isDirectory && !directory.mkdirs()) throw IconPackException("Cannot create export staging directory")
    val archive = File.createTempFile("icons-native-", ".zip", directory)
    val nested = File.createTempFile("verify-", ".zip", directory)
    var success = false
    try {
      val index = IconPackArchive(limits).copyNativeModule(record, archive, nested, check)
      val names = index.entries.mapNotNull { it.nativeName }.toSet()
      val covered = targets.filter { it.packageName in names || it.className in names }
      val skipped = (targets - covered.toSet()).map { IconPackExportSkip(it, "No exact native filename; native aliases are not inferred") }
      check()
      success = true
      return IconPackExportResult(archive, index.nativeIconsSha256!!, covered, skipped, names.size, darkMode,
        preservesNativeResources = true)
    } finally {
      nested.delete()
      if (!success) archive.delete()
    }
  }

  private class LimitedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
    private var count = 0L
    private fun reserve(bytes: Int) { count += bytes; if (count > limit) throw IconPackException("Export size limit exceeded") }
    override fun write(value: Int) { reserve(1); out.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); out.write(bytes, offset, length) }
  }
}
