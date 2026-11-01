package os.kei.hyper.home.xposed

import java.util.Locale
import os.kei.hyper.home.iconpack.NativePaletteIcon

/** Uses the existing monitor worker and the same user context as the acknowledged native policy. */
internal object GeneratedPalettePublication {
  interface Store {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun notifyChanged(key: String)
  }

  enum class Result { UNCHANGED, CHANGED, NATIVE_UNAVAILABLE, SELECTION_CHANGED, WRITE_FAILED }

  fun publish(
    colors: IconColors?, nativeAcknowledged: Boolean, store: Store, isCurrent: () -> Boolean,
  ): Result {
    if (!nativeAcknowledged) return Result.NATIVE_UNAVAILABLE
    if (!isCurrent()) return Result.SELECTION_CHANGED
    fun hex(color: Int) = "#" + color.toUInt().toString(16).padStart(8, '0').uppercase(Locale.ROOT)
    // Settings has no multi-key transaction. Seed both colors before the first enable notification.
    val targets = if (colors == null) listOf(NativePaletteIcon.ENABLED_KEY to "0") else listOf(
      NativePaletteIcon.FOREGROUND_KEY to hex(colors.foreground),
      NativePaletteIcon.BACKGROUND_KEY to hex(colors.background),
      NativePaletteIcon.ENABLED_KEY to "1",
    )
    var changed = false
    for ((key, value) in targets) {
      if (!isCurrent()) return Result.SELECTION_CHANGED
      if (store.read(key) == value) continue
      if (!isCurrent()) return Result.SELECTION_CHANGED
      if (!store.write(key, value)) {
        if (isCurrent() && store.read(NativePaletteIcon.ENABLED_KEY) != "0" && isCurrent() &&
          store.write(NativePaletteIcon.ENABLED_KEY, "0") && isCurrent()) {
          store.notifyChanged(NativePaletteIcon.ENABLED_KEY)
        }
        return Result.WRITE_FAILED
      }
      changed = true
      if (!isCurrent()) return Result.SELECTION_CHANGED
      store.notifyChanged(key)
    }
    return if (changed) Result.CHANGED else Result.UNCHANGED
  }
}
