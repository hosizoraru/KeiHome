package os.kei.hyper.home.xposed

import os.kei.hyper.home.status.withIdentity

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.app.ActivityManager
import android.os.UserHandle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import os.kei.hyper.home.data.MonetAction
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetSchemes
import os.kei.hyper.home.data.ThemeEffectsPreferences
import os.kei.hyper.home.data.parseMonetPaletteJson
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.status.IconRefreshReceipt
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.NativeRuntimeDiagnostic
import os.kei.hyper.home.status.RuntimeStatusInbox
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusRequest
import os.kei.hyper.home.status.SystemIconThemeReport
import os.kei.hyper.home.status.ThemeMaterialAutoEnable

/**
 * Keeps HyperOS's selected monochrome icon color aligned with its live
 * wallpaper palette without taking ownership of a user's manual selection.
 *
 * The launcher stores this color as a signed int32 in Settings.System. The
 * secure marker lets a later wallpaper/theme change distinguish a value
 * written by this module from one selected by the user.
 */
internal class MonetColorSynchronizer(private val iconTheme: ActiveIconTheme) {
  private val native = NativeMonetClient()
  private val resources = MonetResourcePolicy(iconTheme)
  private val actionBaseline = MonetAction.Baseline()
  private val wallpaperSeeds = WallpaperSeedPublisher()
  private val themeMaterials = ThemeMaterialLease()
  private val launcherLabels = LauncherLabelLease()
  private val dockGlass = DockGlassLease()
  private val launcherGlass = LauncherGlassLease()

  fun synchronizeLauncherGlass(baseContext: Context, preferences: SharedPreferences?, mayWrite: () -> Boolean): Boolean {
    if (!mayWrite() || Thread.currentThread().isInterrupted) return false
    val user = currentUserId() ?: return false
    val context = contextForUser(baseContext, user) ?: return false
    val style = os.kei.hyper.home.data.LauncherGlassPreferences.read(preferences).nativeValue
    val current = { mayWrite() && !Thread.currentThread().isInterrupted && currentUserId() == user &&
      os.kei.hyper.home.data.LauncherGlassPreferences.read(preferences).nativeValue == style }
    return launcherGlass.synchronize(style, user, SystemClock::elapsedRealtime, current) { attempted ->
      if (native.setLauncherGlass(context, style, attempted)) native.acknowledgedLauncherGlassProcess else null
    }
  }

  fun synchronizeDockGlass(baseContext: Context, preferences: SharedPreferences?, mayWrite: () -> Boolean): Boolean {
    if (!mayWrite() || Thread.currentThread().isInterrupted) return false
    val user = currentUserId() ?: return false
    val context = contextForUser(baseContext, user) ?: return false
    val choice = os.kei.hyper.home.data.DockGlassPreferences.readChoice(preferences)
    val enabled = choice.enabled
    val options = choice.options
    val cornerQ8 = if (enabled) DockScreenCorners.readQ8(context) else 0
    val current = { mayWrite() && !Thread.currentThread().isInterrupted && currentUserId() == user &&
      os.kei.hyper.home.data.DockGlassPreferences.readChoice(preferences) == choice }
    return dockGlass.synchronize(enabled, user, SystemClock::elapsedRealtime, current, cornerQ8, options) { attempted ->
      if (native.setDockGlass(context, enabled, cornerQ8, options, attempted)) native.acknowledgedDockGlassProcess else null
    }
  }

  fun synchronizeLauncherLabels(baseContext: Context, preferences: SharedPreferences?, mayWrite: () -> Boolean): Boolean {
    if (!mayWrite()) return false
    val user = currentUserId() ?: return false
    val context = contextForUser(baseContext, user) ?: return false
    val mode = os.kei.hyper.home.data.LauncherLabelPreferences.read(preferences).nativeValue
    val current = { mayWrite() && currentUserId() == user &&
      os.kei.hyper.home.data.LauncherLabelPreferences.read(preferences).nativeValue == mode }
    return launcherLabels.synchronize(mode, user, SystemClock::elapsedRealtime, current) { attempted ->
      if (native.setLauncherLabels(context, mode, attempted)) native.acknowledgedLabelProcess else null
    }
  }
  private val materialIdentityGeneration = AtomicLong()
  private var defaultMaterialIdentityGeneration = -1L
  private var defaultMaterialIdentityUser = -1
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
  fun invalidateIconResources(packageName: String? = null) {
    resources.invalidate(packageName)
    if (packageName == null) materialIdentityGeneration.incrementAndGet()
  }
  fun stop() {
    paletteQuery.getAndSet(null)?.cancel()
    native.stop()
  }
  fun awaitStopped(deadlineNanos: Long): Boolean = native.awaitStopped(deadlineNanos)

