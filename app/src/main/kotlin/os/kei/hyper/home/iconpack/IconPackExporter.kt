package os.kei.hyper.home.iconpack

import android.content.Context
import os.kei.hyper.home.data.RecommendedIconMappings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.data.PreservedIcons

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

internal class IconPackExporter(private val context: Context, private val limits: IconPackLimits,
  private val perfectSources: (String) -> os.kei.hyper.home.iconpack.perfect.PerfectSystemFallback.Source =
    { os.kei.hyper.home.iconpack.perfect.PerfectSystemFallback.open(context, it) }) {
  /** Probe only missing candidates; use the same guarded renderers and live-source policy. */
  fun canComplete(pack: IconPackDescriptor, target: IconPackComponent,
    systemFallback: SystemIconFallback, systemAppNameMatching: Boolean = false,
    nameMatcher: SystemAppNameMatcher? = null, colorOsFallback: IconPackDescriptor? = null,
    recommendedMappings: RecommendedIconMappings = RecommendedIconMappings.snapshot(context), check: () -> Unit): Boolean {
    val pack = BuiltinColorOsMappings.selected(pack, recommendedMappings.colorOsThemeStores)
    val entry = pack.entries.firstOrNull { it.component == target }
      ?: pack.entries.firstOrNull { it.component == IconPackComponent(target.packageName) }
      ?: (nameMatcher ?: SystemAppNameMatcher(context, pack.entries, systemAppNameMatching,
        BuiltinColorOsMappings.excludedNameMatches(pack, recommendedMappings.colorOsThemeStores))).match(target)
    if (pack.colorOsProfile?.source(target)?.requiresLiveIcon == true || livePolicy(pack)(target, entry)) return false
    return try {
      SystemIconFallbackRenderer(context, systemFallback, clipOutput = false, perfectOpener = perfectSources).use { system ->
        val colorOs = (pack.colorOsProfile ?: colorOsFallback?.colorOsProfile)?.let { ColorOsFallbackRenderer(context, style = it.style) }
        IconPackRenderer(context).open(pack, false, colorOsFallback, clipOutput = false, recommendedMappings = recommendedMappings).use { renderer ->
          val missingMode = entry == null || (colorOs != null && entry.artwork(false) == null)
          val bitmap = renderer.renderTarget(entry, target, 48, check)
            ?: if (missingMode && system.requiresLiveSource(target)) null
            else if (missingMode && (systemFallback.isPerfect || system.isSystem(target)))
              system.render(target, 48, check) ?: colorOs?.render(target, false, 48, check = check)
            else if (missingMode) colorOs?.render(target, false, 48, check = check) else null
          (bitmap != null).also { bitmap?.recycle() }
        }
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

  private fun offlineCompletionTargets(
    completion: List<IconPackComponent>, known: List<IconPackComponent>,
    preservedPackages: Set<String>, check: () -> Unit,
  ): List<IconPackComponent> {
    if (completion.isEmpty()) return emptyList()
    val declared = preservedPackages.flatMap { pkg ->
      check()
      try {
        val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
          android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
        context.packageManager.getPackageInfo(pkg,
          android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong())).activities.orEmpty()
          .map { IconPackComponent(pkg, it.name) }
      } catch (_: android.content.pm.PackageManager.NameNotFoundException) { emptyList()
      } catch (_: SecurityException) { emptyList() }
    }
    val components = (known + declared).distinct()
    val packages = components.mapTo(hashSetOf()) { it.packageName } + preservedPackages
    val owners = linkedMapOf<String, MutableSet<IconPackComponent>>()
    for (component in components) {
      check()
      if (component.className == null) continue
      for (name in nativeLauncherIconNames(component).filterNot { it == component.packageName }) {
        owners.getOrPut(name) { linkedSetOf() }.add(component)
      }
    }
    return completion.flatMap { target ->
      check()
      if (target.className != null || target.packageName in packages) listOf(target)
      else {
        val matches = owners[target.packageName]
        // A bare activity stem is not a new package. Drop shared aliases if any owner is preserved.
        if (matches?.any { it.packageName in preservedPackages } == true) emptyList()
        else matches?.toList() ?: listOf(target)
      }
    }.filterNot { it.packageName in preservedPackages }.distinct()
  }

  fun export(
    pack: IconPackDescriptor, targets: List<IconPackComponent>, darkMode: Boolean,
    sizePx: Int, systemFallback: SystemIconFallback? = null,
    systemAppNameMatching: Boolean = false, colorOsFallback: IconPackDescriptor? = null,
    installedOnly: Boolean = false, maskPolicy: IconMaskPolicy? = null,
    preservedPackages: Set<String> = PreservedIcons.snapshot(context),
    recommendedMappings: RecommendedIconMappings = RecommendedIconMappings.snapshot(context),
    phoneCompanionExclusions: Set<String> = emptySet(),
    colorOsSources: ColorOsFallbackRenderer.Sources? = null,
    allowEmpty: Boolean = false,
    onProgress: ((Int, Int, String) -> Unit)? = null,
    onNativeCopyBytes: ((Long, Long) -> Unit)? = null, check: () -> Unit,
  ): IconPackExportResult {
    require(sizePx in 1..512)
    val pack = BuiltinColorOsMappings.selected(pack, recommendedMappings.colorOsThemeStores)
    // Explicit local supplemental artwork can also cover future installs.
    systemFallback?.let { SystemIconFallbackRenderer(context, it, clipOutput = false,
      perfectOpener = perfectSources) }.use { systemRenderer ->
      // Pin the source before target collection, including installed-only native imports.
      systemRenderer?.perfectSourceHash
      val alternateChannels = targets.filter { TelegramChannelIcons.isFamily(it.packageName) &&
        !TelegramChannelIcons.isDefaultTarget(it) }.mapTo(hashSetOf()) { it.packageName }
      val phone = PhoneCompanionIcons(pack.entries, recommendedMappings.phoneCompanions)
      fun authoredPhoneTargets(source: IconPackDescriptor) = source.entries.mapNotNull { entry ->
        entry.component?.takeIf { it.packageName in PhoneCompanionIcons.TARGETS }
          ?: entry.nativeName?.takeIf { it in PhoneCompanionIcons.TARGETS }?.let(::IconPackComponent)
      }
      val phonePresence = mutableMapOf<String, Boolean>()
      val phoneTargets = (phone.completionTargets() + authoredPhoneTargets(pack) + colorOsFallback?.let {
        (PhoneCompanionIcons(it.entries, recommendedMappings.phoneCompanions).completionTargets() +
          authoredPhoneTargets(it))
          .filterNot { target -> target.packageName in phone.authoredTargets }
      }.orEmpty()).distinct().filterNot { it.packageName in phoneCompanionExclusions }.filter { target ->
          !installedOnly || phonePresence.getOrPut(target.packageName) { try {
            context.packageManager.getApplicationInfo(target.packageName,
              android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            true
          } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false
          } catch (_: SecurityException) { false } }
        }
      val candidates = targets + phoneTargets + if (installedOnly) emptyList() else
        (if (pack.colorOsProfile != null) pack.entries.mapNotNull { it.component }
        else colorOsFallback?.entries.orEmpty().mapNotNull { it.component }) +
          (if (recommendedMappings.telegramChannels) TelegramChannelIcons.offlineTargets() else emptyList())
            .filterNot { it.packageName in alternateChannels } +
          offlineCompletionTargets(systemRenderer?.completionTargets().orEmpty(),
            targets + pack.entries.mapNotNull { it.component } +
              colorOsFallback?.entries.orEmpty().mapNotNull { it.component },
            preservedPackages, check)
      if (candidates.distinct().size > minOf(5000, limits.maxEntries / 2))
        throw IconPackException("Export target limit exceeded")
      val normalized = candidates.map { target ->
        IconPackComponent.parse(target.flattened) ?: throw IconPackException("Invalid launcher component")
      }.distinct().filterNot { it.packageName in preservedPackages }
      if (pack.format == IconPackFormat.MIUI_MTZ || pack.format == IconPackFormat.MIUI_ICONS) {
        val result = exportNativeModule(pack, normalized, darkMode, sizePx, systemFallback, colorOsFallback, installedOnly,
          maskPolicy, preservedPackages, recommendedMappings, phoneCompanionExclusions, check, onNativeCopyBytes)
        try {
          systemRenderer?.validateCompletionSource()
          return result
        } catch (failure: Throwable) {
          result.archive.delete()
          throw failure
        }
      }
      val sourceEntries = IconPackEntryIndex(pack.entries)
      val requiresLive = livePolicy(pack)
      val nameMatcher = SystemAppNameMatcher(context, pack.entries, systemAppNameMatching,
        BuiltinColorOsMappings.excludedNameMatches(pack, recommendedMappings.colorOsThemeStores))
      fun match(target: IconPackComponent) = sourceEntries.find(target) ?: nameMatcher.match(target)
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
        val fallback = colorOs?.let { ColorOsFallbackRenderer(context, style = it.style,
          sources = colorOsSources ?: ColorOsFallbackRenderer.Sources(context)) }
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
        IconPackRenderer(context, colorOsSources).open(pack, darkMode, colorOsFallback,
          maskPolicy = maskPolicy, clipOutput = false, preservedPackages = preservedPackages,
          recommendedMappings = recommendedMappings,
          phoneCompanionExclusions = phoneCompanionExclusions).use { renderer ->
          try {
          ZipOutputStream(LimitedOutput(archive.outputStream(), limits.maxInputBytes)).use { zip ->
            fun write(name: String, png: ByteArray) {
              if (!validNativeIconName(name) || name.toByteArray(Charsets.UTF_8).size > 240) {
                throw IconPackException("Component cannot be represented by a native icon filename")
              }
              val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
              val previous = names[name]
              if (previous != null && previous != hash) throw IconPackException("Native icon filename collision: $name")
              if (previous != null) return
              names[name] = hash
              ImageArchiveZip.writeBytes(zip, "res/drawable-xxhdpi/$name.png", png, check = check)
            }
            var processed = 0
            onProgress?.invoke(0, normalized.size, "")
            for ((pkg, group) in normalized.groupBy { it.packageName }) {
              check()
              val matches = group.associateWith { target -> renderer.completedEntry(target, match(target)) }
              // A package fallback is only unambiguous when ALL supplied launcher activities
              // resolve to the same entry. Different activity artwork gets activity aliases only.
              val common = matches.values.takeIf { values -> values.all { it != null } }
                ?.map { it!!.key }?.distinct()?.singleOrNull()
                ?: if ((fallback != null || systemFallback?.isPerfect == true ||
                    systemRenderer?.isSystem(group.first()) == true) && group.size == 1)
                  group.single().flattened else null
              var packageWritten = false
              for ((target, entry) in matches) {
                check()
                onProgress?.invoke(processed++, normalized.size, target.packageName)
                if (colorOs?.source(target)?.requiresLiveIcon == true || requiresLive(target, entry)) {
                  skipped += IconPackExportSkip(target, "Live source handled by native template or original launcher icon")
                  continue
                }
                if (entry == null && fallback == null && systemFallback?.isPerfect != true &&
                  systemRenderer?.isSystem(target) != true && !renderer.hasPhoneCompanion(target)) {
                  skipped += IconPackExportSkip(target, "No component mapping")
                  continue
                }
                var usedSystemFallback = false
                val png = try {
                  val missingMode = entry == null || (colorOs != null && entry.artwork(darkMode) == null)
                  val bitmap = renderer.renderTarget(entry, target, sizePx, check)
                    ?: if (missingMode && systemRenderer?.requiresLiveSource(target) == true) null
                    else if (missingMode && (systemFallback?.isPerfect == true || systemRenderer?.isSystem(target) == true)) {
                      systemRenderer?.render(target, sizePx, darkMode, check)?.also { usedSystemFallback = true }
                        ?: fallback?.render(target, darkMode, sizePx, mask = null, check = check)
                    } else if (missingMode) fallback?.render(target, darkMode, sizePx, mask = null, check = check) else null
                  bitmap?.let { encodePng(renderer.maskOutput(it)) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                  skipped += IconPackExportSkip(target, failure.message ?: "Artwork cannot be rendered")
                  continue
                }
                if (png == null) {
                  skipped += IconPackExportSkip(target, "No static artwork for this mode")
                  continue
                }
                nativeLauncherActivityName(target)?.let { write(it, png) }
                if (common != null && !packageWritten) { write(pkg, png); packageWritten = true }
                if (target.className == null && !packageWritten) write(pkg, png)
                if (usedSystemFallback) {
                  nativeLauncherActivityName(target)?.let(systemNames::add)
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
                          nativeLauncherActivityName(target)?.let {
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
            onProgress?.invoke(processed, normalized.size, "")
            systemRenderer?.validateCompletionSource()
            if (systemFallback != null) {
              ImageArchiveZip.writeBytes(zip, SystemIconFallbackManifest.PATH,
                SystemIconFallbackManifest(systemFallback, systemNames, systemAppNameMatching,
                  pack.colorOsProfile == null && colorOsFallback != null,
                  perfectSource = systemRenderer?.perfectSourceHash).encode(), check = check)
            }
          }
          } finally { nativeAdaptationTargets += renderer.nativeAdaptationTargets; mask?.recycle() }
        }
        if (exported.isEmpty()) {
          if (!allowEmpty) throw IconPackException("No selected launcher icons could be exported")
          // A later custom-icon merge supplies the artwork when this base has no coverage.
          check()
          val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
          success = true
          return IconPackExportResult(archive, hash, exported, skipped, 0, darkMode)
        }
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
  }

  /** Native modules already carry their own rendering, masking, animation and mode contracts.
   * Keep every byte and resource qualifier; targets report known exact names, not an allowlist. */
  private fun exportNativeModule(
    pack: IconPackDescriptor, targets: List<IconPackComponent>, darkMode: Boolean, size: Int,
    systemFallback: SystemIconFallback?, colorOsFallback: IconPackDescriptor?, installedOnly: Boolean,
    maskPolicy: IconMaskPolicy?, preservedPackages: Set<String>,
    recommendedMappings: RecommendedIconMappings, phoneCompanionExclusions: Set<String>, check: () -> Unit,
    onNativeCopyBytes: ((Long, Long) -> Unit)?,
  ): IconPackExportResult {
    val record = (pack.source as? IconPackSource.Imported)?.record ?: throw IconPackException("Missing native icon import")
    val directory = File(context.cacheDir, "icon-pack-exports")
    if (!directory.isDirectory && !directory.mkdirs()) throw IconPackException("Cannot create export staging directory")
    val archive = File.createTempFile("icons-native-", ".zip", directory)
    val nested = File.createTempFile("verify-", ".zip", directory)
    var success = false
    try {
      var index = IconPackArchive(limits).copyNativeModule(record, archive, nested, check, onNativeCopyBytes)
      if (preservedPackages.isNotEmpty()) {
        val components = preservedPackages.flatMap { pkg ->
          check()
          try {
            context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(
              (android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).toLong()))
              .activities.orEmpty().map { IconPackComponent(pkg, it.name) }
          } catch (_: android.content.pm.PackageManager.NameNotFoundException) { emptyList() }
        }
        val exclusions = PreservedIconResources.fromInstalled(context, preservedPackages, components, check)
        val replacements = ZipFile(archive).use { source ->
          source.getEntry(SystemIconFallbackManifest.PATH)?.let { item ->
            val previous = SystemIconFallbackManifest.decode(source.getInputStream(item).use {
              it.readNBytes(SystemIconFallbackManifest.MAX_BYTES + 1)
            })
            mapOf(SystemIconFallbackManifest.PATH to previous.copy(
              names = previous.names.filterNot(exclusions::containsName).toSet()).encode())
          }.orEmpty()
        }
        val filtered = File.createTempFile("icons-preserved-", ".zip", directory)
        try {
          RawIconArchive.write(archive, filtered, includes = { !exclusions.containsPath(it) },
            replacements = replacements, check = check, maxOutputBytes = limits.maxInputBytes)
          filtered.copyTo(archive, overwrite = true)
        } finally { filtered.delete() }
        index = index.copy(entries = index.entries.filterNot {
          it.nativeName?.let(exclusions::containsName) == true
        }, nativeIconsSha256 = sha256(archive, check))
      }
      val paletteNames = appendNativeMonet(archive, index.entries, targets, check)
      val family = NativeRecommendedIconCompletion.additions(context, pack, targets, size, maskPolicy,
        preservedPackages, recommendedMappings, phoneCompanionExclusions, archive, limits, check)
      if (family.isNotEmpty()) {
        val completed = File.createTempFile("icons-recommended-", ".zip", directory)
        try {
          RawIconArchive.write(archive, completed, replacements = family, check = check,
            maxOutputBytes = limits.maxInputBytes)
          completed.copyTo(archive, overwrite = true)
        } finally { completed.delete() }
        index = IconPackArchive(limits).inspect(archive, nested, checkCancelled = check)
      }
      val originalNames = index.entries.mapNotNull { it.nativeName }.toSet()
      val extra = if (colorOsFallback != null)
        appendColorOsFallbacks(archive, targets, originalNames, colorOsFallback,
          systemFallback ?: SystemIconFallback.DEFAULT, size, maskPolicy, preservedPackages, recommendedMappings,
          phoneCompanionExclusions + PhoneCompanionIcons(pack.entries).authoredTargets, check)
      else { if (systemFallback != null) appendNativeFallbacks(archive, targets, originalNames, systemFallback, size, maskPolicy, check); null }
      val aliases = appendNativeDefaultAliases(archive, targets, preservedPackages, check)
      val verified = if (systemFallback == null && colorOsFallback == null && paletteNames.isEmpty() && !aliases) index else IconPackArchive(limits).inspect(archive, nested,
        checkCancelled = check)
      val names = verified.entries.mapNotNull { it.nativeName }.toSet()
      val covered = targets.filter { target -> nativeLauncherIconNames(target).any { it in names } }
      val skipped = (targets - covered.toSet()).map { IconPackExportSkip(it, "No exact native filename; native aliases are not inferred") }
      check()
      success = true
      return IconPackExportResult(archive, verified.nativeIconsSha256!!, covered, skipped, names.size, darkMode,
        preservesNativeResources = true, colorOsPackages = extra?.first.orEmpty(),
        nativeAdaptationTargets = extra?.second.orEmpty(), fallbackPaletteNames = paletteNames)
    } finally {
      nested.delete()
      if (!success) archive.delete()
    }
  }

  private fun appendNativeDefaultAliases(
    archive: File, targets: List<IconPackComponent>, preservedPackages: Set<String>, check: () -> Unit,
  ): Boolean {
    val replacements = linkedMapOf<String, ByteArray>()
    val pm = context.packageManager
    val launchers = hashMapOf<String, Set<String>>()
    ZipFile(archive).use { zip ->
      val entries = zip.entries().asSequence().filterNot { it.isDirectory }.associateBy { it.name }
      val aliases = NativeDefaultAliasCompletion.plan(entries.keys, targets, preservedPackages, { target ->
        check()
        try {
          val activity = requireNotNull(target.className).let {
            if (it.startsWith('.')) target.packageName + it else it
          }
          val info = pm.getActivityInfo(android.content.ComponentName(target.packageName, activity),
            android.content.pm.PackageManager.ComponentInfoFlags.of(0))
          if (info.name != activity) null else {
            val enabled = launchers.getOrPut(target.packageName) {
              check()
              val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(target.packageName)
              pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                .take(5000).mapNotNull { it.activityInfo?.name }.toSet()
            }
            NativeDefaultAliasCompletion.Identity(info.packageName, info.applicationInfo.packageName,
              info.applicationInfo.icon, info.iconResource, activity in enabled)
          }
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null
        } catch (_: SecurityException) { null }
      }, limits, check)
      val sourceBytes = hashMapOf<String, ByteArray>()
      val expanded = entries.values.sumOf { it.size }
      val budget = minOf(NativeDefaultAliasCompletion.MAX_COPY_BYTES, limits.maxExpandedBytes - expanded)
      var copied = 0L
      for (alias in aliases) {
        check()
        val sizes = alias.paths.values.map { entries.getValue(it).size }
        if (sizes.any { it < 0 || it > limits.maxEntryBytes || it > budget } ||
          sizes.sum() > budget - copied) continue
        val complete = linkedMapOf<String, ByteArray>()
        for ((destination, source) in alias.paths) {
          check()
          complete[destination] = sourceBytes.getOrPut(source) {
            val entry = entries.getValue(source)
            val bytes = ByteArray(entry.size.toInt())
            zip.getInputStream(entry).use { input ->
              var offset = 0
              while (offset < bytes.size) {
                check()
                val count = input.read(bytes, offset, minOf(8192, bytes.size - offset))
                if (count <= 0) throw IconPackException("Truncated native alias resource")
                offset += count
              }
              check()
              if (input.read() != -1) throw IconPackException("Oversized native alias resource")
            }
            bytes
          }
        }
        replacements.putAll(complete)
        copied += sizes.sum()
      }
    }
    if (replacements.isEmpty()) return false
    val completed = File.createTempFile("icons-default-alias-", ".zip", archive.parentFile)
    try {
      RawIconArchive.write(archive, completed, replacements = replacements, check = check,
        maxOutputBytes = limits.maxInputBytes, limits = limits)
      check()
      completed.copyTo(archive, overwrite = true)
    } finally { completed.delete() }
    return true
  }

  /** Add event-driven Monet only for closed, pixel-proven pairs used by installed targets.
   * All other authored programs (including date/clock programs) are copied unchanged. */
  private fun appendNativeMonet(
    archive: File, entries: List<IconPackEntry>, targets: List<IconPackComponent>, check: () -> Unit,
  ): Set<String> {
    val targetNames = targets.flatMapTo(hashSetOf(), ::nativeLauncherIconNames)
    val candidates = entries.filter {
      (it.light as? IconPackArtwork.Archive)?.palettePair == true &&
        it.nativeName in targetNames
    }
    if (candidates.isEmpty()) return emptySet()
    val replacedResources = hashSetOf<String>()
    val paletteNames = linkedSetOf<String>()
    fun png(bitmap: Bitmap): ByteArray {
      check()
      return ByteArrayOutputStream().also {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot encode native mask" }
      }.toByteArray().also { check() }
    }
    SpoolIconEntries.open(archive.parentFile!!, limits).use { replacements ->
      ZipFile(archive).use { source ->
        val pathsByName = source.entries().asSequence().filterNot { it.isDirectory }
          .map { it.name }.filter { it.startsWith("fancy_icons/") }
          .groupBy { it.substringAfter('/').substringBefore('/') }
        for (entry in candidates) {
          check()
          val name = requireNotNull(entry.nativeName)
          val root = "fancy_icons/$name/"
          val dayPath = (entry.light as? IconPackArtwork.Archive)?.variants?.singleOrNull()?.path ?: continue
          val nightPath = (entry.dark as? IconPackArtwork.Archive)?.variants?.singleOrNull()?.path ?: continue
          val originalPaths = setOf(root + "manifest.xml", dayPath, nightPath)
          if (pathsByName[name].orEmpty().any { it !in originalPaths }) continue
          NativeMamlMonet.read(entry, check) { path ->
            val item = source.getEntry(path) ?: throw IconPackException("Missing paired image")
            if (item.size !in 1..4L * 1024 * 1024) byteArrayOf()
            else source.getInputStream(item).use { it.readNBytes(4 * 1024 * 1024 + 1) }
          }?.use { artwork ->
            // The source is fancy-only. Do not duplicate its two PNGs into additional static trees.
            val generated = NativePaletteIcon.entries(name, artwork.light, artwork.dark,
              png(artwork.foreground), png(artwork.background)).filterKeys { it.startsWith(root) }
            replacements.append(generated, check)
            replacedResources += originalPaths
            paletteNames += name
          }
        }
      }
      if (paletteNames.isEmpty()) return emptySet()
      val stage = File.createTempFile("native-monet-", ".zip", archive.parentFile)
      try {
        RawIconArchive.write(archive, stage,
          includes = { it !in replacedResources }, spooled = replacements, limits = limits,
          check = check, maxOutputBytes = limits.maxInputBytes)
        check()
        if (!stage.renameTo(archive)) throw IconPackException("Cannot publish native Monet staging")
      } finally { stage.delete() }
    }
    return paletteNames
  }

  /** Add only uncovered names; supplied native animations, night trees and artwork stay authoritative. */
  private fun appendColorOsFallbacks(archive: File, targets: List<IconPackComponent>, covered: Set<String>,
    fallback: IconPackDescriptor, style: SystemIconFallback, size: Int,
    maskPolicy: IconMaskPolicy?, preservedPackages: Set<String>, recommendedMappings: RecommendedIconMappings,
    phoneCompanionExclusions: Set<String>,
    check: () -> Unit): Pair<Set<String>, Set<IconPackComponent>> {
    require(fallback.colorOsProfile != null && fallback.format == IconPackFormat.CLASSIC_ZIP)
    val protected = java.util.zip.ZipFile(archive).use { source ->
      covered + nativeFallbackProtectedNames(source.entries().asSequence().map { it.name })
    }
    val missing = targets.filter { target -> nativeLauncherIconNames(target).none { it in protected } }
    if (missing.isEmpty()) return emptySet<String>() to emptySet<IconPackComponent>()
    // The outer export already expanded offline targets and resolved their ownership.
    val day = export(fallback, missing, false, size, style, installedOnly = true,
      maskPolicy = maskPolicy, preservedPackages = preservedPackages, recommendedMappings = recommendedMappings,
      phoneCompanionExclusions = phoneCompanionExclusions, check = check)
    var night: IconPackExportResult? = null
    val copy = File.createTempFile("native-coloros-fallback-", ".zip", archive.parentFile)
    try {
      night = export(fallback, missing, true, size, style, installedOnly = true,
        maskPolicy = maskPolicy, preservedPackages = preservedPackages, recommendedMappings = recommendedMappings,
        phoneCompanionExclusions = phoneCompanionExclusions, check = check)
      val additions = linkedMapOf<String, ByteArray>()
      val systemNames = linkedSetOf<String>()
      var perfectSource: String? = null
      java.util.zip.ZipFile(day.archive).use { light ->
        java.util.zip.ZipFile(night.archive).use { dark ->
          light.getEntry(SystemIconFallbackManifest.PATH)?.let { entry ->
            val manifest = SystemIconFallbackManifest.decode(light.getInputStream(entry).use { it.readBytes() })
            systemNames += manifest.names - protected
            perfectSource = manifest.perfectSource
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
            else NativeDayNightIcon.entries(name, first, second)
            additions.putAll(pair)
          }
        }
      }
      java.util.zip.ZipFile(archive).use { source ->
        val previous = source.getEntry(SystemIconFallbackManifest.PATH)?.let { entry ->
          SystemIconFallbackManifest.decode(source.getInputStream(entry).use { it.readBytes() })
        }
        require(previous == null || previous.style == style) { "Use the original pack to change a generated fallback" }
        for (path in additions.keys) {
          check()
          require(source.getEntry(path) == null) { "Fallback would overwrite supplied artwork: $path" }
        }
        RawIconArchive.write(archive, copy,
          replacements = additions + (SystemIconFallbackManifest.PATH to
            SystemIconFallbackManifest(style, systemNames + previous?.names.orEmpty(),
              previous?.nameMatching == true, colorOsFallback = true,
              perfectSource = previous?.perfectSource ?: perfectSource).encode()),
          check = check, maxOutputBytes = limits.maxInputBytes)
      }
      check()
      check(copy.renameTo(archive)) { "Cannot publish native ColorOS fallback copy" }
      val addedNames = nativeFallbackProtectedNames(additions.keys.asSequence())
      return (day.colorOsPackages + night.colorOsPackages).filterTo(linkedSetOf()) { it in addedNames } to
        (day.nativeAdaptationTargets + night.nativeAdaptationTargets).filterTo(linkedSetOf()) {
          nativeLauncherIconNames(it).none { name -> name in protected }
        }
    } finally { copy.delete(); day.archive.delete(); night?.archive?.delete() }
  }

  private fun appendNativeFallbacks(archive: File, targets: List<IconPackComponent>, covered: Set<String>,
    style: SystemIconFallback, size: Int, maskPolicy: IconMaskPolicy?, check: () -> Unit) {
    SystemIconFallbackRenderer(context, style, clipOutput = false, perfectOpener = perfectSources).use { renderer ->
      val shape = java.util.zip.ZipFile(archive).use { source ->
        val config = source.getEntry(NativeIconShapeConfig.PATH)?.let { entry ->
          require(entry.size in 0..NativeIconShapeConfig.MAX_BYTES.toLong())
          source.getInputStream(entry).use { it.readBytes() }
        }
        (maskPolicy ?: IconMaskPolicy.snapshot(context)).resolve(config,
          NativeIconShapeConfig.hasLegacyStyle(source.entries().asSequence().map { it.name }))
      }
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
      val additions = linkedMapOf<String, Pair<ByteArray, ByteArray>>()
      for ((pkg, group) in targets.groupBy { it.packageName }) {
        for (target in group) {
          check()
          if (nativeLauncherIconNames(target).any { it in protected }) continue
          fun render(dark: Boolean): ByteArray? {
            val raw = renderer.render(target, size, dark, check) ?: return null
            val bitmap = try { IconMaskPolicy.clip(raw, shape) } finally {
              // clip returns its input only when no shape was selected.
              if (shape != null) raw.recycle()
            }
            return try { ByteArrayOutputStream().also {
              check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray() } finally { bitmap.recycle() }
          }
          val day = render(false) ?: continue
          val night = if (style.isPerfect) render(true) ?: day else day
          val bytes = day to night
          nativeLauncherActivityName(target)?.let { name ->
            val previous = additions[name]
            require(previous == null || previous.first.contentEquals(day) && previous.second.contentEquals(night)) {
              "Native fallback filename collision: $name"
            }
            additions[name] = bytes
          }
          if (group.size == 1 || target.className == null) additions[pkg] = bytes
        }
      }
      renderer.validateCompletionSource()
      if (additions.isEmpty()) return
      val copy = File.createTempFile("native-fallback-", ".zip", archive.parentFile)
      try {
        val resources = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipFile(archive).use { source ->
          for ((name, bytes) in additions) {
            check()
            for ((prefix, image) in listOf("" to bytes.first, "nightmode/" to bytes.second)) {
              val path = "${prefix}res/drawable-xxhdpi/$name.png"
              require(source.getEntry(path) == null)
              resources[path] = image
            }
            for ((path, data) in NativeDayNightIcon.entries(name, bytes.first, bytes.second)) {
              if (!path.startsWith("fancy_icons/")) continue
              require(source.getEntry(path) == null)
              resources[path] = data
            }
          }
        }
        resources[SystemIconFallbackManifest.PATH] =
          SystemIconFallbackManifest(style, additions.keys, perfectSource = renderer.perfectSourceHash).encode()
        RawIconArchive.write(archive, copy, replacements = resources,
          check = check, maxOutputBytes = limits.maxInputBytes)
        check(copy.renameTo(archive)) { "Cannot publish native fallback copy" }
      } finally { copy.delete() }
    }
  }

  private class LimitedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
    private var count = 0L
    private fun reserve(bytes: Int) { count += bytes; if (count > limit) throw IconPackException("Export size limit exceeded") }
    override fun write(value: Int) { reserve(1); out.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); out.write(bytes, offset, length) }
  }
}
