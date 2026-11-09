package os.kei.hyper.home.iconpack

import java.util.concurrent.CancellationException
import org.xmlpull.v1.XmlPullParser

/** Optional CandyBar display metadata; never infer authors from labels or framework credits. */
internal object ApkIconPackAuthorMetadata {
  // CandyBar's dashboard_icon_pack.xml declares these aliases with "Developer" defaults.
  private val authorStrings = listOf("theme_author", "authorName", "developer_name", "developerName")

  fun read(
    readString: (String) -> String?,
    readCreator: (String) -> String? = { null },
    check: () -> Unit = {},
  ): String? {
    fun optional(read: () -> String?): String? {
      check()
      return try { name(read())
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) { null }
    }
    for (key in authorStrings) optional { readString(key) }?.let { return it }
    val title = optional { readString("about_title") } ?: return null
    return optional { readCreator(title) }
  }

  /** Only the pack's contributors XML and an explicit creator-of-title role qualify. */
  fun creator(parser: XmlPullParser, title: String, check: () -> Unit = {}): String? {
    var events = 0
    var rootSeen = false
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
      check()
      if (++events > 512 || parser.depth > 3) return null
      if (parser.eventType == XmlPullParser.START_TAG) {
        if (parser.depth == 1) {
          if (parser.name != "resources") return null
          rootSeen = true
        }
        if (parser.attributeCount > 8) return null
        if (rootSeen && parser.depth == 2 && parser.name == "contributor" &&
          parser.getAttributeValue(null, "contribution") == "Creator of $title"
        ) {
          return name(parser.getAttributeValue(null, "name"))
        }
      }
      parser.next()
    }
    return null
  }

  private fun name(value: String?): String? = value?.takeIf { it.length <= 120 }?.trim()
    ?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) && !it.equals("Developer", ignoreCase = true) }
}
