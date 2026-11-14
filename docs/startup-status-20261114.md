# Cold-start status presentation

## Cause

The framework service StateFlow starts with null before its connection callback.
The UI previously interpreted that initial null as a confirmed disconnection.
Desktop cards also interpreted a not-yet-read scope as a missing scope. The first
successful observation corrected those states, producing a brief error flash.

## Behavior

- `FrameworkUiState.checking` distinguishes the initial bind/snapshot read from a
  completed observation. The first bind has a bounded 4-second wait, canceled as
  soon as the service arrives. This does not block app startup. Losing an
  established connection is reported immediately, without another grace period.
- Initial runtime requests are pending until they produce a result. Existing
  freshness, version checks, 6-second runtime timeout, and failure classification
  remain authoritative. A still-valid report stays visible during refresh.
- `StatusTone.CHECKING` presents an honest neutral state for the Status hero,
  runtime details, desktop connection/feature cards and scope summary.
- Dock, labels, launcher/search glass, icon-pack Monet and splash settings wait
  for scope resolution instead of announcing missing scope. Their controls remain
  disabled until authorization/capabilities are confirmed.
- Scope attention prompts wait for the first plan read. Confirmed absent scope,
  unsupported capabilities, read/save failure and timeout keep their existing
  warning/error handling.
- No persisted success cache or fixed display delay is added. There are no
  native/Hook changes, preference migrations or new startup disk writes.

Existing localized checking/reading labels are reused in all three languages.

## Verification

Focused JVM group `startup-status`: 115 tests, no failures or skips, including
the first-bind deadline, cancellation on success, immediate subsequent
disconnection, late recovery, neutral initial presentation, freshness and real
failure severity, scope requests and desktop feature transitions.

AVD group `ui-startup-status`: 9 tests passed, including a pending bind/snapshot
transition to verified success, confirmed disconnection/read failure, scope plan
loading and the existing scope authorization regressions.

Evidence is retained under
`.artifacts/research/startup-status-20261114/`: JVM and instrumentation results,
ordinary API 102 deployment receipts, Release build/Lint output, cold-start
recordings, screenshots and final delivery acceptance. Recordings supplement the
deterministic UI transition tests; sampled video frames do not prove every
possible display frame on every device.

Delivery uses in-place APK updates. No app data reset, launcher termination,
guest reboot, ADB/network reconfiguration or native reload is required.
