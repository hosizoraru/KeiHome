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
  val restartReminderVersion: String = "",
  val restartReminderReadThrough: String = "",
  val unreadVersions: List<String> = emptyList(),
  val readVersions: List<String> = emptyList(),
  val readTrackingInitialized: Boolean = false,
)

internal object ReleaseNotesPolicy {
  fun observe(
    previous: ReleaseNotesState,
    versionName: String,
    updatedInstallation: Boolean,
    publishedVersions: List<String>,
    restartVersions: List<String> = emptyList(),
    initialUnreadVersions: List<String> = publishedVersions,
    newInstallation: Boolean = false,
  ): ReleaseNotesState {
    val core = observePresentation(previous, versionName, updatedInstallation, publishedVersions, restartVersions)
    val current = ReleaseVersion.parse(versionName) ?: return previous
    val available = normalize(publishedVersions).filter { ReleaseVersion.parse(it)!! <= current }
    val initialized = previous.readTrackingInitialized && !newInstallation
    val initial = when {
      !updatedInstallation -> available.filter { it == current.toString() }
      previous.observedVersion.isBlank() && previous.readThrough.isBlank() ->
        core.pendingVersions.filter { it in initialUnreadVersions }
      else -> core.pendingVersions
    }
    val read = if (initialized) normalize(previous.readVersions) else available.filter { it !in initial }
    val unread = normalize(if (initialized) previous.unreadVersions + core.pendingVersions else initial)
      .filter { it !in read }
    return core.copy(
      pendingVersions = core.pendingVersions.filter { it !in read },
      unreadVersions = unread, readVersions = read, readTrackingInitialized = true,
    )
  }

  private fun normalize(versions: List<String>): List<String> =
    versions.mapNotNull(ReleaseVersion::parse).distinct().sortedDescending().map { it.toString() }

  private fun observePresentation(
    previous: ReleaseNotesState,
    versionName: String,
    updatedInstallation: Boolean,
    publishedVersions: List<String>,
    restartVersions: List<String> = emptyList(),
  ): ReleaseNotesState {
    val current = requireNotNull(ReleaseVersion.parse(versionName))
    val available = publishedVersions.mapNotNull(ReleaseVersion::parse).distinct().filter { it <= current }.sorted()
    val newest = available.lastOrNull()
    val read = ReleaseVersion.parse(previous.readThrough)
    val restarts = restartVersions.mapNotNull(ReleaseVersion::parse).distinct().filter { it <= current }
    val restartRead = ReleaseVersion.parse(previous.restartReminderReadThrough)
    val observed = ReleaseVersion.parse(previous.observedVersion)
    // A restored backup can carry old state into a fresh installation.
    if (!updatedInstallation) return ReleaseNotesState(
      current.toString(), listOfNotNull(read, newest).maxOrNull()?.toString().orEmpty(),
      restartReminderReadThrough = (restarts + listOfNotNull(restartRead)).maxOrNull()?.toString().orEmpty(),
    )
    // Keep an unconfirmed notice across process death and later ordinary releases.
    // Skipping multiple native-changing releases needs only the newest applicable notice.
    val restart = (restarts.filter { (observed == null || it > observed) &&
      (restartRead == null || it > restartRead) } +
      listOfNotNull(ReleaseVersion.parse(previous.restartReminderVersion))
        .filter { it <= current && (restartRead == null || it > restartRead) }).maxOrNull()
    val pending = previous.pendingVersions.mapNotNull(ReleaseVersion::parse).filter { it in available }.toMutableSet()
    val changed = ReleaseVersion.parse(previous.observedVersion) != current
    if (changed) pending += available.filter { read == null || it > read }
    return ReleaseNotesState(current.toString(), previous.readThrough, pending.sortedDescending().map { it.toString() },
      if (changed || previous.pendingVersion == current.toString()) current.toString() else "",
      restart?.toString().orEmpty(), previous.restartReminderReadThrough)
  }

  fun acknowledge(state: ReleaseNotesState): ReleaseNotesState {
    val read = (state.pendingVersions + state.readThrough).mapNotNull(ReleaseVersion::parse).maxOrNull()
    return state.copy(readThrough = read?.toString().orEmpty(), pendingVersions = emptyList(), pendingVersion = "")
  }

  fun markRead(state: ReleaseNotesState, versions: Set<String>): ReleaseNotesState {
    val acknowledged = state.unreadVersions.filter { it in versions }
    if (acknowledged.isEmpty()) return state
    val highest = normalize(acknowledged + state.readThrough).firstOrNull().orEmpty()
    return state.copy(readThrough = highest, pendingVersions = state.pendingVersions - acknowledged.toSet(),
      unreadVersions = state.unreadVersions - acknowledged.toSet(),
      readVersions = normalize(state.readVersions + acknowledged))
  }

  fun acknowledgeRestart(state: ReleaseNotesState, version: String): ReleaseNotesState {
    if (version.isEmpty() || version != state.restartReminderVersion) return state
    val read = listOf(state.restartReminderReadThrough, version).mapNotNull(ReleaseVersion::parse).maxOrNull()
    return state.copy(restartReminderVersion = "", restartReminderReadThrough = read?.toString().orEmpty())
  }
}
