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
| About copy action and scrolling | `ui-about` |
| Settings search and preview preference placement | `ui-settings-search` |
| Cache pills, chart layout, reminder editor and resource navigation | `ui-cache` |
| OS4 scope page, component versions, grant states and large text | `ui-scope` |
| OS4 guide, resume/replay, destination round trips and large text | `ui-onboarding` |
| Version-prefix upgrade decisions and skipped release history | `release-notes`; `ui-release-notes` for navigation and large text |
| Cache reminder settings and protected resource accounting | `cache-budget` |
| Desktop categories, scope viewer and wizard shortcut | `ui-desktop` |
| Desktop capability and acknowledgement summaries | `desktop-state` |
| Dock/search materials, persistence, leases and protocol | `desktop-materials` |
| Appearance preferences/recovery | `ui-settings` |
| Date/time display | `pack-time`; pill test only if its layout changes |
| Perfect source/sync/history/health | `perfect-sync` |
| Scope requests and authorization state | `scope`; manual onboarding check if wording/flow changes |
| Native icon config completion, generation flags and import boundaries | `icon-shape`; `icon-shape-device` for archive and raster checks |
| Custom mask XML, policy and regeneration identity | `icon-mask` |
| Mask import, alpha edges and source/export preservation | `icon-mask-device` |
| Mask child page, real comparison and restore confirmation | `ui-icon-mask`; affected preview screenshot |
| Reload retirement bookkeeping | `hook-lifecycle`; normal deployment/handoff checks when runtime changes |
| Broad infrastructure/dependency changes or release gate | `release-jvm`, appropriate lint and affected device/native suites |

Examples:

```sh
python3 scripts/checks.py pack-time --run
python3 scripts/checks.py ui-actions --serial emulator-5580 --run
python3 scripts/checks.py ui-actions --method syncIssuePillsKeepTheLastSuccessAndDistinguishFirstUse --serial emulator-5580 --run
```

`perfect-sync` includes selective Git objects, resource-graph deletion, offline
readiness and last-good snapshot preservation. `ui-perfect` covers the resource
manager's batch selection, four-action downloaded-style dialogs, resource sizes,
overview generation switches and temporary transfer status, without network or
real source deletion. Actual transport acceptance is opt-in:

```sh
adb -s emulator-5580 shell am instrument -w \
  -e class os.kei.hyper.home.iconpack.perfect.PerfectGitDeviceTest \
  -e perfectGitNetwork true \
  -e perfectGitFixture /data/local/tmp/keihome-perfect-git-fixture-20261111 \
  os.kei.hyper.home.test/androidx.test.runner.AndroidJUnitRunner
```

This transport fixture must be prepared explicitly; it is an owned small bare
repository with two revisions, selected/shared resources and an unrelated blob.
The test uses an isolated cache root, preserves the user's source data, and
deletes that temporary root on completion. See `docs/perfect-git-sync.md`.

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
