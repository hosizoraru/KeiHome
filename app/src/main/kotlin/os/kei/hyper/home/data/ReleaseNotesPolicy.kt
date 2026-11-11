package os.kei.hyper.home.data

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
  override fun compareTo(other: ReleaseVersion): Int =
    compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

  override fun toString(): String = "$major.$minor.$patch"

  companion object {
    fun parse(value: String): ReleaseVersion? {
      val parts = value.substringBefore('+').split('.')
      if (parts.size != 3) return null
      val numbers = parts.map { it.toIntOrNull()?.takeIf { number -> number >= 0 } ?: return null }
      return ReleaseVersion(numbers[0], numbers[1], numbers[2])
    }
  }
}

internal data class ReleaseNotesState(
  val observedVersion: String = "",
  val readThrough: String = "",
  val pendingVersions: List<String> = emptyList(),
  val pendingVersion: String = "",
)

internal object ReleaseNotesPolicy {
  fun observe(
    previous: ReleaseNotesState,
    versionName: String,
    updatedInstallation: Boolean,
    publishedVersions: List<String>,
  ): ReleaseNotesState {
    val current = requireNotNull(ReleaseVersion.parse(versionName))
    val available = publishedVersions.mapNotNull(ReleaseVersion::parse).distinct().filter { it <= current }.sorted()
    val newest = available.lastOrNull()
    val read = ReleaseVersion.parse(previous.readThrough)
    // A restored backup can carry old state into a fresh installation.
    if (!updatedInstallation) return ReleaseNotesState(
      current.toString(), listOfNotNull(read, newest).maxOrNull()?.toString().orEmpty(),
    )
    val pending = previous.pendingVersions.mapNotNull(ReleaseVersion::parse).filter { it in available }.toMutableSet()
    val changed = ReleaseVersion.parse(previous.observedVersion) != current
    if (changed) pending += available.filter { read == null || it > read }
    return ReleaseNotesState(current.toString(), previous.readThrough, pending.sortedDescending().map { it.toString() },
      if (changed || previous.pendingVersion == current.toString()) current.toString() else "")
  }

  fun acknowledge(state: ReleaseNotesState): ReleaseNotesState {
    val read = (state.pendingVersions + state.readThrough).mapNotNull(ReleaseVersion::parse).maxOrNull()
    return state.copy(readThrough = read?.toString().orEmpty(), pendingVersions = emptyList(), pendingVersion = "")
  }
}
