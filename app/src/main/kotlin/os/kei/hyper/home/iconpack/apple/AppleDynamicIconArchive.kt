package os.kei.hyper.home.iconpack.apple

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.ImageArchiveZip
import os.kei.hyper.home.iconpack.GeneratedIconPng
import os.kei.hyper.home.iconpack.NativeAppleDynamicIcon
import os.kei.hyper.home.iconpack.validDottedName

/** A bounded declarative index. Imported XML, expressions, and scripts are never executed. */
internal object AppleDynamicIconArchive {
  const val INDEX = "keihome-apple-dynamic.json"
  private const val ROOT = "apple-dynamic-assets"
  private fun leaves(kind: NativeAppleDynamicIcon.Kind) = listOf("day.png", "night.png") +
    if (kind == NativeAppleDynamicIcon.Kind.CLOCK) listOf("hour.png", "minute.png", "second.png",
      "night_hour.png", "night_minute.png", "night_second.png") else emptyList()

  suspend fun write(output: ZipOutputStream,
    assignments: Map<String, Pair<NativeAppleDynamicIcon.Kind, String>>,
    options: AppleIconOptions, artwork: AppleDynamicArtwork) {
    if (assignments.isEmpty()) return
    require(assignments.size <= 2000)
    val items = JSONObject()
    assignments.toSortedMap().forEach { (pkg, value) ->
      require(validDottedName(pkg) && value.second.matches(Regex("[0-9]+_[a-z]{2}")))
      items.put(pkg, JSONObject().put("kind", value.first.name).put("resource", value.second))
    }
    ImageArchiveZip.writeBytes(output, INDEX,
      JSONObject().put("schema", 1).put("items", items).toString().toByteArray(), time = 0)
    for (kind in assignments.values.map { it.first }.distinct()) {
      currentCoroutineContext().ensureActive()
      val assets = artwork.assets(kind, options)
      for ((leaf, bytes) in assets) {
        currentCoroutineContext().ensureActive()
        ImageArchiveZip.writeBytes(output, "$ROOT/${kind.name.lowercase()}/$leaf", bytes, time = 0)
      }
    }
  }

  fun entries(pack: IconPackDescriptor, targets: List<IconPackComponent>, installedOnly: Boolean,
    appearance: IconPackAppearance, check: () -> Unit): Map<String, ByteArray> {
    val file = when (val source = pack.source) {
      is IconPackSource.Generated -> source.file
      is IconPackSource.Imported -> source.record.originalFile
      else -> return emptyMap()
    }
    val marker = ZipFile(file).use { zip ->
      if (zip.getEntry(INDEX) == null) return emptyMap()
      IconPackArchive.readEntry(file, INDEX, 512 * 1024, check)
    }
    val metadata = JSONObject(marker.toString(Charsets.UTF_8))
    require(metadata.getInt("schema") == 1)
    val items = metadata.getJSONObject("items")
    require(items.length() in 1..2000)
    val selected = (targets + if (installedOnly) emptyList()
      else pack.entries.mapNotNull { it.component }).distinct()
    val shared = selected.filter { it.className != null }.groupBy { it.className }
      .filterValues { values -> values.map { it.packageName }.distinct().size > 1 }.keys.filterNotNull().toSet()
    val sourceAssets = mutableMapOf<NativeAppleDynamicIcon.Kind, Map<String, ByteArray>>()
    val result = linkedMapOf<String, ByteArray>()
    var total = 0L
    for (target in selected) {
      check()
      val item = items.optJSONObject(target.packageName) ?: continue
      require(validDottedName(target.packageName) && pack.entries.any {
        it.component?.packageName == target.packageName
      })
      val kind = NativeAppleDynamicIcon.Kind.valueOf(item.getString("kind"))
      val resource = item.getString("resource")
      require(resource.matches(Regex("[0-9]+_[a-z]{2}")))
      val assets = sourceAssets.getOrPut(kind) {
        leaves(kind).associateWith { leaf ->
          IconPackArchive.readEntry(file, "$ROOT/${kind.name.lowercase()}/$leaf", 8 * 1024 * 1024, check)
        }
      }.toMutableMap()
      assets["fallback.png"] = fallbackSnapshot(IconPackArchive.readEntry(file,
        "res/drawable-nodpi/apple_$resource.png", 8 * 1024 * 1024, check))
      assets["fallback-night.png"] = fallbackSnapshot(IconPackArchive.readEntry(file,
        "res/drawable-night-nodpi/apple_$resource.png", 8 * 1024 * 1024, check))
      val fixed = when (appearance) {
        IconPackAppearance.SYSTEM -> null
        IconPackAppearance.DARK -> true
        else -> false
      }
      val aliases = listOfNotNull(target.packageName, target.className?.let { "${target.packageName}_$it" },
        target.className?.takeUnless { it in shared })
      for (name in aliases.distinct()) {
        if ("fancy_icons/$name/manifest.xml" in result) continue
        val entries = NativeAppleDynamicIcon.entries(name, kind, assets, fixed,
          appearance == IconPackAppearance.DIM_AT_NIGHT)
        total += entries.values.sumOf { it.size.toLong() }
        require(total <= 64L * 1024 * 1024) { "Apple dynamic icon resources exceed 64 MiB" }
        result.putAll(entries)
      }
    }
    return result
  }

  private fun fallbackSnapshot(bytes: ByteArray): ByteArray {
    GeneratedIconPng.validate(bytes, 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth == bounds.outHeight && bounds.outWidth in 1..1024)
    if (bounds.outWidth <= 512) return bytes
    // The browsing preview may be 1024px; native theme admission stays bounded to 512px.
    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
      BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
    return try {
      ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
      }
    } finally { bitmap.recycle() }
  }
}
