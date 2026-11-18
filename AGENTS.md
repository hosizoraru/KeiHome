# KeiHome

## Project conventions

- Follow the shared icon resolver and rendering contracts across preview, custom
  selections, recommended mappings, original-icon preservation, fallback,
  publication, and runtime consumers. Trace a geometry or precedence issue to its
  owner before changing individual surfaces. Preserve authored layers, dynamic
  resources, and user choices. See [icon rendering contracts](docs/icon-rendering-contracts.md).
- Use existing MIUIX Glass components and adaptive chrome for UI changes. Check
  shared layout and navigation changes in compact Phone and wide Pad windows.
  See the [MIUIX playbook](docs/miuix-os4-playbook.md) and [Pad layout guide](docs/pad-adaptive-ui.md).
- Follow the [localization guide](docs/localization.md) for user-facing text.
  Preserve canonical source identities and third-party attribution.

## Verification and delivery

- Choose checks based on the changed behavior. `python3 scripts/checks.py --list`
  lists focused groups. See [testing.md](docs/testing.md) for execution and
  device prerequisites.
- For visual fixes, compare the reported state at real display size and try the
  affected interaction. A startup or transition fix needs evidence during
  motion as well as at the resting state. Recheck the affected state when a new
  screenshot or recording reveals a problem.
- Report the checks actually run and any blocker, skip, or unverified surface.
  Build, installation, Java hot reload, native initialization, and visible
  behavior each need their own evidence. Keep task-specific receipts with the
  run artifacts and durable conclusions in `docs/`.
- When asked for segmented commits, group by responsibility and preserve unrelated
  changes. Treat local commits, pushes, and publication as distinct operations.

## Device work

- Use the device authorized for the task. Verify its serial, OEM build, launcher
  build, and installed APK before relying on prior results. Prefer DexSQL for
  exact APK inspection.
- Preserve launcher layout, activation, theme, palette, and app data. Update
  KeiHome in place with an increasing `versionCode` and compatible signing.
  Keep device and process lifecycle changes within the requested scope.
- Use [keihome-device-validation](.agents/skills/keihome-device-validation/SKILL.md)
  for deployment, hot reload, and runtime acceptance. The skill routes to the
  maintained scripts and release checks.

## Shared machine

- Coordinate Gradle builds and device actions through one owner at a time.
  If delegation is enabled and in scope, independent source, log, and test
  reviews can run in parallel. Give each reviewer a distinct question and
  evidence target.
- Close task-owned temporary jobs and remove disposable files when finished.
  Preserve requested running AVDs, original fixtures, mappings, and evidence.
