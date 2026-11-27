package os.kei.hyper.home.status

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.ColorOsFallbackRenderer
import os.kei.hyper.home.iconpack.CustomIconResources
import os.kei.hyper.home.iconpack.ExtraIcon
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackException
import os.kei.hyper.home.iconpack.NativeDayNightIcon
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.NativeMamlPair
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.iconpack.nativeLauncherIconNames
import os.kei.hyper.home.iconpack.validNativeIconName

/** Replaces the night branch of static icons while keeping existing day artwork and palette masks. */
internal object PublishedExtraIcons {
  private const val MAX_IMAGE = 4 * 1024 * 1024
  private const val DRAWABLE = "res/drawable-xxhdpi/"
  private val paletteFiles = setOf("manifest.xml", "day.png", "night.png", "mask.png", "bg.png")
  private val ordinary = Regex("(?:res/drawable(?:-[A-Za-z0-9]+)*/)?([^/]+)\\.png")

  fun replacements(
    context: Context, archive: ZipFile, targets: List<IconPackComponent>, packages: Set<String>,
    check: () -> Unit,
  ): Map<String, ByteArray> {
    val paths = archive.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
    return replacements(context, paths, { path, limit -> archive.readBounded(path, limit) }, targets, packages, check)
  }

  fun replacements(
    context: Context,
    paths: Set<String>,
    read: (String, Int) -> ByteArray?,
    targets: List<IconPackComponent>,
    packages: Set<String>,
    check: () -> Unit,
  ): Map<String, ByteArray> {
    if (packages.isEmpty()) return emptyMap()
    val selected = ExtraIcon.entries.filter { it.packageName in packages }
      .associateWith { icon -> targets.filter(icon::accepts) }
      .filterValues { it.isNotEmpty() }
    if (selected.isEmpty()) return emptyMap()
    val renderer = ColorOsFallbackRenderer(context)
    val byOwner = paths.groupBy(CustomIconResources::owner)
    val shape = IconMaskPolicy.fromConfig(read(NativeIconShapeConfig.PATH, NativeIconShapeConfig.MAX_BYTES))
      ?: IconMaskPolicy.hyperOs4Preview().resolve(null)
    return buildMap {
      for ((icon, applications) in selected) {
        val written = hashSetOf<String>()
        for (target in applications) {
          val aliases = nativeLauncherIconNames(target)
          val existing = aliases.filter { it in byOwner }
          val names = existing.ifEmpty { listOf(aliases.first()) }
            .filterNot(written::contains)
          val rendered = mutableMapOf<Int, ByteArray?>()
          val plans = names.map { name ->
            plan(read, name, byOwner[name].orEmpty(), check) {
              renderer.renderExtra(icon, target, 192, shape, original = true, check = check)?.toPng()
            }
          }
          // A component must keep authored programs reachable through all of its aliases.
          if (plans.any { it == null }) continue
          for (plan in plans.filterNotNull()) {
            if (plan.size !in rendered) {
              rendered[plan.size] = renderer.renderExtra(icon, target, plan.size, shape, check = check)?.toPng()
            }
            val dark = rendered[plan.size] ?: continue
            val entries = when {
              plan.palette != null -> {
                val (mask, background) = plan.palette
                NativePaletteIcon.entries(plan.name, plan.day, dark, mask, background,
                  legacyExpanded = "fancy_icons/${plan.name}/night.png" in paths,
                  legacyScalarBinders = plan.legacyScalarBinders)
              }
              plan.nightPath != null -> mapOf("nightmode/$DRAWABLE${plan.name}.png" to dark, plan.nightPath to dark)
              else -> NativeDayNightIcon.entries(plan.name, plan.day, dark)
            }
            putAll(entries.filterKeys { it.startsWith("fancy_icons/") || it !in paths || it.startsWith("nightmode/") })
            for (path in plan.flat) put("nightmode/${path.removePrefix("nightmode/")}", dark)
            written.add(plan.name)
          }
        }
      }
    }
  }

  private data class Plan(val name: String, val day: ByteArray, val size: Int, val flat: List<String>,
    val nightPath: String?, val palette: Pair<ByteArray, ByteArray>?, val legacyScalarBinders: Boolean)

  private fun plan(read: (String, Int) -> ByteArray?, name: String, owned: List<String>, check: () -> Unit,
    original: () -> ByteArray?): Plan? {
    check()
    if (!validNativeIconName(name)) return null
    val flat = owned.filter { ordinary.matches(it.removePrefix("nightmode/")) }
    val root = "fancy_icons/$name/"
    val fancy = owned.filter { it.startsWith(root) }
    if (owned.any { it !in flat && it !in fancy }) return null
    val manifest = read(root + "manifest.xml", 32 * 1024)
    val authoredPair = try { manifest?.let { NativeMamlPair.parse(it, check) } }
      catch (_: IconPackException) { null }
    val day = read(root + (authoredPair?.light ?: "day.png"), MAX_IMAGE)
      ?: read("$DRAWABLE$name.png", MAX_IMAGE)
      ?: flat.firstOrNull { !it.startsWith("nightmode/") }?.let { read(it, MAX_IMAGE) }
      ?: (if (owned.isEmpty()) original() else null) ?: return null
    val size = try { NativeDayNightIcon.pngSize(day) } catch (_: IllegalArgumentException) { return null }
    if (size !in 32..512) return null
    val compact = manifest?.contentEquals(NativeDayNightIcon.manifest(size, true)) == true
    val pair = authoredPair ?: if (manifest?.contentEquals(NativeDayNightIcon.manifest(size, false)) == true)
      NativeMamlPair("day.png", "night.png") else null
    val mask = read(root + "mask.png", MAX_IMAGE)
    val background = read(root + "bg.png", MAX_IMAGE)
    val palette = if (manifest != null && mask != null && background != null &&
      fancy.all { it.removePrefix(root) in paletteFiles } &&
      NativePaletteIcon.recognizes(name, manifest, day,
        read(root + "night.png", MAX_IMAGE) ?: day, mask, background)) mask to background else null
    val compactResources = compact && fancy.all { it.removePrefix(root) in setOf("day.png", "manifest.xml") }
    if (fancy.isNotEmpty() && palette == null && !compactResources &&
      (pair == null || fancy.any { it.removePrefix(root) !in setOf("manifest.xml", pair.light, pair.dark) })) return null
    // Compact selectors share one image. Adding a dark override needs a new
    // selector and night image, while the existing day image stays intact.
    val nightPath = pair?.takeIf { it.light != it.dark }?.let { root + it.dark }
    return Plan(name, day, size, flat, nightPath, palette,
      palette != null && manifest!!.decodeToString().contains("#palette_enabled"))
  }

  private fun ZipFile.readBounded(path: String, limit: Int): ByteArray? {
    val entry = getEntry(path) ?: return null
    if (entry.size !in 1..limit.toLong()) return null
    return getInputStream(entry).use { it.readNBytes(limit + 1) }.takeIf { it.size <= limit }
  }

  private fun Bitmap.toPng(): ByteArray = try {
    ByteArrayOutputStream().also { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
  } finally {
    recycle()
  }
}
