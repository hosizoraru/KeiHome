package os.kei.hyper.home.xposed

/** Keep the OEM black/white composites while retaining contrast if its sample still describes the previous page. */
internal fun plainGesturePaint(color: Int): Int {
  val alpha = color ushr 24
  val gray = color and 255
  if (alpha == 0 || alpha == 255 || color and 0x00ffffff != gray * 0x010101) return color
  // Taper the adjustment for nearly transparent or opaque vendor paints.
  val extra = minOf(48, alpha / 2, 255 - alpha)
  val opacity = alpha + extra
  val channel = (gray * alpha + (255 - gray) * extra + opacity / 2) / opacity
  return (opacity shl 24) or (channel * 0x010101)
}
