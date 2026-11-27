package os.kei.hyper.home.iconpack

import os.kei.hyper.home.data.RecommendedIconMappings

/** Applied generation decisions, independent of preview additions and catalog size. */
internal data class IconGenerationManifest(
  val colorOsPackages: Set<String> = emptySet(),
  val considered: Set<String> = emptySet(),
  val nativeAdaptations: Map<String, String> = emptyMap(),
  val installedOnly: Boolean = false,
  val schemaVersion: Int = 9,
  val completeSystemIconConfig: Boolean = false,
  val iconMaskIdentity: String = if (completeSystemIconConfig) IconMaskPolicy.DEFAULT_IDENTITY else "original",
  val activeLauncherTargets: Set<String> = emptySet(),
  val preservedIconPackages: Set<String> = emptySet(),
  val recommendedMappings: RecommendedIconMappings =
    when {
      schemaVersion < 9 -> RecommendedIconMappings.LEGACY
      schemaVersion < 18 -> RecommendedIconMappings.BEFORE_WEIBO
      else -> RecommendedIconMappings()
    },
  val customIconIdentity: String? = null,
  val monetUnadaptedIcons: Boolean = false,
  val monetFullBleedIconPadding: Boolean = false,
  val extraIconPackages: Set<String> = emptySet(),
) {
  fun encode(): ByteArray {
    require(schemaVersion in 1..CURRENT_SCHEMA && (schemaVersion != 1 || !installedOnly) &&
      (schemaVersion >= 3 || !completeSystemIconConfig))
    require(validMaskIdentity(iconMaskIdentity) &&
      (completeSystemIconConfig == (iconMaskIdentity != "original")))
    require((schemaVersion >= 6 || activeLauncherTargets.isEmpty()) &&
      activeLauncherTargets.all(::validActiveTarget))
    require((schemaVersion >= 7 || preservedIconPackages.isEmpty()) &&
      preservedIconPackages.size <= 4096 &&
      preservedIconPackages.all(os.kei.hyper.home.data.PreservedIcons::isValidPackageName))
    require(schemaVersion >= 8 || recommendedMappings == RecommendedIconMappings.LEGACY)
    require(schemaVersion >= 9 || !recommendedMappings.phoneCompanions)
    require(schemaVersion >= 18 || !recommendedMappings.weiboLite)
    require(customIconIdentity == null || schemaVersion >= 10 && customIconIdentity.matches(Regex("[0-9a-f]{64}")))
    require(schemaVersion >= 11 || !monetUnadaptedIcons)
    require(!monetFullBleedIconPadding || schemaVersion >= 15)
    require((schemaVersion >= 17 || extraIconPackages.isEmpty()) &&
      extraIconPackages == ExtraIcons.normalize(extraIconPackages))
    return (listOf("KeiHomeIconGeneration$schemaVersion") +
    listOfNotNull(customIconIdentity?.let { "custom\t$it" }) +
    (if (schemaVersion >= 11) listOf("monet-fallback\t${if (monetUnadaptedIcons) "on" else "off"}") else emptyList()) +
    (if (schemaVersion >= 15) listOf("monet-inset\t${if (monetFullBleedIconPadding) "on" else "off"}") else emptyList()) +
    (if (schemaVersion >= 2) listOf("scope\t${if (installedOnly) "installed" else "all"}") else emptyList()) +
    (if (schemaVersion >= 3) listOf("shape\t${if (completeSystemIconConfig) "complete" else "original"}") else emptyList()) +
    (if (schemaVersion >= 4) listOf("mask\t$iconMaskIdentity") else emptyList()) +
    (if (schemaVersion >= 8) listOf("mappings\t${recommendedMappings.bits}") else emptyList()) +
    colorOsPackages.sorted().map { "coloros\t$it" } + considered.sorted().map { "target\t$it" } +
    nativeAdaptations.toSortedMap().map { (key, value) -> "native\t$key\t$value" } +
    activeLauncherTargets.sorted().map { "active\t$it" } +
    preservedIconPackages.sorted().map { "preserve\t$it" } +
    extraIconPackages.sorted().map { "extra\t$it" })
    .also { require(it.size < maxLines(schemaVersion)) }
    .joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
    .also { require(it.size <= MAX_BYTES) }
  }

  companion object {
    const val CURRENT_SCHEMA = 24
    const val TELEGRAM_COMPONENT_SCHEMA = 21
    const val LAYERED_MONET_SCHEMA = 24
    const val LAYERED_EXTRA_ARTWORK_SCHEMA = 20
    const val PATH = "keihome-icon-generation.txt"
    const val MAX_BYTES = 2 * 1024 * 1024
    fun decode(bytes: ByteArray): IconGenerationManifest {
      require(bytes.size in 1..MAX_BYTES)
      val lines = bytes.toString(Charsets.UTF_8).split('\n')
      val schema = when (lines.first()) {
        "KeiHomeIconGeneration1" -> 1
        "KeiHomeIconGeneration2" -> 2
        "KeiHomeIconGeneration3" -> 3
        "KeiHomeIconGeneration4" -> 4
        "KeiHomeIconGeneration5" -> 5
        "KeiHomeIconGeneration6" -> 6
        "KeiHomeIconGeneration7" -> 7
        "KeiHomeIconGeneration8" -> 8
        "KeiHomeIconGeneration9" -> 9
        "KeiHomeIconGeneration10" -> 10
        "KeiHomeIconGeneration11" -> 11
        "KeiHomeIconGeneration12" -> 12
        "KeiHomeIconGeneration13" -> 13
        "KeiHomeIconGeneration14" -> 14
        "KeiHomeIconGeneration15" -> 15
        "KeiHomeIconGeneration16" -> 16
        "KeiHomeIconGeneration17" -> 17
        "KeiHomeIconGeneration18" -> 18
        "KeiHomeIconGeneration19" -> 19
        "KeiHomeIconGeneration20" -> 20
        "KeiHomeIconGeneration21" -> 21
        "KeiHomeIconGeneration22" -> 22
        "KeiHomeIconGeneration23" -> 23
        "KeiHomeIconGeneration24" -> 24
        else -> error("Unknown generation schema")
      }
      require(lines.last().isEmpty() && lines.size <= maxLines(schema))
      val packages = linkedSetOf<String>(); val targets = linkedSetOf<String>(); val native = linkedMapOf<String, String>()
      val active = linkedSetOf<String>()
      val preserved = linkedSetOf<String>()
      val extra = linkedSetOf<String>()
      var scope: String? = null
      var shape: String? = null
      var mask: String? = null
      var mappings: RecommendedIconMappings? = null
      var custom: String? = null
      var monetFallback: Boolean? = null
      var monetInset: Boolean? = null
      fun target(value: String) = IconPackComponent.parse(value)?.flattened == value
      lines.drop(1).dropLast(1).forEach { line ->
        val row = line.split('\t')
        when (row.first()) {
          "monet-inset" -> {
            require(schema >= 15 && row.size == 2 && monetInset == null && row[1] in setOf("on", "off"))
            monetInset = row[1] == "on"
          }
          "monet-fallback" -> {
            require(schema >= 11 && row.size == 2 && monetFallback == null && row[1] in setOf("on", "off"))
            monetFallback = row[1] == "on"
          }
          "custom" -> {
            require(schema >= 10 && row.size == 2 && custom == null && row[1].matches(Regex("[0-9a-f]{64}")))
            custom = row[1]
          }
          "mappings" -> {
            val bits = row.getOrNull(1)?.toIntOrNull()
            val maxBits = when {
              schema >= 18 -> 15
              schema >= 9 -> 7
              else -> 3
            }
            require(schema >= 8 && row.size == 2 && mappings == null &&
              bits != null && bits in 0..maxBits && bits.toString() == row[1])
            mappings = RecommendedIconMappings.fromBits(bits)
          }
          "scope" -> {
            require(schema >= 2 && row.size == 2 && scope == null && row[1] in setOf("all", "installed"))
            scope = row[1]
          }
          "shape" -> {
            require(schema >= 3 && row.size == 2 && shape == null && row[1] in setOf("complete", "original"))
            shape = row[1]
          }
          "mask" -> {
            require(schema >= 4 && row.size == 2 && mask == null && validMaskIdentity(row[1]))
            mask = row[1]
          }
          "coloros" -> require(row.size == 2 && validDottedName(row[1]) && packages.add(row[1]))
          "target" -> require(row.size == 2 && target(row[1]) && targets.add(row[1]))
          "native" -> require(row.size == 3 && target(row[1]) &&
            (row[2] == "none" || row[2].matches(Regex("[0-9a-f]{64}"))) && native.put(row[1], row[2]) == null)
          "active" -> require(schema >= 6 && row.size == 2 && validActiveTarget(row[1]) && active.add(row[1]))
          "preserve" -> require(schema >= 7 && row.size == 2 &&
            os.kei.hyper.home.data.PreservedIcons.isValidPackageName(row[1]) &&
            preserved.size < 4096 && preserved.add(row[1]))
          "extra" -> require(schema >= 17 && row.size == 2 &&
            ExtraIcon.entries.any { it.packageName == row[1] } && extra.add(row[1]))
          else -> error("Unknown generation decision")
        }
      }
      require(schema == 1 || scope != null)
      require(schema < 3 || shape != null)
      require(schema < 4 || mask != null)
      require(schema < 8 || mappings != null)
      require(schema < 11 || monetFallback != null)
      require(schema < 15 || monetInset != null)
      return IconGenerationManifest(packages, targets, native, scope == "installed", schema, shape == "complete",
        mask ?: if (shape == "complete") IconMaskPolicy.DEFAULT_IDENTITY else "original", active, preserved,
        mappings ?: RecommendedIconMappings.LEGACY, custom, monetFallback == true, monetInset == true, extra).also {
        require(it.encode().contentEquals(bytes)) { "Noncanonical generation decisions" }
      }
    }
    // Active components can add one row per previously considered target.
    private fun maxLines(schema: Int) = if (schema >= 17) 60013 + ExtraIcon.entries.size
      else if (schema >= 15) 60013 else if (schema >= 10) 60012 else if (schema >= 6) 60005 else 30005
    private fun validActiveTarget(value: String): Boolean {
      val component = IconPackComponent.parse(value) ?: return false
      return component.className != null && component.flattened == value
    }
    private fun validMaskIdentity(value: String) = value in setOf("original", IconMaskPolicy.DEFAULT_IDENTITY) ||
      value.matches(Regex("custom-[0-9a-f]{64}"))
  }
}
