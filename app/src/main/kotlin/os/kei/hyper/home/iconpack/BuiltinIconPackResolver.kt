package os.kei.hyper.home.iconpack

/** Installation must work even when the optional catalog page has not been read. */
internal suspend fun resolveBuiltinIconPack(
  key: String,
  loaded: List<IconPackDescriptor>,
  fetch: suspend () -> IconPackScanResult,
): IconPackDescriptor? =
  loaded.firstOrNull { it.builtinKey == key } ?: fetch().packs.firstOrNull { it.builtinKey == key }
