package os.kei.hyper.home.data

import android.content.ContentResolver
import android.content.SharedPreferences
import android.os.Process
import android.provider.Settings
import io.github.libxposed.service.XposedService

internal data class MonetActionRequest(
  val revision: Long, val userId: Int, val customization: MonetCustomization? = null,
  val enabled: Boolean = true,
  val preserveEnabled: Boolean = false,
)

internal enum class MonetActionStatus { IDLE, APPLYING, APPLIED, UNCONFIRMED, UNAVAILABLE, FAILED }

/** One explicit, user-scoped request. Remote preferences carry intent, never an application ACK. */
internal object MonetAction {
  const val GROUP = "monet"
  const val REQUEST_REVISION = "request_revision"
  const val REQUEST_USER = "request_user"
  const val REQUEST_CUSTOMIZATION = "request_customization"
  const val REQUEST_ENABLED = "request_enabled"
  const val REQUEST_PRESERVE_REVISION = "request_preserve_revision"
  const val REFRESH_REVISION = "refresh_revision"
  const val REFRESH_USER = "refresh_user"
  const val PALETTE_REFRESHED = "keihome_palette_refreshed"
  const val REQUEST_LIFETIME_MS = 60_000L

  enum class Setting(val key: String, val secure: Boolean) {
    BACKGROUND("key_monochrome_color", false),
    AUTO_COLOR("keihome_monet_auto_color", true),
    ENABLED("key_monochrome", false),
    HANDLED("keihome_monet_handled_revision", true),
    APPLIED("keihome_monet_applied_revision", true),
    CUSTOMIZATION("keihome_monet_customization", true),
  }

  data class Selection(
    val background: String?, val autoColor: String?, val enabled: String?,
    val handled: String?, val applied: String?,
    val customization: String? = null,
  ) {
    fun sameChoice(other: Selection): Boolean = background == other.background &&
      autoColor == other.autoColor && enabled == other.enabled && customization == other.customization

    fun updated(setting: Setting, value: String): Selection = when (setting) {
      Setting.BACKGROUND -> copy(background = value)
      Setting.AUTO_COLOR -> copy(autoColor = value)
      Setting.ENABLED -> copy(enabled = value)
      Setting.HANDLED -> copy(handled = value)
      Setting.APPLIED -> copy(applied = value)
      Setting.CUSTOMIZATION -> copy(customization = value)
    }
  }

  interface Store {
    fun read(): Selection
    fun write(setting: Setting, value: String): Boolean
    fun notifyChanged()
  }

  enum class ApplyResult { APPLIED, SELECTION_CHANGED, WRITE_FAILED, NATIVE_UNAVAILABLE }

  data class Attempt(val request: MonetActionRequest, val initial: Selection)

  /** One baseline per synchronizer; failed I/O must not rebase a pending request onto manual colors. */
  class Baseline {
    private var observed: Attempt? = null

    fun capture(request: MonetActionRequest?, userId: Int, selection: Selection, now: Long): Attempt? {
      val active = pending(request, userId, selection, now) ?: return null
      val original = observed?.takeIf { it.request == active }?.initial ?: selection
      return Attempt(active, original.copy(handled = selection.handled, applied = selection.applied))
        .also { observed = it }
    }
  }

  fun decode(snapshot: Map<String, *>): MonetActionRequest? {
    val legacyRevision = snapshot[REQUEST_REVISION] as? Long ?: return null
    // Negative legacy revisions are inert to older runtimes and still notify the existing
    // revision listener on every press. Positive revisions from older apps always win.
    val preserve = legacyRevision < 0L
    val revision = if (preserve) {
      (snapshot[REQUEST_PRESERVE_REVISION] as? Long)
        ?.takeIf { it > 0 && legacyRevision == -it } ?: return null
    } else legacyRevision
    val user = snapshot[REQUEST_USER] as? Int ?: return null
    val customization = MonetCustomization.decode(snapshot[REQUEST_CUSTOMIZATION] as? String)
    return MonetActionRequest(revision, user, customization, snapshot[REQUEST_ENABLED] as? Boolean ?: true, preserve)
      .takeIf { revision > 0 && user >= 0 }
  }

  fun read(preferences: SharedPreferences?): MonetActionRequest? =
    preferences?.let { decode(it.all) }

  fun nextRevision(previous: Long, now: Long): Long {
    require(previous < Long.MAX_VALUE) { "Monet request revision exhausted" }
    return maxOf(1L, now, previous + 1L)
  }

