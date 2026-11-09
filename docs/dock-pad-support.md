# Pad Dock support and capability admission

## Decision

Keep the native Pad Dock. Do not put the phone's added glass plate, five-app
geometry assumptions or phone corner/inset calculations over that Dock.
Only enable module overrides whose actual loaded launcher has admitted the
corresponding native contract. The current native override contracts are phone
contracts. For Android products explicitly classified as `tablet`, skip these
unverified overrides and report a zero mask until independent Pad contracts
exist. This is not a ban on the app's Pad use, a model/version allowlist or a
screen-width heuristic.

The retained Pad 5490 source has an independent `_buildPadDock` path, native
glass, pin/recent-app regions and its own layout/corner calculations. Its Dock
radius incorporates the native icon radius plus padding; it should not be
replaced by the phone's decorative geometry. See the earlier independent source
evidence in `.artifacts/research/dock-material-20261105/pad-native/FINDINGS.md`.

On the current Pad AVD, all five native feature resolvers reject their contracts.
The phone Dock profile rejects the exact Pad image with
`ambiguous label instruction roles`. Preserve that rejection; increasing the
phone icon-count limit or accepting a partial pattern is not Pad adaptation.

The classification comes from the exact `tablet` token in Android's
`ro.build.characteristics`. Substrings such as `notablet` do not match. A tablet
startup does not enter phone profile scans, the independent monochrome support
gate, or the unverified MAML ZIP release override. Framework safety ownership
guards remain in place. Diagnostic admission is safe for an already loaded
isolate because it does not resolve or patch Dart code.

## Runtime and UI

The recognized launcher starts the existing authenticated policy endpoint even
when every feature resolver rejects. Its zero capability mask is a diagnostic
result, not a successful setting acknowledgement. Missing-feature commands,
including disable commands, remain rejected without touching feature state.
An absent endpoint, failed authentication or an unknown capability bit remains
an error; none is converted into a zero mask.

Status schema 7 appends a nullable capability mask. Zero means an authenticated
resident launcher has admitted no feature; null means unknown. Schemas 1-6 remain
readable and cannot establish a negative capability merely by omitting it.
PID/start identity, module version, report age and user checks remain in force.

The desktop overview counts supported features rather than always displaying
three expected acknowledgements. With no admitted override it shows a neutral
unsupported summary, not a success state or an endless reception indicator.
Dock, name-display and material controls independently reflect their own bits.
Saved choices are retained. Dock layout admission is separate from basic Dock
admission; legacy basic choices must remain recoverable without layout support.

This does not port Pad-native padding, recent-app areas, keyboard/taskbar modes,
orientation behavior or drag geometry into new settings. Those require a
separate Pad factory/layout contract and device acceptance.

## First activation

After first enabling the module and confirming scope, recommend one system
restart so system_server, SystemUI and native launcher processes load it at a
known starting point. Ordinary supported preference changes and subsequent Java
updates continue to use API 102. A newly built native payload may need a
development scope restart. Neither repeated restarts nor re-saving settings can
make a rejected feature contract supported.

## Evidence boundary

Source, native installer/command-isolation tests and UI status tests are separate
from real Pad acceptance. Research and final results are recorded under
`.artifacts/research/pad-dock-20261110/`.

The first Pad emulator session crashed in the macOS host MoltenVK descriptor
update path. Disabling Vulkan is not a valid workaround for this image:
SurfaceFlinger requires a Vulkan-capable renderer. Software rendering is not
used for performance conclusions. These environment failures do not establish
that a module feature caused them, or that an unmodified Pad Dock was visually
accepted.
