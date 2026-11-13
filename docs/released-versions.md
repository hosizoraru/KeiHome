# Public build ledger

This ledger records exact builds explicitly confirmed as publicly released.
Use it to identify the build behind user feedback. Release-note prefixes,
catalog dates and local tags describe separate parts of release history; an
artifact receipt identifies the APK.

## Default feedback baseline

When subsequent user feedback says only **1.9.0**, use
**1.9.0+g70ea216.b0394**, versionCode **109000394**, as the public baseline.
An explicitly supplied full version or build identity takes precedence.
Later development builds retaining the 1.9.0 prefix do not replace this baseline
without a further explicit public-release confirmation.

## 1.9.0+g70ea216.b0394

Public release was explicitly confirmed by the user on **2026-11-13,
Asia/Singapore (UTC+08:00)**. The date records that confirmation; no time of day
was supplied.

| Field | Recorded identity |
| --- | --- |
| Version prefix | `1.9.0` |
| Full versionName | `1.9.0+g70ea216.b0394` |
| versionCode | `109000394` |
| Source commit | `70ea21606be2d8277da0c1031853d94b257b396f` |
| Source committer timestamp | `2026-11-13T09:05:32+08:00` |
| Source commit subject | `Default the app navigation bar to OS4 Glass` |
| Build-time worktree | Clean, as recorded in the receipt |
| APK | `.artifacts/releases/109000394/KeiHome-1.9.0+g70ea216.b0394.apk` |
| APK size | `76932974` bytes |
| APK SHA-256 | `4f437842b595dd6859338c582bab4c0ea368a0bf6ce635cf6f00fd1ed1c25346` |
| Signing certificate SHA-256 | `c5c4c43ba6d03268773122bc99d1e870908609ae5251c7c8ad4b6029b573c82a` |
| Build receipt | `.artifacts/releases/109000394/release.json` |

The version, versionCode, source commit, artifact digest, size and certificate
digest above come from the build receipt. Git independently resolves the full
source commit and its committer timestamp. The local APK's SHA-256 and byte size
were checked against the receipt when this ledger was created; the certificate
digest is recorded from the receipt.

The receipt retains its build-time `releaseStatus` value,
`local-release-unpublished`. The later user confirmation establishes the public
status recorded here. Keep the receipt as the original build snapshot. A public
hosting service or download URL was not specified by that confirmation.

### Local tag and note provenance

The existing local `v1.9.0` tag resolves to
`d23803205de01c61ec9f48515d32a93fd7df1bfd`, with committer timestamp
`2026-11-13T07:12:24+08:00` and subject
`Prepare 1.9.0 release notes and prefix-based upgrade presentation`.
That preparation commit is an ancestor of the public source commit, four
commits earlier.

The release-note catalog continues to use this tag and its timestamp as the
1.9.0 note anchor. The tag target does not identify the shipped APK. Keep the
tag unchanged and use the full source commit, versionCode and APK digest above
for public-build attribution.

See [release-note provenance](release-notes-history.md) and the published
[1.9.0 notes](releases/1.9.0.md).

### Changes after this public build

Keep published 1.9.0 release notes limited to the recorded public build. Pending
features belong to later work even when the development version retains the
same prefix. In particular, the new default-on recommended mapping from in-call
UI to Telecom/Phone is post-release work and is not part of this APK.

For future public confirmations, add the exact full version, versionCode,
source commit, APK and certificate digests, confirmation date/time zone, and
the relationship to any existing tag. Update the default feedback baseline
only when explicitly confirmed.
