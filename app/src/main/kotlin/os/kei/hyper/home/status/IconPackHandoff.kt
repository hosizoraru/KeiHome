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
import kotlinx.coroutines.runInterruptible
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackRepository
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.ForeignTelegramAliases
import os.kei.hyper.home.iconpack.NativeDayNightIcon
import os.kei.hyper.home.iconpack.OriginalAppIcons
import os.kei.hyper.home.iconpack.TelegramChannelIcons
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
import os.kei.hyper.home.iconpack.ColorOsFallbackRenderer
import os.kei.hyper.home.iconpack.ColorOsGenerationProgress
import os.kei.hyper.home.iconpack.ColorOsGenerationStage
import os.kei.hyper.home.iconpack.ExtraIcons
import os.kei.hyper.home.iconpack.SystemIconFallbackManifest
import os.kei.hyper.home.iconpack.IconBuildInventory
import os.kei.hyper.home.iconpack.IconThemeMetadata
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconGenerationManifest
import os.kei.hyper.home.iconpack.IconPackExportResult
import os.kei.hyper.home.iconpack.NativeIconAdaptations
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.PreservedIconResources

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
    recommendedMappings: RecommendedIconMappings? = null,
    applyCustomIcons: Boolean = true,
    monetUnadaptedIcons: Boolean = false,
    monetFullBleedIconPadding: Boolean = false,
    onProgress: (ColorOsGenerationProgress) -> Unit = {}): IconPackHandoff {
    val sources = if (pack.colorOsProfile != null) ColorOsFallbackRenderer.Sources(context) else null
    fun progress(stage: ColorOsGenerationStage): (Int, Int, String) -> Unit = { done, total, name ->
      onProgress(ColorOsGenerationProgress(stage, done, total, name.takeIf { it.isNotEmpty() }))
    }
    fun byteProgress(stage: ColorOsGenerationStage): (Long, Long) -> Unit = { done, total ->
      onProgress(ColorOsGenerationProgress(stage, (done / 1024).toInt(), (total / 1024).toInt(), inKiB = true))
    }
    val mappings = recommendedMappings ?: withContext(Dispatchers.IO) { RecommendedIconMappings.snapshot(context) }
    val pack = BuiltinColorOsMappings.selected(pack, mappings.colorOsThemeStores)
    val iconMask = withContext(Dispatchers.IO) {
      os.kei.hyper.home.iconpack.IconMaskPolicy.snapshot(context, completeSystemIconConfig)
    }
    val custom = withContext(Dispatchers.IO) {
      val caller = currentCoroutineContext()
      if (applyCustomIcons) {
        os.kei.hyper.home.iconpack.CustomIconGeometry.refresh(context, repository) { caller.ensureActive() }
        os.kei.hyper.home.iconpack.CustomIconStore.read(context) { caller.ensureActive() }
      } else os.kei.hyper.home.iconpack.CustomIconSnapshot(emptyList(), emptyMap())
    }
    val preserved = withContext(Dispatchers.IO) {
      if (applyCustomIcons) os.kei.hyper.home.data.PreservedIcons.snapshot(context) - custom.packages else emptySet()
    }
    val extraIcons = withContext(Dispatchers.IO) {
      if (applyCustomIcons) ExtraIcons.snapshot(context) else emptySet()
    }
    val effectiveExtraIcons = extraIcons - preserved - custom.packages
    val exportTargets = targets.filterNot { it.packageName in preserved }
    val sourceAppearance = when (pack.format) {
      IconPackFormat.MIUI_MTZ, IconPackFormat.MIUI_ICONS -> IconPackAppearance.SYSTEM
      else -> appearance
    }
    val firstStage = when (pack.format) {
      IconPackFormat.MIUI_MTZ, IconPackFormat.MIUI_ICONS -> ColorOsGenerationStage.COPYING_NATIVE
      else -> ColorOsGenerationStage.LIGHT
    }
    onProgress(ColorOsGenerationProgress(firstStage))
    val light = repository.exportNativeIcons(pack, exportTargets, darkMode = sourceAppearance.sourceDarkMode(false),
      systemFallback = systemFallback, systemAppNameMatching = systemAppNameMatching,
      colorOsForOtherPacks = colorOsForOtherPacks, installedOnly = installedOnly, maskPolicy = iconMask,
      preservedPackages = preserved, recommendedMappings = mappings, colorOsSources = sources,
      allowEmpty = custom.resources.isNotEmpty(),
      onProgress = progress(ColorOsGenerationStage.LIGHT),
      onNativeProgress = onProgress)
    var darkFile: File? = null
    var darkResult: IconPackExportResult? = null
    var merged: File? = null
    val token = UUID.randomUUID().toString()
    val target = file(context, token)
    try {
      val icons = if (light.preservesNativeResources) light.archive else {
        onProgress(ColorOsGenerationProgress(ColorOsGenerationStage.DARK))
        val dark = when (sourceAppearance) {
          IconPackAppearance.SYSTEM ->
            repository.exportNativeIcons(pack, exportTargets, darkMode = true, systemFallback = systemFallback,
              systemAppNameMatching = systemAppNameMatching, colorOsForOtherPacks = colorOsForOtherPacks,
              installedOnly = installedOnly, maskPolicy = iconMask, preservedPackages = preserved, recommendedMappings = mappings,
              colorOsSources = sources, allowEmpty = custom.resources.isNotEmpty(),
              onProgress = progress(ColorOsGenerationStage.DARK))
              .also { darkResult = it }
              .archive.also { darkFile = it }
          IconPackAppearance.DIM_AT_NIGHT ->
            dimmedIcons(context, light.archive, progress(ColorOsGenerationStage.DARK)).also { darkFile = it }
          else -> light.archive
        }
        onProgress(ColorOsGenerationProgress(ColorOsGenerationStage.PAIRING))
        withContext(Dispatchers.IO) {
          val current = currentCoroutineContext()
          val exclusions = os.kei.hyper.home.iconpack.PreservedIconResources(preserved, targets)
          val dynamic = (colorOsDynamicEntries(pack, exportTargets, sourceAppearance) { current.ensureActive() } +
            AppleDynamicIconArchive.entries(pack, exportTargets, installedOnly, sourceAppearance) { current.ensureActive() })
            .filterKeys { !exclusions.containsPath(it) }
          File.createTempFile("paired-icons-", ".zip", context.cacheDir).also { paired ->
            merged = paired
            ZipFile(light.archive).use { dayZip ->
              ZipFile(dark).use { nightZip ->
                val pairAppearances = systemFallback != null || includePalette ||
                  sourceAppearance == IconPackAppearance.SYSTEM || sourceAppearance == IconPackAppearance.DIM_AT_NIGHT
                val dayCandidates = if (pairAppearances) dayZip.entries().asSequence().filter {
                  !it.isDirectory && it.name.startsWith("res/drawable-xxhdpi/") && it.name.endsWith(".png")
                }.toList() else emptyList()
                val pairingProgress = progress(ColorOsGenerationStage.PAIRING)
                val total = dayZip.size() + nightZip.size() + dayCandidates.size + dynamic.size
                var done = 0
                ZipOutputStream(paired.outputStream()).use { output ->
                  listOf(dayZip to false, nightZip to true).forEach { (zip, night) ->
                    zip.entries().asSequence().forEach { entry ->
                      current.ensureActive()
                      pairingProgress(done++, total, "")
                      if (entry.isDirectory) return@forEach
                      if (night && entry.name == SystemIconFallbackManifest.PATH) return@forEach
                      val name = if (night) "nightmode/${entry.name}" else entry.name
                      if (name in dynamic) return@forEach
                      ImageArchiveZip.copyEntry(output, zip, entry, name, time = 0) { current.ensureActive() }
                    }
                  }
                  // The current Flutter icon chain does not select nightmode PNGs. The native
                  // MAML loader has its own configuration observer and can select this fixed pair.
                  // Keep both ordinary trees for native readers that do not allow fancy icons.
                  if (pairAppearances) {
                    val sameArchive = dark == light.archive
                    val systemNames = dayZip.getEntry(SystemIconFallbackManifest.PATH)?.let { entry ->
                      SystemIconFallbackManifest.decode(dayZip.getInputStream(entry).use {
                        it.readNBytes(SystemIconFallbackManifest.MAX_BYTES + 1)
                      }).names
                    }.orEmpty()
                    dayCandidates.forEach { dayEntry ->
                      current.ensureActive()
                      pairingProgress(done++, total, "")
                      val name = dayEntry.name.removePrefix("res/drawable-xxhdpi/").removeSuffix(".png")
                      if ("fancy_icons/$name/manifest.xml" in dynamic) return@forEach
                      val mask = light.monochrome[name].takeIf { includePalette || name in light.fallbackPaletteNames }
                      val background = light.monochromeBackgrounds[name] ?: light.paletteBackground
                      if (sameArchive && (mask == null || background == null) && name !in systemNames) return@forEach
                      val nightEntry = nightZip.getEntry(dayEntry.name) ?: return@forEach
                      val dayBytes = dayZip.getInputStream(dayEntry).use { it.readBytes() }
                      val nightBytes = if (sameArchive) dayBytes else nightZip.getInputStream(nightEntry).use { it.readBytes() }
                      // HYOS system frontends can ignore a flat PNG override. Their fallback
                      // needs an explicit descriptor even when day and night artwork is equal.
                      val generated = when {
                        mask != null && background != null ->
                          NativePaletteIcon.entries(name, dayBytes, nightBytes, mask, background)
                        NativeDayNightIcon.needsSelector(dayBytes, nightBytes, name in systemNames) ->
                          NativeDayNightIcon.entries(name, dayBytes, nightBytes)
                        else -> return@forEach
                      }
                      for ((path, bytes) in generated) {
                        if (!path.startsWith("fancy_icons/")) continue
                        ImageArchiveZip.writeBytes(output, path, bytes) { current.ensureActive() }
                      }
                    }
                  }
                  for ((path, bytes) in dynamic) {
                    current.ensureActive()
                    pairingProgress(done++, total, "")
                    ImageArchiveZip.writeBytes(output, path, bytes) { current.ensureActive() }
                  }
                }
                pairingProgress(done, total, "")
              }
            }
          }
        }
      }
      val first = iconPackPreviewEntry(pack, exportTargets)
      val artwork = first?.let { previews.load(pack, it, 144, maskPolicy = iconMask) }
      onProgress(ColorOsGenerationProgress(ColorOsGenerationStage.ADAPTATIONS))
      return withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        val exports = listOfNotNull(light, darkResult)
        val nativeTargets = exports.flatMap { it.nativeAdaptationTargets }
          .filterNot { it.packageName in custom.packages }.mapTo(linkedSetOf()) { it.flattened }
        val generation = IconGenerationManifest(
          exports.flatMapTo(linkedSetOf()) { it.colorOsPackages },
          (targets + exports.flatMap { it.exportedTargets } + exports.flatMap { it.skippedTargets.map { skip -> skip.component } })
            .mapTo(linkedSetOf()) { it.flattened },
          NativeIconAdaptations(context).read(nativeTargets, sources,
            progress(ColorOsGenerationStage.ADAPTATIONS)) { caller.ensureActive() },
          installedOnly = installedOnly,
          completeSystemIconConfig = completeSystemIconConfig,
          iconMaskIdentity = iconMask.identity,
          activeLauncherTargets = targets.filter { it.className != null }.mapTo(linkedSetOf()) { it.flattened },
          preservedIconPackages = preserved,
          recommendedMappings = mappings,
          schemaVersion = IconGenerationManifest.CURRENT_SCHEMA,
          extraIconPackages = extraIcons,
          customIconIdentity = custom.identity,
          monetUnadaptedIcons = monetUnadaptedIcons,
          monetFullBleedIconPadding = monetFullBleedIconPadding,
        )
        currentCoroutineContext().ensureActive()
        onProgress(ColorOsGenerationProgress(ColorOsGenerationStage.STAMPING))
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val stampedIcons = File.createTempFile("stamped-icons-", ".zip", context.cacheDir)
        val maskedIcons = File(context.cacheDir, "masked-icons-${UUID.randomUUID()}.zip")
        val customIcons = File(context.cacheDir, "custom-icons-${UUID.randomUUID()}.zip")
        val genericMonet = File(context.cacheDir, "generic-monet-${UUID.randomUUID()}.zip")
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
          if (targets.any { TelegramChannelIcons.foreignLauncherClass(it) != null }) {
            ZipFile(icons).use { source ->
              stamps.putAll(ForeignTelegramAliases.additions(source, targets, light.preservesNativeResources,
                { caller.ensureActive() }) { component, dark ->
                OriginalAppIcons.render(context, component, 144, dark) { caller.ensureActive() }?.let { bitmap ->
                  try {
                    java.io.ByteArrayOutputStream().use { buffer ->
                      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer))
                      buffer.toByteArray()
                    }
                  } finally { bitmap.recycle() }
                }
              })
            }
          }
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
            onProgress = progress(ColorOsGenerationStage.STAMPING).let { emit -> { done, total -> emit(done, total, "") } },
            check = { caller.ensureActive() }, maxOutputBytes = IconPackLimits().maxInputBytes)
          onProgress(ColorOsGenerationProgress(ColorOsGenerationStage.MASKING))
          val maskingProgress = progress(ColorOsGenerationStage.MASKING)
          val shapedSource = if (PublishedIconCompositeMask.write(stampedIcons, maskedIcons,
              enabled = completeSystemIconConfig, check = { caller.ensureActive() },
              onProgress = { done, total -> maskingProgress(done, total, "") })) maskedIcons else stampedIcons
          val monetProgress = progress(ColorOsGenerationStage.MONET)
          val monetWriteProgress = progress(ColorOsGenerationStage.MONET_WRITING)
          val maskedSource = if ((monetUnadaptedIcons || monetFullBleedIconPadding) &&
            PublishedGenericMonet.write(shapedSource, genericMonet,
              monetUnadaptedIcons, PreservedIconResources.fromInstalled(context, preserved + custom.packages, targets) {
                caller.ensureActive()
              }, insetFullBleed = monetFullBleedIconPadding,
              generatedPaletteNames = if (monetFullBleedIconPadding) buildSet {
                for (export in listOfNotNull(light, darkResult)) {
                  addAll(export.monochrome.keys)
                  addAll(export.fallbackPaletteNames)
                  addAll(export.colorOsPackages)
                }
              } else emptySet(),
              onProgress = { done, total -> monetProgress(done, total, "") },
              onWriteProgress = { done, total -> monetWriteProgress(done, total, "") }
            ) { caller.ensureActive() }) genericMonet else shapedSource
          val replaceExtraIcons = if (!light.preservesNativeResources && sourceAppearance == IconPackAppearance.LIGHT)
            emptySet() else effectiveExtraIcons
          val publishedIcons = if (replaceExtraIcons.isNotEmpty() || custom.selections.isNotEmpty()) {
            onProgress(ColorOsGenerationProgress(ColorOsGenerationStage.CUSTOM_ICONS))
            val exclusions = PreservedIconResources.fromInstalled(context, custom.packages, targets) { caller.ensureActive() }
            val replacements = linkedMapOf<String, ByteArray>()
            custom.resources.forEach { (packageName, resources) ->
              val names = linkedSetOf(packageName).apply {
                targets.filter { it.packageName == packageName }.mapNotNullTo(this, ::nativeLauncherActivityName)
              }
              replacements.putAll(os.kei.hyper.home.iconpack.CustomIconResources.retarget(resources, names))
            }
            ZipFile(maskedSource).use { source ->
              replacements.putAll(PublishedExtraIcons.replacements(context, source, targets,
                replaceExtraIcons) { caller.ensureActive() })
              source.getEntry(SystemIconFallbackManifest.PATH)?.let { entry ->
                val provenance = source.getInputStream(entry).use { SystemIconFallbackManifest.decode(it.readBytes()) }
                replacements[SystemIconFallbackManifest.PATH] = provenance.copy(
                  names = provenance.names.filterNotTo(linkedSetOf(), exclusions::containsName)).encode()
              }
            }
            RawIconArchive.write(maskedSource, customIcons, includes = { path ->
              !exclusions.containsPath(path) && path != IconBuildInventory.NAMES_PATH
            }, replacements = replacements,
              onProgress = progress(ColorOsGenerationStage.CUSTOM_ICONS).let { emit -> { done, total -> emit(done, total, "") } },
              check = { caller.ensureActive() }, maxOutputBytes = IconPackLimits().maxInputBytes)
            customIcons
          } else maskedSource
          onProgress(ColorOsGenerationProgress(ColorOsGenerationStage.PACKAGING))
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
            ImageArchiveZip.writeFile(output, "icons", publishedIcons,
              onBytes = byteProgress(ColorOsGenerationStage.PACKAGING)) { caller.ensureActive() }
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
        } finally { stampedIcons.delete(); maskedIcons.delete(); customIcons.delete(); genericMonet.delete() }
        val convertedOrigin = when (pack.format) {
          os.kei.hyper.home.iconpack.IconPackFormat.INSTALLED_APK,
          os.kei.hyper.home.iconpack.IconPackFormat.APK -> "CONVERTED_APK"
          os.kei.hyper.home.iconpack.IconPackFormat.CLASSIC_ZIP -> "CONVERTED_CLASSIC_ZIP"
          os.kei.hyper.home.iconpack.IconPackFormat.MIUI_ICONS,
          os.kei.hyper.home.iconpack.IconPackFormat.MIUI_MTZ ->
            os.kei.hyper.home.xposed.ConvertedIconArchive.LOCAL_NATIVE
          else -> null
        }
        // Fail before creating a native-library entry if local application cannot
        // be admitted. Publishing an unusable import invites an online DRM retry.
        if (convertedOrigin != null) try {
          runInterruptible { os.kei.hyper.home.xposed.ConvertedIconArchive.inspect(target, convertedOrigin) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          android.util.Log.e("KeiHomePackImport", "Local publication validation failed before native import", failure)
          throw os.kei.hyper.home.iconpack.IconPackException(
            context.getString(os.kei.hyper.home.R.string.pack_local_apply_unavailable) + "\n" + failure.message,
            failure)
        }
        IconPackHandoff(token, target, pack.id, pack.displayName, convertedOrigin)
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

  private suspend fun dimmedIcons(context: Context, source: File,
    onProgress: (Int, Int, String) -> Unit): File = withContext(Dispatchers.IO) {
    val current = currentCoroutineContext()
    val archive = File.createTempFile("dimmed-icons-", ".zip", context.cacheDir)
    try {
      val paint = nightDimmingPaint()
      ZipFile(source).use { zip ->
        var done = 0
        val total = zip.size()
        ZipOutputStream(archive.outputStream()).use { output ->
          zip.entries().asSequence().forEach { entry ->
            current.ensureActive()
            onProgress(done++, total, "")
            if (entry.isDirectory) return@forEach
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
        onProgress(done, total, "")
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
