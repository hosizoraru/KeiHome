# Dependency review: 2026-11-12

## Selection and provenance

The audit checked all 40 non-MIUIX catalog version references, including 60
published coordinates and the separate build-logic plugin declarations. Google
Maven, Maven Central and the Gradle Plugin Portal metadata were checked against
their published POMs. Gradle's distribution checksum, Foojay, the pinned NDK and
SDK-packaged CMake were checked separately.

Use the highest published stable or RC release, comparing the numeric base before
the qualifier. An older stable release must not replace a newer RC. Alpha, beta,
milestone, EAP, development and snapshot versions are excluded from this choice;
MIUIX is the explicitly requested snapshot exception.

| Dependency | Before | After | Decision |
| --- | --- | --- | --- |
| Kotlin, Compose compiler and serialization compiler plugin | 2.4.21 | 2.5.0-RC | Latest eligible RC; align catalog and all four build-logic literals. |
| All eight MIUIX modules | 0.9.4-ca674992-SNAPSHOT | 0.9.4-9d4b9c85-SNAPSHOT | Latest upstream `main` revision and published artifacts. |
| Remaining 39 non-MIUIX version references | Existing pins | Unchanged | No newer eligible stable/RC publication. |
| Gradle wrapper | 9.9.1 | Unchanged | Current distribution; official checksum matches. |
| Native toolchain | Existing pins | Unchanged | No native migration or payload replacement. |

This is a direct-dependency/toolchain selection policy, not a claim that every
internal build-tool dependency is stable: AGP 9.5.1 itself declares Jetifier
1.0.0-beta10. Do not replace AGP internals independently just to remove that
pre-existing qualifier.

The complete coordinate/version/source/hash matrix is retained in
`.artifacts/research/dependencies-os4-20261112/dependency-audit.json`.

## Kotlin migration

Read the [official 2.5.0-RC release](https://github.com/JetBrains/kotlin/releases/tag/v2.5.0-RC)
and tagged changelog before changing the compiler. Gradle build logic, application
compilation, Compose compiler and serialization compiler must resolve the same
Kotlin revision. Keep existing JVM targets, Android SDK policy and API102 entry
member/name guards.

The RC produces additional ignored-return-value diagnostics in existing code.
The compiler change does not justify suppressing diagnostics globally or rewriting
best-effort cleanup and Hook hot paths without a specific behavioral reason.

The official Kotlin 2.5 migration guide and the Android D8/R8 support-table entry
were unavailable at audit time. Actual acceptance therefore includes compilation,
Lint, a minified signed Release, library/entry probes, and complete API102
retirement/adoption and initialization checks. A successful debug build alone is
insufficient; these checks do not establish a real system cold boot.

## MIUIX migration

The new source pin is `9d4b9c85c18707c7da12cf8c497ea57955fcf81e`.
Review the six commits after the previous pin:

- `2f7052e`: revised blur ladder and fractional backdrop downscaling.
  `CombinedBackdrop` forwards `Float` factors to both sampled layers; lens
  geometry uses the same fractional scale instead of rounding it to an integer.
- `d05ecf9`: removed the pager's `TapToHalt` interception mode. Old persisted
  `IOS_LIKE` pager values fall back to supported `CROSS_AXIS`; native and cross-axis
  values remain stable. Remove the unavailable choice and update all three
  localized descriptions.
- `8923292`: Glass popup anchors use window coordinates. Remove KeiHome's old
  root-origin/viewport compensation and let native popup hosts translate anchors.
- `96d613a`: slower completion-text fade in pull-to-refresh; no application
  workaround is required.
- `8a083bd`: upstream Gradle update; KeiHome already uses the same version.
- `9d4b9c8`: serialization runtime 1.13.0; resolve the updated MIUIX runtime graph
  and preserve consumer rules.

The pager migration is independent of navigation-bar appearance. Both selectable
**iOS-like** and **OS4 Glass** bars remain, including the existing Desktop glyph.

## Independent OS4 UI follow-up

InstallerX-Revived's current checkout was inspected read-only for interaction
patterns and public MIUIX API usage. Its GPL implementation and assets were not
copied or translated.

App settings now use native `GlassDropdownPreference` for palette, appearance,
language, bar style, top-bar blur and pager interaction. A small KeiHome adapter
supplies the app's backdrop and light/dark material; native opaque fallback,
selection, disabled state and Back handling remain available.

The OS4 navigation bar now consumes horizontal safe-drawing insets explicitly
while retaining its existing independent bottom-spacing policy. Native bar
insets stay disabled to avoid applying them twice.

## Focused validation and limits

- Debug APK, Android test APK and Release Lint pass with Kotlin 2.5.0-RC.
- Eight focused device tests pass: persisted pager migration, native grouped
  menus in LTR/RTL, opaque menus, Glass/opaque preferences, both navigation styles
  and tab interruption/selection.
- The first Debug update lacked the required system-server initialization log;
  its reload is **not accepted**. Retirement/adoption and active worker evidence
  were present. A managed thread dump showed no blocked initialization callback;
  the cause of the missing acknowledgement remains undetermined.
- The following minified candidate Release completed the strict initialization
  and retirement/adoption checks without restarting critical processes.
- Final artifact identities, selected JVM checks, library/entry probe results,
  real-app screenshots and the clean Release handoff are recorded separately in
  `.artifacts/research/dependencies-os4-20261112/acceptance.json`.

No repository tag, push or public release is part of this dependency/UI task.
