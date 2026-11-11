# HyperOS AVD first-run research and KeiHome OS4 guide

## Inspected input

- Installed package: `com.android.provision`, `/system_ext/priv-app/Provision/Provision.apk`.
- Manifest versionName: 17 / versionCode: 37. This is the AVD fixture, not a claim about all HyperOS builds.
- Retained local input: `.artifacts/inputs/provision/HyperOS-AVD-Provision.apk`.
- SHA256: `675fe33e05cdb29f70982abd67a2a9f4339b0899a1fbab4203e573061083d841`.
- `com.android.managedprovisioning` is also installed, but is the managed-device provisioning package; the ordinary wizard HOME/default activity is in `com.android.provision`.

## Direct observations

DexSQL manifest, resource XML and method inspections identify `DefaultActivity`, `StartupLanguageActivity`, `miuix.provision.ProvisionBaseActivity`, and a nested `DefaultActivity$StateMachine`.
`DefaultActivity.onCreate` calls `Utils.isDeviceProvisioned` and directly invokes `finishSetup(false)` for an already configured device. Accordingly, the OEM flow was not relaunched and provisioning flags were not reset. Both `device_provisioned` and `user_setup_complete` read 1.

Resource structure examined: `startup_layout_new.xml`, `new_provision_actionbar.xml`, and `new_provision_lyt_title.xml`. It separates sparse startup artwork, a centered title/subtitle, and persistent action navigation. Raw XML decoding contains unresolved/suspicious numeric dimensions; those values were not treated as design specifications or copied.

OS4-specific classes include `com.android.provision.ui40.widget.GlassButton`, `GlassMaterialHelper`, `MorphingButton`, and startup title animation. `ProvisionBaseActivity.applyLayoutAdaptation` applies display/font layout adaptation. `DefaultActivity$StateMachine.transitToNext` selects the next available state and saves progress.

These observations support general UI principles: a clear primary action, controlled progression, persistent navigation, large titles, optional/conditional steps, state restoration, and layout adaptation. They do not constitute live visual acceptance of the OEM wizard.

## Independent KeiHome implementation

KeiHome uses the existing pinned open-source Compose MIUIX OS4 components, its own artwork and copy. OEM Java MIUIX provisioning classes are distinct from that library; no OEM code, assets, state machine, strings or numerical layout constants were imported.

The five stages remain welcome, framework, icons, desktop and finish. The guide now has a light header with animated step markers, a title/artwork focus, grouped choices, and a separate floating OS4 back control and primary action. Existing navigation transitions remain responsible for page direction. Artwork reveals are finite; no animation dependency, video or infinite animation is added.

Framework status and scope requests stay explicit. A compact panel replaces the full scope viewer; the dedicated authorization page exposes per-host details. Icons retain all existing sources, with optional advanced controls and an OS4 disclosure for local-resource details. Desktop links open the existing categories directly. Completion reports guide completion, not inferred host loading. All stages remain optional.

Progress persistence, fresh-install detection, upgrade behavior, Settings replay and preferences remain unchanged. Navigation ignores repeated callbacks from a page that is no longer the current route.

Focused acceptance is `ui-onboarding`; scope tests and unrelated native suites are not part of routine UI iteration. Phone AVD acceptance does not establish physical-device or OEM-wizard runtime acceptance.
