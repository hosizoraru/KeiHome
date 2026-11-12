package os.kei.hyper.home.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.NativeDayNightIcon
import os.kei.hyper.home.iconpack.nativeLauncherActivityName
import os.kei.hyper.home.iconpack.ImageArchiveZip
import os.kei.hyper.home.iconpack.RawIconArchive
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon
import os.kei.hyper.home.iconpack.apple.AppleDynamicIconArchive
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.data.RecommendedIconMappings
import os.kei.hyper.home.iconpack.BuiltinColorOsMappings
import os.kei.hyper.home.iconpack.SystemIconFallbackManifest
import os.kei.hyper.home.iconpack.IconBuildInventory
import os.kei.hyper.home.iconpack.IconThemeMetadata
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconGenerationManifest
import os.kei.hyper.home.iconpack.IconPackExportResult
import os.kei.hyper.home.iconpack.NativeIconAdaptations
import os.kei.hyper.home.iconpack.NativeIconShapeConfig

/** A user-requested icons-only MTZ, offered to the existing system module through a private URI. */
internal data class IconPackHandoff(
  val token: String, val file: File, val sourceId: String, val title: String,
  val convertedOrigin: String? = null,
)

internal object IconPackHandoffs {
  private val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  fun file(context: Context, token: String): File {
    require(tokenPattern.matches(token)) { "Invalid icon handoff" }
    return File(File(context.cacheDir, "native-icon-imports"), "$token.mtz")
  }

