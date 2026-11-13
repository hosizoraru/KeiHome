# Recommended icon mappings

The Icon completion and fallback page links to **Recommended mappings**. The
page uses the shared OS4 progressive app bar, status pills and switches, and is
also discoverable through Settings search. It contains four independent rules:

| Rule | Scope and precedence |
| --- | --- |
| Match system icons by app name | The existing `system_app_name_matching` preference, with its existing value and signed factory-app eligibility preserved. It searches eligible system/preinstalled apps only. |
| Share official Telegram icons | Play (`org.telegram.messenger`), Web (`org.telegram.messenger.web`) and Beta (`org.telegram.messenger.beta`) default launcher icons can borrow an actual day/night adaptation. Explicit light artwork remains; an existing valid dark adaptation wins. Alternate launcher icons and Telegram X do not participate. |
| Share Phone icons with call services | Missing `com.android.server.telecom` and `com.android.phone` artwork can borrow `com.android.incallui` artwork. Each target is checked independently. Any authored target entry, including an activity-specific or light-only entry, blocks this recommendation in both appearances. There is no reverse mapping. |
| Map ColorOS theme-store icons | The built-in ColorOS resources can supply HeyTap theme-store artwork for missing `com.android.thememanager` and `com.miui.themestore` entries. Authored target mappings win. Disabling this rule also prevents the ColorOS name-matching path from immediately restoring the same recommendation. |

The Telegram and ColorOS theme-store preferences default to enabled, preserving
the previous automatic behavior. The new Phone recommendation also defaults to
enabled. It is pending work for 1.10.0, **not part of the published 1.9.0 build**.
Turning them off does not delete preferences, source resources or
explicit icon mappings. The original-icon preservation list takes precedence
over every supplemental rule.

## Preview and generation

- Mapping options are captured once per generation. Static exports, native
  recommended supplementation, ColorOS supplementation, both appearances, cover
  artwork and the final handoff use that snapshot.
- Pending preview cache keys include both options. Changing any mapping rule
  invalidates relevant previews and refreshes the cached built-in catalog. A
  rendering session uses its immutable snapshot without per-icon preference
  lookups inside the batch.
- Published-theme thumbnails render the artwork already written to the theme;
  pending recommendations cannot silently change them.
- Changing an option marks a generated managed theme as needing an update. The
  user must update/import and apply it before the system theme changes. No scope,
  Launcher or system restart is introduced.

`IconGenerationManifest` schema 9 records Telegram (bit 1), ColorOS themes
(bit 2), and Phone companions (bit 4) as a canonical `mappings` field.
Schemas 1–7 retain the first two as enabled; schema 8 retains its saved two
choices. Every schema before 9 decodes Phone as disabled: a previously generated
theme needs updating and applying to acquire the newly enabled recommendation.
Historical encodings remain byte compatible. Unrecordable choices cannot be
written under a legacy schema. Duplicate,
missing, noncanonical or unknown bits are rejected. Name matching continues to
use its existing fallback manifest field.

Synthetic ColorOS aliases are tracked on the in-memory descriptor, separately
from authored entries. Disabling the rule removes only those synthetic entries
and their profile records. Re-enabling it reconstructs the same missing-only
aliases without accumulating duplicates.

Phone candidates use a package-level donor when present. Activity-only donors
are accepted only when their artwork agrees; ambiguous alternate designs are
left alone. A primary pack's authored target ownership is propagated into
supplementary sessions, preventing a ColorOS supplement from restoring this
recommendation over a light-only target. The two service packages have no
launcher activities: generation includes them explicitly, with bounded package
presence checks in installed-only mode. Native imports share the existing paired
day/night supplementation session without rewriting authored programs.

## Audit boundary

These optional recommendations are distinct from mandatory compatibility work:

- Factory signatures and Download Manager companion checks establish trusted
  resource ownership and fallback eligibility.
- Component/native-resource-name normalization identifies the same launcher
  entry; it does not recommend another app's artwork.
- Apple user mappings and upstream Perfect resource mappings are explicit source
  mappings, not supplemental recommendations created by these rules.

Those mechanisms remain intact. New optional cross-app recommendations should
join this page, generation metadata and preview identity together.

## Focused checks

`python3 scripts/checks.py recommended-mappings --run` runs the relevant JVM
contracts. `python3 scripts/checks.py ui-recommended-mappings --serial
emulator-5580 --run` runs isolated navigation, switch state, layout and on-device
archive/preview/handoff tests. The groups do not build or install APKs; install
the matching app and test APKs first. Device tests generate private temporary
fixtures and do not apply a theme or alter the real preservation list.

The shared destination is `PackRoute("recommended-mappings")`. Search indexes
localized labels, switch descriptions and keywords. UI checks cover sibling/back
navigation, preserved search text, independent persistence and import gating.
Stable selectors include `icon-packs.open-recommended-mappings`,
`settings.entry.recommended-mappings`, `recommended-mappings.page`,
`icon-packs.system-name-matching`, `recommended-mappings.telegram`,
`recommended-mappings.coloros-theme-store`, `recommended-mappings.phone-companions`.

For the 1.10.0 preparation, 103 focused JVM cases and 15 grouped AVD cases
passed, covering independent ownership, imported native bytes, installed-only
service discovery, supplemental-source precedence, preview masks, persistence,
navigation, and import gating. Three existing Telegram device cases also passed.
Ten pipeline cases were rerun after extending installed-only discovery to
authored service entries: disabling the recommendation keeps dedicated service
artwork, including component-specific artwork. Package presence is memoized per
export, so multiple entries still need at most two package checks.
The bundled ColorOS fixture already owns `com.android.phone`: the test confirms
that only its uncovered companion is borrowed, preserving the authored service
icon.

Evidence is retained under
`.artifacts/research/phone-companion-mapping-20261113/`. Development APKs were
installed only on `emulator-5580` using ordinary API 102 reload; Launcher,
System UI and system-server identities remained unchanged. Tests use temporary
archives, do not apply a theme, and do not prove live physical call-notification
behavior. The separate [system companion audit](system-icon-companions.md)
records candidates without enabling additional mappings.
