# Framework manager entry

The scope card (including onboarding) and the Hook-log card share the same
bounded launcher. An already running parasitic manager can be reused without an
installed manager APK or visible notification. Cold launch is not fully
supported; see the known limitation below.

## Routes

1. For a recognized connected framework, ask the already loaded `system_server`
   module to open the genuine parasitic manager.
2. If that route is unavailable, try the installed framework manager.
3. If neither opens, show a MIUIX card dialog with module-page guidance, an
   `ACTION_DIAL` entry, and a copy-code action. The LSPosed code is
   `*#*#5776733#*#*`. The dialer URI is built with `Uri.fromParts` so that `#`
   is not accidentally treated as a URI fragment. ROMs can require manual input.

The installed LSPosed module's `action.sh` sends the protected secret-code
broadcast. Ordinary applications cannot safely copy that privileged command.
KeiHome does not run `su`, ask for notification access, or execute caller-supplied
commands.

## Parasitic protocol

LSPosed's daemon selects a launch intent or the first enabled main-process
Activity of `com.android.shell`, then adds the manager category. The component
is discovered from the actual installed package, not a version table.

- LSPosed: `org.lsposed.manager.LAUNCH_MANAGER`.
- Vector: `org.matrix.vector.manager.LAUNCH_MANAGER`; source-based support,
  not device acceptance.
- Log destination: the manager's `logs` data URI.
- `NEW_TASK | SINGLE_TOP`: the injected host retains its own manifest launch
  mode. Without `SINGLE_TOP`, an existing task can merely return to the
  foreground without dispatching the new log destination.

Primary sources:

- [LSPosed manager service](https://github.com/LSPosed/LSPosed/blob/master/daemon/src/main/java/org/lsposed/lspd/service/LSPManagerService.java)
- [LSPosed activity controller](https://github.com/LSPosed/LSPosed/blob/master/magisk-loader/src/main/java/org/lsposed/lspd/service/ActivityController.java)
- [LSPosed manager activity](https://github.com/LSPosed/LSPosed/blob/master/app/src/main/java/org/lsposed/manager/ui/activity/MainActivity.java)
- [Vector module action](https://github.com/JingMatrix/LSPosed/blob/master/magisk-loader/action.sh)

## Bridge boundaries and lifecycle

The ordered broadcast targets `android`. A signature permission protects the
dynamic receiver; the receiver also validates the actual shared sender UID and
package against the installed KeiHome owner-user identity. Intent extras cannot
supply that identity.

Only two fixed destinations and known manager categories are accepted. The
request has a 1.5-second elapsed-time deadline, checked again after package
resolution. The client stops waiting after two seconds. No arbitrary component,
shell command, or URI can cross this bridge.

The receiver uses the launcher control worker shared with navigation receipts
and desktop appearance notifications. It participates in the runtime
retirement gate and unregisters before that worker stops. This keeps the
ordered broadcast responsive during icon sweeps without a separate manager
thread or polling. API 102 Java reload updates the bridge.

Parasitic launch is currently an owner-user route, matching the native daemon.
An unavailable `system_server` scope falls through to the installed manager or
the manual-entry dialog.

## Acceptance

Evidence is stored under
`.artifacts/research/framework-manager-20261106/acceptance.json`.

The HyperOS Phone AVD has LSPosed 2.2.1-log (7936) and no standalone manager APK.
The scope entry and Hook-log entry are checked by actual interaction and
screenshots with an already running parasitic manager. Focused tests
cover protocol bounds, malformed and expired requests, denied transport, and
bounded waiting. Normal API 102 updates must preserve Launcher, SystemUI and
`system_server` identities.

### Known cold-launch limitation

On 2026-11-06 the user tested the case where LSPosed was absent from Recents.
The entry opened the host Shell Activity's native bugreport warning instead of
the injected manager. This is a failed cold launch, not manager availability.
Cancel that warning; do not generate a bugreport as part of opening the manager.

The bridge currently acknowledges that Android accepted the Activity start.
That acknowledgement does not prove manager injection, so this failure does not
automatically show the manual-entry dialog. The device bridge tests likewise
verify transport and intent acceptance, not cold-start injection.

On 2026-11-23, b0736 on Xiaomi 17 Pro reproduced this limitation during a
44.57-second icon sweep. The control worker called the host Activity within
0.09 seconds of the tap. Android displayed the Shell warning, and the
test returned to KeiHome without generating a bugreport. See the
[shared control acceptance](status-dashboard.md#receipts-during-an-appearance-change).

The user explicitly chose to retain the current implementation and defer
further cold-launch work. The workaround is to first open the genuine manager
through the root module's action or the LSPosed dial code, then reuse KeiHome's
entry. Do not describe this checkpoint as complete parasitic-manager startup
support or use the warm-launch screenshots as cold-start evidence.

The separate host-QEMU crash and recovery are recorded under
`.artifacts/research/avd-host-crash-20261106/`. Its MoltenVK fence/mutex abort is
not an Android Java exception; the exact triggering operation remains unknown.
