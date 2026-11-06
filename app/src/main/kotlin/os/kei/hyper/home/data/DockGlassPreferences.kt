package os.kei.hyper.home.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class DockGlassCornerStyle(val nativeValue: Int) {
  ROUNDED(0), RECENTS(1), PAD(2), CONCENTRIC(3), CUSTOM(4);
  companion object {
    fun from(value: Int) = entries.firstOrNull { it.nativeValue == (value and 7) } ?: RECENTS
  }
}

@androidx.compose.runtime.Immutable
internal data class DockGlassChoice(
  val enabled: Boolean = false,
  val cornerStyle: DockGlassCornerStyle = DockGlassCornerStyle.RECENTS,
  val verticalPaddingDp: Int = 18,
  val verticalOffsetDp: Int = 0,
  val customRadiusDp: Int = 24,
) {
  init {
    require(verticalPaddingDp in 0..32)
    require(verticalOffsetDp in -48..96)
    require(customRadiusDp in 0..64)
  }

  val options: Int get() = DockGlassOptions.encode(this)
}

/** Command 8 word 5. Default Rounded/Recents remain byte-for-byte compatible with old payloads. */
internal object DockGlassOptions {
  const val MAX_MASK = 0x00ffffff

  fun encode(choice: DockGlassChoice): Int {
    val padding = if (choice.verticalPaddingDp == 18) 0 else choice.verticalPaddingDp + 1
    val radius = if (choice.cornerStyle == DockGlassCornerStyle.CUSTOM) choice.customRadiusDp else 0
    return choice.cornerStyle.nativeValue or (padding shl 3) or (radius shl 9) or
      ((choice.verticalOffsetDp and 0xff) shl 16)
  }

  fun isValid(options: Int): Boolean {
    if (options and MAX_MASK.inv() != 0) return false
    val style = options and 7
    val padding = (options ushr 3) and 63
    val radius = (options ushr 9) and 127
    val offset = (options ushr 16).toByte().toInt()
    return style in 0..4 && padding in 0..33 && radius in 0..64 &&
      (style == DockGlassCornerStyle.CUSTOM.nativeValue || radius == 0) && offset in -48..96
  }

  fun decode(options: Int): DockGlassChoice? {
    if (!isValid(options)) return null
    val padding = (options ushr 3) and 63
    val style = DockGlassCornerStyle.from(options)
    return DockGlassChoice(
      cornerStyle = style,
      verticalPaddingDp = if (padding == 0) 18 else padding - 1,
      verticalOffsetDp = (options ushr 16).toByte().toInt(),
      customRadiusDp = if (style == DockGlassCornerStyle.CUSTOM) (options ushr 9) and 127 else 24,
    )
  }
}

internal object DockGlassPreferences {
  const val GROUP = ThemeEffectsPreferences.GROUP
  const val KEY = "dock_glass_background"
  const val CORNER_STYLE_KEY = "dock_glass_corner_style"
  const val VERTICAL_PADDING_KEY = "dock_glass_vertical_padding_dp"
  const val VERTICAL_OFFSET_KEY = "dock_glass_vertical_offset_dp"
  const val CUSTOM_RADIUS_KEY = "dock_glass_custom_radius_dp"
  fun isPreferenceKey(key: String?): Boolean = when (key) {
    null, KEY, CORNER_STYLE_KEY, VERTICAL_PADDING_KEY, VERTICAL_OFFSET_KEY, CUSTOM_RADIUS_KEY -> true
    else -> false
  }
  private val mutex = Mutex()
  private val unconfirmed = WeakHashMap<SharedPreferences, Boolean>()

  fun read(preferences: SharedPreferences?): Boolean = preferences?.getBoolean(KEY, false) ?: false

  fun readChoice(preferences: SharedPreferences?): DockGlassChoice {
    // One snapshot prevents mixing the enabled/geometry fields of two committed choices.
    val values = preferences?.all.orEmpty()
    return DockGlassChoice(
      enabled = values[KEY] as? Boolean ?: false,
      cornerStyle = DockGlassCornerStyle.from((values[CORNER_STYLE_KEY] as? Int)?.takeIf { it in 0..4 } ?: 1),
      verticalPaddingDp = (values[VERTICAL_PADDING_KEY] as? Int)?.takeIf { it in 0..32 } ?: 18,
      verticalOffsetDp = (values[VERTICAL_OFFSET_KEY] as? Int)?.takeIf { it in -48..96 } ?: 0,
      customRadiusDp = (values[CUSTOM_RADIUS_KEY] as? Int)?.takeIf { it in 0..64 } ?: 24,
    )
  }

  suspend fun read(service: XposedService): DockGlassChoice = withContext(Dispatchers.IO) { readConfirmed(remote(service)) }

  internal suspend fun readConfirmed(preferences: SharedPreferences): DockGlassChoice = withContext(Dispatchers.IO) {
    mutex.withLock {
      check(!unconfirmed.containsKey(preferences)) { "Remote save is unconfirmed" }
      readChoice(preferences)
    }
  }

  suspend fun save(service: XposedService, choice: DockGlassChoice) = withContext(Dispatchers.IO) { save(remote(service), choice) }

  internal suspend fun save(preferences: SharedPreferences, choice: DockGlassChoice) = withContext(Dispatchers.IO) {
    mutex.withLock {
      // RemotePreferences can change its local cache before a failed Binder commit.
      unconfirmed[preferences] = true
      check(preferences.edit().putBoolean(KEY, choice.enabled)
        .putInt(CORNER_STYLE_KEY, choice.cornerStyle.nativeValue)
        .putInt(VERTICAL_PADDING_KEY, choice.verticalPaddingDp)
        .putInt(VERTICAL_OFFSET_KEY, choice.verticalOffsetDp)
        .putInt(CUSTOM_RADIUS_KEY, choice.customRadiusDp).commit()) { "Remote save is unconfirmed" }
      unconfirmed.remove(preferences)
      Unit
    }
  }

  private fun remote(service: XposedService): SharedPreferences {
    if (service.apiVersion < 102 || service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L)
      throw UnsupportedOperationException("Framework remote preferences unavailable")
    return service.getRemotePreferences(GROUP)
  }
}
