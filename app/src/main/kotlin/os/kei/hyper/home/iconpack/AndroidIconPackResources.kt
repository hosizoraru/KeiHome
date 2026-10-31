package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import java.io.Closeable
import java.io.File
import java.io.ByteArrayOutputStream
import org.xmlpull.v1.XmlPullParser

internal data class AndroidIconPackIndex(
  val packageName: String,
  val displayName: String,
  val entries: List<IconPackEntry>,
  val notices: Set<IconPackNotice>,
  val directives: List<IconPackDirective>,
)

/** Resource-only loading. No foreign Context flags, classes, application code, or APK install. */
internal class AndroidIconPackResources(
  private val context: Context,
  private val limits: IconPackLimits = IconPackLimits(),
) {
  fun archiveInfo(file: File): PackageInfo = context.packageManager.getPackageArchiveInfo(
    file.absolutePath, PackageManager.PackageInfoFlags.of(0),
  )?.also { info ->
    info.applicationInfo?.apply { sourceDir = file.absolutePath; publicSourceDir = file.absolutePath }
  } ?: throw IconPackException("Android could not read this APK; split APK bundles are not supported")

  fun installed(info: ApplicationInfo, dark: Boolean): Session = Session(
    context.packageManager.getResourcesForApplication(info, configuration(dark)), info.packageName,
  )

  fun archive(file: File, dark: Boolean, info: PackageInfo = archiveInfo(file)): Session {
    val app = info.applicationInfo ?: throw IconPackException("APK has no application resources")
    // ApplicationInfo points at our private APK. PackageManager creates a separate resource
    // table and owns its AssetManager lifetime; no code or foreign Context is loaded.
    // Manually adding/removing loaders on Resources.getSystem().assets crashes this OEM build.
    return Session(context.packageManager.getResourcesForApplication(app, configuration(dark)), info.packageName)
  }

  fun index(info: PackageInfo, file: File? = null, check: () -> Unit = {}): AndroidIconPackIndex? {
    val app = info.applicationInfo ?: throw IconPackException("APK has no application resources")
    val parsed = mutableMapOf<String, IconAppFilter?>()
    fun open(dark: Boolean) = if (file == null) installed(app, dark) else archive(file, dark, info)
    fun read(dark: Boolean): Pair<IconAppFilter?, String?> = open(dark).use { session ->
      val filter = filter(session, dark, parsed, check)
      val label = app.nonLocalizedLabel?.toString() ?: app.labelRes.takeIf { it != 0 }?.let {
        runCatching { session.resources.getText(it).toString() }.getOrNull()
      }
      filter to label
    }
    check()
    val (light, label) = read(false)
    check()
    val (dark, _) = read(true)
    if (light == null && dark == null) return null
    val keys = light?.mappings.orEmpty().keys + dark?.mappings.orEmpty().keys
    if (keys.isEmpty()) throw IconPackException("Appfilter contains no supported component mappings")
    return AndroidIconPackIndex(info.packageName, label?.take(256) ?: info.packageName,
      keys.map { component ->
        IconPackEntry(component.flattened, component = component,
          light = light?.mappings?.get(component)?.let(IconPackArtwork::Resource),
          dark = dark?.mappings?.get(component)?.let(IconPackArtwork::Resource))
      }, light?.notices.orEmpty() + dark?.notices.orEmpty(), light?.directives.orEmpty() + dark?.directives.orEmpty())
  }

  private fun filter(session: Session, dark: Boolean, parsed: MutableMap<String, IconAppFilter?>,
    check: () -> Unit): IconAppFilter? {
    val resources = session.resources
    val mode = if (dark) IconNightMode.DARK else IconNightMode.LIGHT
    val xmlId = resources.getIdentifier("appfilter", "xml", session.packageName)
    fun key(id: Int, type: String): String {
      val value = android.util.TypedValue()
      resources.getValue(id, value, true)
      return "$type:${value.assetCookie}:${value.string}"
    }
    fun cached(path: String) = parsed[path]?.let { filter ->
      filter.copy(directives = filter.directives.map { it.copy(mode = mode) })
    }
    if (xmlId != 0) {
      val path = key(xmlId, "xml")
      if (path in parsed) return cached(path)
      resources.getXml(xmlId).use { parser ->
        val collector = AppFilterParser.Collector(limits, mode)
        var events = 0
        var rootSeen = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
          check()
          if (++events > limits.maxMappings * 8L || parser.depth > 16) throw IconPackException("Compiled appfilter limit exceeded")
          if (parser.eventType == XmlPullParser.START_TAG) {
            if (parser.depth == 1) {
              if (parser.name != "resources") throw IconPackException("Invalid compiled appfilter root")
              rootSeen = true
            }
            if (parser.attributeCount > 32) throw IconPackException("Appfilter attribute limit exceeded")
            if (parser.depth == 2) {
              val attributes = linkedMapOf<String, String>()
              for (i in 0 until parser.attributeCount) {
                val key = parser.getAttributeName(i)
                val reference = parser.getAttributeResourceValue(i, 0)
                val value = if (key == "drawable" && reference != 0) {
                  "${resources.getResourceTypeName(reference)}/${resources.getResourceEntryName(reference)}"
                } else parser.getAttributeValue(i)
                if (key.length > 128 || value.length > 4096) throw IconPackException("Appfilter attribute limit exceeded")
                attributes[key] = value
              }
              collector.tag(parser.name, attributes)
            }
          }
          parser.next()
        }
        if (!rootSeen) throw IconPackException("Empty compiled appfilter")
        return collector.finish().also { parsed[path] = it }
      }
    }
    val rawId = resources.getIdentifier("appfilter", "raw", session.packageName)
    val path = if (rawId != 0) key(rawId, "raw") else "asset:appfilter.xml"
    if (path in parsed) return cached(path)
    val input = if (rawId != 0) resources.openRawResource(rawId) else {
      // Listing the merged asset root scans every archive, including large OEM packages.
      // A direct lookup is sufficient for the only legacy asset name we support.
      try { resources.assets.open("appfilter.xml") }
      catch (_: java.io.FileNotFoundException) { parsed[path] = null; return null }
    }
    val bytes = input.use {
      val output = ByteArrayOutputStream()
      copyBounded(it, output, limits.maxXmlBytes.toLong(), check)
      output.toByteArray()
    }
    return AppFilterParser(limits).parse(bytes, mode, check).also { parsed[path] = it }
  }

  private fun configuration(dark: Boolean) = Configuration(context.resources.configuration).apply {
    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
      if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
  }

  class Session(val resources: Resources, val packageName: String, private val release: () -> Unit = {}) : Closeable {
    fun drawableId(drawable: IconDrawableName): Int {
      val id = resources.getIdentifier(drawable.name, drawable.type, packageName)
      return if (id == 0 && drawable.type == "drawable") resources.getIdentifier(drawable.name, "mipmap", packageName) else id
    }
    override fun close() = release()
  }
}
