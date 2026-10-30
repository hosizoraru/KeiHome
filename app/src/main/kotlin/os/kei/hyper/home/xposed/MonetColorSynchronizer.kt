package os.kei.hyper.home.xposed

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.app.ActivityManager
import android.os.UserHandle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.net.Uri
import android.provider.Settings
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetSchemes
import os.kei.hyper.home.data.parseMonetPaletteJson

/**
 * Keeps HyperOS's selected monochrome icon color aligned with its live
 * wallpaper palette without taking ownership of a user's manual selection.
 *
 * The launcher stores this color as a signed int32 in Settings.System. The
 * secure marker lets a later wallpaper/theme change distinguish a value
 * written by this module from one selected by the user.
 */
internal class MonetColorSynchronizer(iconTheme: ActiveIconTheme) {
  private val native = NativeMonetClient()
  private val resources = MonetResourcePolicy(iconTheme)
  private val actionBaseline = MonetAction.Baseline()
  private val wallpaperSeeds = WallpaperSeedPublisher()
  private var lastPaletteRefresh: Long? = null
  internal data class Publication(
    val colors: IconColors,
    val denied: Set<String>,
    val process: NativeMonetProcessIdentity?,
    val theme: ActiveIconTheme.Signature?,
    val userId: Int,
  ) {
    // Changed backgrounds reach mounted icons through the launcher's own Rx.
    // An unchanged background can suppress that event, so foreground-only
    // changes still need the package refresh fallback.
    fun needsPackageRefresh(previous: Publication?): Boolean = previous == null ||
      userId != previous.userId || denied != previous.denied ||
      process != previous.process || theme != previous.theme ||
      (colors.background == previous.colors.background && colors.foreground != previous.colors.foreground)
  }
  private var previousPolicy: Publication? = null
  private var acknowledgedReset = false
  private val paletteQuery = AtomicReference<CancellationSignal?>()

  fun start(): Boolean = native.start()
  fun stop() {
    paletteQuery.getAndSet(null)?.cancel()
    native.stop()
  }
  fun awaitStopped(deadlineNanos: Long): Boolean = native.awaitStopped(deadlineNanos)

