# Telegram channel icons: verified identities and preservation policy

Research date: 2026-11-12. Read-only official-source research and inspection of
the three installed packages on Phone AVD `emulator-5580`. No physical device,
installation, login, component switch, production code or test was touched.
Telegram X is explicitly outside this channel family.

| Channel | Exact application ID | Captured installed version |
| --- | --- | --- |
| Store | `org.telegram.messenger` | 13.2.2 / 73031 |
| Web / standalone | `org.telegram.messenger.web` | 13.2.2 / 73039 |
| Beta | `org.telegram.messenger.beta` | 12.10.2 / 70399 |

`org.telegram.message` is not one of these IDs. Treat that spelling as a typo,
not a fourth matching target. Do not broaden matching to arbitrary
`org.telegram.*` packages or include Telegram X.

Official verification uses Telegram's [Android download page](https://telegram.org/android)
and [applications directory](https://telegram.org/apps), plus the official
Android source pinned at `21f7d109da6ab3506c9e9577f98c6a38771ab335`:
[base package](https://github.com/DrKLO/Telegram/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/gradle.properties),
[Store build variants](https://github.com/DrKLO/Telegram/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/TMessagesProj_App/build.gradle),
[standalone variants](https://github.com/DrKLO/Telegram/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/TMessagesProj_AppStandalone/build.gradle),
and [beta variants](https://github.com/DrKLO/Telegram/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/TMessagesProj_AppHockeyApp/build.gradle).
The base package is `org.telegram.messenger`; the variants append `.web` or
`.beta`. This source checkpoint is 13.2.2, not the older captured beta build.

## Alias and resource inventory

All three installed packages currently resolve to the same alias class,
`org.telegram.messenger.DefaultIcon`, targeting
`org.telegram.ui.LaunchActivity`. The alias namespace does not change with the
application ID. Default inherits the application icon; the five alternatives
declare their own icon resources.

The following table lists resource names, not Telegram implementation:

| Full alias suffix under `org.telegram.messenger.` | Store/Beta mipmap | Web mipmap |
| --- | --- | --- |
| `DefaultIcon` | `ic_launcher` | `ic_launcher_sa` |
| `VintageIcon` | `icon_6_launcher` | `icon_6_launcher_sa` |
| `AquaIcon` | `icon_4_launcher` | `icon_4_launcher_sa` |
| `PremiumIcon` | `icon_3_launcher` | `icon_3_launcher_sa` |
| `TurboIcon` | `icon_5_launcher` | `icon_5_launcher_sa` |
| `NoxIcon` | `icon_2_launcher` | `icon_2_launcher_sa` |

Store/Beta declare corresponding `_round` resources. Web aliases use their
same `_sa` resource for regular/round icons. The shared source also declares
`org.telegram.ui.CallsActivity` as a permission-protected call alias; it is not
a seventh launcher-icon choice. See the official
[manifest](https://github.com/DrKLO/Telegram/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/TMessagesProj/src/main/AndroidManifest.xml)
and [icon-controller metadata](https://github.com/DrKLO/Telegram/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/TMessagesProj/src/main/java/org/telegram/ui/LauncherIconController.java).

The inspected launcher definitions include adaptive XML and legacy
density-specific PNGs. Recursive resource inspection covered 45 resources and
30 XML entries in each Store/Beta APK, and 25 resources/16 XML entries in Web.
There were **no `night` configurations in any inspected launcher resource graph**,
including referenced backgrounds/foregrounds/colors. XML roots were adaptive
icons, layer lists, shapes and vectors. The source controller exposes the six
manual choices and has no night/dark/uiMode selection metadata in that file.

Consequently, Nox is an alternate fixed design, not evidence of a day/night
pair. Switching to another official icon does not establish independent night
artwork. This finding is bounded to these captured APKs and the linked source
checkpoint; do not promise it for every future release.

## What the bundled ColorOS packs actually contain

The generated packs are inputs to `app/build.gradle.kts` asset packaging. Their
Telegram appfilter rows target only `org.telegram.messenger`, through:

```text
coloros_c9d1614367038910661973fc
```

There are no explicit rows for `.web`, `.beta`, or any of the six launcher
aliases in the inspected ColorOS ZIPs.

| Pack | Telegram coverage |
| --- | --- |
| `coloros-classic-art-on.zip` | Independent day and night entries, plus monochrome |
| `coloros-runtime-fallback.zip` | Same independent day/night entries, plus monochrome |
| `coloros-daylight-art-on.zip` | Day entry and monochrome; no independent night entry |
| `coloros-material-art-on.zip` | Day entry and monochrome; no independent night entry |
| `coloros-pebble-art-on.zip` | Day entry and monochrome; no independent night entry |
| `coloros-nightshadow-art-on.zip` | No Telegram appfilter row |
| All inspected `*-art-off.zip` | No Telegram appfilter row |

The verified classic/runtime paths are:

```text
res/drawable/coloros_c9d1614367038910661973fc.png
res/drawable-night/coloros_c9d1614367038910661973fc.png
```

Their SHA-256 values differ: day
`3b3d6f8e5fa477e9ef8f8bcfd1a533f87186756dadc3977384a84be0d09bf3e8`,
night `032b2bada88341a3a541a0ae721c629e934c13a5ec28b247ce093573daff65c9`.
The retained original runtime-uxicons directory also contains `rec_night.png`
and night rectangle variants. Distinct hashes establish different files; this
research did not perform a live theme/notification acceptance test.

## Recommended matching policy

Whitelist the three exact application IDs. Reuse the ColorOS default Telegram
design across this family only for the verified default alias. Treat
`org.telegram.ui.LaunchActivity` as the target/possible legacy identity, not as
the currently enabled alias; any historical compatibility route needs explicit
resolution rather than assuming that every activity targeting it is default.

For Vintage/Aqua/Premium/Turbo/Nox, preserve the selected app-provided alias
design unless the pack supplies an explicit matching design or the user chooses
replacement. Do not apply the default package artwork blindly to every alias.
Read each installed channel's resource references; Web's `_sa` layout already
differs from Store/Beta.

For the selected alternative, retain adaptive layers and transparency. If there
is no authored night resource, report that fact or reuse the same artwork
according to the selected product policy; do not label invented recoloring as
Telegram's original night asset.

When exporting native component filenames, use the owning application ID and
actual class independently. The foreign class namespace in Web/Beta must not
be rewritten to `.web.DefaultIcon` or `.beta.DefaultIcon`; KeiHome's canonical
native naming helper owns any required `package#class` representation.

## Evidence and limits

Evidence is under
`.artifacts/research/telegram-preserved-icons-20261112/`:

- `installed-packages.txt`, `*-package.txt`, `*-launcher.txt`: exact device
  identities and currently resolved components.
- `apk-identities.json`, `*-badging.txt`, `*-signer.txt`: APK hashes, versions and
  signing evidence. All three verify with certificate SHA-256
  `49c1522548ebacd46ce322b6fd47f6092bb745d0f88082145caf35e14dcc38e1`;
  a shared certificate alone is not a source-to-binary reproducibility claim.
- `installed-aliases.json`, `*-manifest.txt`, `*-resource-table.txt`,
  `installed-launcher-resource-graphs.json`: complete alias/resource evidence.
- `official-source-index.json`, `official-source-facts.json`: pinned official
  metadata and resource names; no Telegram Java implementation was copied.
- `coloros-coverage.json`, `coloros-entries/`: actual bundled mappings, extracted
  relevant assets and per-entry hashes.
- `dexsql-receipt.json`: the owned dataset was closed. Complete local resource
  tables complement the bounded MCP resource pages.

The other five choices were inspected statically; none was activated. No APK
was rebuilt, no GPL implementation was ported, and no visual/runtime success
or publication completeness is claimed.
