package os.kei.hyper.home.data

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import os.kei.hyper.home.R

internal data class ReleaseNote(
  val version: String,
  val tag: String? = null,
  val committedAt: String? = null,
  @param:StringRes val introduction: Int = 0,
  @param:ArrayRes val changes: Int = 0,
  @param:StringRes val footnote: Int = 0,
  val expandOnUpgrade: List<String> = emptyList(),
  val recommendSystemRestart: Boolean = false,
)

internal object ReleaseNotesCatalog {
  // Dates come from the tagged commits, rather than tag creation or APK build time.
  val releases: List<ReleaseNote> = listOf(
    ReleaseNote(
      version = "1.18.0",
      introduction = R.string.release_notes_1_18_0_introduction,
      changes = R.array.release_notes_1_18_0_changes,
      footnote = R.string.release_notes_1_18_0_footnote,
      recommendSystemRestart = true,
    ),
    ReleaseNote(
      version = "1.17.0",
      tag = "v1.17.0",
      committedAt = "2026-11-27T03:11:30+08:00",
      introduction = R.string.release_notes_1_17_0_introduction,
      changes = R.array.release_notes_1_17_0_changes,
      footnote = R.string.release_notes_1_17_0_footnote,
    ),
    ReleaseNote(
      version = "1.16.0",
      tag = "v1.16.0",
      committedAt = "2026-11-25T22:01:40+08:00",
      introduction = R.string.release_notes_1_16_0_introduction,
      changes = R.array.release_notes_1_16_0_changes,
      footnote = R.string.release_notes_1_16_0_footnote,
      recommendSystemRestart = true,
    ),
    ReleaseNote(
      version = "1.15.0",
      tag = "v1.15.0",
      committedAt = "2026-11-24T16:20:13+08:00",
      introduction = R.string.release_notes_1_15_0_introduction,
      changes = R.array.release_notes_1_15_0_changes,
      footnote = R.string.release_notes_1_15_0_footnote,
    ),
    ReleaseNote(
      version = "1.14.0",
      tag = "v1.14.0",
      committedAt = "2026-11-22T13:51:29+08:00",
      introduction = R.string.release_notes_1_14_0_introduction,
      changes = R.array.release_notes_1_14_0_changes,
      footnote = R.string.release_notes_1_14_0_footnote,
      recommendSystemRestart = true,
    ),
    ReleaseNote(
      version = "1.13.0",
      tag = "v1.13.0",
      committedAt = "2026-11-19T14:49:41+08:00",
      introduction = R.string.release_notes_1_13_0_introduction,
      changes = R.array.release_notes_1_13_0_changes,
      footnote = R.string.release_notes_1_13_0_footnote,
      recommendSystemRestart = true,
    ),
    ReleaseNote(
      version = "1.12.0",
      tag = "v1.12.0",
      committedAt = "2026-11-16T14:19:32+08:00",
      introduction = R.string.release_notes_1_12_0_introduction,
      changes = R.array.release_notes_1_12_0_changes,
      footnote = R.string.release_notes_1_12_0_footnote,
    ),
    ReleaseNote(
      version = "1.11.0",
      tag = "v1.11.0",
      committedAt = "2026-11-15T13:24:02+08:00",
      introduction = R.string.release_notes_1_11_0_introduction,
      changes = R.array.release_notes_1_11_0_changes,
      footnote = R.string.release_notes_1_11_0_footnote,
      expandOnUpgrade = listOf("1.10.0"),
      recommendSystemRestart = true,
    ),
    ReleaseNote(
      version = "1.10.0",
      tag = "v1.10.0",
      committedAt = "2026-11-15T02:22:35+08:00",
      introduction = R.string.release_notes_1_10_0_introduction,
      changes = R.array.release_notes_1_10_0_changes,
      footnote = R.string.release_notes_1_10_0_footnote,
      expandOnUpgrade = listOf("1.10.0"),
    ),
    ReleaseNote(
      version = "1.9.0",
      tag = "v1.9.0",
      committedAt = "2026-11-13T07:12:24+08:00",
      introduction = R.string.release_notes_1_9_0_introduction,
      changes = R.array.release_notes_1_9_0_changes,
      footnote = R.string.release_notes_1_9_0_footnote,
      expandOnUpgrade = listOf("1.9.0", "1.8.0", "1.7.0", "1.6.0"),
    ),
    ReleaseNote(
      version = "1.8.0",
      tag = "v1.8.0",
      committedAt = "2026-11-12T09:15:03+08:00",
      introduction = R.string.release_notes_1_8_0_introduction,
      changes = R.array.release_notes_1_8_0_changes,
      footnote = R.string.release_notes_1_8_0_footnote,
      expandOnUpgrade = listOf("1.8.0", "1.7.0", "1.6.0"),
    ),
    ReleaseNote(
      version = "1.7.0",
      tag = "v1.7.0",
      committedAt = "2026-11-11T17:53:39+08:00",
      introduction = R.string.release_notes_1_7_0_introduction,
      changes = R.array.release_notes_1_7_0_changes,
      footnote = R.string.release_notes_1_7_0_footnote,
      expandOnUpgrade = listOf("1.7.0", "1.6.0"),
    ),
    ReleaseNote(
      version = "1.6.0",
      tag = "v1.6.0",
      committedAt = "2026-11-11T03:24:52+08:00",
      introduction = R.string.release_notes_1_6_0_introduction,
      changes = R.array.release_notes_1_6_0_changes,
      footnote = R.string.release_notes_1_6_0_footnote,
    ),
    ReleaseNote(
      version = "1.5.0",
      tag = "v1.5.0",
      committedAt = "2026-11-07T14:07:22+08:00",
      changes = R.array.release_notes_1_5_0_changes,
    ),
    ReleaseNote(
      version = "1.4.0",
      tag = "v1.4.0",
      committedAt = "2026-11-06T11:43:46+08:00",
      introduction = R.string.release_notes_1_4_0_introduction,
      changes = R.array.release_notes_1_4_0_changes,
      footnote = R.string.release_notes_1_4_0_footnote,
    ),
  )

  fun initiallyExpandedVersions(currentVersion: String, upgrade: Boolean): List<String> {
    val current = ReleaseVersion.parse(currentVersion) ?: return emptyList()
    val visible = releases.filter { ReleaseVersion.parse(it.version)!! <= current }
    val currentRelease = visible.firstOrNull { ReleaseVersion.parse(it.version) == current }
    val defaultRelease = currentRelease ?: visible.firstOrNull() ?: return emptyList()
    val configured = if (upgrade) currentRelease?.expandOnUpgrade.orEmpty() else emptyList()
    val available = visible.mapTo(mutableSetOf()) { it.version }
    return (listOf(defaultRelease.version) + configured).filter { it in available }.distinct()
  }
}
