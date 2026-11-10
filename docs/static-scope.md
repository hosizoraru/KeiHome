# Static scope authorization

KeiHome declares `staticScope=true` and reads its targets from the packaged
`META-INF/xposed/scope.list`. Declaration, user authorization, and a target
loading the module are separate states.

The implementation follows the project's pinned libxposed service 102.0.0:

- [Scope requests and OnScopeEventListener](https://github.com/libxposed/service/blob/102.0.0/service/src/main/java/io/github/libxposed/service/XposedService.java)
- [Module declaration and the virtual system target](https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html)

The request set is the declared targets intersected with confirmed packages
installed for the current user, minus the actual framework grants. `system` is
the virtual system-server target and requires `PROP_CAP_SYSTEM`; `android` is
not substituted for it. An individual package lookup failure remains
unconfirmed and cannot revive a known absent target. An empty request is never
submitted, and framework grants outside the declaration do not affect counts.

The declared app-icon hosts include `com.android.intentresolver` (**System share
sheet**), `com.android.settings` (**Settings**, including app management and app
details), and `com.miui.securitycenter` (**Security**, including the sidebar).
Settings and Security are explicitly listed in manifest queries; the existing
installed-app browser also retains `QUERY_ALL_PACKAGES`. Like other real packages,
these hosts are eligible only when confirmed installed for the current user.
Existing grants remain valid; an upgrade requests only missing installed targets.

The shared scope card in onboarding, System and Hook, and Status displays this
host authorization state. Granting Settings or Security access does not require
restarting the system, Launcher, or System UI. A host process already running
without the module must load it on its next start; authorization alone does not
confirm that its app-icon hooks are loaded.

The current framework scope is reread immediately before submission, after
either callback, on returning to the activity, and on request timeout. Callback
approval is a signal to reconcile, rather than proof that every target was
granted. Read results have ordered generations, and replacing or losing the
service invalidates pending callbacks. Only one request may be active. Returning
without a result releases the waiting state after a short callback grace period;
the timeout also reconciles before allowing a retry. The deadline starts before
the submission-time read, so that read cannot leave the card waiting forever.
A submission read also participates in ordering: a newer partial grant cannot
be overwritten by an older preflight result. Reconciliation immediately refreshes
the feature gates through the existing framework snapshot reader.

Callbacks retained by libxposed hold only a weak reference to the request
controller. Binder calls and per-package lookups run on IO; state changes run in
the ViewModel's main scope. Existing framework observation supplies periodic
scope updates; this feature adds no polling loop. Transient waiting state is
not persisted.

System and Hook contains the complete scope card. Status shows it only while
authorization needs attention, and settings search can find it. Framework access
is offered when the module service is unavailable. Authorization does not
restart targets or claim that a newly granted target has already loaded; actual
API 102 target and native acknowledgement reports retain that responsibility.

Host tests cover eligibility, current grants, duplicate requests, partial and
missing approval, denial, Binder/read failures, service replacement, stale reads,
activity return and timeout. AVD acceptance and the final release identity are
recorded under `.artifacts/research/static-scope-20261106/`.

On HyperOS, the framework presents the request in a system notification. Long
pressing that notification exposes its native Allow, Reject, and Never ask
actions. KeiHome points to the framework dialog or notification; the framework
handles the decision.

The Phone AVD acceptance temporarily removed only the Themes authorization,
verified the 3/4 state and missing-only request, rejected it through the native
notification, retried, and allowed the new request. All four original grants
were read back afterward. Launcher, SystemUI, and system-server process identities
remained unchanged. The final receipt records the tested build and UI evidence.

Framework snapshots require a matching live service/client binding and an ordered
read handle. An obsolete service cannot publish either a success or a failure
after reconnect. Dock, launcher glass, and app-name settings require both the
virtual `system` grant and `com.miui.home`; loading status is reported separately.

The original focused JVM verification covered 25 request-controller cases and
4 scope-gate cases; the current request suite also covers app-icon host
eligibility and missing-only upgrades. Final Release UI/build evidence is recorded in
`.artifacts/research/static-scope-20261106/acceptance.json`.
