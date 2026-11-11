package os.kei.hyper.home.data

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import os.kei.hyper.home.R

internal data class ReleaseNote(
  val version: String,
  val tag: String,
  val commit: String,
  val committedAt: String,
  @param:StringRes val introduction: Int = 0,
  @param:ArrayRes val changes: Int,
  @param:StringRes val footnote: Int = 0,
)

internal object ReleaseNotesCatalog {
  // Dates come from the tagged commits, rather than tag creation or APK build time.
  val releases: List<ReleaseNote> = listOf(
    ReleaseNote(
      version = "1.6.0",
      tag = "v1.6.0",
      commit = "02c8badfcd929c0563fb8c25361167ae5eed0008",
      committedAt = "2026-11-11T03:24:52+08:00",
      introduction = R.string.release_notes_1_6_0_introduction,
      changes = R.array.release_notes_1_6_0_changes,
      footnote = R.string.release_notes_1_6_0_footnote,
    ),
    ReleaseNote(
      version = "1.5.0",
      tag = "v1.5.0",
      commit = "1bf0c4ccb43d049d3caa09f1f3cef96d74052c68",
      committedAt = "2026-11-07T14:07:22+08:00",
      changes = R.array.release_notes_1_5_0_changes,
    ),
    ReleaseNote(
      version = "1.4.0",
      tag = "v1.4.0",
      commit = "e00c6c6c5cc46e979633bf14942a455bde5d5a44",
      committedAt = "2026-11-06T11:43:46+08:00",
      introduction = R.string.release_notes_1_4_0_introduction,
      changes = R.array.release_notes_1_4_0_changes,
      footnote = R.string.release_notes_1_4_0_footnote,
    ),
  )
}
