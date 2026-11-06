# Framework manager entry

The scope card (including onboarding) and the Hook-log card share the same
bounded launcher. An installed manager APK and a visible manager notification
are not prerequisites.

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

The receiver uses the existing icon-refresh worker, participates in the runtime
retirement gate, and unregisters before that worker stops. It adds no polling,
new worker thread, persistent service, or native payload change. API 102 Java
reload is sufficient.

Parasitic launch is currently an owner-user route, matching the native daemon.
An unavailable `system_server` scope falls through to the installed manager or
the manual-entry dialog.

## Acceptance

Evidence is stored under
`.artifacts/research/framework-manager-20261106/acceptance.json`.

The HyperOS Phone AVD has LSPosed 2.2.1-log (7936) and no standalone manager APK.
The scope entry and Hook-log entry are checked by actual interaction and
screenshots, including an already running parasitic manager. Focused tests
cover protocol bounds, malformed and expired requests, denied transport, and
bounded waiting. Normal API 102 updates must preserve Launcher, SystemUI and
`system_server` identities.

The separate host-QEMU crash and recovery are recorded under
`.artifacts/research/avd-host-crash-20261106/`. Its MoltenVK fence/mutex abort is
not an Android Java exception; the exact triggering operation remains unknown.
