package os.kei.hyper.home.xposed

import android.os.Bundle
import org.json.JSONObject

/** Copies a delivered rule bundle; the OEM controller and cloud table remain untouched. */
internal object NavigationRuleOverlay {
  const val MAX_RULE_BYTES = 128 * 1024
  const val MAX_ACTIVITIES = 512

  fun apply(original: Bundle?, launchActivity: String? = null): Bundle? {
    @Suppress("DEPRECATION")
    if (original?.containsKey("activityRules") == true &&
      original.get("activityRules") !is String) return original
    val raw = original?.getString("activityRules").orEmpty()
    val rewritten = rewriteActivityRules(raw, launchActivity) ?: return original
    return Bundle(original ?: Bundle()).apply {
      putBoolean("enable", true)
      putString("activityRules", rewritten)
    }
  }

  fun rewriteActivityRules(raw: String, launchActivity: String? = null): String? {
    if (raw.isEmpty() && launchActivity == null) return ""
    if (raw.length > MAX_RULE_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_RULE_BYTES) return null
    if (raw.isNotEmpty() && raw.firstOrNull { !it.isWhitespace() } != '{') return null
    var depth = 0
    var finished = false
    var quoted = false
    var escaped = false
    for (character in raw) {
      if (finished && !character.isWhitespace()) return null
      if (quoted) {
        if (escaped) escaped = false
        else if (character == '\\') escaped = true
        else if (character == '"') quoted = false
      } else when (character) {
        '"' -> quoted = true
        '{', '[' -> { if (++depth > 32) return null }
        '}', ']' -> { if (--depth < 0) return null; if (depth == 0) finished = true }
      }
    }
    if (quoted || depth != 0) return null
    val rules = if (raw.isEmpty()) JSONObject() else try { JSONObject(raw) }
      catch (_: Exception) { return null }
    if (rules.length() > MAX_ACTIVITIES) return null
    val keys = rules.keys()
    while (keys.hasNext()) {
      val name = keys.next()
      val activity = rules.optJSONObject(name) ?: return null
      if (activity.opt("mode") == 0) {
        activity.put("mode", -1)
        if (name == launchActivity && activity.isNull("color") &&
          (!activity.has("viewRules") || activity.optJSONArray("viewRules")?.length() == 0) &&
          (!activity.has("sf_sampling_mode") || activity.opt("sf_sampling_mode") == 0)
        ) activity.put("sf_sampling_mode", 1)
      }
    }
    if (launchActivity != null && !rules.has(launchActivity) && rules.length() < MAX_ACTIVITIES) {
      rules.put(launchActivity, JSONObject().put("mode", -1).put("sf_sampling_mode", 1))
    }
    return rules.toString().takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_RULE_BYTES }
  }
}
