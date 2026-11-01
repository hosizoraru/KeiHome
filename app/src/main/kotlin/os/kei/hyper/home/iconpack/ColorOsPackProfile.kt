package os.kei.hyper.home.iconpack

import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

/** Local, resource-only conversion metadata; it never grants native import privileges. */
internal data class ColorOsPackProfile(
  val file: File,
  val maskPath: String,
  val icons: Map<IconPackComponent, ColorOsIconSource>,
) {
  fun source(target: IconPackComponent): ColorOsIconSource? =
    icons[target] ?: icons[IconPackComponent(target.packageName)]

  fun read(path: String, check: () -> Unit = {}): ByteArray =
    IconPackArchive.readEntry(file, path, 4 * 1024 * 1024, check)

  companion object {
    fun load(record: StoredIconPack, check: () -> Unit): ColorOsPackProfile? {
      if (record.format != IconPackFormat.CLASSIC_ZIP) return null
      if (ZipFile(record.originalFile).use { it.getEntry("coloros-source-manifest.json") == null }) return null
      val root = JSONObject(String(IconPackArchive.readEntry(record.originalFile,
        "coloros-source-manifest.json", 4 * 1024 * 1024, check), Charsets.UTF_8))
      val fallback = root.optJSONObject("fallback") ?: return null
      if (root.optInt("schema_version") != 1 || root.optString("style") != "classic" ||
        fallback.optString("kind") != "coloros-classic-v1" ||
        fallback.optString("mask") != "coloros-mask.png") return null
      val icons = linkedMapOf<IconPackComponent, ColorOsIconSource>()
      val rows = root.getJSONArray("entries")
      require(rows.length() <= 10000)
      repeat(rows.length()) { index ->
        check()
        val row = rows.getJSONObject(index)
        val component = IconPackComponent.parse(row.getString("component")) ?: return@repeat
        val mono = row.optJSONObject("monochrome")?.optString("path")?.takeIf {
          it.matches(Regex("coloros-monochrome/[a-z0-9_]+\\.png"))
        }
        val dynamicNames = row.optJSONObject("dynamic_source")?.optJSONArray("icon_names")
        val dynamic = dynamicNames?.let { names ->
          (0 until names.length()).map { names.getString(it) }.singleOrNull()
        }
        icons[component] = ColorOsIconSource(mono, dynamic, dynamicNames != null && dynamicNames.length() > 0)
      }
      return ColorOsPackProfile(record.originalFile, "coloros-mask.png", icons)
    }
  }
}

internal data class ColorOsIconSource(
  val monochromePath: String?,
  val dynamicName: String?,
  val requiresLiveIcon: Boolean,
)
