package os.kei.hyper.home.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections

/** Exact package selections shared by app preferences and the app-owned renderer worker. */
internal object PreservedIcons {
  const val KEY = "preserved_icon_packages"
  const val MAX_PACKAGES = 4_096
  const val MAX_PACKAGE_NAME_LENGTH = 223
  private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

  fun snapshot(context: Context): Set<String> = read(AppDisplayStorage.open(context))

  fun isValidPackageName(value: String): Boolean =
    value.length in 1..MAX_PACKAGE_NAME_LENGTH && (value == "android" || packageName.matches(value))

  /** Never repair a name into a different app; retain only bounded, exact valid selections. */
  fun normalize(packages: Iterable<*>): Set<String> {
    val result = linkedSetOf<String>()
    for (value in packages) {
      if (value is String && isValidPackageName(value)) result.add(value)
      if (result.size == MAX_PACKAGES) break
    }
    return Collections.unmodifiableSet(result)
  }

  internal fun read(preferences: SharedPreferences): Set<String> {
    if (!preferences.contains(KEY)) return emptySet()
    val packages = try {
      preferences.getStringSet(KEY, null)
    } catch (_: ClassCastException) {
      null
    }
    return normalize(packages.orEmpty())
  }
}
