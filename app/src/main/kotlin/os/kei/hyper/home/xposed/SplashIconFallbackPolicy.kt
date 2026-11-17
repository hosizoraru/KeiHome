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
      !input.plainBackground || !input.plainOverlay -> "artwork-or-transparency"
      else -> "fill"
    }

  fun ownPalette(packageName: String, ownPackage: String, palette: Long): Boolean =
    packageName == ownPackage && palette ushr 48 == 1L
}
