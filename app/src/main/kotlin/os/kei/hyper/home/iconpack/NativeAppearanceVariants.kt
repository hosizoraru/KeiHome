package os.kei.hyper.home.iconpack

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Derives fixed light and dark copies of a generated, system-following icons archive.
 *
 * HyperOS Home ignores `nightmode/res` for ordinary icons, so a system-following archive gives
 * each differing pair a generated MAML selector. Every selector enters Home's MAML path during
 * launch and return transitions. The static copies remove only selectors that this generator
 * can reproduce byte for byte from the archive's own day and night PNGs. The dark copy then
 * places the night PNG at the default path. Authored programs (clock, calendar, weather),
 * system-frontend compatibility descriptors (including phone companions), icons that another
 * resource can also serve, and unrecognized groups stay unchanged.
 *
 * Exactly one copy is active at a time; the caller swaps them when the system appearance
 * changes. Palette selectors are flattened too; [Result.paletteFlattened] tells the caller that
 * the original archive is still required while the live palette is enabled.
 */
internal object NativeAppearanceVariants {
  enum class Role { PRIMARY, LIGHT, DARK }

  data class Result(val flattened: Int, val paletteFlattened: Int, val retained: Int)

  class NothingToFlatten : IllegalArgumentException("No generated appearance selectors to flatten")

  private const val FANCY = "fancy_icons/"
  private const val DAY = "res/drawable-xxhdpi/"
  private const val NIGHT = "nightmode/res/drawable-xxhdpi/"
  private const val NIGHT_ROOT = "nightmode/"
  private const val PNG = ".png"
  private val PROGRAMS = setOf("fancy_icons", "animating_icons", "layer_animating_icons")
  private const val MAX_MANIFEST_BYTES = 64 * 1024
  private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

  /** The palette-backed original keeps live Monet coloring; otherwise follow the system mode. */
  fun target(night: Boolean, paletteEnabled: Boolean, paletteFlattened: Boolean): Role = when {
    paletteEnabled && paletteFlattened -> Role.PRIMARY
    night -> Role.DARK
    else -> Role.LIGHT
  }

  fun write(
    source: File,
    light: File,
    dark: File,
    check: () -> Unit = {},
    limits: IconPackLimits = IconPackLimits(),
  ): Result {
    require(setOf(source.canonicalFile, light.canonicalFile, dark.canonicalFile).size == 3)
    val plan = ZipFile(source).use { zip -> plan(zip, check, limits) }
    if (plan.flattened.isEmpty()) throw NothingToFlatten()
    val excluded = plan.flattened.flatMapTo(HashSet()) { it.files }
    val includes = { path: String -> path !in excluded }
    RawIconArchive.write(source, light, includes = includes, check = check,
      maxOutputBytes = limits.maxInputBytes, limits = limits)
    RawIconArchive.write(source, dark, includes = includes,
      replacements = plan.flattened.associate { "$DAY${it.name}.png" to it.night },
      check = check, maxOutputBytes = limits.maxInputBytes, limits = limits)
    return Result(plan.flattened.size, plan.flattened.count { it.palette }, plan.retained)
  }

  private class Selector(val name: String, val files: Set<String>, val night: ByteArray, val palette: Boolean)
  private class Plan(val flattened: List<Selector>, val retained: Int)