  /** Independent of palette/settings/probes: works with Monet disabled on the same owned worker. */
  fun synchronizeThemeMaterials(
    baseContext: Context,
    preferences: SharedPreferences?,
    mayWrite: () -> Boolean,
  ): Boolean {
    if (!mayWrite() || Thread.currentThread().isInterrupted) return false
    val userId = currentUserId() ?: return false
    val context = contextForUser(baseContext, userId) ?: return false
    val identityGeneration = materialIdentityGeneration.get()
    if (preferences != null && !preferences.getBoolean(ThemeEffectsPreferences.AUTO_ENABLE_HANDLED, false) &&
      (defaultMaterialIdentityGeneration != identityGeneration || defaultMaterialIdentityUser != userId)) {
      when (NativeIconPackManager.identity(context)?.default) {
        true -> {
          defaultMaterialIdentityGeneration = identityGeneration
          defaultMaterialIdentityUser = userId
        }
        false -> if (mayWrite() && !Thread.currentThread().isInterrupted && currentUserId() == userId) {
          context.contentResolver.call(RuntimeStatusInbox.AUTHORITY, ThemeMaterialAutoEnable.METHOD, null, null)
        }
        null -> Unit
      }
    }
    val enabled = preferences?.getBoolean(ThemeEffectsPreferences.KEY, false) ?: false
    val isCurrent = {
      mayWrite() && !Thread.currentThread().isInterrupted && currentUserId() == userId &&
        (preferences?.getBoolean(ThemeEffectsPreferences.KEY, false) ?: false) == enabled
    }
    return themeMaterials.synchronize(
      enabled, userId, nowElapsed = { SystemClock.elapsedRealtime() }, isCurrent = isCurrent,
    ) { onWriteAttempted ->
      if (native.setThemeMaterials(context, enabled, onWriteAttempted)) native.acknowledgedMaterialProcess else null
    }
  }

  /** Runs on the existing monitor worker, serialized with policy exchanges and retirement. */
  fun reportRuntimeStatus(
    baseContext: Context,
    request: RuntimeStatusRequest,
    refresh: IconRefreshReceipt?,
    isCurrent: () -> Boolean,
  ): Boolean {
    if (!isCurrent()) return false
    val context = contextForUser(baseContext, request.userId) ?: return false
    val activeUser = currentUserId()
    val identities = NativeRuntimeIdentitySelection()
    if (activeUser == request.userId) {
      native.isAvailable(context)
      identities.observe(native.lastGreeting)
    }
    // Do not add another endpoint timeout when this optional feature has no ACK.
    val probeDock = activeUser == request.userId &&
      dockGlass.hasAcknowledgement(request.userId, SystemClock.elapsedRealtime()) &&
      isCurrent()
    val dockAvailable = probeDock && native.isDockGlassAvailable(context)
    if (probeDock) identities.observe(native.lastGreeting)
    // Select every ACK after the last probe, including authenticated capability rejections.
    val greeting = identities.greeting
    val process = identities.process
    val acknowledgementProcess = identities.acknowledgementProcess
    val launcherGlassAvailable = greeting?.launcherGlassAvailable == true
    val dockProcess = acknowledgementProcess.takeIf { dockAvailable }
    val dockCheckedAt = SystemClock.elapsedRealtime()
    val dockEnabled = dockGlass.acknowledgedEnabled(dockProcess, request.userId, dockCheckedAt)
    val dockOptions = dockGlass.acknowledgedOptions(dockProcess, request.userId, dockCheckedAt)
    val state = when {
      activeUser != null && activeUser != request.userId -> NativeRuntimeState.INACTIVE_USER
      greeting?.monetAvailable == true -> NativeRuntimeState.READY
      else -> NativeRuntimeState.UNAVAILABLE
    }
    val report = RuntimeStatusReport(
      requestId = request.id, userId = request.userId,
      moduleVersion = BuildConfig.VERSION_CODE.toLong(), checkedAtElapsed = SystemClock.elapsedRealtime(),
      nativeState = state, launcherPid = process?.pid, launcherStartTicks = process?.startTicks,
      refresh = identities.retainEarlierAcknowledgement(refresh),
      launcherLabelMode = launcherLabels.acknowledgedMode(acknowledgementProcess, SystemClock.elapsedRealtime()),
      dockGlassEnabled = dockEnabled,
      dockGlassCornerStyle = dockOptions,
      dockGlassPid = dockProcess?.pid.takeIf { dockEnabled != null },
      dockGlassStartTicks = dockProcess?.startTicks.takeIf { dockEnabled != null },
      launcherGlassAvailable = launcherGlassAvailable,
      launcherGlassStyle = launcherGlass.acknowledgedStyle(
        acknowledgementProcess.takeIf { launcherGlassAvailable }, request.userId, SystemClock.elapsedRealtime()),
      iconTheme = if (request.includeIconTheme && activeUser == request.userId) {
        val inventory = iconTheme.describe()
        val identity = NativeIconPackManager.identity(context)
        inventory.withIdentity(
          identity?.title, identity?.localId, identity?.default, identity?.hash).copy(
          previewKey = CurrentIconSnapshotPublisher.publish(context, inventory, isCurrent, request.userId)
            ?.takeIf { isCurrent() && iconTheme.describe().revision == inventory.revision })
      } else null,
      detail = if (state == NativeRuntimeState.UNAVAILABLE) {
        if (activeUser == null) "Foreground user unavailable"
        else if (greeting != null && !greeting.monetAvailable) NativeRuntimeDiagnostic.MONET_CAPABILITY_UNAVAILABLE
        else NativeMonetClient.lastTransportFailure
      } else null,
    )
    if (!isCurrent() || currentUserId() != activeUser) return false
    return context.contentResolver.call(
      RuntimeStatusInbox.AUTHORITY, RuntimeStatusInbox.REPORT_METHOD, report.encode(), null,
    )?.getBoolean("accepted") == true
  }

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
    if (action?.enabled == false) {
      val disabled = MonetAction.applyDisabled(action, initial, actionStore, actionCurrent)
      if (disabled != MonetAction.ApplyResult.APPLIED) return SynchronizationResult.SelectionChanged
      withdraw(context, excludingObserver, stillCurrent)
      if (!acknowledgedReset) return SynchronizationResult.NativeUnavailable
      if (!stillCurrent()) return SynchronizationResult.SelectionChanged
      if (!actionStore.write(MonetAction.Setting.APPLIED, action.revision.toString())) {
        return SynchronizationResult.WriteFailed(0)
      }
      return SynchronizationResult.Applied(0, refreshPackages = true)
    }
    if (action == null && initial.enabled != "1") {
      val reset = withdraw(context, excludingObserver, stillCurrent)
      // The background remains stored when the toggle alone is turned off.
      // Keep its ownership marker so re-enabling can resume automatic colors.
      return if (reset) SynchronizationResult.Restored else SynchronizationResult.Disabled
    }

