package os.kei.hyper.home.iconpack

/** Immutable product resources, initialized on preview/snapshot workers. */
internal object OriginalIconShape {
  val mask: NativeAdaptiveIconMask? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    NativeAdaptiveIconMask.load()
  }
}