  private fun plan(zip: ZipFile, check: () -> Unit, limits: IconPackLimits): Plan {
    require(zip.size() in 1..limits.maxEntries) { "Icon archive entry limit" }
    val entries = LinkedHashMap<String, ZipEntry>()
    val groups = LinkedHashMap<String, MutableSet<String>>()
    zip.entries().asSequence().forEach { entry ->
      check()
      require(entries.put(entry.name, entry) == null) { "Duplicate icon archive entry: ${entry.name}" }
      if (!entry.isDirectory && entry.name.startsWith(FANCY)) {
        val relative = entry.name.removePrefix(FANCY)
        val slash = relative.indexOf('/')
        if (slash > 0) groups.getOrPut(relative.substring(0, slash)) { linkedSetOf() } += entry.name
      }
    }
    fun read(name: String, limit: Int): ByteArray? {
      val entry = entries[name]?.takeIf { !it.isDirectory && it.size in 0..limit.toLong() } ?: return null
      return zip.getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream(entry.size.toInt().coerceAtLeast(32))
        val buffer = ByteArray(16 * 1024)
        while (true) {
          check()
          val count = input.read(buffer)
          if (count < 0) break
          require(output.size().toLong() + count <= limit) { "Icon archive entry changed while reading" }
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
    }
    // System frontends can ignore a flat override; their explicit descriptors stay authoritative.
    val systemNames = read(SystemIconFallbackManifest.PATH, SystemIconFallbackManifest.MAX_BYTES)
      ?.let { SystemIconFallbackManifest.decode(it).names }.orEmpty()
    val competing = competingNames(entries.values.filterNot { it.isDirectory }.map { it.name })
    val flattened = ArrayList<Selector>()
    var retained = 0
    for ((name, files) in groups) {
      check()
      val selector = if (name in systemNames || requiresDescriptor(name) || name in competing ||
        "$FANCY$name/manifest.xml" !in files) null
        else recognize(name, files, ::read)
      if (selector == null) retained++ else flattened += selector
    }
    return Plan(flattened, retained)
  }

  /**
   * Phone companions get a descriptor even for equal artwork ([NativeRecommendedIconCompletion]);
   * their frontends can ignore a flat PNG. Activity names start with the package name.
   */
  private fun requiresDescriptor(name: String) = RecommendedPackageIcons.PHONE_TARGETS.any { target ->
    name == target || name.startsWith("$target.") || name.startsWith("$target#")
  }

  /**
   * Names another resource can serve once the selector is gone: static layer pairs, other
   * densities, root PNGs, animated programs and night MAML. Home may prefer any of them to the
   * default PNG, which only the day/night pair of a flattened selector replaces.
   */
  private fun competingNames(paths: Collection<String>): Set<String> {
    val names = HashSet<String>()
    for (path in paths) {
      val night = path.startsWith(NIGHT_ROOT)
      val parts = path.removePrefix(NIGHT_ROOT).split('/')
      val name = when {
        parts.size == 1 -> parts[0].removeSuffix(PNG).takeIf { it.length < parts[0].length }
        parts[0] == "res" && parts.size >= 3 && parts[1].startsWith("drawable") ->
          if (parts.size > 3) parts[2]
          else parts[2].removeSuffix(PNG).takeIf {
            it.length < parts[2].length && path != "$DAY$it$PNG" && path != "$NIGHT$it$PNG"
          }
        parts.size >= 3 && parts[0] in PROGRAMS && (night || "${parts[0]}/" != FANCY) -> parts[1]
        else -> null
      }
      if (!name.isNullOrEmpty()) names += name
    }
    return names
  }

  private fun recognize(name: String, files: Set<String>, read: (String, Int) -> ByteArray?): Selector? {
    if (!validNativeIconName(name)) return null
    val day = read("$DAY$name.png", MAX_IMAGE_BYTES) ?: return null
    val night = read("$NIGHT$name.png", MAX_IMAGE_BYTES) ?: return null
    val group = files.associateWith { path ->
      read(path, if (path.endsWith("/manifest.xml")) MAX_MANIFEST_BYTES else MAX_IMAGE_BYTES) ?: return null
    }
    fun same(expected: Map<String, ByteArray>): Boolean {
      val fancy = expected.filterKeys { it.startsWith(FANCY) }
      return fancy.keys == group.keys && fancy.all { (path, bytes) -> bytes.contentEquals(group.getValue(path)) }
    }
    fun attempt(build: () -> Map<String, ByteArray>): Boolean = try { same(build()) }
      catch (_: IllegalArgumentException) { false } catch (_: IllegalStateException) { false }
    val root = "$FANCY$name"
    val mask = group["$root/mask.png"]
    val background = group["$root/bg.png"]
    if (mask == null && background == null) {
      for (legacyExpanded in listOf(false, true)) {
        if (attempt { NativeDayNightIcon.entries(name, day, night, legacyExpanded) }) {
          return Selector(name, files, night, palette = false)
        }
      }
      return null
    }
    if (mask == null || background == null) return null
    for (legacyExpanded in listOf(false, true)) for (legacyScalarBinders in listOf(false, true)) {
      if (attempt { NativePaletteIcon.entries(name, day, night, mask, background, legacyExpanded, legacyScalarBinders) }) {
        return Selector(name, files, night, palette = true)
      }
    }
    return null
  }
}
