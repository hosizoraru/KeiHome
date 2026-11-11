# Select checks by the change

Routine UI work uses the smallest relevant group. A caption or glyph change
usually needs compilation and an actual screenshot of the affected page.
Layout or interaction changes add that component's device test. State,
persistence and formatting changes add the corresponding JVM group. Do not
repeat a passed check until another change affects what it covered.

`python3 scripts/checks.py` lists groups. A group alone previews its command;
`--run` executes it. Independent groups remain separate, so a timestamp edit
does not automatically run navigation, color editing or native tests.

| Change | Group / acceptance |
| --- | --- |
| FAB, centered action, disclosure, metadata pill | `ui-actions`; affected screen screenshot |
| Navbar, tabs, themes or font geometry | `ui-navigation` |
| Status cards and detail navigation | `ui-status` |
| Theme-effect controls | `ui-theme-effects` |
| Color editor, input validation, draft lifecycle | `ui-color-editor` |
| Pack preferences navigation and wizard shortcut | `ui-pack-settings` |
| Appearance preferences/recovery | `ui-settings` |
| Date/time display | `pack-time`; pill test only if its layout changes |
| Perfect source/sync/history/health | `perfect-sync` |
| Scope requests and authorization state | `scope`; manual onboarding check if wording/flow changes |
| Reload retirement bookkeeping | `hook-lifecycle`; normal deployment/handoff checks when runtime changes |
| Broad infrastructure/dependency changes or release gate | `release-jvm`, appropriate lint and affected device/native suites |

Examples:

```sh
python3 scripts/checks.py pack-time --run
python3 scripts/checks.py ui-actions --serial emulator-5580 --run
python3 scripts/checks.py ui-actions --method syncIssuePillsKeepTheLastSuccessAndDistinguishFirstUse --serial emulator-5580 --run
```

Device groups operate on the APKs already installed on the explicit target.
They do not build, sign, install, clear data, uninstall, restart scopes or fetch
icon resources. Build main and AndroidTest together before deploying a changed
test, so their BuildConfig/API identities match:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Use the project's signing/deployment procedure. Retain the test package and
update it with `adb install -r` to preserve HyperOS associated-start
authorization. Check the runner's actual result: adb's exit code alone does
not establish instrumentation success; `checks.py` requires a nonempty
successful test summary.

Native changes use the individual `scripts/tests/*/README.md` suites for the
affected structural resolver and linked bridge, including the retained OEM
fixtures and rejection cases. Device acceptance and deliberate native
activation remain separate. UI edits do not require this matrix.

Full regression is a deliberate checkpoint for broad risk or release
preparation, not a prerequisite for each visual adjustment. Record which
source/artifact each result covers. Reuse valid evidence within a session;
do not rerun a full JVM suite merely to package an already checked change.