  private fun storedRevision(raw: String?): Long? =
    if (raw == null) 0L else raw.toLongOrNull()?.takeIf { it >= 0L }

  fun isUnconsumed(request: MonetActionRequest, selection: Selection): Boolean {
    val handled = storedRevision(selection.handled) ?: return false
    val applied = storedRevision(selection.applied) ?: return false
    return request.revision > maxOf(handled, applied)
  }

  fun pending(
    request: MonetActionRequest?, userId: Int, selection: Selection, now: Long,
  ): MonetActionRequest? {
    request ?: return null
    return request.takeIf {
      it.userId == userId && isUnconsumed(it, selection) &&
        it.revision <= now && now - it.revision <= REQUEST_LIFETIME_MS
    }
  }

  /**
   * Returns null only while both ownership and the complete expected snapshot still match.
   * Receipt/request changes are not manual selection changes. Cancellation writes only HANDLED;
   * it never acknowledges application or changes colors, ownership markers or the enable switch.
   */
  fun checkSelection(
    request: MonetActionRequest, expected: Selection, store: Store, isCurrent: () -> Boolean,
  ): ApplyResult? {
    if (!isCurrent()) return ApplyResult.SELECTION_CHANGED
    val current = store.read()
    if (!expected.sameChoice(current)) return cancel(request, store, isCurrent)
    return if (current == expected && isCurrent()) null else ApplyResult.SELECTION_CHANGED
  }

  private fun cancel(request: MonetActionRequest, store: Store, isCurrent: () -> Boolean): ApplyResult {
    if (!isCurrent()) return ApplyResult.SELECTION_CHANGED
    // Re-read at cancellation time, not from the snapshot that detected the manual change.
    val current = store.read()
    val handled = storedRevision(current.handled) ?: return ApplyResult.WRITE_FAILED
    val applied = storedRevision(current.applied) ?: return ApplyResult.WRITE_FAILED
    if (request.revision <= maxOf(handled, applied)) return ApplyResult.SELECTION_CHANGED
    if (!isCurrent()) return ApplyResult.SELECTION_CHANGED
    // SettingsProvider has no CAS. The runtime serializes its writer/retirement, but a foreign
    // writer between this read and put cannot be made atomic by another local check or lock.
    return if (store.write(Setting.HANDLED, request.revision.toString())) ApplyResult.SELECTION_CHANGED
    else ApplyResult.WRITE_FAILED
  }

  fun requestPaletteRefresh(service: XposedService): MonetActionRequest = requestPaletteRefresh(
    service.getRemotePreferences(GROUP), Process.myUid(), System.currentTimeMillis(),
  )

  internal fun requestPaletteRefresh(preferences: SharedPreferences, appUid: Int, now: Long): MonetActionRequest {
    val previous = preferences.all[REFRESH_REVISION] as? Long ?: 0L
    val request = MonetActionRequest(nextRevision(previous, now), appUid / 100_000)
    check(preferences.edit().putLong(REFRESH_REVISION, request.revision)
      .putInt(REFRESH_USER, request.userId).commit()) { "Palette refresh request failed" }
    return request
  }

  fun paletteRefresh(preferences: SharedPreferences?, userId: Int): Long? {
    val values = preferences?.all ?: return null
    return (values[REFRESH_REVISION] as? Long)?.takeIf { it > 0 && values[REFRESH_USER] == userId }
  }

