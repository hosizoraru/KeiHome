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

The current declaration has seven targets; the UI reads it dynamically rather
than storing a fixed count.

| Target | UI name | Purpose and limits |
| --- | --- | --- |
| `system` | System framework | System-side synchronization and supported recent-task app-icon snapshots. Requires framework system-scope support. |
| `com.miui.home` | Launcher | Supported desktop, Dock and icon controls. Native launcher capabilities still determine availability. |
| `com.android.thememanager` | Themes | Apply generated/imported packs through the supported theme path. |
| `com.android.systemui` | System UI | Supported materials and notification app-icon surfaces; not every notification symbol or image. |
| `com.android.intentresolver` | System share | Supported OEM share-target app icons; package presence alone does not establish a compatible profile. |
| `com.miui.securitycenter` | Security · Sidebar | Supported Sidebar app-icon entries in recognized Security processes and profiles. |
| `com.android.settings` | Settings | Supported app-icon loading; this does not describe arbitrary Settings artwork. |

These are scopes rather than seven universally available features. Existing host,
process, user, signature and profile predicates remain authoritative. Unsupported
or ambiguous entries retain native icons. An unchanged icon alone does not
establish an authorization failure. The full scope card keeps these purposes
in one compact explanation beside the existing status pills; the attention-only
card omits this explanation on Status and the final guide step.

The shared scope card in onboarding, Scope authorization, and Status displays this
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

Scope authorization contains the complete scope card in read-only mode: refresh
and the external module-manager shortcut remain available, while grant requests
belong to onboarding and the Status attention card. Icon effects and launch
background controls now live in Desktop > Icons and launch screens.
Status shows the scope card only while
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
verified the historical 3/4 state and missing-only request, rejected it through the native
notification, retried, and allowed the new request. All four original grants
were read back afterward. Launcher, SystemUI, and system-server process identities
remained unchanged. The final receipt records that earlier build and UI evidence;
it does not validate the expanded seven-target declaration.

Framework snapshots require a matching live service/client binding and an ordered
read handle. An obsolete service cannot publish either a success or a failure
after reconnect. Dock, launcher glass, and app-name settings require both the
virtual `system` grant and `com.miui.home`; loading status is reported separately.

The original focused JVM verification covered 25 request-controller cases and
4 scope-gate cases; the current request suite also covers app-icon host
eligibility and missing-only upgrades. Final Release UI/build evidence is recorded in
`.artifacts/research/static-scope-20261106/acceptance.json`.

## Current UI and validation handoff

`ui/main/ScopeAuthorizationScreen.kt` owns the dedicated OS4 settings page,
including its collapsing glass toolbar, refresh action, authorization summary,
grouped target rows, framework-manager entry and loading explanation. Each row
shows its purpose, independent authorization status, full version name and build
code. The virtual system target shows Android release and API level. Unsupported,
unconfirmed and absent targets remain distinct. Disconnected or failed reads do
not present previously granted entries as currently confirmed.

`ScopeTargetVersions.kt` reads only the declared packages, on IO, on page
entry/resume and manual refresh. There is no polling, icon decoding, APK hashing
or version-code cache; OEM version names can change without a different code.
This is display metadata, not an input to hook matching or authorization.
`LibxposedStaticScopeClient.kt` shares the existing module declaration parser
with the page so the UI does not maintain a second scope list.

The compact request card in `ui/main/StaticScopeCard.kt` remains for onboarding
and attention prompts. Settings search links to the complete page instead of
embedding that card. `SettingsContent.kt` makes
the surface explanation searchable. EN/ZH/JA `static_scope.xml` resources name
Security and Sidebar together and distinguish authorization, loading and update
behavior. Existing request decisions, source predicates and status implementations
are unchanged by this UI/documentation slice.

After first enablement and scope authorization, one system reboot is recommended.
Subsequent module updates normally use API 102 automatic hot reload; supported
settings sync automatically. This is separate from a newly authorized host needing
its next process start. Status reports describe only the targets actually reported
by the framework and launcher acknowledgements. They do not prove all hosts loaded
or that their icons changed.

Focused checks are `StaticScopeRequestsTest` and `python3 scripts/checks.py
ui-scope --serial SERIAL --run`. Device cases cover refreshing version names
without a code change, stale-grant handling, absent/unknown/unsupported targets,
long versions at 1.6x font, manager access and installed package metadata.

Compare the count against confirmed-installed/supported declared targets in
LSPosed. With all six APK targets confirmed installed, system scope supported and
all seven authorized, expect 7 / 7 and a toolbar Refresh action. Missing,
unsupported and unconfirmed entries stay distinct; another device may have a
smaller denominator. Controls remain disabled during a request, and runtime
warnings remain independent of the granted count.

Check disconnected, partly granted and fully granted layouts. Open the launcher,
recent tasks, a supported notification row, share sheet, Settings app list and
Security Sidebar as available. Compare app icons after applying a pack and changing
light/dark mode; confirm native fallback for unsupported entries. Preserve existing
host/user/profile guards and distinguish authorization from actual loading and
visible results.