  suspend fun prepare(context: Context, repository: IconPackRepository, previews: IconPackPreviewRepository,
    pack: IconPackDescriptor, targets: List<IconPackComponent>, appearance: IconPackAppearance,
    includePalette: Boolean = true, systemFallback: SystemIconFallback? = null,
    systemAppNameMatching: Boolean = false, colorOsForOtherPacks: Boolean = false,
    installedOnly: Boolean = false, completeSystemIconConfig: Boolean = true,
    recommendedMappings: RecommendedIconMappings? = null): IconPackHandoff {
    val mappings = recommendedMappings ?: withContext(Dispatchers.IO) { RecommendedIconMappings.snapshot(context) }
    val pack = BuiltinColorOsMappings.selected(pack, mappings.colorOsThemeStores)
    val iconMask = withContext(Dispatchers.IO) {
      os.kei.hyper.home.iconpack.IconMaskPolicy.snapshot(context, completeSystemIconConfig)
    }
    val preserved = withContext(Dispatchers.IO) { os.kei.hyper.home.data.PreservedIcons.snapshot(context) }
    val exportTargets = targets.filterNot { it.packageName in preserved }
    val sourceAppearance = when (pack.format) {
      IconPackFormat.MIUI_MTZ, IconPackFormat.MIUI_ICONS -> IconPackAppearance.SYSTEM
      else -> appearance
    }
    val light = repository.exportNativeIcons(pack, exportTargets, darkMode = sourceAppearance.sourceDarkMode(false),
      systemFallback = systemFallback, systemAppNameMatching = systemAppNameMatching,
      colorOsForOtherPacks = colorOsForOtherPacks, installedOnly = installedOnly, maskPolicy = iconMask,
      preservedPackages = preserved, recommendedMappings = mappings)
    var darkFile: File? = null
    var darkResult: IconPackExportResult? = null
    var merged: File? = null
    val token = UUID.randomUUID().toString()
    val target = file(context, token)
    try {
      val icons = if (light.preservesNativeResources) light.archive else {
        val dark = when (sourceAppearance) {
          IconPackAppearance.SYSTEM ->
            repository.exportNativeIcons(pack, exportTargets, darkMode = true, systemFallback = systemFallback,
              systemAppNameMatching = systemAppNameMatching, colorOsForOtherPacks = colorOsForOtherPacks,
              installedOnly = installedOnly, maskPolicy = iconMask, preservedPackages = preserved, recommendedMappings = mappings)
              .also { darkResult = it }
              .archive.also { darkFile = it }
          IconPackAppearance.DIM_AT_NIGHT ->
            dimmedIcons(context, light.archive).also { darkFile = it }
          else -> light.archive
        }
        withContext(Dispatchers.IO) {
          val current = currentCoroutineContext()
          val exclusions = os.kei.hyper.home.iconpack.PreservedIconResources(preserved, targets)
          val dynamic = (colorOsDynamicEntries(pack, exportTargets, sourceAppearance) { current.ensureActive() } +
            AppleDynamicIconArchive.entries(pack, exportTargets, installedOnly, sourceAppearance) { current.ensureActive() })
            .filterKeys { !exclusions.containsPath(it) }
          File.createTempFile("paired-icons-", ".zip", context.cacheDir).also { paired ->
            merged = paired
            ZipOutputStream(paired.outputStream()).use { output ->
              listOf(light.archive to false, dark to true).forEach { (source, night) ->
                ZipFile(source).use { zip ->
                  zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    current.ensureActive()
                    if (night && entry.name == SystemIconFallbackManifest.PATH) return@forEach
                    val name = if (night) "nightmode/${entry.name}" else entry.name
                    if (name in dynamic) return@forEach
                    ImageArchiveZip.copyEntry(output, zip, entry, name, time = 0) { current.ensureActive() }
                  }
                }
              }
              // The current Flutter icon chain does not select nightmode PNGs. The native
              // MAML loader has its own configuration observer and can select this fixed pair.
              // Keep both ordinary trees for native readers that do not allow fancy icons.
              if (systemFallback != null || includePalette || sourceAppearance == IconPackAppearance.SYSTEM || sourceAppearance == IconPackAppearance.DIM_AT_NIGHT) {
                ZipFile(light.archive).use { dayZip ->
                  val systemNames = dayZip.getEntry(SystemIconFallbackManifest.PATH)?.let { entry ->
                    SystemIconFallbackManifest.decode(dayZip.getInputStream(entry).use {
                      it.readNBytes(SystemIconFallbackManifest.MAX_BYTES + 1)
                    }).names
                  }.orEmpty()
                  ZipFile(dark).use { nightZip ->
                    dayZip.entries().asSequence()
                      .filterNot { it.isDirectory }
                      .filter { it.name.startsWith("res/drawable-xxhdpi/") && it.name.endsWith(".png") }
                      .forEach { dayEntry ->
                        current.ensureActive()
                        val nightEntry = nightZip.getEntry(dayEntry.name) ?: return@forEach
                        val dayBytes = dayZip.getInputStream(dayEntry).use { it.readBytes() }
                        val nightBytes = nightZip.getInputStream(nightEntry).use { it.readBytes() }
                        val name = dayEntry.name.removePrefix("res/drawable-xxhdpi/").removeSuffix(".png")
                        if ("fancy_icons/$name/manifest.xml" in dynamic) return@forEach
                        val mask = light.monochrome[name].takeIf { includePalette || name in light.fallbackPaletteNames }
                        val background = light.monochromeBackgrounds[name] ?: light.paletteBackground
                        // HYOS system frontends can ignore a flat PNG override. Their fallback
                        // needs an explicit descriptor even when day and night artwork is equal.
                        if (mask == null && name !in systemNames && dayBytes.contentEquals(nightBytes)) return@forEach
                        val generated = if (mask != null && background != null)
                          NativePaletteIcon.entries(name, dayBytes, nightBytes, mask, background)
                        else NativeDayNightIcon.entries(name, dayBytes, nightBytes)
                        for ((path, bytes) in generated) {
                          if (!path.startsWith("fancy_icons/")) continue
                          ImageArchiveZip.writeBytes(output, path, bytes) { current.ensureActive() }
                        }
                      }
                  }
                }
              }
              for ((path, bytes) in dynamic) {
                current.ensureActive()
                ImageArchiveZip.writeBytes(output, path, bytes) { current.ensureActive() }
              }
            }
          }
        }
      }
      val first = iconPackPreviewEntry(pack, exportTargets)
      val artwork = first?.let { previews.load(pack, it, 144, maskPolicy = iconMask) }
      return withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        val exports = listOfNotNull(light, darkResult)
        val nativeTargets = exports.flatMap { it.nativeAdaptationTargets }.mapTo(linkedSetOf()) { it.flattened }
        val generation = IconGenerationManifest(
          exports.flatMapTo(linkedSetOf()) { it.colorOsPackages },
          (targets + exports.flatMap { it.exportedTargets } + exports.flatMap { it.skippedTargets.map { skip -> skip.component } })
            .mapTo(linkedSetOf()) { it.flattened },
          NativeIconAdaptations(context).read(nativeTargets) { caller.ensureActive() },
          installedOnly = installedOnly,
          completeSystemIconConfig = completeSystemIconConfig,
          iconMaskIdentity = iconMask.identity,
          activeLauncherTargets = targets.filter { it.className != null }.mapTo(linkedSetOf()) { it.flattened },
          preservedIconPackages = preserved,
          recommendedMappings = mappings,
        )
        currentCoroutineContext().ensureActive()
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val stampedIcons = File.createTempFile("stamped-icons-", ".zip", context.cacheDir)
        try {
          fun metadata(value: String?, length: Int) =
            value?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")?.trim()?.take(length)?.takeIf { it.isNotEmpty() }
          val stamps = linkedMapOf(
            IconBuildInventory.SOURCE_PATH to IconBuildInventory.sourceIdentity(pack).toByteArray(Charsets.UTF_8),
            IconGenerationManifest.PATH to generation.encode(),
            IconThemeMetadata.PATH to IconThemeMetadata(
              metadata(pack.description.title ?: pack.displayName, 120),
              metadata(pack.description.version ?: (pack.source as? IconPackSource.Installed)?.versionName, 80),
              metadata(pack.description.author ?: pack.description.designer, 120),
            ).encode(),
          )
          if (completeSystemIconConfig) {
            run {
              ZipFile(icons).use { source ->
                val entry = source.getEntry(NativeIconShapeConfig.PATH)
                val legacyStyle = NativeIconShapeConfig.hasLegacyStyle(source.entries().asSequence().map { it.name })
                if (!legacyStyle && (entry == null || entry.size in 0..NativeIconShapeConfig.MAX_BYTES.toLong())) {
                  val original = entry?.let {
                    source.getInputStream(it).use { input -> input.readNBytes(NativeIconShapeConfig.MAX_BYTES + 1) }
                  }
                  if (original == null || original.size <= NativeIconShapeConfig.MAX_BYTES) {
                    val completed = iconMask.complete(original)
                    if (completed != null && (original == null || !completed.contentEquals(original))) {
                      stamps[NativeIconShapeConfig.PATH] = completed
                    }
                  }
                }
              }
            }
          }
          RawIconArchive.write(icons, stampedIcons,
            includes = { it != IconBuildInventory.NAMES_PATH }, replacements = stamps,
            check = { caller.ensureActive() }, maxOutputBytes = IconPackLimits().maxInputBytes)
          ZipOutputStream(target.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("description.xml").apply { time = 0 })
            val title = xml(pack.displayName)
            val author = xml(pack.description.author ?: "Original icon pack authors")
            val designer = xml(pack.description.designer ?: pack.description.author ?: "Original icon pack authors")
            val version = xml(pack.description.version ?: (pack.source as? IconPackSource.Installed)?.versionName ?: "1.0")
            output.write(("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
              "<MIUI-Theme><title>$title</title><author>$author</author><designer>$designer</designer>" +
              "<version>$version</version><uiVersion>13</uiVersion>" +
              "<description>Local icon import by KeiHome. Artwork: $title.</description></MIUI-Theme>").toByteArray())
            output.closeEntry()
            ImageArchiveZip.writeFile(output, "icons", stampedIcons) { caller.ensureActive() }
            // Themes crops previews to a portrait phone frame, including icons-only components.
            val cover = Bitmap.createBitmap(400, 860, Bitmap.Config.ARGB_8888)
            try {
              val canvas = Canvas(cover)
              canvas.drawColor(Color.rgb(242, 243, 246))
              canvas.drawRect(200f, 0f, 400f, 860f, Paint().apply { color = Color.rgb(35, 36, 40) })
              val lightArtwork = if (sourceAppearance.sourceDarkMode(false)) artwork?.dark else artwork?.light
              val darkArtwork = if (sourceAppearance.sourceDarkMode(true)) artwork?.dark else artwork?.light
              lightArtwork?.let { canvas.drawBitmap(it, 28f, 358f, null) }
              val nightPaint = if (sourceAppearance == IconPackAppearance.DIM_AT_NIGHT) nightDimmingPaint() else null
              darkArtwork?.let { canvas.drawBitmap(it, 228f, 358f, nightPaint) }
              val png = java.io.ByteArrayOutputStream().use { buffer ->
                check(cover.compress(Bitmap.CompressFormat.PNG, 100, buffer))
                buffer.toByteArray()
              }
              ImageArchiveZip.writeBytes(output, "preview/preview_icons_0.png", png) { caller.ensureActive() }
            } finally { cover.recycle() }
          }
        } finally { stampedIcons.delete() }
        IconPackHandoff(token, target, pack.id, pack.displayName, when (pack.format) {
          os.kei.hyper.home.iconpack.IconPackFormat.INSTALLED_APK,
          os.kei.hyper.home.iconpack.IconPackFormat.APK -> "CONVERTED_APK"
          os.kei.hyper.home.iconpack.IconPackFormat.CLASSIC_ZIP -> "CONVERTED_CLASSIC_ZIP"
          os.kei.hyper.home.iconpack.IconPackFormat.MIUI_ICONS ->
            os.kei.hyper.home.xposed.ConvertedIconArchive.LOCAL_NATIVE
          else -> null
        })
      }
    } catch (failure: Throwable) {
      target.delete()
      throw failure
    } finally {
      light.archive.delete()
      darkFile?.delete()
      merged?.delete()
    }
  }

  private fun colorOsDynamicEntries(
    pack: IconPackDescriptor, targets: List<IconPackComponent>, appearance: IconPackAppearance,
    check: () -> Unit,
  ): Map<String, ByteArray> {
    val profile = pack.colorOsProfile ?: return emptyMap()
    val output = linkedMapOf<String, ByteArray>()
    val paths = profile.paths()
    for ((pkg, group) in targets.distinct().groupBy { it.packageName }) {
      for (target in group) {
        check()
        val name = profile.source(target)?.dynamicName ?: continue
        val kind = when (name) {
          "ic_launcher_calender" -> NativeColorOsDynamicIcon.Kind.CALENDAR
          "ic_launcher_clock" -> NativeColorOsDynamicIcon.Kind.CLOCK
          else -> continue
        }
        val leaves = listOf("day.png", "night.png") + if (kind == NativeColorOsDynamicIcon.Kind.CLOCK)
          listOf("clock_day_bg.png", "clock_day_hour_rectangle.png", "clock_day_minute_rectangle.png",
            "night_clock_day_bg.png", "night_clock_day_hour_rectangle.png", "night_clock_day_minute_rectangle.png")
          else emptyList()
        if (leaves.any { "coloros-dynamic/$name/$it" !in paths }) continue
        val assets = leaves.associateWith { profile.read("coloros-dynamic/$name/$it", check) }
        val aliases = listOfNotNull(nativeLauncherActivityName(target)) +
          if (group.size == 1 || target.className == null) listOf(pkg) else emptyList()
        for (alias in aliases.distinct()) {
          output.putAll(NativeColorOsDynamicIcon.entries(alias, kind, assets,
            fixedDark = when (appearance) {
              IconPackAppearance.LIGHT, IconPackAppearance.DIM_AT_NIGHT -> false
              IconPackAppearance.DARK -> true
              IconPackAppearance.SYSTEM -> null
            },
            dim = appearance == IconPackAppearance.DIM_AT_NIGHT))
        }
      }
    }
    return output
  }

  private suspend fun dimmedIcons(context: Context, source: File): File = withContext(Dispatchers.IO) {
    val current = currentCoroutineContext()
    val archive = File.createTempFile("dimmed-icons-", ".zip", context.cacheDir)
    try {
      val paint = nightDimmingPaint()
      ZipFile(source).use { zip ->
        ZipOutputStream(archive.outputStream()).use { output ->
          zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
            current.ensureActive()
            if (entry.name.startsWith("res/drawable-xxhdpi/") && entry.name.endsWith(".png")) {
              val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                ?: error("Cannot decode exported icon")
              try {
                val dimmed = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                try {
                  Canvas(dimmed).drawBitmap(bitmap, 0f, 0f, paint)
                  val bytes = java.io.ByteArrayOutputStream().use { buffer ->
                    check(dimmed.compress(Bitmap.CompressFormat.PNG, 100, buffer))
                    buffer.toByteArray()
                  }
                  ImageArchiveZip.writeBytes(output, entry.name, bytes) { current.ensureActive() }
                } finally { dimmed.recycle() }
              } finally { bitmap.recycle() }
            } else ImageArchiveZip.copyEntry(output, zip, entry, time = 0) { current.ensureActive() }
          }
        }
      }
      current.ensureActive()
      archive
    } catch (failure: Throwable) {
      archive.delete()
      throw failure
    }
  }

  // ColorOS's default-style night filter multiplies RGB by floor(255 * 0.84) / 255.
  private fun nightDimmingPaint() = Paint().apply {
    colorFilter = LightingColorFilter(IconPackAppearance.DIM_COLOR_MULTIPLY, 0)
  }

  private fun iconPackPreviewEntry(pack: IconPackDescriptor, targets: List<IconPackComponent>): os.kei.hyper.home.iconpack.IconPackEntry? {
    val components = targets.toHashSet()
    val packages = targets.mapTo(HashSet()) { it.packageName }
    return pack.entries.firstOrNull { it.component in components || it.nativeName in packages }
      ?: pack.entries.firstOrNull { it.light != null }
  }

  private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
