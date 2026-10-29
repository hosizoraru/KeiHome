package os.kei.hyper.home.xposed

import android.content.Context
import android.app.ActivityManager
import android.os.UserHandle
import android.net.Uri
import android.provider.Settings
import os.kei.hyper.home.data.parseMonetPaletteJson

/**
 * Keeps HyperOS's selected monochrome icon color aligned with its live
 * wallpaper palette without taking ownership of a user's manual selection.
 *
 * The launcher stores this color as a signed int32 in Settings.System. The
 * secure marker lets a later wallpaper/theme change distinguish a value
 * written by this module from one selected by the user.
 */
internal class MonetColorSynchronizer {
  private val native = NativeMonetClient()
  private val resources = MonetResourcePolicy()
  private var previousPolicy: Triple<Int, Int, Set<String>>? = null
  private var acknowledgedReset = false

  fun start(): Boolean = native.start()
  fun stop() = native.stop()
  fun awaitStopped(deadlineNanos: Long): Boolean = native.awaitStopped(deadlineNanos)

  fun synchronize(
    baseContext: Context,
    mayWrite: () -> Boolean = { true },
  ): SynchronizationResult {
    if (!mayWrite()) return SynchronizationResult.SelectionChanged
    val userId = currentUserId() ?: return SynchronizationResult.Unsupported
    val context = contextForUser(baseContext, userId) ?: return SynchronizationResult.Unsupported
    val stillCurrent = { mayWrite() && currentUserId() == userId }
    val resolver = context.contentResolver
    if (Settings.System.getInt(resolver, KEY_MONOCHROME, 0) != 1) {
      val reset = withdraw(context)
      // The background remains stored when the toggle alone is turned off.
      // Keep its ownership marker so re-enabling can resume automatic colors.
      return if (reset) SynchronizationResult.Restored else SynchronizationResult.Disabled
    }

    if (!SupportedMonetLauncher.matches(context)) return SynchronizationResult.Unsupported
    if (!native.isAvailable(context)) return SynchronizationResult.NativeUnavailable
    val palette = readPalette(context, resolver) ?: return SynchronizationResult.NoPalette
    val colors = MonetColorPolicy.selectPair(
      palette = palette,
      uiMode = context.resources.configuration.uiMode,
    )
      ?: return SynchronizationResult.NoColor
    val selected = colors.background
    val currentRaw = Settings.System.getString(resolver, KEY_MONOCHROME_COLOR)
    val current = parseInt32(currentRaw)
    val marker = parseInt32(Settings.Secure.getString(resolver, KEY_AUTO_COLOR))

    if ((currentRaw != null && current == null) ||
      !MonetColorPolicy.mayReplace(true, current, marker)
    ) {
      val reset = withdraw(context)
      if (marker != null && stillCurrent()) {
        Settings.Secure.putString(resolver, KEY_AUTO_COLOR, null)
      }
      return if (reset) SynchronizationResult.Restored else SynchronizationResult.Manual(current)
    }
    val denied = resources.deniedPackages(context, stillCurrent)
    // An explicit selection may have arrived while querying the wallpaper.
    if (!stillCurrent() ||
      Settings.System.getInt(resolver, KEY_MONOCHROME, 0) != 1 ||
      Settings.System.getString(resolver, KEY_MONOCHROME_COLOR) != currentRaw
    ) {
      return SynchronizationResult.SelectionChanged
    }
    // A new Java generation must not darken the background while an older
    // native payload still renders a fixed black foreground.
    if (!native.publish(context, colors.foreground, colors.background, denied)) {
      return SynchronizationResult.NativeUnavailable
    }
    acknowledgedReset = false
    if (!stillCurrent() || Settings.System.getString(resolver, KEY_MONOCHROME_COLOR) != currentRaw) {
      native.disable(context)
      return SynchronizationResult.SelectionChanged
    }
    val policy = Triple(colors.foreground, colors.background, denied)
    val changed = previousPolicy != policy
    if (current == selected && marker == current) {
      previousPolicy = policy
      return if (changed) SynchronizationResult.Applied(selected)
      else SynchronizationResult.Unchanged(selected)
    }
    if (!Settings.System.putInt(resolver, KEY_MONOCHROME_COLOR, selected)) {
      return SynchronizationResult.WriteFailed(selected)
    }
    // This marker is private to KeiHome and is only used to avoid replacing
    // a later user-selected color.
    Settings.Secure.putString(resolver, KEY_AUTO_COLOR, selected.toString())
    previousPolicy = policy
    return SynchronizationResult.Applied(selected)
  }

