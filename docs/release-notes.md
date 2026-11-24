# In-app release notes

Settings has a dedicated OS4 release-history page and a searchable entry.
Each release has its own disclosure card. The installed release opens by
default; when its notes have not been authored, its card contains only a concise
placeholder. The page has no duplicate current-version or first-public-release pills. Other cards
remain collapsed until opened. Compact ISO dates sit to the right of each version,
with wrapping on narrow or large-text windows. Markdown is parsed with CommonMark
and rendered with native MIUIX text, lists and theme colors. Independent expansion and scroll state survive
saved-state restoration.

Dates are derived from each release tag's **target commit committer timestamp**,
preserving the recorded offset. They are unrelated to annotated-tag creation
times or APK build dates. The current version can show its authored notes
before tagging, with the date omitted. Runtime uses the bundled catalog without Git,
network requests, APK inspection or native hooks.

## Startup

- First installation enters the existing usage guide. It does not automatically
  open release notes after finishing or leaving that guide.
- An upgrade compares the saved version prefix with the installed prefix,
  for example `1.6.0` and `1.7.0`.
- Changing only a Git suffix or build number within `1.7.0` does not open notes.
  Adding text within the same prefix also does not override this rule.
- A changed prefix opens notes once even when that release's text is pending.
  Back, predictive back and Continue acknowledge the presentation.
- Pending presentation persists if the process dies before acknowledgment.
  Navigation restoration removes an obsolete acknowledged upgrade route.
- Skipped release notes remain separate. Cards newer than the installed prefix
  are excluded; newly encountered recorded versions have an unread pill.
- The manual Settings entry remains available independently of startup.

`ReleaseNote.expandOnUpgrade` controls the cards initially expanded during the
one-time upgrade presentation. For 1.11.0, both 1.11.0 and 1.10.0 expand by default: 1.10.0 was not publicly released, so the public upgrade path is 1.9.0 to 1.11.0.
For 1.10.0 only the new 1.10.0 details expanded by default.
For 1.9.0 this includes 1.9.0, 1.8.0, 1.7.0 and 1.6.0; older
unread versions remain available without being forced open. Manual Settings
entry always defaults to the current release only. The selection is authored
before release and does not change the prefix-based automatic presentation rule.

The scrolling viewport extends behind the toolbar, with the scaffold's top
inset applied inside the scrollable content. This lets the shared MIUIX chrome
render the selected progressive or Gaussian material; OFF retains an opaque
toolbar. Large text and the floating Continue action retain their own clearance.

The single MMKV snapshot stores the observed prefix, acknowledged release,
pending target prefix and pending recorded versions. No build numbers are used
to decide presentation. On first migration from a build without this snapshot,
a completed guide plus an actually updated installation bootstraps the one-time
upgrade presentation. A fresh installation or restored backup on a fresh
installation primes the history without opening notes. This bootstrap does not
invent an unknown previously installed prefix.

## Restart advice after upgrade notes

After the upgrade notes are acknowledged and the app returns to its main page,
show restart advice once for the installed version. This is a general
per-version upgrade policy, not a condition hardcoded to 1.11.0. The advice
must follow the release notes rather than cover them. Its confirmation button
becomes available after a five-second wait.

Confirming acknowledges the advice; it does not initiate a reboot or count as
proof of a completed system restart. The user restarts the device manually.
API 102 automatic hot reload updates only the Java part and cannot replace
native libraries already loaded in a process. The 1.11.0, 1.13.0, and 1.14.0 entries
enable the restart recommendation for their native library updates.
First activation independently requires a full system restart after module
enablement and scope authorization, followed by unlocking to the home screen.
Finishing or skipping the guide and confirming version advice cannot satisfy
that requirement. Neither flow requires clearing app data.

The catalog, persistence policy, reminder resources, UI and behavioral tests
belong to the corresponding implementation. Documentation and resource checks
alone do not establish that reminder timing or once-per-version behavior has
passed runtime acceptance.

## Content

The catalog begins with 1.4.0. Chinese 1.4.0 and 1.5.0 notes use the user's
public text; English and Japanese are translations. 1.6.0 describes the final
changes from `v1.5.0` to `v1.6.0`. See
[content provenance](release-notes-history.md) and the standalone
[1.6.0 notes](releases/1.6.0.md). [1.7.0](releases/1.7.0.md) describes
`v1.6.0..v1.7.0`; [1.8.0](releases/1.8.0.md) describes `v1.7.0..v1.8.0`;
[1.9.0](releases/1.9.0.md) describes the confirmed public 1.9.0 build.
[1.10.0](releases/1.10.0.md) records the subsequent OS4, performance, icon,
activation and startup work with matching English, Chinese and Japanese content.
[1.11.0](releases/1.11.0.md) records Launcher 9258 structural and dependency-graph
resolution, Selection/Monet/Dock/ImageRecovery adaptation, the native-update
restart requirement and general once-per-version restart advice. Ten version
fixtures passed the documented compatibility checks; this is not a guarantee
for every unknown launcher version. Existing published entries remain verbatim,
including MIUIX refinements already described in 1.10.0.
[1.12.0](releases/1.12.0.md) covers per-app icons, the Monet editor, and
icon-pack storage. [1.13.0](releases/1.13.0.md) covers the 0308 and 9261
launcher updates, large icon packs, Recents and launch icons, the soft glass
gesture handle, Bilibili home artwork, and Phone/Pad Glass refinements.
[1.14.0](releases/1.14.0.md) compares the release with the public 1.13.0
beta (`g89c8421.b0647.override`) and covers Dock spacing, icon preparation
and application, and the reorganized pack and Monet tabs.
[1.15.0](releases/1.15.0.md) covers appearance-based wallpaper presets,
separate dimming strengths, app appearance and palette, Glass controls,
icon pack improvements, notification and launch icon fixes, and less repeated
work during launcher icon refreshes. Perfect Icons uses the original static
artwork for weather icons that query an external provider during animation.
The 1.7.0 placeholder is now replaced with authored notes. Chinese, English
and Japanese use matching version entries and feature groupings.

See the [public build ledger](released-versions.md) for exact shipped APK
identities and feedback baselines. Unspecified "1.9.0" feedback means
`1.9.0+g70ea216.b0394` (`109000394`, source `70ea216`); explicit full versions
override that default. The local `v1.9.0` tag points to an older preparation
commit and remains the catalog date anchor. Published 1.9.0 notes stay scoped
to the public build, excluding pending features from later development.

When preparing the next version, add localized content and the desired upgrade
expansion list. When tagging that version, add the exact tag and its target
committer timestamp. The catalog stores
the tag rather than redundantly embedding its own commit hash: a release commit
cannot contain its own final hash. The focused checker resolves each tag and
checks its target timestamp. Set the release commit timestamp to the recorded
value and tag that complete commit, so the tag includes its own notes and the
signed APK is built from that same clean source. Creating a local tag does not
publish a remote release.

## Focused checks

```sh
python3 scripts/tests/release-notes/check_catalog.py
python3 scripts/checks.py release-notes --run
python3 scripts/checks.py ui-release-notes --serial emulator-5580 --run
```

The device group assumes a matching signed app/test pair has already been
installed. It uses isolated preference stores, exercises navigation and
restoration, and verifies large text against the measured Continue action.
