package os.kei.hyper.home.iconpack

/** Immutable stock shape, independent of pending settings and the active theme. */
internal object OriginalIconShape {
  val mask: NativeAdaptiveIconMask by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    resolveOriginalIconShape { NativeAdaptiveIconMask.load() }
  }
}

internal fun resolveOriginalIconShape(loadNative: () -> NativeAdaptiveIconMask?): NativeAdaptiveIconMask =
  loadNative() ?: NativeAdaptiveIconMask.fromPathData(
    "retained-hyperos4", NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA)
