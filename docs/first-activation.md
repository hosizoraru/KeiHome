# First activation and device restart

First activation requires enabling KeiHome, authorizing the required scopes,
then restarting the device and unlocking to HOME. Completing or skipping the
guide, restarting the app, or restarting Launcher alone is not the first-use
procedure. Later ordinary Java updates normally use API 102 hot reload;
updating an already-loaded native payload remains a separate activation case.

## Persistent reminder

`InitialActivationPolicy` tracks first activation independently of
`OnboardingPreferences`. The Status hero prioritizes the first-use reminder
and replaces its usual automatic-loading footer. A compact guidance card opens
the existing setup replay route. Looking at the guide does not acknowledge or
hide the reminder. Runtime details also use this guidance instead of suggesting
a per-scope restart when first activation is still pending.

The app observes `Settings.Global.BOOT_COUNT` on the IO dispatcher. A boot
baseline is armed only after the system scope has actually been observed as
granted. Changes before authorization do not satisfy activation. A different
known boot count after that baseline completes the one-time reminder.
Explicit scope revocation before completion clears the baseline.

A live framework target showing that system_server has loaded this module
also confirms initial injection. A loaded older generation counts for this
one-time purpose, so an ordinary app upgrade does not require another first
restart. It does not establish native freshness, readiness of other hosts,
or correctness of a feature. Those retain their existing live diagnostics.
Failed or zero-version targets do not supply this confirmation.

| Evidence | Reminder |
| --- | --- |
| Authorized scope and known baseline, same boot | Device restart required |
| Boot identity unavailable, or authorization not yet observed | Explicitly cannot confirm; show first-use sequence |
| Different known boot after authorization | First-use reminder completed |
| Confirmed loaded system target | First-use reminder completed |
| Guide completed/skipped, app recreated or manually refreshed | No completion by itself |

Completion is persisted once; later framework refreshes do not reread the boot
counter after it is confirmed. The atomic record lives in `noBackupFilesDir`,
so normal app-setting backup/restore cannot import another device's completion.
An unreadable or invalid record falls back to unconfirmed, not completed.
Storage errors do not turn a successful framework read into a connection error.
There is no reboot command, forced scope restart or new timer.

## Focused verification

- `initial-activation`: pure policy, persistence format, authorization order,
  unknown boot identities, actual boot changes and existing loaded generations.
- `ui-initial-activation`: real atomic-file round trips, skipped guide, process
  recreation, boot counter simulation, failed targets, Status guidance and
  removal of the conflicting loading footer.
- The existing onboarding large-text journey checks the strengthened first-use
  instructions at font scale 1.6.

Device tests use isolated storage names and simulated boot counters. They do
not reboot or wipe a configured test device. Real-device first-install and
post-reboot acceptance remains distinct from these simulated transitions.
