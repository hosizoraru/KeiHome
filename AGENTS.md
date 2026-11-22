# KeiHome

## Task workflow

- Use the requested outcome and its acceptance criteria to guide the work.
  Continue through implementation and relevant verification within the
  authorized scope. Make reversible implementation choices when the evidence
  supports them and disclose assumptions that affect the result. Ask when
  missing input blocks progress or an action needs authorization.
- Check the current checkout and relevant runtime evidence before relying on
  an earlier diagnosis. Use new feedback to update the affected acceptance
  criteria and carry forward the rest of the agreed scope.
- Use the current branch and checkout for routine work. Create a new branch
  or worktree when the user asks for one.

## Project conventions

- Follow the normal entry point to the code that owns the behavior and check
  its callers before editing. Reuse existing resolvers, renderers, and UI
  components where their contracts fit. Preserve behavior existing callers
  and saved settings depend on unless the task changes it.
- Follow the shared icon resolver and rendering contracts across preview, custom
  selections, recommended mappings, original-icon preservation, fallback,
  publication, and runtime consumers. Trace a geometry or precedence issue to its
  owner before changing individual surfaces. Preserve authored layers, dynamic
  resources, and user choices. See [icon rendering contracts](docs/icon-rendering-contracts.md).
- Use existing MIUIX Glass components and adaptive chrome for UI changes. Check
  shared layout and navigation changes in compact Phone and wide Pad windows.
  See the [MIUIX playbook](docs/miuix-os4-playbook.md) and [Pad layout guide](docs/pad-adaptive-ui.md).
  Use supplied visual references to check layout and interactions. Keep UI
  copy focused on actions, choices, and status the user needs.
- Follow the [localization guide](docs/localization.md) for user-facing text.
  Preserve canonical source identities and third-party attribution.

## Verification and delivery

- Choose checks based on the changed behavior. `python3 scripts/checks.py --list`
  lists focused groups. See [testing.md](docs/testing.md) for execution and
  device prerequisites. Reuse existing tests and add focused regression
  coverage when the current suite would miss the failure.
- For visual fixes, compare the reported state at real display size and try the
  affected interaction. A startup or transition fix needs evidence during
  motion as well as at the resting state. Recheck the affected state when a new
  screenshot or recording reveals a problem.
- Close with a self-contained account of the outcome, checks actually run,
  and any blocker, skip, or unverified surface.
  Build, installation, Java hot reload, native initialization, and visible
  behavior each need their own evidence. Keep task-specific receipts with the
  run artifacts and durable conclusions in `docs/`.
- When asked for segmented commits, group by responsibility and preserve unrelated
  changes. Treat local commits, pushes, and publication as distinct operations.
- When cleaning up after a merge, verify that the destination contains the
  source commits. Preserve any local work and needed artifacts before removing
  a branch or worktree, and retain experiments the user has asked to keep.

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
