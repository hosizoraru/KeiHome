# Offline SecurityCenter sidebar profile matrix

Run from the repository root with Python 3.9+, local `dexsql` and Android SDK `aapt2`:

```sh
python3 scripts/tests/sidebar-profile/securitycenter_matrix.py \
  --only bec1ab84a5c9 \
  --output-root .artifacts/research/oem-refresh-20261111/securitycenter/dexsql-final
```

The script reads 1..16 archived `securitycenter.apk` files and their
`provenance.json` files under `.artifacts/inputs/securitycenter`. SHA256 and byte
size must match the existing provenance. Package metadata comes from `aapt2
dump badging`, not the directory name.

`--output-root` must name a new directory beneath `.artifacts/research/`.
The runner refuses any existing output directory, even an empty directory or a
partial run with no receipt, and rejects paths escaping the research root.
Choose another output root for each run. The historical default remains
`.artifacts/research/securitycenter-matrix-20261111/dexsql/`; existing evidence
there stays intact. `--only SUBSTRING` selects structural analysis for matching
archives. Omitting it analyses all archives, up to the same 16-input bound as
the device matrix. An empty selection fails before output creation.
Use `--dexsql PATH`, `--aapt2 PATH`, or `--timeout SECONDS` (1..60, default 45)
to select local tools or a shorter timeout. There are no third-party Python
dependencies, persistent DexSQL caches, device operations, or network calls.

The runner snapshots `HyperOsSidebarProfile.kt` without modifying it. SQL
discovers URI seeds, binders, model/identity fields, Dock getter callers, request
wrappers, native loader calls, cancellation and holder inheritance from each
archive independently. No obfuscated application class/method/field name is
hardcoded. DexSQL `decode` saves smali for the selected methods.

`REPORT.md` and `matrix.json` contain the matrix. Each archive directory contains
`shape-proof.json`, exact `.sql`, raw stdout/stderr, parsed `.rows.json`, decoded
smali, metadata and `commands.jsonl` with exact argv/exit status/duration.
`device-fixtures.json` records all archived inputs, including the eighth sample,
13.8.1-261023.0.1 / SHA256
`bec1ab84a5c94974b5c57a0626bfe21c1a2be962851b30eb0b27d7f9fae58c6c`.
Every inventory entry verifies actual bytes, provenance and `aapt2` metadata,
even on a focused structural run. Inventory membership is not structural or
runtime acceptance. `device-push.sh` contains exact private-cache staging commands
and is only written, never executed by the runner.

The command returns nonzero if any archive fails a query, provenance check,
budget, uniqueness constraint or structural check. It continues to collect
results for the other structurally selected archives; inventory failures stop
before analysis.

Bounds: at most 16 inputs, each at most 512 MiB, two DexSQL threads, sequential APK processing,
110 subprocesses and 480 seconds per APK, 45 seconds per subprocess by default,
32 MiB retained stdout/stderr per APK, 4096 result rows per general query, 128
URI seeds/getters/Dock caller edges, 12 superclass levels, and 24 decoded
methods. Queries request one extra row to detect overflow rather than silently
truncate evidence. No `--trusted-input` option is used.

`SQL_SHAPE_MATCH` describes independent static SQL evidence. It is not a
successful production DexKit execution or a test of reflection, resource IDs,
cache restoration or runtime behavior. The parent task owns content caching and
actual device DexKit tests. Framework methods that are merely DEX references
cannot have their Android runtime modifiers validated by this script.

Main-owned runtime acceptance passed for all eight archived fixtures.
`HyperOsSidebarMatrixDeviceTest#archivedApksResolveAndRestoreInIndependentLoaders`
completed as one instrumentation test in 8.395 seconds, with
`sameVersionRejections=1` and two discovered request methods per profile. All
eight used the same private code-cache APK pathname with distinct resolve and
restore loaders, and their cached SHA256 identities matched the inventory.
Both same-versionCode 40001366 APKs and the `w7.a`/`a8.c` to `v7.a`/`z7.c`
grid/rail owner changes were covered.

The 13.8.1 sample recorded 289 ms profile resolution and 83 ms cache restoration.
These are individual resolve/cache latency samples from one diagnostic run,
not FPS, frame-time or statistical benchmark results. Exact receipts:
`.artifacts/research/oem-refresh-20261111/securitycenter/device-acceptance.json`,
`device-private-test.log` and `device-matrix-logcat.txt` in the same directory.
The offline runner itself does not execute Android tests.

The successful staging/test recipe, for a main-owned rerun:

```sh
export SIDEBAR_SERIAL='MAIN_OWNED_TARGET'
bash \
  .artifacts/research/oem-refresh-20261111/securitycenter/run-as-staging/device-push.sh
adb -s "$SIDEBAR_SERIAL" shell am instrument -w -r \
  -e class os.kei.hyper.home.xposed.HyperOsSidebarMatrixDeviceTest#archivedApksResolveAndRestoreInIndependentLoaders \
  -e sidebarFixtureDirectory /data/user/0/os.kei.hyper.home/cache/keihome-sidebar-matrix \
  os.kei.hyper.home.test/androidx.test.runner.AndroidJUnitRunner
```

Build and install the current debuggable application and instrumentation APKs
using the main-owned workflow first. The script creates
`cache/keihome-sidebar-matrix` with `adb shell -T run-as os.kei.hyper.home mkdir -p`.
Each APK and the inventory are streamed with
`adb shell -T run-as os.kei.hyper.home tee cache/keihome-sidebar-matrix/<name>
< quoted-host-file > /dev/null`. Input redirection preserves binary bytes, and
discarding stdout prevents `tee` from printing the APK. This uses app-private
storage without storage permission grants or app-data clearing. `shell -T`
forwards stdin without allocating a PTY; `exec-out` is unsuitable for these
stdin-fed `tee` streams.

Use exactly the inventoried APKs plus `device-fixtures.json`; stale extra APKs
cause a test failure. The script does not delete existing device files. Its
exact destination names are the `file` values in the inventory, under the
private-cache directory above.

The first `/sdcard/Download` attempt failed with `EACCES` reading the inventory,
before any resolver ran. The original `dexsql-final/device-push.sh` and failure
evidence remain historical. `run-as-staging/` contains the corrected script and
an unchanged copy of the verified eight-fixture inventory; no oracle rerun is
needed for this staging-only correction. The `shell -T run-as ... tee` staging
then succeeded, and the device run above passed. Main owns cleanup of its
private and earlier shared staging directories.

The matrix checks the production DexKit profile in independent class loaders,
restores JSON into a fresh loader, matches each source SHA256 to the inventory,
and rejects old proofs for every same-versionCode/different-hash pair after
replacement at the same APK path. Hashes authenticate test inputs and cache
identity; they do not admit a production profile. Actual sidebar rendering,
resource IDs and installed-host behavior remain separate runtime acceptance.

Focused runner regression checks:

```sh
python3 -m unittest discover -s scripts/tests/sidebar-profile -p 'test_*.py' -v
```
