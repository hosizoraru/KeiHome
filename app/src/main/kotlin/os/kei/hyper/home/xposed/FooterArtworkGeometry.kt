package os.kei.hyper.home.xposed

/** Returns the top of the uncropped image when aligned to the window bottom. */
internal fun bottomAlignedFooterTop(
  windowBottom: Int,
  insetTop: Int,
  earliestArtworkTop: Int,
  artworkHeight: Float,
): Float? {
  val top = windowBottom - artworkHeight
  return top.takeIf {
    windowBottom > insetTop && insetTop > 0 && it.isFinite() &&
      it >= maxOf(0, earliestArtworkTop) && it < insetTop
  }
}
