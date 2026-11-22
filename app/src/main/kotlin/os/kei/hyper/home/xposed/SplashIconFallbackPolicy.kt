package os.kei.hyper.home.xposed

/** Only fill missing icons; authored launch surfaces and snapshots stay native. */
internal object SplashIconFallbackPolicy {
  data class Input(
    val enabled: Boolean,
    val initialType: Int,
    val finalType: Int,
    val hasSnapshot: Boolean,
    val hasExplicitIcon: Boolean,
    val hasBranding: Boolean,
    val protectedWindow: Boolean,
    val plainBackground: Boolean,
    val plainOverlay: Boolean,
    val ownPalette: Boolean = false,
    val previewDisabled: Boolean = false,
    val startingFlags: Int? = null,
    val neutralBacking: Boolean = false,
  )

  fun beforeBackground(
    enabled: Boolean, initialType: Int, finalType: Int,
    hasSnapshot: Boolean, hasExplicitIcon: Boolean, hasBranding: Boolean,
    ownPalette: Boolean = false,
  ): String? = when {
    !enabled && !ownPalette -> "disabled"
    hasSnapshot -> "snapshot"
    initialType != 1 && initialType != 3 && initialType != 6 -> "initial-type"
    finalType != 3 && finalType != 6 && !(ownPalette && finalType == 1) -> "existing-icon-or-special-type"
    hasExplicitIcon && !ownPalette -> "explicit-icon"
    hasBranding -> "branding"
    else -> null
  }

  fun reason(input: Input): String =
    beforeBackground(input.enabled, input.initialType, input.finalType,
      input.hasSnapshot, input.hasExplicitIcon, input.hasBranding, input.ownPalette) ?: when {
      input.protectedWindow -> "protected-window"
      input.previewDisabled && !acceptsExistingColdPreview(input) -> "disabled-preview"
      !input.plainBackground || !input.plainOverlay -> "artwork-or-transparency"
      else -> "fill"
    }

  // StartingWindowInfo.TYPE_PARAMETER_* (the Shell request, not process state at build time).
  private const val NEW_TASK = 0x1
  private const val PROCESS_RUNNING = 0x4
  private const val ACTIVITY_CREATED = 0x10
  private const val USE_SOLID_COLOR = 0x20
  private const val ACTIVITY_DRAWN = 0x40
  private const val WINDOWLESS = 0x100
  private const val LEGACY_SPLASH = Int.MIN_VALUE
  private const val INELIGIBLE_PREVIEW_FLAGS = PROCESS_RUNNING or ACTIVITY_CREATED or USE_SOLID_COLOR or
    ACTIVITY_DRAWN or WINDOWLESS or LEGACY_SPLASH

  private fun acceptsExistingColdPreview(input: Input): Boolean {
    val flags = input.startingFlags ?: return false
    // HyperOS can create a modern splash despite windowDisablePreview. Only complete one
    // that Shell is already building as its type-6, color-only variant.
    // The solid-color exit-listener bit is independent of whether a solid splash was requested.
    return input.enabled && input.initialType == 1 && input.finalType == 6 &&
      input.neutralBacking && flags and NEW_TASK != 0 && flags and INELIGIBLE_PREVIEW_FLAGS == 0
  }

  fun ownPalette(packageName: String, ownPackage: String, palette: Long): Boolean =
    packageName == ownPackage && palette ushr 48 == 1L
}
