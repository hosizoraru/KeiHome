package os.kei.hyper.home.iconpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import os.kei.hyper.home.data.SystemIconFallback

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
  val monochromeBackgrounds: Map<String, ByteArray> = emptyMap(),
  val fallbackPaletteNames: Set<String> = emptySet(),
  val colorOsPackages: Set<String> = emptySet(),
  val nativeAdaptationTargets: Set<IconPackComponent> = emptySet(),
)

internal class IconPackExporter(private val context: Context, private val limits: IconPackLimits) {
  /** Probe only missing candidates; use the same guarded renderers and live-source policy. */
  fun canComplete(pack: IconPackDescriptor, target: IconPackComponent,
    systemFallback: SystemIconFallback, systemAppNameMatching: Boolean = false,
    nameMatcher: SystemAppNameMatcher? = null, colorOsFallback: IconPackDescriptor? = null, check: () -> Unit): Boolean {
    val entry = pack.entries.firstOrNull { it.component == target }
      ?: pack.entries.firstOrNull { it.component == IconPackComponent(target.packageName) }
      ?: (nameMatcher ?: SystemAppNameMatcher(context, pack.entries, systemAppNameMatching)).match(target)
    if (pack.colorOsProfile?.source(target)?.requiresLiveIcon == true || livePolicy(pack)(target, entry)) return false
    return try {
      val system = SystemIconFallbackRenderer(context, systemFallback)
      val colorOs = (pack.colorOsProfile ?: colorOsFallback?.colorOsProfile)?.let { ColorOsFallbackRenderer(context, style = it.style) }
      IconPackRenderer(context).open(pack, false, colorOsFallback).use { renderer ->
        val missingMode = entry == null || (colorOs != null && entry.artwork(false) == null)
        val bitmap = renderer.renderTarget(entry, target, 48, check)
          ?: if (missingMode && system.isSystem(target)) system.render(target, 48, check)
          else if (missingMode) colorOs?.render(target, false, 48, check = check) else null
        (bitmap != null).also { bitmap?.recycle() }
      }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { false }
  }

  private fun livePolicy(pack: IconPackDescriptor): (IconPackComponent, IconPackEntry?) -> Boolean {
    val directives = pack.directives.filter { it.tag == "calendar" || it.tag == "dynamic-clock" }
    val components = directives.mapNotNull { it.attributes["component"]?.let(IconPackComponent::parse) }.toSet()
    val drawables = directives.mapNotNull { it.attributes["drawable"]?.let(IconDrawableName::parse) }.toSet()
    val names = drawables.mapTo(HashSet()) { it.name }
    return { target, entry ->
      target in components || IconPackComponent(target.packageName) in components ||
        listOfNotNull(entry?.light, entry?.dark).any { artwork ->
          when (artwork) {
            is IconPackArtwork.Resource -> artwork.drawable in drawables
            is IconPackArtwork.Archive -> artwork.variants.any {
              it.path.substringAfterLast('/').substringBeforeLast('.') in names
            }
          }
        }
    }
  }

  fun export(
    pack: IconPackDescriptor, targets: List<IconPackComponent>, darkMode: Boolean,
    sizePx: Int, systemFallback: SystemIconFallback? = null,
    systemAppNameMatching: Boolean = false, colorOsFallback: IconPackDescriptor? = null,
    installedOnly: Boolean = false, check: () -> Unit,
  ): IconPackExportResult {
    require(sizePx in 1..512)
    // Offline ColorOS mappings also cover future installs unless generation is scoped to targets.
    val candidates = targets + if (installedOnly) emptyList() else if (pack.colorOsProfile != null)
      pack.entries.mapNotNull { it.component } else colorOsFallback?.entries.orEmpty().mapNotNull { it.component }
    if (candidates.distinct().size > minOf(5000, limits.maxEntries / 2))
      throw IconPackException("Export target limit exceeded")
    val normalized = candidates.map { target ->
      IconPackComponent.parse(target.flattened) ?: throw IconPackException("Invalid launcher component")
    }.distinct()
    // Forks can retain an identical activity name under different application IDs.
    // Such names cannot be global filenames; keep their package-specific fallback.
    val sharedActivities = normalized.filter { it.className != null }.groupBy { it.className }
      .filterValues { group -> group.map { it.packageName }.distinct().size > 1 }.keys
    if (pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS) {
      return exportNativeModule(pack, normalized, darkMode, sizePx, systemFallback, colorOsFallback, installedOnly, check)
    }
    val componentEntries = pack.entries.mapNotNull { it.component?.let { component -> component to it } }.toMap()
    val nativeEntries = pack.entries.mapNotNull { it.nativeName?.let { name -> name to it } }.toMap()
    val requiresLive = livePolicy(pack)
    val nameMatcher = SystemAppNameMatcher(context, pack.entries, systemAppNameMatching)
    fun match(target: IconPackComponent) = componentEntries[target]
      ?: componentEntries[IconPackComponent(target.packageName)]
      ?: target.className?.let(nativeEntries::get) ?: nativeEntries[target.packageName]
      ?: nameMatcher.match(target)
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
      val monochromeBackgrounds = linkedMapOf<String, ByteArray>()
      val fallbackPaletteNames = linkedSetOf<String>()
      val colorOsPackages = linkedSetOf<String>()
      val nativeAdaptationTargets = linkedSetOf<IconPackComponent>()
      val colorOs = pack.colorOsProfile ?: colorOsFallback?.colorOsProfile
      val fallback = colorOs?.let { ColorOsFallbackRenderer(context, style = it.style) }
      val systemRenderer = systemFallback?.let { SystemIconFallbackRenderer(context, it) }
      val systemNames = linkedSetOf<String>()
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
      IconPackRenderer(context).open(pack, darkMode, colorOsFallback).use { renderer ->
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
            ImageArchiveZip.writeBytes(zip, "res/drawable-xxhdpi/$name.png", png, check = check)
          }
          for ((pkg, group) in normalized.groupBy { it.packageName }) {
            check()
            val matches = group.associateWith(::match)
            // A package fallback is only unambiguous when ALL supplied launcher activities
            // resolve to the same entry. Different activity artwork gets activity aliases only.
            val common = matches.values.takeIf { values -> values.all { it != null } }
              ?.map { it!!.key }?.distinct()?.singleOrNull()
              ?: if ((fallback != null || systemRenderer?.isSystem(group.first()) == true) && group.size == 1)
                group.single().flattened else null
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
              if (entry == null && fallback == null && systemRenderer?.isSystem(target) != true) {
                skipped += IconPackExportSkip(target, "No component mapping")
                continue
              }
              var usedSystemFallback = false
              val png = try {
                val missingMode = entry == null || (colorOs != null && entry.artwork(darkMode) == null)
                val bitmap = renderer.renderTarget(entry, target, sizePx, check)
                  ?: if (missingMode && systemRenderer?.isSystem(target) == true) {
                    systemRenderer.render(target, sizePx, check)?.also { usedSystemFallback = true }
                  } else if (missingMode) fallback?.render(target, darkMode, sizePx, mask, check) else null
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
              if (usedSystemFallback) {
                target.className?.takeUnless { it in sharedActivities }?.let(systemNames::add)
                if (common != null || target.className == null) systemNames += pkg
              }
              if (!usedSystemFallback && (colorOs != null || entry != null)) {
                val declaredLayers = if (pack.colorOsProfile == null && entry != null) try {
                  renderer.themedLayers(entry, sizePx, check)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { null } else null
                val declaredBackground = declaredLayers?.second?.let { background ->
                  try { scaledPng(background).also { if (paletteBackground == null) paletteBackground = it } }
                  finally { background.recycle() }
                }
                run {
                  val bitmap = declaredLayers?.first ?: renderer.colorOsMonochrome(target, sizePx, check)
                  if (bitmap != null) {
                    try {
                      require(bitmap.width in 1..512 && bitmap.height == bitmap.width)
                      val maskPng = scaledPng(bitmap)
                      val usable = try { GeneratedIconPng.requireAlphaMask(maskPng, sizePx); true }
                        catch (_: IllegalArgumentException) { false }
                      if (usable) {
                        if (renderer.usedRuntimeMonochrome(target)) colorOsPackages += pkg
                        val fallbackPalette = declaredLayers == null && renderer.colorOsMonochromeIsFallback(target)
                        target.className?.takeUnless { it in sharedActivities }?.let {
                          monochrome[it] = maskPng
                          if (fallbackPalette) fallbackPaletteNames += it
                          declaredBackground?.let { background -> monochromeBackgrounds[it] = background }
                        }
                        if (common != null || target.className == null) {
                          monochrome[pkg] = maskPng
                          if (fallbackPalette) fallbackPaletteNames += pkg
                          declaredBackground?.let { monochromeBackgrounds[pkg] = it }
                        }
                      }
                    } finally { bitmap.recycle() }
                  }
                }
              }
              exported += target
              if (renderer.usedRuntimeArtwork(target)) colorOsPackages += pkg
            }
          }
          if (systemFallback != null) {
            ImageArchiveZip.writeBytes(zip, SystemIconFallbackManifest.PATH,
              SystemIconFallbackManifest(systemFallback, systemNames, systemAppNameMatching,
                pack.colorOsProfile == null && colorOsFallback != null).encode(), check = check)
          }
        }
        } finally { nativeAdaptationTargets += renderer.nativeAdaptationTargets; mask?.recycle() }
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
        monochrome = monochrome, paletteBackground = paletteBackground, monochromeBackgrounds = monochromeBackgrounds,
        fallbackPaletteNames = fallbackPaletteNames, colorOsPackages = colorOsPackages,
        nativeAdaptationTargets = nativeAdaptationTargets)
    } finally {
      nested.delete()
      if (!success) archive.delete()
    }
  }

  /** Native modules already carry their own rendering, masking, animation and mode contracts.
   * Keep every byte and resource qualifier; targets report known exact names, not an allowlist. */
  private fun exportNativeModule(
    pack: IconPackDescriptor, targets: List<IconPackComponent>, darkMode: Boolean, size: Int,
    systemFallback: SystemIconFallback?, colorOsFallback: IconPackDescriptor?, installedOnly: Boolean, check: () -> Unit,
  ): IconPackExportResult {
    val record = (pack.source as? IconPackSource.Imported)?.record ?: throw IconPackException("Missing native icon import")
    val directory = File(context.cacheDir, "icon-pack-exports")
    if (!directory.isDirectory && !directory.mkdirs()) throw IconPackException("Cannot create export staging directory")
    val archive = File.createTempFile("icons-native-", ".zip", directory)
    val nested = File.createTempFile("verify-", ".zip", directory)
    var success = false
    try {
      val index = IconPackArchive(limits).copyNativeModule(record, archive, nested, check)
      val originalNames = index.entries.mapNotNull { it.nativeName }.toSet()
      val extra = if (colorOsFallback != null)
        appendColorOsFallbacks(archive, targets, originalNames, colorOsFallback,
          systemFallback ?: SystemIconFallback.DEFAULT, size, installedOnly, check)
      else { if (systemFallback != null) appendNativeFallbacks(archive, targets, originalNames, systemFallback, size, check); null }
      val verified = if (systemFallback == null && colorOsFallback == null) index else IconPackArchive(limits).inspect(archive, nested,
        checkCancelled = check)
      val names = verified.entries.mapNotNull { it.nativeName }.toSet()
      val covered = targets.filter { it.packageName in names || it.className in names }
      val skipped = (targets - covered.toSet()).map { IconPackExportSkip(it, "No exact native filename; native aliases are not inferred") }
      check()
      success = true
      return IconPackExportResult(archive, verified.nativeIconsSha256!!, covered, skipped, names.size, darkMode,
        preservesNativeResources = true, colorOsPackages = extra?.first.orEmpty(), nativeAdaptationTargets = extra?.second.orEmpty())
    } finally {
      nested.delete()
      if (!success) archive.delete()
    }
  }

  /** Add only uncovered names; supplied native animations, night trees and artwork stay authoritative. */
  private fun appendColorOsFallbacks(archive: File, targets: List<IconPackComponent>, covered: Set<String>,
    fallback: IconPackDescriptor, style: SystemIconFallback, size: Int, installedOnly: Boolean,
    check: () -> Unit): Pair<Set<String>, Set<IconPackComponent>> {
    require(fallback.colorOsProfile != null && fallback.format == IconPackFormat.CLASSIC_ZIP)
    val protected = java.util.zip.ZipFile(archive).use { source ->
      covered + nativeFallbackProtectedNames(source.entries().asSequence().map { it.name })
    }
    val missing = targets.filter { it.packageName !in protected && it.className !in protected }
    if (installedOnly && missing.isEmpty()) return emptySet<String>() to emptySet<IconPackComponent>()
    val day = export(fallback, missing, false, size, style, installedOnly = installedOnly, check = check)
    var night: IconPackExportResult? = null
    val copy = File.createTempFile("native-coloros-fallback-", ".zip", archive.parentFile)
    try {
      night = export(fallback, missing, true, size, style, installedOnly = installedOnly, check = check)
      val additions = linkedMapOf<String, ByteArray>()
      val systemNames = linkedSetOf<String>()
      java.util.zip.ZipFile(day.archive).use { light ->
        java.util.zip.ZipFile(night.archive).use { dark ->
          light.getEntry(SystemIconFallbackManifest.PATH)?.let { entry ->
            systemNames += SystemIconFallbackManifest.decode(light.getInputStream(entry).use { it.readBytes() }).names - protected
          }
          light.entries().asSequence().filter { !it.isDirectory &&
            it.name.startsWith("res/drawable-xxhdpi/") && it.name.endsWith(".png") }.forEach { entry ->
            check()
            val name = entry.name.removePrefix("res/drawable-xxhdpi/").removeSuffix(".png")
            if (name in protected) return@forEach
            val other = dark.getEntry(entry.name) ?: return@forEach
            val first = light.getInputStream(entry).use { it.readBytes() }
            val second = dark.getInputStream(other).use { it.readBytes() }
            val mask = day.monochrome[name]
            val background = day.monochromeBackgrounds[name] ?: day.paletteBackground
            val pair = if (mask != null && background != null)
              NativePaletteIcon.entries(name, first, second, mask, background)
            else NativeDayNightIcon.entries(name, first, second) +
              ("nightmode/res/drawable-xxhdpi/$name.png" to second)
            additions.putAll(pair)
          }
        }
      }
      java.util.zip.ZipFile(archive).use { source ->
        val previous = source.getEntry(SystemIconFallbackManifest.PATH)?.let { entry ->
          SystemIconFallbackManifest.decode(source.getInputStream(entry).use { it.readBytes() })
        }
        require(previous == null || previous.style == style) { "Use the original pack to change a generated fallback" }
        ZipOutputStream(LimitedOutput(copy.outputStream(), limits.maxInputBytes)).use { output ->
          source.entries().asSequence().forEach { entry ->
            check()
            if (entry.name == SystemIconFallbackManifest.PATH) return@forEach
            ImageArchiveZip.copyEntry(output, source, entry, check = check)
          }
          for ((path, bytes) in additions) {
            check()
            require(source.getEntry(path) == null) { "Fallback would overwrite supplied artwork: $path" }
            ImageArchiveZip.writeBytes(output, path, bytes, check = check)
          }
          ImageArchiveZip.writeBytes(output, SystemIconFallbackManifest.PATH,
            SystemIconFallbackManifest(style, systemNames + previous?.names.orEmpty(),
              previous?.nameMatching == true, colorOsFallback = true).encode(), check = check)
        }
      }
      copy.copyTo(archive, overwrite = true)
      val addedNames = nativeFallbackProtectedNames(additions.keys.asSequence())
      return (day.colorOsPackages + night.colorOsPackages).filterTo(linkedSetOf()) { it in addedNames } to
        (day.nativeAdaptationTargets + night.nativeAdaptationTargets).filterTo(linkedSetOf()) {
          it.packageName !in protected && it.className !in protected
        }
    } finally { copy.delete(); day.archive.delete(); night?.archive?.delete() }
  }

  private fun appendNativeFallbacks(archive: File, targets: List<IconPackComponent>, covered: Set<String>,
    style: SystemIconFallback, size: Int, check: () -> Unit) {
    val renderer = SystemIconFallbackRenderer(context, style)
    val previous = java.util.zip.ZipFile(archive).use { source ->
      source.getEntry(SystemIconFallbackManifest.PATH)?.let { entry ->
        require(entry.size in 1..SystemIconFallbackManifest.MAX_BYTES)
        SystemIconFallbackManifest.decode(source.getInputStream(entry).use {
          it.readNBytes(SystemIconFallbackManifest.MAX_BYTES + 1)
        })
      }
    }
    if (previous != null) {
      require(previous.style == style) { "Use the original pack source to change an already-generated fallback" }
      return
    }
    val protected = java.util.zip.ZipFile(archive).use { zip ->
      covered + nativeFallbackProtectedNames(zip.entries().asSequence().map { it.name })
    }
    val additions = linkedMapOf<String, ByteArray>()
    val shared = targets.mapNotNull { it.className }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    for ((pkg, group) in targets.groupBy { it.packageName }) {
      for (target in group) {
        check()
        if (pkg in protected || target.className in protected || target.className in shared) continue
        val bitmap = renderer.render(target, size, check) ?: continue
        val bytes = try { ByteArrayOutputStream().also {
          check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray() } finally { bitmap.recycle() }
        target.className?.let { additions[it] = bytes }
        if (group.size == 1 || target.className == null) additions[pkg] = bytes
      }
    }
    if (additions.isEmpty()) return
    val copy = File.createTempFile("native-fallback-", ".zip", archive.parentFile)
    try {
      java.util.zip.ZipFile(archive).use { source ->
        ZipOutputStream(LimitedOutput(copy.outputStream(), limits.maxInputBytes)).use { output ->
          source.entries().asSequence().forEach { entry ->
            check()
            if (entry.name == SystemIconFallbackManifest.PATH) return@forEach
            ImageArchiveZip.copyEntry(output, source, entry, check = check)
          }
          for ((name, bytes) in additions) {
            for (prefix in listOf("", "nightmode/")) {
              val path = "${prefix}res/drawable-xxhdpi/$name.png"
              require(source.getEntry(path) == null)
              ImageArchiveZip.writeBytes(output, path, bytes, check = check)
            }
            for ((path, data) in NativeDayNightIcon.entries(name, bytes, bytes)) {
              if (!path.startsWith("fancy_icons/")) continue
              require(source.getEntry(path) == null)
              ImageArchiveZip.writeBytes(output, path, data, check = check)
            }
          }
          ImageArchiveZip.writeBytes(output, SystemIconFallbackManifest.PATH,
            SystemIconFallbackManifest(style, additions.keys).encode(), check = check)
        }
      }
      check(copy.renameTo(archive)) { "Cannot publish native fallback copy" }
    } finally { copy.delete() }
  }

  private class LimitedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
    private var count = 0L
    private fun reserve(bytes: Int) { count += bytes; if (count > limit) throw IconPackException("Export size limit exceeded") }
    override fun write(value: Int) { reserve(1); out.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); out.write(bytes, offset, length) }
  }
}