    // Compatibility comes from the live native resolver and its authenticated
    // policy ACK below. A launcher update must not require an APK hash allowlist.
    val customization = action?.customization ?: MonetCustomization.decode(initial.customization)
    val palette = if (customization?.source == os.kei.hyper.home.data.MonetSeedSource.SYSTEM_COLOR &&
      customization.paletteOverrides.isEmpty()) {
      readFrameworkPalette(context) ?: return SynchronizationResult.NoPalette
    } else if (customization != null) {
      val seed = if (customization.source == os.kei.hyper.home.data.MonetSeedSource.SYSTEM_COLOR)
        readFrameworkPalette(context)?.get("primary50") else customization.seed(wallpaper)
      if (seed == null) return SynchronizationResult.NoPalette
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
      val reset = withdraw(context, excludingObserver, stillCurrent)
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
    var paletteChanged = false
    fun publishPalette(): SynchronizationResult? {
      val result = publishMamlPalette(context, colors, excludingObserver) {
        actionCurrent() && Settings.System.getInt(resolver, KEY_MONOCHROME, 0) == 1 &&
          parseInt32(Settings.System.getString(resolver, KEY_MONOCHROME_COLOR)) == selected &&
          parseInt32(Settings.Secure.getString(resolver, KEY_AUTO_COLOR)) == selected
      }
      return when (result) {
        GeneratedPalettePublication.Result.CHANGED -> { paletteChanged = true; null }
        GeneratedPalettePublication.Result.UNCHANGED -> null
        GeneratedPalettePublication.Result.SELECTION_CHANGED -> SynchronizationResult.SelectionChanged
        GeneratedPalettePublication.Result.NATIVE_UNAVAILABLE -> SynchronizationResult.NativeUnavailable
        GeneratedPalettePublication.Result.WRITE_FAILED -> {
          withdraw(context, excludingObserver, stillCurrent)
          SynchronizationResult.WriteFailed(selected)
        }
      }
    }
    if (action != null) {
      return when (MonetAction.applyAcknowledged(
        action, selected, initial, actionStore, nativeAcknowledged = true, isCurrent = actionCurrent,
      )) {
        MonetAction.ApplyResult.APPLIED -> {
          publishPalette()?.let { return it }
          previousPolicy = policy
          SynchronizationResult.Applied(selected, refreshPackages = true)
        }
        MonetAction.ApplyResult.WRITE_FAILED -> {
          withdraw(context, excludingObserver, stillCurrent)
          SynchronizationResult.WriteFailed(selected)
        }
        MonetAction.ApplyResult.SELECTION_CHANGED -> {
          withdraw(context, excludingObserver, stillCurrent)
          SynchronizationResult.SelectionChanged
        }
        MonetAction.ApplyResult.NATIVE_UNAVAILABLE -> SynchronizationResult.NativeUnavailable
      }
    }
    if (!stillCurrent() || Settings.System.getString(resolver, KEY_MONOCHROME_COLOR) != currentRaw) {
      withdraw(context, excludingObserver, stillCurrent)
      return SynchronizationResult.SelectionChanged
    }
    val changed = previousPolicy != policy
    if (current == selected && marker == current) {
      publishPalette()?.let { return it }
      previousPolicy = policy
      if (changed) resolver.notifyChange(Settings.System.getUriFor(KEY_MONOCHROME_COLOR), excludingObserver)
      return if (changed || paletteChanged) SynchronizationResult.Applied(selected, refreshPackages)
      else SynchronizationResult.Unchanged(selected)
    }
    if (!Settings.System.putInt(resolver, KEY_MONOCHROME_COLOR, selected)) {
      return SynchronizationResult.WriteFailed(selected)
    }
    // This marker is private to KeiHome and is only used to avoid replacing
    // a later user-selected color.
    Settings.Secure.putString(resolver, KEY_AUTO_COLOR, selected.toString())
    publishPalette()?.let { return it }
    previousPolicy = policy
    resolver.notifyChange(Settings.System.getUriFor(KEY_MONOCHROME_COLOR), excludingObserver)
    return SynchronizationResult.Applied(selected, refreshPackages)
  }

