# Recommended icon mappings

The Icon completion and fallback page links to **Recommended mappings**. The
page uses the shared OS4 progressive app bar, status pills and switches, and is
also discoverable through Settings search. It contains three independent rules:

| Rule | Scope and precedence |
| --- | --- |
| Match system icons by app name | The existing `system_app_name_matching` preference, with its existing value and signed factory-app eligibility preserved. It searches eligible system/preinstalled apps only. |
| Share official Telegram icons | Play (`org.telegram.messenger`), Web (`org.telegram.messenger.web`) and Beta (`org.telegram.messenger.beta`) default launcher icons can borrow an actual day/night adaptation. Explicit light artwork remains; an existing valid dark adaptation wins. Alternate launcher icons and Telegram X do not participate. |
| Map ColorOS theme-store icons | The built-in ColorOS resources can supply HeyTap theme-store artwork for missing `com.android.thememanager` and `com.miui.themestore` entries. Authored target mappings win. Disabling this rule also prevents the ColorOS name-matching path from immediately restoring the same recommendation. |

The two new preferences default to enabled, preserving the previous automatic
behavior. Turning them off does not delete preferences, source resources or
explicit icon mappings. The original-icon preservation list takes precedence
over every supplemental rule.

## Preview and generation

- Mapping options are captured once per generation. Static exports, native
  Telegram supplementation, ColorOS supplementation, both appearances, cover
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

`IconGenerationManifest` schema 8 records the two new choices as a canonical
`mappings` bit field. Schemas 1–7 decode as both enabled, matching historical
behavior. Disabled choices cannot be written under a legacy schema. Duplicate,
missing, noncanonical or unknown bits are rejected. Name matching continues to
use its existing fallback manifest field.

Synthetic ColorOS aliases are tracked on the in-memory descriptor, separately
from authored entries. Disabling the rule removes only those synthetic entries
and their profile records. Re-enabling it reconstructs the same missing-only
aliases without accumulating duplicates.

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
`icon-packs.system-name-matching`, `recommended-mappings.telegram`, and
`recommended-mappings.coloros-theme-store`.
