package os.kei.hyper.home.status

/** A missing provider identity must not erase bounded metadata read from the actual module. */
internal fun SystemIconThemeReport.withIdentity(
  title: String?, localId: String?, isDefault: Boolean?, nativeHash: String?,
): SystemIconThemeReport {
  val identifiedSource = when (isDefault) {
    true -> SystemIconThemeReport.Source.DEFAULT
    false -> SystemIconThemeReport.Source.THEME
    null -> source
  }
  val sameSource = identifiedSource == source
  return copy(source = identifiedSource,
    title = title?.takeIf { it.isNotBlank() } ?: this.title.takeIf { sameSource },
    version = version.takeIf { sameSource },
    localId = localId, nativeHash = nativeHash)
}
