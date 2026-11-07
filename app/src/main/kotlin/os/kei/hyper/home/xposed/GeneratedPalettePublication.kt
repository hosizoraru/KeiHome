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
    // Older applied themes still observe the scalar keys. New themes observe only the
    // final packed write, so a color/enable change arrives as one indivisible value.
    val targets = if (colors == null) listOf(
      NativePaletteIcon.ENABLED_KEY to "0", NativePaletteIcon.STATE_KEY to "0",
    ) else listOf(
      NativePaletteIcon.FOREGROUND_KEY to hex(colors.foreground),
      NativePaletteIcon.BACKGROUND_KEY to hex(colors.background),
      NativePaletteIcon.ENABLED_KEY to "1",
      NativePaletteIcon.STATE_KEY to NativePaletteIcon.packedState(colors.foreground, colors.background).toString(),
    )
    var changed = false
    for ((key, value) in targets) {
      if (!isCurrent()) return Result.SELECTION_CHANGED
      if (store.read(key) == value) continue
      if (!isCurrent()) return Result.SELECTION_CHANGED
      if (!store.write(key, value)) {
        for (disableKey in listOf(NativePaletteIcon.STATE_KEY, NativePaletteIcon.ENABLED_KEY)) {
          if (isCurrent() && store.read(disableKey) != "0" && isCurrent() &&
            store.write(disableKey, "0") && isCurrent()) {
            store.notifyChanged(disableKey)
          }
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
