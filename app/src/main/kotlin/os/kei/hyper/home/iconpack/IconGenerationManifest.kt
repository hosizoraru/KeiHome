package os.kei.hyper.home.iconpack

/** Applied generation decisions, independent of preview additions and catalog size. */
internal data class IconGenerationManifest(
  val colorOsPackages: Set<String> = emptySet(),
  val considered: Set<String> = emptySet(),
  val nativeAdaptations: Map<String, String> = emptyMap(),
) {
  fun encode(): ByteArray = (listOf("KeiHomeIconGeneration1") +
    colorOsPackages.sorted().map { "coloros\t$it" } + considered.sorted().map { "target\t$it" } +
    nativeAdaptations.toSortedMap().map { (key, value) -> "native\t$key\t$value" })
    .joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

  companion object {
    const val PATH = "keihome-icon-generation.txt"
    const val MAX_BYTES = 2 * 1024 * 1024
    fun decode(bytes: ByteArray): IconGenerationManifest {
      require(bytes.size in 1..MAX_BYTES)
      val lines = bytes.toString(Charsets.UTF_8).split('\n')
      require(lines.first() == "KeiHomeIconGeneration1" && lines.last().isEmpty() && lines.size <= 30002)
      val packages = linkedSetOf<String>(); val targets = linkedSetOf<String>(); val native = linkedMapOf<String, String>()
      fun target(value: String) = IconPackComponent.parse(value)?.flattened == value
      lines.drop(1).dropLast(1).forEach { line ->
        val row = line.split('\t')
        when (row.first()) {
          "coloros" -> require(row.size == 2 && validDottedName(row[1]) && packages.add(row[1]))
          "target" -> require(row.size == 2 && target(row[1]) && targets.add(row[1]))
          "native" -> require(row.size == 3 && target(row[1]) &&
            (row[2] == "none" || row[2].matches(Regex("[0-9a-f]{64}"))) && native.put(row[1], row[2]) == null)
          else -> error("Unknown generation decision")
        }
      }
      return IconGenerationManifest(packages, targets, native).also {
        require(it.encode().contentEquals(bytes)) { "Noncanonical generation decisions" }
      }
    }
  }
}
