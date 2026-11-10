# Offline SecurityCenter sidebar profile matrix

Run from any directory with Python 3, local `dexsql` and Android SDK `aapt2`:

```sh
python3 scripts/tests/sidebar-profile/securitycenter_matrix.py
```

The script reads the seven archived `securitycenter.apk` files and their
`provenance.json` files under `.artifacts/inputs/securitycenter`. SHA256 and byte
size must match the existing provenance. Package metadata comes from `aapt2
dump badging`, not the directory name.

All generated files go to
`.artifacts/research/securitycenter-matrix-20261111/dexsql/`. The runner refuses
to overwrite an existing `run.json`, including a partial run. Preserve a previous
run within that permitted directory before rerunning. `--only SUBSTRING` permits
a focused archive run; the normal invocation requires exactly seven archives.
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
The command returns nonzero if any archive fails a query, provenance check,
budget, uniqueness constraint or structural check. It continues to collect
results for the other archives.

Bounds: at most seven inputs, two DexSQL threads, sequential APK processing,
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
