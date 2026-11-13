package os.kei.hyper.home.iconpack

internal object IconBuildInventory {
  const val NAMES_PATH = "keihome-browser-native-names.txt"
  const val SOURCE_PATH = "keihome-build-source.txt"
  const val MAX_BYTES = 16 * 1024 * 1024
  private val maxNames = IconPackLimits().maxEntries

  fun encodeNames(names: Set<String>): ByteArray {
    require(names.size <= maxNames && names.all(::validNativeIconName))
    return names.sorted().joinToString("\n", postfix = if (names.isEmpty()) "" else "\n")
      .toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
  }

  fun decodeNames(bytes: ByteArray): Set<String> {
    require(bytes.size <= MAX_BYTES)
    val names = bytes.toString(Charsets.UTF_8).split('\n').dropLast(1)
    require(names.all(::validNativeIconName) && names.size <= maxNames)
    return names.toSet().also { require(encodeNames(it).contentEquals(bytes)) }
  }

  fun sourceIdentity(pack: IconPackDescriptor): String = pack.id + "\n" + when (val source = pack.source) {
    is IconPackSource.Installed -> "${source.packageName}:${source.versionCode}:${source.updatedAt}:palette-apk-1"
    is IconPackSource.Imported -> source.record.id + if (pack.format == IconPackFormat.APK) ":palette-apk-1" else if (pack.entries.any {
      (it.light as? IconPackArtwork.Archive)?.palettePair == true
    }) ":native-pair-1" else ""
    is IconPackSource.Builtin -> source.record.id +
      if (pack.builtinKey?.startsWith("coloros-") == true) ":${BuiltinColorOsMappings.REVISION}" else ""
    is IconPackSource.Generated -> source.digest
  } + if (pack.format in setOf(IconPackFormat.MIUI_MTZ, IconPackFormat.MIUI_ICONS))
    ":${NativeDefaultAliasCompletion.REVISION}" else ""

  fun decodeSource(bytes: ByteArray): String {
    require(bytes.size in 1..4096)
    val value = bytes.toString(Charsets.UTF_8)
    val lines = value.split('\n')
    require(lines.size == 2 && lines.all { line ->
      line.length in 1..2048 && line.all { it.isLetterOrDigit() || it in ":._-$" }
    })
    require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes))
    return value
  }
}
