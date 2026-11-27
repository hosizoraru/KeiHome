package os.kei.hyper.home.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.*

/** Adds native palette layers only to static artwork; authored programs and custom choices win. */
internal object PublishedGenericMonet {
  private const val MAX_IMAGE = 4 * 1024 * 1024
  private val flat = Regex("res/drawable(?:-[A-Za-z0-9]+)*/([^/]+)\\.png")
  private data class NativePair(val image: ByteArray, val foreground: IntArray?)

  fun write(source: File, output: File, enabled: Boolean,
    excluded: PreservedIconResources, insetFullBleed: Boolean = false,
    generatedPaletteNames: Set<String> = emptySet(), onProgress: (Int, Int) -> Unit = { _, _ -> },
    onWriteProgress: (Int, Int) -> Unit = { _, _ -> },
    check: () -> Unit = {}): Boolean {
    check()
    if (!enabled && !insetFullBleed) return false
    require(source.canonicalFile != output.canonicalFile && !output.exists())
    val replacedFancyPaths = hashSetOf<String>()
    SpoolIconEntries.open(output.absoluteFile.parentFile!!).use { replacements ->
      val modified = ZipFile(source).use { zip ->
        val paths = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
        fun read(path: String, limit: Int): ByteArray? {
          check()
          val entry = zip.getEntry(path) ?: return null
          if (entry.size !in 1..limit.toLong()) return null
          return zip.getInputStream(entry).use { it.readNBytes(limit + 1) }.takeIf { it.size <= limit }
        }
        apply(
          paths = paths,
          read = ::read,
          writeEntries = { layers -> replacements.append(layers, check) },
          replacedFancyPaths = replacedFancyPaths,
          enabled = enabled,
          excluded = excluded,
          insetFullBleed = insetFullBleed,
          generatedPaletteNames = generatedPaletteNames,
          onProgress = onProgress,
          check = check,
        )
      }
      if (!modified || replacements.isEmpty) return false
      RawIconArchive.write(source, output, spooled = replacements, check = check,
        includes = { it !in replacedFancyPaths },
        onProgress = onWriteProgress,
        maxOutputBytes = IconPackLimits().maxInputBytes)
      return true
    }
  }

