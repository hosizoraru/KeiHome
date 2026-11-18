---
name: keihome-device-validation
description: Validate KeiHome on an authorized Android device. Use for in-place APK deployment, API 102 hot reload, Phone/Pad visual QA, or actual icon-pack and Monet application. Use focused repository checks directly for source-only work.
---

# KeiHome device validation

Confirm the requested serial, form factor, installed builds, and validation scope
before sending device commands. Use an explicit serial for each operation. Check
live identities against historical evidence before reusing a fixture or receipt.

## Choose the checks

- **App UI.** Use the focused groups in [testing.md](../../../docs/testing.md).
  For shared layout changes, exercise the actual Phone and Pad windows. Check
  selection, scroll position, overlay coverage, and touch ownership when they
  are affected. Capture real UI at the reported state and use the relevant
  interaction fixtures from the [Pad guide](../../../docs/pad-adaptive-ui.md).
- **APK deployment and API 102.** For the managed HyperOS Phone and Pad AVDs, consult
  [the deployment script](../../../scripts/deploy-hyperos-avd.py) and its
  `--help`. Use the readiness checks in [testing.md](../../../docs/testing.md).
  Record the installed APK identity and per-target reload receipts, including
  failed or unknown targets. Report the observed population. If packaged native
  bytes change, verify the native load separately using the
  [launcher matrix](../../../docs/launcher-native-matrix.md).
- **Icon packs and Monet.** Follow the [rendering contracts](../../../docs/icon-rendering-contracts.md).
  For a reported import or visual issue, start with the exact source pack and
  reproduce through the normal UI. Verify the applied theme and visible
  artwork at the affected surface, including light/dark or palette transitions
  as applicable. For a release, use the full
  [icon-pack acceptance gate](../../../docs/release-iconpack-acceptance.md) and
  confirm the live device matches the selected workflow.
- **Performance.** Compare the same build type, scenario, device state, and
  workload before and after. Keep measured stages and cache states explicit.
  Use the Android profiler skill when a trace or allocation profile is needed.

## Preserve and verify state

Before a test temporarily changes theme, palette, appearance, custom selections,
or rotation, capture the relevant starting state. Restore and verify that state
after the test, including after failure. Retain the original reporter fixtures
and the APK, mapping, and receipts needed to identify the tested build.

Use an in-place update for a compatible candidate. For installation-only work,
limit checks to the candidate identity and installation result. Keep UI actions,
runtime checks, and device lifecycle operations within the requested scope.

In the result, name the device and build, state the actual outcomes, and call
out any skips, unresolved visual defects, or checks that could not run.