  /** API 102 sends the revision, user and complete choice in one Binder update bundle. */
  @Synchronized
  fun submit(service: XposedService, customization: MonetCustomization? = null, enabled: Boolean = true,
    preserveEnabled: Boolean = false): MonetActionRequest {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    }
    return submit(service.getRemotePreferences(GROUP), Process.myUid(), System.currentTimeMillis(),
      customization, enabled, preserveEnabled)
  }

  internal fun submit(
    preferences: SharedPreferences, appUid: Int, now: Long, customization: MonetCustomization? = null,
    enabled: Boolean = true,
    preserveEnabled: Boolean = false,
  ): MonetActionRequest {
    require(appUid >= 0)
    val snapshot = preferences.all
    val previous = maxOf(snapshot[REQUEST_REVISION] as? Long ?: 0L,
      snapshot[REQUEST_PRESERVE_REVISION] as? Long ?: 0L)
    val request = MonetActionRequest(nextRevision(previous, now), appUid / 100_000, customization, enabled, preserveEnabled)
    // commit can update the service client's cache before a failed Binder write. Never infer
    // persistence from that cache and never report application success from commit alone.
    val editor = preferences.edit().putLong(REQUEST_REVISION, if (preserveEnabled) -request.revision else request.revision)
      .putInt(REQUEST_USER, request.userId)
      .putBoolean(REQUEST_ENABLED, enabled)
      .putString(REQUEST_CUSTOMIZATION, customization?.encode())
    if (preserveEnabled) editor.putLong(REQUEST_PRESERVE_REVISION, request.revision)
    check(editor.commit()) { "Monet request commit failed" }
    return request
  }

  fun isApplied(resolver: ContentResolver, request: MonetActionRequest): Boolean = try {
    Settings.Secure.getString(resolver, Setting.APPLIED.key)?.toLongOrNull() == request.revision
  } catch (_: Exception) {
    // Custom Secure keys may be unreadable to an app on an OEM build. This is not success.
    false
  }

  fun applyDisabled(request: MonetActionRequest, initial: Selection, store: Store,
    isCurrent: () -> Boolean): ApplyResult {
    require(!request.enabled && !request.preserveEnabled)
    var expected = initial
    for ((setting, value) in listOf(
      Setting.HANDLED to request.revision.toString(), Setting.ENABLED to "0",
    )) {
      checkSelection(request, expected, store, isCurrent)?.let { return it }
      if (!store.write(setting, value)) return ApplyResult.WRITE_FAILED
      expected = expected.updated(setting, value)
    }
    store.notifyChanged()
    return ApplyResult.APPLIED
  }

  /**
   * Only system_server calls this after native ACK. Settings has no multi-key transaction:
   * consume first, then write the background before enabling, and publish success last.
   * A partial failure must require a fresh press rather than replay over a later manual choice.
   */
  fun applyAcknowledged(
    request: MonetActionRequest, color: Int, initial: Selection, store: Store,
    nativeAcknowledged: Boolean, isCurrent: () -> Boolean,
  ): ApplyResult {
    require(!request.preserveEnabled)
    return applyColors(request, color, initial, store, nativeAcknowledged, { ApplyResult.APPLIED }, isCurrent)
  }

  /**
   * Preserves even the exact raw enable value (including absent/off) and never writes ENABLED.
   * Native policy must already match that state. The final publication verifies MAML before ACK.
   */
  fun applyPreservingEnabled(
    request: MonetActionRequest, color: Int, initial: Selection, store: Store,
    nativeAcknowledged: Boolean, publish: (Selection) -> ApplyResult, isCurrent: () -> Boolean,
  ): ApplyResult {
    require(request.preserveEnabled)
    return applyColors(request, color, initial, store, nativeAcknowledged, publish, isCurrent)
  }

  private fun applyColors(
    request: MonetActionRequest, color: Int, initial: Selection, store: Store,
    nativeAcknowledged: Boolean, publish: (Selection) -> ApplyResult, isCurrent: () -> Boolean,
  ): ApplyResult {
    if (!nativeAcknowledged) return ApplyResult.NATIVE_UNAVAILABLE
    if (!isUnconsumed(request, initial)) return ApplyResult.SELECTION_CHANGED
    var expected = initial
    val writes = listOfNotNull(
      Setting.HANDLED to request.revision.toString(),
      Setting.BACKGROUND to color.toString(),
      Setting.AUTO_COLOR to color.toString(),
      (Setting.ENABLED to "1").takeUnless { request.preserveEnabled },
      request.customization?.let { Setting.CUSTOMIZATION to it.encode() },
    )
    for ((setting, value) in writes) {
      checkSelection(request, expected, store, isCurrent)?.let { return it }
      if (!store.write(setting, value)) return ApplyResult.WRITE_FAILED
      expected = expected.updated(setting, value)
    }
    checkSelection(request, expected, store, isCurrent)?.let { return it }
    store.notifyChanged()
    checkSelection(request, expected, store, isCurrent)?.let { return it }
    val publication = publish(expected)
    if (publication != ApplyResult.APPLIED) return publication
    checkSelection(request, expected, store, isCurrent)?.let { return it }
    return if (store.write(Setting.APPLIED, request.revision.toString())) ApplyResult.APPLIED else ApplyResult.WRITE_FAILED
  }
}
