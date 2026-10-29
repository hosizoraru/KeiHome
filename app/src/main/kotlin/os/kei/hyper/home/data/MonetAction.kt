package os.kei.hyper.home.data

import android.content.ContentResolver
import android.content.SharedPreferences
import android.os.Process
import android.provider.Settings
import io.github.libxposed.service.XposedService

internal data class MonetActionRequest(
  val revision: Long, val userId: Int, val customization: MonetCustomization? = null,
)

internal enum class MonetActionStatus { IDLE, APPLYING, APPLIED, UNCONFIRMED, UNAVAILABLE, FAILED }

/** One explicit, user-scoped request. Remote preferences carry intent, never an application ACK. */
internal object MonetAction {
  const val GROUP = "monet"
  const val REQUEST_REVISION = "request_revision"
  const val REQUEST_USER = "request_user"
  const val REQUEST_CUSTOMIZATION = "request_customization"
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
    val revision = snapshot[REQUEST_REVISION] as? Long ?: return null
    val user = snapshot[REQUEST_USER] as? Int ?: return null
    val customization = MonetCustomization.decode(snapshot[REQUEST_CUSTOMIZATION] as? String)
    return MonetActionRequest(revision, user, customization).takeIf { revision > 0 && user >= 0 }
  }

  fun read(preferences: SharedPreferences?): MonetActionRequest? =
    preferences?.let { decode(it.all) }

  fun nextRevision(previous: Long, now: Long): Long {
    require(previous < Long.MAX_VALUE) { "Monet request revision exhausted" }
    return maxOf(1L, now, previous + 1L)
  }

  private fun storedRevision(raw: String?): Long? =
    if (raw == null) 0L else raw.toLongOrNull()?.takeIf { it >= 0L }

  fun pending(
    request: MonetActionRequest?, userId: Int, selection: Selection, now: Long,
  ): MonetActionRequest? {
    request ?: return null
    val handled = storedRevision(selection.handled) ?: return null
    val applied = storedRevision(selection.applied) ?: return null
    return request.takeIf {
      it.userId == userId && it.revision > maxOf(handled, applied) &&
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

  /** API 102 sends the revision, user and complete choice in one Binder update bundle. */
  @Synchronized
  fun submit(service: XposedService, customization: MonetCustomization? = null): MonetActionRequest {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    }
    return submit(service.getRemotePreferences(GROUP), Process.myUid(), System.currentTimeMillis(), customization)
  }

  internal fun submit(
    preferences: SharedPreferences, appUid: Int, now: Long, customization: MonetCustomization? = null,
  ): MonetActionRequest {
    require(appUid >= 0)
    val previous = preferences.all[REQUEST_REVISION] as? Long ?: 0L
    val request = MonetActionRequest(nextRevision(previous, now), appUid / 100_000, customization)
    // commit can update the service client's cache before a failed Binder write. Never infer
    // persistence from that cache and never report application success from commit alone.
    check(preferences.edit().putLong(REQUEST_REVISION, request.revision)
      .putInt(REQUEST_USER, request.userId)
      .putString(REQUEST_CUSTOMIZATION, customization?.encode()).commit()) { "Monet request commit failed" }
    return request
  }

  fun isApplied(resolver: ContentResolver, request: MonetActionRequest): Boolean = try {
    Settings.Secure.getString(resolver, Setting.APPLIED.key)?.toLongOrNull() == request.revision
  } catch (_: Exception) {
    // Custom Secure keys may be unreadable to an app on an OEM build. This is not success.
    false
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
    if (!nativeAcknowledged) return ApplyResult.NATIVE_UNAVAILABLE
    val handled = storedRevision(initial.handled) ?: return ApplyResult.SELECTION_CHANGED
    val applied = storedRevision(initial.applied) ?: return ApplyResult.SELECTION_CHANGED
    if (request.revision <= maxOf(handled, applied)) return ApplyResult.SELECTION_CHANGED
    var expected = initial
    val writes = listOfNotNull(
      Setting.HANDLED to request.revision.toString(),
      Setting.BACKGROUND to color.toString(),
      Setting.AUTO_COLOR to color.toString(),
      Setting.ENABLED to "1",
      request.customization?.let { Setting.CUSTOMIZATION to it.encode() },
      Setting.APPLIED to request.revision.toString(),
    )
    for ((setting, value) in writes) {
      checkSelection(request, expected, store, isCurrent)?.let { return it }
      if (setting == Setting.APPLIED) {
        store.notifyChanged()
        checkSelection(request, expected, store, isCurrent)?.let { return it }
      }
      if (!store.write(setting, value)) return ApplyResult.WRITE_FAILED
      expected = expected.updated(setting, value)
    }
    return ApplyResult.APPLIED
  }
}