  internal fun apply(
    paths: Set<String>,
    read: (String, Int) -> ByteArray?,
    writeEntries: (Map<String, ByteArray>) -> Unit,
    replacedFancyPaths: MutableSet<String>,
    enabled: Boolean,
    excluded: PreservedIconResources,
    insetFullBleed: Boolean = false,
    generatedPaletteNames: Set<String> = emptySet(),
    targets: List<IconPackComponent> = emptyList(),
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    check: () -> Unit = {},
  ): Boolean {
    check()
    if (!enabled && !insetFullBleed) return false
    val fancyGroups = paths.filter { it.startsWith("fancy_icons/") }
      .groupBy { it.split('/', limit = 3)[1] }
    val animatingGroups = paths.filter {
      it.startsWith("layer_animating_icons/") || it.startsWith("nightmode/layer_animating_icons/")
    }.groupBy { it.removePrefix("nightmode/").split('/', limit = 3)[1] }
    val nativeGroups = paths.filter { it.startsWith("res/drawable-xxhdpi/") &&
      !it.endsWith('/') && it.count { c -> c == '/' } == 3 }
      .groupBy { it.split('/')[2] }
    val nightGroups = paths.filter { it.startsWith("nightmode/res/drawable-xxhdpi/") &&
      !it.endsWith('/') && it.count { c -> c == '/' } == 4 }
      .groupBy { it.split('/')[3] }
    val protectedNames = hashSetOf<String>()
    for (path in paths) {
      when {
        path.startsWith("animating_icons/") ->
          path.split('/', limit = 3).getOrNull(1)?.let(protectedNames::add)
        path.startsWith("nightmode/fancy_icons/") ->
          path.split('/', limit = 4).getOrNull(2)?.let(protectedNames::add)
      }
    }
    val staticAnimatingNames = hashSetOf<String>()
    for ((name, groupPaths) in animatingGroups) {
      check()
      if (!enabled || isLiveDynamicAnimatingIcon(name, groupPaths, read)) {
        protectedNames += name
      } else {
        staticAnimatingNames += name
      }
    }
    // Single-activity foreign-class packages (e.g. com.neoruaa.meilox#com.ljyh.mei.MainActivity,
    // com.peetzweg.opendisplay#app.opendisplay.android.ui.MainActivity) need a package-name
    // MAML root (`fancy_icons/<pkg>/`) so HyperOS Launcher, SystemUI, and Share resolve Monet.
    val foreignPackageRoots = buildMap {
      val byPackage = targets.filter { it.className != null }.distinct().groupBy { it.packageName }
      for ((pkg, components) in byPackage) {
        if (components.size != 1) continue
        val activityName = nativeLauncherActivityName(components.single()) ?: continue
        if ('#' in activityName && validNativeIconName(pkg)) put(activityName, pkg)
      }
    }
    val shape by lazy {
      IconMaskPolicy.fromConfig(read(NativeIconShapeConfig.PATH, NativeIconShapeConfig.MAX_BYTES))
    }
    var insetCoverage: Pair<Int, IntArray>? = null
    fun paddingCoverage(size: Int): IntArray? {
      val mask = shape ?: return null
      insetCoverage?.takeIf { it.first == size }?.let { return it.second }
      check()
      val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      val pixels = try {
        Canvas(bitmap).drawPath(mask.pathForBounds(Rect(0, 0, size, size)),
          Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
      } finally { bitmap.recycle() }
      for (index in pixels.indices) pixels[index] = pixels[index] ushr 24
      return pixels.also { insetCoverage = size to it }
    }
    fun compositeLayerPaths(layerPaths: List<String>, nativeViewport: Boolean, wantForeground: Boolean): NativePair? {
      if (layerPaths.isEmpty()) return null
      val layers = mutableListOf<Bitmap>()
      try {
        for (layerPath in layerPaths) {
          val raw = read(layerPath, MAX_IMAGE) ?: return null
          val png = ImportedStaticPng.normalizeNearlySquare(raw, check = check) ?: return null
          layers += BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        }
        val size = layers.first().width
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
          val bounds = Rect(0, 0, size, size)
          val canvas = Canvas(output)
          val drawBounds = if (nativeViewport) NativeLayerViewport.bounds(bounds) else bounds
          val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
          layers.forEach { check(); canvas.drawBitmap(it, null, drawBounds, paint) }
          val clipped = IconMaskPolicy.clip(output, shape)
          val image = try {
            ByteArrayOutputStream().also {
              check()
              check(clipped.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
          } finally { if (clipped !== output) clipped.recycle() }
          val foreground = if (wantForeground && layers.size >= 2) {
            output.eraseColor(0)
            for (layer in layers.drop(1)) { check(); canvas.drawBitmap(layer, null, drawBounds, paint) }
            val clippedFg = IconMaskPolicy.clip(output, shape)
            try {
              IntArray(size * size).also { clippedFg.getPixels(it, 0, size, 0, 0, size, size) }
            } finally { if (clippedFg !== output) clippedFg.recycle() }
          } else null
          return NativePair(image, foreground)
        } finally { output.recycle() }
      } finally { layers.forEach(Bitmap::recycle) }
    }
    fun nativePair(name: String, prefix: String): NativePair? =
      compositeLayerPaths(
        (0..1).map { "${prefix}res/drawable-xxhdpi/$name/$it.png" },
        nativeViewport = true,
        wantForeground = prefix.isEmpty(),
      )
    fun animatingQuietPair(name: String, prefix: String): NativePair? {
      val base = "${prefix}layer_animating_icons/$name/"
      val config = read(base + "config.xml", 64 * 1024) ?: return null
      val availableLayers = (0 until NativeLayerConfig.MAX_LAYERS)
        .map(Int::toString).filterTo(linkedSetOf()) { layer ->
          "$base$layer/quiet/quietImage.png" in paths || "$base$layer/fancy/manifest.xml" in paths
        }
      val layerConfig = try {
        NativeLayerConfig.parse(config, IconPackLimits(maxXmlBytes = 64 * 1024), check, availableLayers)
      } catch (_: IconPackException) { null } ?: return null
      for (quiet in layerConfig.quietSnapshots(base) { it in paths }) {
        compositeLayerPaths(quiet.paths, nativeViewport = quiet.nativeLayers, wantForeground = prefix.isEmpty())
          ?.let { return it }
      }
      return null
    }
    val candidates = paths.mapNotNull { path ->
      flat.matchEntire(path)?.groupValues?.get(1)?.takeIf {
        validNativeIconName(it) && !excluded.containsName(it) &&
          !it.startsWith("icon_") && !it.endsWith(".9")
      }?.let { it to path }
    }.groupBy({ it.first }, { it.second }).toMutableMap()
    val layeredNames = if (enabled) fancyGroups.keys + nativeGroups.keys + staticAnimatingNames else generatedPaletteNames
    for (name in layeredNames) {
      if (validNativeIconName(name) && !excluded.containsName(name)) candidates.putIfAbsent(name, emptyList())
    }
    val writtenPackageRoots = hashSetOf<String>()
    var modified = false
    var processed = 0
    for ((name, variants) in candidates) {
      check()
      onProgress(processed++, candidates.size)
      val root = "fancy_icons/$name/"
      if (name in protectedNames) continue
      val manifest = read(root + "manifest.xml", 64 * 1024)
      if (manifest == null && name in fancyGroups) continue
      // Only export-owned, verified palette trees enter this path. A matching
      // template alone must not opt imported authored programs into resizing.
      if (insetFullBleed && name in generatedPaletteNames && manifest != null &&
        fancyGroups[name].orEmpty().all { it in setOf(
          root + "manifest.xml", root + "day.png", root + "night.png", root + "mask.png", root + "bg.png") }) {
        val padded = insetGeneratedPalette(name, manifest, read, ::paddingCoverage, check)
        if (padded != null) {
          writeEntries(mapOf(root + "mask.png" to padded))
          modified = true
          continue
        }
      }
      if (!enabled) continue
      val pair = if (manifest != null) {
        try { NativeMamlPair.parse(manifest, check) } catch (_: IconPackException) { null } ?: continue
      } else null
      // Unknown assets may be referenced by an authored program; never replace that tree.
      if (pair != null && fancyGroups[name].orEmpty().any {
          it !in setOf(root + "manifest.xml", root + pair.light, root + pair.dark) }) continue
      val path = variants.sorted().firstOrNull { "xxhdpi" in it } ?: variants.sorted().firstOrNull()
      // Native pairs outrank their flat aliases. Unknown/incomplete sets stay untouched.
      val useNativePair = pair == null && name in nativeGroups &&
        nativeGroups[name]!!.toSet() == (0..1).map { "res/drawable-xxhdpi/$name/$it.png" }.toSet()
      if (pair == null && name in nativeGroups && !useNativePair) continue
      if (pair == null && nightGroups[name]?.any {
          it !in (0..1).map { index -> "nightmode/res/drawable-xxhdpi/$name/$index.png" }
        } == true) continue
      val nativeDay = when {
        useNativePair -> nativePair(name, "") ?: continue
        pair == null && name in staticAnimatingNames -> animatingQuietPair(name, "")
        else -> null
      }
      val sourceDay = nativeDay?.image
        ?: read(pair?.let { root + it.light } ?: path ?: continue, MAX_IMAGE) ?: continue
      val nightPath = pair?.let { root + it.dark } ?: path?.let { "nightmode/$it" }
      val sourceNight = when {
        pair == null && name in nightGroups ->
          nativePair(name, "nightmode/")?.image ?: nightPath?.let { read(it, MAX_IMAGE) } ?: sourceDay
        pair == null && name in staticAnimatingNames ->
          animatingQuietPair(name, "nightmode/")?.image ?: nightPath?.let { read(it, MAX_IMAGE) } ?: sourceDay
        else -> nightPath?.let { read(it, MAX_IMAGE) } ?: sourceDay
      }
      val day = ImportedStaticPng.normalizeNearlySquare(sourceDay, check = check) ?: continue
      val night = if (sourceNight === sourceDay) day
        else ImportedStaticPng.normalizeNearlySquare(sourceNight, check = check) ?: continue
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(day, 0, day.size, bounds)
      if (bounds.outWidth !in 32..512 || bounds.outHeight != bounds.outWidth ||
        bounds.outMimeType != "image/png") continue
      val nightBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(night, 0, night.size, nightBounds)
      if (nightBounds.outWidth != bounds.outWidth || nightBounds.outHeight != bounds.outHeight ||
        nightBounds.outMimeType != "image/png") continue
      val bitmap = BitmapFactory.decodeByteArray(day, 0, day.size) ?: continue
      try {
        val size = bitmap.width
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val plate by lazy {
          val maskAlpha = paddingCoverage(size)
          if (maskAlpha != null) {
            IntArray(pixels.size) { i ->
              if (i % size == 0) check()
              val alpha = minOf(pixels[i] ushr 24, maskAlpha[i])
              (alpha shl 24) or 0x00ffffff
            }
          } else {
            GenericMonetMask.background(pixels, size, check)
          }
        }
        val extracted = nativeDay?.foreground?.let {
          GenericMonetMask.layeredForeground(it, pixels, plate, size, check)
        } ?: GenericMonetMask.foreground(pixels, size, plate, check) ?: continue
        val foreground = if (insetFullBleed)
          GenericMonetMask.insetFullBleed(extracted, size, paddingCoverage(size), check) else extracted
        fun png(colors: IntArray): ByteArray {
          check()
          val mask = Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
          return try {
            ByteArrayOutputStream().also {
              check(mask.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
          } finally { mask.recycle() }
        }
        val maskPng = png(foreground)
        val platePng = png(plate)
        val layers = NativePaletteIcon.entries(name, day, night, maskPng, platePng)
          .filterKeys { it.startsWith(root) }.toMutableMap()
        // Keep alias-free native artwork alias-free. If either canonical flat
        // alias exists, publish both aliases from the same day/night pair.
        val dayFallback = "res/drawable-xxhdpi/$name.png"
        val nightFallback = "nightmode/$dayFallback"
        if (dayFallback in paths || nightFallback in paths) {
          layers[dayFallback] = day
          layers[nightFallback] = night
        }
        // Mirror single-activity foreign-class icons onto their package-name MAML root so
        // HyperOS Launcher, SystemUI, Settings, Sidebar, and Share all resolve the Monet layers.
        val pkgRoot = foreignPackageRoots[name]
        if (pkgRoot != null && pkgRoot != name && pkgRoot !in writtenPackageRoots &&
          pkgRoot !in protectedNames && !excluded.containsName(pkgRoot) &&
          pkgRoot !in fancyGroups && (pkgRoot !in candidates || pkgRoot in staticAnimatingNames)) {
          layers.putAll(NativePaletteIcon.entries(pkgRoot, day, night, maskPng, platePng))
          animatingGroups[pkgRoot]?.let(replacedFancyPaths::addAll)
          writtenPackageRoots += pkgRoot
        }
        writeEntries(layers)
        replacedFancyPaths.addAll(fancyGroups[name].orEmpty())
        animatingGroups[name]?.let(replacedFancyPaths::addAll)
        modified = true
      } finally { bitmap.recycle() }
    }
    onProgress(processed, candidates.size)
    return modified
  }

  /**
   * Protects live-data dynamic icons (Clock, Calendar, Weather, or any animating manifest
   * referencing live system variables) while allowing static bounce-only `layer_animating_icons`
   * (Telegram, WeChat, QQ, music/map apps, etc.) to participate in Monet completion.
   */
  internal fun isLiveDynamicAnimatingIcon(
    name: String,
    groupPaths: List<String>,
    read: (String, Int) -> ByteArray?,
  ): Boolean {
    val pkg = name.substringBefore('#')
    if (LIVE_DYNAMIC_PREFIXES.any { pkg == it || pkg.startsWith("$it.") }) return true
    for (path in groupPaths) {
      if (!path.endsWith(".xml") || path.endsWith("/config.xml")) continue
      val xml = read(path, 64 * 1024)?.toString(Charsets.UTF_8) ?: continue
      if (LIVE_MAML_MARKERS.any { xml.contains(it, ignoreCase = true) }) return true
    }
    return false
  }

  private val LIVE_DYNAMIC_PREFIXES = listOf(
    "com.android.deskclock",
    "com.android.calendar",
    "com.xiaomi.calendar",
    "com.miui.weather2",
  )

  private val LIVE_MAML_MARKERS = listOf(
    "ContentProviderBinder",
    "VariableBinders",
    "#hour",
    "#minute",
    "#second",
    "#date",
    "#day_of_week",
    "#year",
    "#month",
    "#weather",
    "time_format",
  )

  private fun insetGeneratedPalette(name: String, manifest: ByteArray,
    read: (String, Int) -> ByteArray?, coverage: (Int) -> IntArray?, check: () -> Unit): ByteArray? {
    val root = "fancy_icons/$name/"
    val day = read(root + "day.png", MAX_IMAGE) ?: return null
    val night = read(root + "night.png", MAX_IMAGE) ?: day
    val mask = read(root + "mask.png", MAX_IMAGE) ?: return null
    val background = read(root + "bg.png", MAX_IMAGE) ?: return null
    if (!NativePaletteIcon.recognizes(name, manifest, day, night, mask, background)) return null
    val size = GeneratedIconPng.squareSize(mask)
    check()
    val bitmap = BitmapFactory.decodeByteArray(mask, 0, mask.size) ?: return null
    try {
      val pixels = IntArray(size * size)
      bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
      val padded = GenericMonetMask.insetFullBleed(pixels, size, coverage(size), check)
      if (padded === pixels) return null
      val output = Bitmap.createBitmap(padded, size, size, Bitmap.Config.ARGB_8888)
      return try {
        check()
        ByteArrayOutputStream().also {
          require(output.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray().also { check() }
      } finally { output.recycle() }
    } finally { bitmap.recycle() }
  }

  /**
   * Generates native palette icons for installed launcher apps that are not covered by the
   * theme archive at all. HyperOS sets `DeviceConfig.isDefaultIcon = false` whenever a custom
   * icon pack is active, which disables the launcher's native APK monochrome path for every
   * uncovered app. Generating verified `NativePaletteIcon` entries (preferring each app's
   * official usable `<monochrome>` layer, falling back to `GenericMonetMask`) restores Monet
   * coverage across Launcher and all cross-surface consumers while preserving real light/dark
   * original artwork when Monet is off.
   */
  internal fun applyUncoveredInstalled(
    context: Context,
    paths: Set<String>,
    read: (String, Int) -> ByteArray?,
    writeEntries: (Map<String, ByteArray>) -> Unit,
    targets: List<IconPackComponent>,
    excluded: PreservedIconResources,
    insetFullBleed: Boolean = false,
    check: () -> Unit = {},
  ): Boolean {
    check()
    if (targets.isEmpty()) return false
    val coveredOwners = hashSetOf<String>()
    for (path in paths) {
      val owner = CustomIconResources.owner(path)
      if (owner != null) coveredOwners += owner
    }
    val shape = IconMaskPolicy.fromConfig(read(NativeIconShapeConfig.PATH, NativeIconShapeConfig.MAX_BYTES))
      ?: OriginalIconShape.mask
    val size = 192
    val maskAlpha = run {
      val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      val pixels = try {
        Canvas(bitmap).drawPath(shape.pathForBounds(Rect(0, 0, size, size)),
          Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
      } finally { bitmap.recycle() }
      for (i in pixels.indices) pixels[i] = pixels[i] ushr 24
      pixels
    }
    fun encodePng(bitmap: Bitmap): ByteArray {
      check()
      return ByteArrayOutputStream().also {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }.toByteArray()
    }
    fun encodeMask(colors: IntArray): ByteArray {
      check()
      val mask = Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
      return try { encodePng(mask) } finally { mask.recycle() }
    }

    val targetsByPackage = targets.filter { it.className != null }.distinct().groupBy { it.packageName }
    val written = hashSetOf<String>()
    var modified = false
    for (target in targets) {
      check()
      if (excluded.containsName(target.packageName)) continue
      // Only inspect and emit package-owned names (pkg#foreignClass, pkg.Activity, pkg).
      // Never include bare foreign class names (e.g. org.telegram.messenger.DefaultIcon on
      // org.telegram.messenger.beta), which belong to another package and would collide.
      val ownedAliases = listOfNotNull(
        nativeLauncherActivityName(target),
        target.packageName,
      ).distinct().filter(::validNativeIconName)
      if (ownedAliases.isEmpty()) continue
      if (ownedAliases.any { it in coveredOwners || excluded.containsName(it) }) continue
      val packageTargets = targetsByPackage[target.packageName].orEmpty()
      val namesToWrite = if (packageTargets.size > 1) {
        listOfNotNull(nativeLauncherActivityName(target)?.takeIf(::validNativeIconName))
      } else {
        ownedAliases
      }.filterNot { it in written }
      if (namesToWrite.isEmpty()) continue

      val dayBitmap = OriginalAppIcons.render(context, target, size, dark = false, check = check, mask = shape)
        ?: continue
      val nightBitmap = OriginalAppIcons.render(context, target, size, dark = true, check = check, mask = shape)
      try {
        val dayPixels = IntArray(size * size).also { dayBitmap.getPixels(it, 0, size, 0, 0, size, size) }
        val plate = IntArray(dayPixels.size) { i ->
          if (i % size == 0) check()
          (minOf(dayPixels[i] ushr 24, maskAlpha[i]) shl 24) or 0x00ffffff
        }
        val monoBitmap = OriginalAppIcons.renderMonochromeMask(context, target, size, dark = false, check = check)
        val extracted = if (monoBitmap != null) {
          try {
            val monoPixels = IntArray(size * size).also { monoBitmap.getPixels(it, 0, size, 0, 0, size, size) }
            IntArray(monoPixels.size) { i ->
              if (i % size == 0) check()
              (minOf(monoPixels[i] ushr 24, plate[i] ushr 24) shl 24) or 0x00ffffff
            }
          } finally { monoBitmap.recycle() }
        } else {
          val fgBitmap = OriginalAppIcons.renderAdaptiveForeground(context, target, size, dark = false, mask = shape, check = check)
          val layered = if (fgBitmap != null) {
            try {
              val fgPixels = IntArray(size * size).also { fgBitmap.getPixels(it, 0, size, 0, 0, size, size) }
              GenericMonetMask.layeredForeground(fgPixels, dayPixels, plate, size, check)
            } finally { fgBitmap.recycle() }
          } else null
          layered ?: GenericMonetMask.foreground(dayPixels, size, plate, check) ?: continue
        }
        val foreground = if (insetFullBleed)
          GenericMonetMask.insetFullBleed(extracted, size, maskAlpha, check) else extracted
        val dayBytes = encodePng(dayBitmap)
        val nightBytes = if (nightBitmap != null && !nightBitmap.sameAs(dayBitmap)) encodePng(nightBitmap) else dayBytes
        val maskBytes = encodeMask(foreground)
        val plateBytes = encodeMask(plate)
        val entries = linkedMapOf<String, ByteArray>()
        for (name in namesToWrite) {
          entries.putAll(NativePaletteIcon.entries(name, dayBytes, nightBytes, maskBytes, plateBytes))
          written += name
          coveredOwners += name
        }
        writeEntries(entries)
        modified = true
      } finally {
        dayBitmap.recycle()
        nightBitmap?.recycle()
      }
    }
    return modified
  }
}
