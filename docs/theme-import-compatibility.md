# Native Themes import compatibility

Verified on HyperOS `emulator-5580`, 2026-11-04. APKs are local research inputs,
excluded from source control. The version names below identify test fixtures;
production does not select a contract by an OEM version number.

| Fixture | Local APK | SHA-256 | Resolved and reflected roles |
| --- | --- | --- | --- |
| 11.5.4.0 / 11540 | `.artifacts/inputs/themes/thememanager.apk` | `089429477c7b33784e306a419e8684651bcad86f480e060cfb9bb8ed389a8659` | 11 / 11 |
| 11.5.4.1 / 11541 | `.artifacts/research/via-night-20261103/Themes-11541.apk` | `65db2e6521e3059a36e2e4fab3a97f31ab711a0dbd483235088079ace1f44fc3` | 11 / 11 |
| 11.5.6.0 / 11560 | `.artifacts/inputs/themes/Themes-11560.apk` | `cbae7d380763a4d7844c33a53852ac4b4e77bee7b70a86646e142b79fca047fa` | 11 / 11 |

The user-provided 11.5.6.0 APK was moved from Downloads to the path above,
preserving its bytes. All three APK signatures verify with the same MIUI
certificate SHA-256:
`c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`.

## Production contract

`ThemeImportProfile` uses DexKit method signatures, string anchors and verified
call relationships to resolve rights, apply, detailReady, validate, parent,
inner, registry, iconContext, controllerManager, lookup and appContext.
It rejects missing or ambiguous roles. The cached result belongs to the exact
installed APK path and DEX/Manifest identities; an APK replacement invalidates
the old identity. Reflection checks the resolved methods in the host loader.

The 33 actual method definitions across these fixtures have identical role
descriptors, access flags, instruction counts and raw instruction streams.
The existing production resolver therefore covers the new fixture without a
version-specific branch or a broader, less selective matcher. This is evidence
for these fixtures, not a promise that every future APK has the same contract.

## Repeatable device diagnostics

`ThemeImportProfileDeviceTest` always inspects the installed Themes APK. The
optional `themesApks` instrumentation argument accepts a JSON array containing
up to 16 additional APK paths. The older `previousThemesApk` argument remains
available. Fixture versions are diagnostic metadata, not matching conditions.

Each fixture must independently resolve the complete role set. A separate
`PathClassLoader` then loads that fixture's classes, with the installed Themes
application's shared-library files as its parent. The test reflects every
resolved method and checks that the declaring class belongs to that fixture's
loader. It does not initialize or run the fixture application. The MIUI shared
libraries are necessary: a boot-only parent cannot resolve native MIUI model
dependencies and would report a misleading reflection failure.

With the matching Debug target and Android test APK installed, stage the two
older APKs as read-only files and run:

```bash
adb -s emulator-5580 shell mkdir -p /data/local/tmp/keihome-theme-profile-fixtures
adb -s emulator-5580 push .artifacts/inputs/themes/thememanager.apk /data/local/tmp/keihome-theme-profile-fixtures/11540.apk
adb -s emulator-5580 push .artifacts/research/via-night-20261103/Themes-11541.apk /data/local/tmp/keihome-theme-profile-fixtures/11541.apk
adb -s emulator-5580 shell chmod 0444 /data/local/tmp/keihome-theme-profile-fixtures/11540.apk /data/local/tmp/keihome-theme-profile-fixtures/11541.apk
adb -s emulator-5580 shell am instrument -w -r \
  -e class os.kei.hyper.home.xposed.ThemeImportProfileDeviceTest \
  -e themesApks "'[\"/data/local/tmp/keihome-theme-profile-fixtures/11540.apk\",\"/data/local/tmp/keihome-theme-profile-fixtures/11541.apk\"]'" \
  os.kei.hyper.home.test/androidx.test.runner.AndroidJUnitRunner
```

The successful run reports `OK (2 tests)`, including rejection of an unrelated
APK. The per-fixture versions, resolved descriptors and timings are written to
the target App's `cache/theme-import-profile-diagnostics.json`. The checked run
is preserved as `three-version-device-tests.log` and `three-version-roles.json`
under `.artifacts/research/themes-11560-20261104/`.

## Live acceptance

The AVD's Themes app was updated in place from 11.5.4.1 to 11.5.6.0, keeping
native data. The new host cold-resolved all 11 roles and installed its import
and converted-icon approval hooks. No launcher-native payload reload was
needed. A new ColorOS Daylight ART+ module returned `IMPORTED` with
`convertedReady=true`; its automatic native apply completed, and the App's
applied-state card changed to Daylight, with 778 resources / 727 night resources
and 531 downloaded-fallback packages. Live import/application receipts, final Release identity, screenshots
and process continuity are recorded in that directory's `acceptance.json`.
Physical-device acceptance is separate.

## Deployment log rotation

The diagnostic APK installation coincided with LSPosed rotating its module
log. Hot reload completed, but the old deployment reader kept tailing the
previous filename. `deploy-hyperos-avd.py` now records every existing module
log's inode and byte offset, and includes newly created, replaced or truncated
streams while waiting for fresh API 102 reconciliation. Existing bytes cannot
replay an old acknowledgement. Four host regression tests cover append,
rotation, stale-log exclusion, replacement and truncation.