  fun synchronize(
    baseContext: Context,
    excludingObserver: ContentObserver? = null,
    actionPreferences: SharedPreferences? = null,
    wallpaperChanged: Boolean = false,
    mayWrite: () -> Boolean = { true },
  ): SynchronizationResult {
    if (!mayWrite()) return SynchronizationResult.SelectionChanged
    val userId = currentUserId() ?: return SynchronizationResult.Unsupported
    val context = contextForUser(baseContext, userId) ?: return SynchronizationResult.Unsupported
    val stillCurrent = { mayWrite() && !Thread.currentThread().isInterrupted && currentUserId() == userId }
    val resolver = context.contentResolver
    val refresh = MonetAction.paletteRefresh(actionPreferences, userId)
    val forceRefresh = refresh != null && refresh != lastPaletteRefresh
    val wallpaper = wallpaperSeeds.readAndPublish(context, userId, forceRefresh || wallpaperChanged, stillCurrent)
    if (forceRefresh && stillCurrent()) {
      Settings.Secure.putString(resolver, MonetAction.PALETTE_REFRESHED, refresh.toString())
      lastPaletteRefresh = refresh
    }
    val actionStore = object : MonetAction.Store {
      override fun read() = MonetAction.Selection(
        Settings.System.getString(resolver, KEY_MONOCHROME_COLOR),
        Settings.Secure.getString(resolver, KEY_AUTO_COLOR),
        Settings.System.getString(resolver, KEY_MONOCHROME),
        Settings.Secure.getString(resolver, MonetAction.Setting.HANDLED.key),
        Settings.Secure.getString(resolver, MonetAction.Setting.APPLIED.key),
        Settings.Secure.getString(resolver, MonetAction.Setting.CUSTOMIZATION.key),
      )
      override fun write(setting: MonetAction.Setting, value: String): Boolean =
        if (setting.secure) Settings.Secure.putString(resolver, setting.key, value)
        else Settings.System.putString(resolver, setting.key, value)
      override fun notifyChanged() {
        resolver.notifyChange(Settings.System.getUriFor(KEY_MONOCHROME_COLOR), excludingObserver)
      }
    }
    val observed = actionStore.read()
    val attempt = actionBaseline.capture(
      MonetAction.read(actionPreferences), userId, observed, System.currentTimeMillis(),
    )
    val action = attempt?.request
    val initial = attempt?.initial ?: observed
    val actionCurrent = {
      stillCurrent() && (action == null ||
        (MonetAction.read(actionPreferences) == action &&
          MonetAction.pending(action, userId, initial, System.currentTimeMillis()) == action))
    }
    fun actionInterruption(): SynchronizationResult? {
      val request = action ?: return null
      return when (MonetAction.checkSelection(request, initial, actionStore, actionCurrent)) {
        null -> null
        MonetAction.ApplyResult.WRITE_FAILED -> throw IOException("Could not persist Monet request cancellation")
        else -> SynchronizationResult.SelectionChanged
      }
    }
    actionInterruption()?.let { return it }
    if (action == null && initial.enabled != "1") {
      val reset = withdraw(context, excludingObserver)
      // The background remains stored when the toggle alone is turned off.
      // Keep its ownership marker so re-enabling can resume automatic colors.
      return if (reset) SynchronizationResult.Restored else SynchronizationResult.Disabled
    }

    if (!SupportedMonetLauncher.matches(context)) return SynchronizationResult.Unsupported
    val customization = action?.customization ?: MonetCustomization.decode(initial.customization)
    val palette = if (customization != null) {
      val seed = customization.seed(wallpaper) ?: return SynchronizationResult.NoPalette
      MonetSchemes.generate(seed, customization.style, customization.paletteOverrides)
    } else readPalette(context, resolver, stillCurrent) ?: return SynchronizationResult.NoPalette
    val colors = MonetColorPolicy.selectPair(
      palette = palette,
      uiMode = context.resources.configuration.uiMode,
      customization = customization,
    )
      ?: return SynchronizationResult.NoColor
    val selected = colors.background
    val currentRaw = Settings.System.getString(resolver, KEY_MONOCHROME_COLOR)
    val current = parseInt32(currentRaw)
    val marker = parseInt32(Settings.Secure.getString(resolver, KEY_AUTO_COLOR))

    if (action == null && ((currentRaw != null && current == null) ||
      !MonetColorPolicy.mayReplace(true, current, marker))
    ) {
      val reset = withdraw(context, excludingObserver)
      if (marker != null && stillCurrent()) {
        Settings.Secure.putString(resolver, KEY_AUTO_COLOR, null)
      }
      return if (reset) SynchronizationResult.Restored else SynchronizationResult.Manual(current)
    }
    actionInterruption()?.let { return it }
    val denied = resources.deniedPackages(context, actionCurrent)
    actionInterruption()?.let { return it }
    // An explicit selection may have arrived while querying the wallpaper.
    if (action == null && (!stillCurrent() ||
      Settings.System.getInt(resolver, KEY_MONOCHROME, 0) != 1 ||
      Settings.System.getString(resolver, KEY_MONOCHROME_COLOR) != currentRaw)
    ) {
      return SynchronizationResult.SelectionChanged
    }
    // A new Java generation must not darken the background while an older
    // native payload still renders a fixed black foreground.
    if (!native.publish(context, colors.foreground, colors.background, denied)) {
      return actionInterruption() ?: SynchronizationResult.NativeUnavailable
    }
    // Publishing authenticates the endpoint and waits for its ACK in the same exchange.
    val policy = Publication(colors, denied, native.acknowledgedProcess, resources.themeSignature, userId)
    val refreshPackages = policy.needsPackageRefresh(previousPolicy)
    acknowledgedReset = false
    if (action != null) {
      return when (MonetAction.applyAcknowledged(
        action, selected, initial, actionStore, nativeAcknowledged = true, isCurrent = actionCurrent,
      )) {
        MonetAction.ApplyResult.APPLIED -> {
          previousPolicy = policy
          SynchronizationResult.Applied(selected, refreshPackages = true)
        }
        MonetAction.ApplyResult.WRITE_FAILED -> {
          withdraw(context, excludingObserver)
          SynchronizationResult.WriteFailed(selected)
        }
        MonetAction.ApplyResult.SELECTION_CHANGED -> {
          withdraw(context, excludingObserver)
          SynchronizationResult.SelectionChanged
        }
        MonetAction.ApplyResult.NATIVE_UNAVAILABLE -> SynchronizationResult.NativeUnavailable
      }
    }
    if (!stillCurrent() || Settings.System.getString(resolver, KEY_MONOCHROME_COLOR) != currentRaw) {
      native.disable(context)
      return SynchronizationResult.SelectionChanged
    }
    val changed = previousPolicy != policy
    if (current == selected && marker == current) {
      previousPolicy = policy
      if (changed) resolver.notifyChange(Settings.System.getUriFor(KEY_MONOCHROME_COLOR), excludingObserver)
      return if (changed) SynchronizationResult.Applied(selected, refreshPackages)
      else SynchronizationResult.Unchanged(selected)
    }
    if (!Settings.System.putInt(resolver, KEY_MONOCHROME_COLOR, selected)) {
      return SynchronizationResult.WriteFailed(selected)
    }
    // This marker is private to KeiHome and is only used to avoid replacing
    // a later user-selected color.
    Settings.Secure.putString(resolver, KEY_AUTO_COLOR, selected.toString())
    previousPolicy = policy
    resolver.notifyChange(Settings.System.getUriFor(KEY_MONOCHROME_COLOR), excludingObserver)
    return SynchronizationResult.Applied(selected, refreshPackages)
  }

