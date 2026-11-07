package os.kei.hyper.home.iconpack

/** Applied generation decisions, independent of preview additions and catalog size. */
internal data class IconGenerationManifest(
  val colorOsPackages: Set<String> = emptySet(),
  val considered: Set<String> = emptySet(),
  val nativeAdaptations: Map<String, String> = emptyMap(),
  val installedOnly: Boolean = false,
  val schemaVersion: Int = 2,
) {
  fun encode(): ByteArray {
    require(schemaVersion in 1..2 && (schemaVersion != 1 || !installedOnly))
    return (listOf("KeiHomeIconGeneration$schemaVersion") +
    (if (schemaVersion == 2) listOf("scope\t${if (installedOnly) "installed" else "all"}") else emptyList()) +
    colorOsPackages.sorted().map { "coloros\t$it" } + considered.sorted().map { "target\t$it" } +
    nativeAdaptations.toSortedMap().map { (key, value) -> "native\t$key\t$value" })
    .joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
  }

  companion object {
    const val PATH = "keihome-icon-generation.txt"
    const val MAX_BYTES = 2 * 1024 * 1024
    fun decode(bytes: ByteArray): IconGenerationManifest {
      require(bytes.size in 1..MAX_BYTES)
      val lines = bytes.toString(Charsets.UTF_8).split('\n')
      val schema = when (lines.first()) {
        "KeiHomeIconGeneration1" -> 1
        "KeiHomeIconGeneration2" -> 2
        else -> error("Unknown generation schema")
      }
      require(lines.last().isEmpty() && lines.size <= 30003)
      val packages = linkedSetOf<String>(); val targets = linkedSetOf<String>(); val native = linkedMapOf<String, String>()
      var scope: String? = null
      fun target(value: String) = IconPackComponent.parse(value)?.flattened == value
      lines.drop(1).dropLast(1).forEach { line ->
        val row = line.split('\t')
        when (row.first()) {
          "scope" -> {
            require(schema == 2 && row.size == 2 && scope == null && row[1] in setOf("all", "installed"))
            scope = row[1]
          }
          "coloros" -> require(row.size == 2 && validDottedName(row[1]) && packages.add(row[1]))
          "target" -> require(row.size == 2 && target(row[1]) && targets.add(row[1]))
          "native" -> require(row.size == 3 && target(row[1]) &&
            (row[2] == "none" || row[2].matches(Regex("[0-9a-f]{64}"))) && native.put(row[1], row[2]) == null)
          else -> error("Unknown generation decision")
        }
      }
      require(schema == 1 || scope != null)
      return IconGenerationManifest(packages, targets, native, scope == "installed", schema).also {
        require(it.encode().contentEquals(bytes)) { "Noncanonical generation decisions" }
      }
    }
  }
}