  private fun withdraw(context: Context): Boolean {
    if (!native.disable(context)) return false
    val changed = previousPolicy != null || !acknowledgedReset
    previousPolicy = null
    acknowledgedReset = true
    return changed
  }

  private fun currentUserId(): Int? = try {
    (ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as? Int)
      ?.takeIf { it >= 0 }
  } catch (_: Exception) {
    null
  }

  private fun contextForUser(base: Context, userId: Int): Context? = try {
    val user = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId)
    Context::class.java.getMethod(
      "createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType,
    ).invoke(base, user, 0) as? Context
  } catch (_: Exception) {
    null
  }

  private fun readPalette(
    context: Context,
    resolver: android.content.ContentResolver,
  ): Map<String, Int>? =
    readLauncherPalette(resolver) ?: readFrameworkPalette(context)

  private fun readLauncherPalette(
    resolver: android.content.ContentResolver,
  ): Map<String, Int>? = try {
    val raw = resolver.query(PALETTE_URI, null, null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) null
      else {
        val result = cursor.getColumnIndex("result")
        if (result < 0) null else cursor.getString(result)
      }
    }
    raw?.let { parseMonetPaletteJson(it) }
      ?.flatMap { family ->
        family.colors.mapNotNull { color ->
          color.tone?.let { tone -> "${family.name}$tone" to color.value }
        }
      }
      ?.toMap()
      ?.takeIf { it.isNotEmpty() }
  } catch (_: Throwable) {
    null
  }

  private fun readFrameworkPalette(context: Context): Map<String, Int>? {
    val tones = listOf(
      1000 to 0,
      900 to 10,
      800 to 20,
      700 to 30,
      600 to 40,
      500 to 50,
      400 to 60,
      300 to 70,
      200 to 80,
      100 to 90,
      50 to 95,
      10 to 99,
      0 to 100,
    )
    val families = listOf(
      "primary" to "system_accent1",
      "secondary" to "system_accent2",
      "tertiary" to "system_accent3",
      "neutral" to "system_neutral1",
      "neutral-variant" to "system_neutral2",
    )
    return try {
      families.flatMap { (name, prefix) ->
        tones.mapNotNull { (suffix, tone) ->
          val id = context.resources.getIdentifier(
            "${prefix}_$suffix",
            "color",
            "android",
          )
          if (id == 0) return@mapNotNull null
          try {
            val color = context.resources.getColor(id, context.theme)
            "${name}$tone" to color
          } catch (_: Throwable) {
            null
          }
        }
      }.toMap().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
      null
    }
  }

  private fun parseInt32(value: String?): Int? {
    val number = value?.trim()?.toLongOrNull() ?: return null
    return number.takeIf {
      it >= Int.MIN_VALUE.toLong() && it <= UInt.MAX_VALUE.toLong()
    }?.toInt()
  }

  internal sealed interface SynchronizationResult {
    data object Disabled : SynchronizationResult
    data object Unsupported : SynchronizationResult
    data object NativeUnavailable : SynchronizationResult
    data object Restored : SynchronizationResult
    data object NoPalette : SynchronizationResult
    data object NoColor : SynchronizationResult
    data object SelectionChanged : SynchronizationResult
    data class Manual(val color: Int?) : SynchronizationResult
    data class Unchanged(val color: Int) : SynchronizationResult
    data class Applied(val color: Int) : SynchronizationResult
    data class WriteFailed(val color: Int) : SynchronizationResult
  }

  private companion object {
    val PALETTE_URI: Uri = Uri.parse("content://com.miui.launcher.wallpaper.palette")
    const val KEY_MONOCHROME = "key_monochrome"
    const val KEY_MONOCHROME_COLOR = "key_monochrome_color"
    const val KEY_AUTO_COLOR = "keihome_monet_auto_color"
  }
}