  private fun withdraw(context: Context, excludingObserver: ContentObserver?): Boolean {
    if (!native.disable(context)) return false
    val changed = previousPolicy != null || !acknowledgedReset
    if (changed) context.contentResolver.notifyChange(
      Settings.System.getUriFor(KEY_MONOCHROME_COLOR), excludingObserver,
    )
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
    isCurrent: () -> Boolean,
  ): Map<String, Int>? =
    readLauncherPalette(resolver, isCurrent) ?: readFrameworkPalette(context).takeIf { isCurrent() }

  private fun readLauncherPalette(
    resolver: android.content.ContentResolver,
    isCurrent: () -> Boolean,
  ): Map<String, Int>? {
    val cancellation = CancellationSignal()
    paletteQuery.set(cancellation)
    try {
      if (!isCurrent()) throw OperationCanceledException()
      val raw = resolver.query(PALETTE_URI, null, null, null, null, cancellation)?.use { cursor ->
        if (!cursor.moveToFirst()) null
        else {
          val result = cursor.getColumnIndex("result")
          if (result < 0) null else cursor.getString(result)
        }
      }
      if (!isCurrent()) throw OperationCanceledException()
      return raw?.let { parseMonetPaletteJson(it) }
        ?.flatMap { family ->
          family.colors.mapNotNull { color ->
            color.tone?.let { tone -> "${family.name}$tone" to color.value }
          }
        }
        ?.toMap()
        ?.takeIf { it.isNotEmpty() }
    } catch (cancelled: OperationCanceledException) {
      throw cancelled
    } catch (_: Exception) {
      return null
    } finally {
      paletteQuery.compareAndSet(cancellation, null)
    }
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
    // Class names are obfuscated in Release; log the same state names in every variant.
    val diagnosticName: String get() = when (this) {
      Disabled -> "Disabled"
      Unsupported -> "Unsupported"
      NativeUnavailable -> "NativeUnavailable"
      Restored -> "Restored"
      NoPalette -> "NoPalette"
      NoColor -> "NoColor"
      SelectionChanged -> "SelectionChanged"
      is Manual -> "Manual"
      is Unchanged -> "Unchanged"
      is Applied -> "Applied"
      is WriteFailed -> "WriteFailed"
    }

    data object Disabled : SynchronizationResult
    data object Unsupported : SynchronizationResult
    data object NativeUnavailable : SynchronizationResult
    data object Restored : SynchronizationResult
    data object NoPalette : SynchronizationResult
    data object NoColor : SynchronizationResult
    data object SelectionChanged : SynchronizationResult
    data class Manual(val color: Int?) : SynchronizationResult
    data class Unchanged(val color: Int) : SynchronizationResult
    data class Applied(val color: Int, val refreshPackages: Boolean) : SynchronizationResult
    data class WriteFailed(val color: Int) : SynchronizationResult
  }

  private companion object {
    val PALETTE_URI: Uri = Uri.parse("content://com.miui.launcher.wallpaper.palette")
    const val KEY_MONOCHROME = "key_monochrome"
    const val KEY_MONOCHROME_COLOR = "key_monochrome_color"
    const val KEY_AUTO_COLOR = "keihome_monet_auto_color"
  }
}
