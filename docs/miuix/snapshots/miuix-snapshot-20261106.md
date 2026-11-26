# MIUIX snapshot and dependency migration - 2026-11-06

MIUIX moves from `0.9.4-fc455d9a-SNAPSHOT` to the published
`0.9.4-fa6f5b50-SNAPSHOT` (`fa6f5b50dc5d307028947acf71571a508af377ae`).
All five direct modules resolve at the same pinned snapshot. Its published
metadata still calls the lexicographically larger old hash "latest"; the selected
snapshot is verified against the live commit history and published version list.

The upstream diff changes MaterialKolor to 6.0.0. KeiHome adopts the same version
and migrates `DynamicScheme.Platform` to `com.materialkolor.dynamiccolor`.
The current ThemeController, navigation, blur, preference and popup APIs remain
source compatible. BasicComponent receives upstream unbounded-width handling;
navigation receives the consumed-pointer-release dismissal fix.

AGP moves from 9.4.1 to 9.5.1 in both the catalog and build-logic classpath.
Gradle moves from 9.8.2 to 9.9.0-rc-1; the wrapper verifies the official SHA-256.
Other direct versions are already the newest stable/RC releases in their
repositories. Alpha, beta, milestone and development versions are excluded.
Transitive versions remain governed by their owning libraries and the Compose
BOM; no blanket overrides are applied to generated-code runtimes.

## Official repository audit

The current column below reflects the first audit after selecting MaterialKolor
6.0.0. MaterialKolor was previously 5.0.1. Raw official Maven metadata and Gradle
version records are retained in `.artifacts/research/miuix-snapshot-20261106/`.

| Coordinate | Audited version | Newest stable or RC |
| --- | --- | --- |
| `androidx.core:core-ktx` | `1.19.1` | `1.19.1` |
| `androidx.activity:activity-compose` | `1.13.0` | `1.13.0` |
| `androidx.compose:compose-bom` | `2026.09.00` | `2026.09.00` |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.11.0` | `2.11.0` |
| `androidx.test:core` | `1.7.0` | `1.7.0` |
| `androidx.test.ext:junit` | `1.3.0` | `1.3.0` |
| `androidx.test.espresso:espresso-core` | `3.7.0` | `3.7.0` |
| `androidx.test:runner` | `1.7.0` | `1.7.0` |
| `junit:junit` | `4.13.2` | `4.13.2` |
| `org.luckypray:dexkit` | `2.3.0` | `2.3.0` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.11.0` | `1.11.0` |
| `io.github.libxposed:api` | `102.0.0` | `102.0.0` |
| `com.materialkolor:material-color-utilities` | `6.0.0` | `6.0.0` |
| `com.tencent:mmkv` | `2.4.3` | `2.4.3` |
| `com.android.tools.build:gradle` | `9.4.1` | `9.5.1` |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | `2.4.21` | `2.4.21` |

## Offline component catalog

The settings page groups dependencies, adapted/reference sources, and historical
attribution separately. Each component links to source and offline licenses.
The full DexKit source/rebuilding offer is retained; LSPlt is explicitly historical.

A variant-specific generated TSV records actual resolved module versions rather
than duplicating version strings in UI source. Source assets include current
MaterialKolor MIT and libc++/LLVM license terms; generic Apache 2.0 is copied from
`LICENSES/Apache-2.0.txt`. Asset reading occurs on IO and preserves coroutine
cancellation. Read failures expose retry; documents remain selectable offline.

Validation includes the complete 610-test Debug JVM suite, three AVD asset/catalog
tests, Release lint, and minified Release UI acceptance. The final deployment
uses normal API 102 Java reload and verifies host process identity continuity.

Acceptance and final artifact: see
`.artifacts/research/miuix-snapshot-20261106/acceptance.json`.