  private fun publishMamlPalette(
    context: Context, colors: IconColors?, excludingObserver: ContentObserver?, isCurrent: () -> Boolean,
  ): GeneratedPalettePublication.Result {
    val resolver = context.contentResolver
    return GeneratedPalettePublication.publish(colors, nativeAcknowledged = true,
      store = object : GeneratedPalettePublication.Store {
        override fun read(key: String): String? = Settings.System.getString(resolver, key)
        override fun write(key: String, value: String) = Settings.System.putString(resolver, key, value)
        override fun notifyChanged(key: String) {
          resolver.notifyChange(Settings.System.getUriFor(key), excludingObserver)
        }
      }, isCurrent = isCurrent)
  }

  private fun withdraw(
    context: Context, excludingObserver: ContentObserver?, isCurrent: () -> Boolean,
  ): Boolean {
    if (!isCurrent() || !native.disable(context)) return false
    val palette = publishMamlPalette(context, null, excludingObserver, isCurrent)
    if (palette == GeneratedPalettePublication.Result.SELECTION_CHANGED) return false
    if (palette == GeneratedPalettePublication.Result.WRITE_FAILED) throw IOException("Could not disable generated icon palette")
    val changed = previousPolicy != null || !acknowledgedReset ||
      palette == GeneratedPalettePublication.Result.CHANGED
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

/** Confirmed changes and recovery from uncertain writes reconcile icons; normal renewals do not. */
internal class ThemeMaterialLease {
  private data class Acknowledged(
    val enabled: Boolean,
    val userId: Int,
    val process: NativeMonetProcessIdentity,
    val atElapsed: Long,
  )
  private var previous: Acknowledged? = null
  private var dirty = false

  /** exchange reports attempted writes on this caller's thread before returning or throwing. */
  fun synchronize(
    enabled: Boolean,
    userId: Int,
    nowElapsed: () -> Long,
    isCurrent: () -> Boolean,
    exchange: (onWriteAttempted: () -> Unit) -> NativeMonetProcessIdentity?,
  ): Boolean {
    if (!isCurrent()) return false
    // Conservative lease age starts before I/O, not after a potentially delayed ACK.
    val started = nowElapsed()
    var writeAttempted = false
    var acknowledged = false
    try {
      val process = exchange { writeAttempted = true } ?: return false
      if (!isCurrent()) return false
      val old = previous
      val refresh = dirty || if (old == null || old.userId != userId || old.process != process) enabled else {
        old.enabled != enabled ||
          (enabled && (started < old.atElapsed || started - old.atElapsed >= EXPIRY_MS))
      }
      previous = Acknowledged(enabled, userId, process, started)
      dirty = false
      acknowledged = true
      return refresh
    } finally {
      // Native may commit before an ACK is lost or becomes stale, even if we return to the old value.
      if (writeAttempted && !acknowledged) dirty = true
    }
  }

  private companion object {
    const val EXPIRY_MS = 45_000L
  }
}
