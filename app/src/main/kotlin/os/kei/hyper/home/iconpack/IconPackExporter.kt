package os.kei.hyper.home.iconpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
  val monochrome: Map<String, ByteArray> = emptyMap(),
  val paletteBackground: ByteArray? = null,
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
    // Forks can retain an identical activity name under different application IDs.
    // Such names cannot be global filenames; keep their package-specific fallback.
    val sharedActivities = normalized.filter { it.className != null }.groupBy { it.className }
      .filterValues { group -> group.map { it.packageName }.distinct().size > 1 }.keys
    if (pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS) {
      return exportNativeModule(pack, normalized, darkMode, check)
    }
    val componentEntries = pack.entries.mapNotNull { it.component?.let { component -> component to it } }.toMap()
    val nativeEntries = pack.entries.mapNotNull { it.nativeName?.let { name -> name to it } }.toMap()
    val liveDirectives = pack.directives.filter { it.tag == "calendar" || it.tag == "dynamic-clock" }
    val liveComponents = liveDirectives.mapNotNull { it.attributes["component"]?.let(IconPackComponent::parse) }.toSet()
    val liveDrawables = liveDirectives.mapNotNull { it.attributes["drawable"]?.let(IconDrawableName::parse) }.toSet()
    val liveNames = liveDrawables.mapTo(HashSet()) { it.name }
    fun requiresLive(target: IconPackComponent, entry: IconPackEntry?): Boolean =
      target in liveComponents || IconPackComponent(target.packageName) in liveComponents ||
        listOfNotNull(entry?.light, entry?.dark).any { artwork ->
          when (artwork) {
            is IconPackArtwork.Resource -> artwork.drawable in liveDrawables
            is IconPackArtwork.Archive -> artwork.variants.any {
              it.path.substringAfterLast('/').substringBeforeLast('.') in liveNames
            }
          }
        }
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
      val monochrome = linkedMapOf<String, ByteArray>()
      val colorOs = pack.colorOsProfile
      val fallback = colorOs?.let { ColorOsFallbackRenderer(context, style = it.style) }
      val mask = colorOs?.read(colorOs.maskPath, check)?.let { bytes ->
        GeneratedIconPng.squareSize(bytes)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IconPackException("Invalid ColorOS shape mask")
      }
      fun encodePng(bitmap: Bitmap): ByteArray = try {
        ByteArrayOutputStream().also {
          if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IconPackException("PNG encoding failed")
        }.toByteArray()
      } finally { bitmap.recycle() }
      fun scaledPng(bitmap: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, true)
        return encodePng(if (scaled === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else scaled)
      }
      var paletteBackground = mask?.let(::scaledPng)
      IconPackRenderer(context).open(pack, darkMode).use { renderer ->
        try {
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
              ?: if (fallback != null && group.size == 1) group.single().flattened else null
            var packageWritten = false
            for ((target, entry) in matches) {
              check()
              if (colorOs?.source(target)?.requiresLiveIcon == true || requiresLive(target, entry)) {
                skipped += IconPackExportSkip(target, "Live source handled by native template or original launcher icon")
                continue
              }
              if (target.className in sharedActivities && common == null) {
                skipped += IconPackExportSkip(target, "Activity filename is shared by other packages")
                continue
              }
              if (entry == null && fallback == null) {
                skipped += IconPackExportSkip(target, "No component mapping")
                continue
              }
              val png = try {
                val bitmap = entry?.let { renderer.render(it, sizePx, check) }
                  ?: fallback?.render(target, darkMode, sizePx, mask, check)
                bitmap?.let(::encodePng)
              } catch (cancelled: CancellationException) { throw cancelled
              } catch (failure: Exception) {
                skipped += IconPackExportSkip(target, failure.message ?: "Artwork cannot be rendered")
                continue
              }
              if (png == null) {
                skipped += IconPackExportSkip(target, "No static artwork for this mode")
                continue
              }
              target.className?.takeUnless { it in sharedActivities }?.let { write(it, png) }
              if (common != null && !packageWritten) { write(pkg, png); packageWritten = true }
              if (target.className == null && !packageWritten) write(pkg, png)
              if (colorOs != null || entry != null) {
                val sourceMask = colorOs?.source(target)?.monochromePath?.let { colorOs.read(it, check) }
                sourceMask?.let(GeneratedIconPng::squareSize)
                val declaredLayers = if (colorOs == null && entry != null) try {
                  renderer.themedLayers(entry, sizePx, check)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { null } else null
                declaredLayers?.second?.let { background ->
                  try { if (paletteBackground == null) paletteBackground = scaledPng(background) }
                  finally { background.recycle() }
                }
                // Source artwork is a separate mask, never a luminance conversion of its PNG.
                if (sourceMask != null || entry == null || declaredLayers != null) {
                  val bitmap = if (sourceMask != null) BitmapFactory.decodeByteArray(sourceMask, 0, sourceMask.size)
                    else declaredLayers?.first ?: fallback?.monochrome(target, false, sizePx, mask, check)
                  if (bitmap != null) {
                    try {
                      require(bitmap.width in 1..512 && bitmap.height == bitmap.width)
                      val maskPng = scaledPng(bitmap)
                      val usable = try { GeneratedIconPng.requireAlphaMask(maskPng, sizePx); true }
                        catch (_: IllegalArgumentException) { false }
                      if (usable) {
                        target.className?.takeUnless { it in sharedActivities }?.let { monochrome[it] = maskPng }
                        if (common != null || target.className == null) monochrome[pkg] = maskPng
                      }
                    } finally { bitmap.recycle() }
                  }
                }
              }
              exported += target
            }
          }
        }
        } finally { mask?.recycle() }
      }
      if (exported.isEmpty()) throw IconPackException("No selected launcher icons could be exported")
      val verified = IconPackArchive(limits).inspect(archive, nested, checkCancelled = check)
      if (verified.format != IconPackFormat.MIUI_ICONS || verified.entries.size != names.size) {
        throw IconPackException("Native icon archive validation failed")
      }
      val hash = verified.nativeIconsSha256 ?: throw IconPackException("Missing exported archive checksum")
      check()
      success = true
      return IconPackExportResult(archive, hash, exported, skipped, names.size, darkMode,
        monochrome = monochrome, paletteBackground = paletteBackground)
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
