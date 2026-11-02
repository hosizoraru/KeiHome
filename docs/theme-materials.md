# Theme material compatibility

Historical `/tmp/keihome-*` evidence below was consolidated on November 1.
See the [research archive index](research/README.md) for retained inputs and
archive-member lookup.

Settings → Hook behavior contains **Preserve system materials**. The default is
off. After the system worker verifies a non-default applied icon component with
Themes' native `isDefaultIcon` contract, it asks the private App provider to enable
the preference once. The App commits the value and consumed marker together via
API 102. Injected remote preferences remain read-only. The request also works
with Monet disabled and without an open Activity; it uses the existing material
worker rather than a new polling loop.

Any explicit user choice consumes that one-time suggestion, including choosing
off before the first non-default pack. Later refreshes, pack switches, process
starts and API 102 reloads preserve manual off. Unknown/default theme identity
does not trigger a write. A failed automatic commit may retry, but cannot
overwrite an unconfirmed manual choice. Saving a preference or seeing an LSPosed
loaded target does not acknowledge that every visual surface is supported.

## SystemUI

The live `miui.systemui.plugin` loader supplies the classes. DexKit locates and
checks the material capability and theme-restricted gate, their shared fields
and direct callers. The replacement uses the original result unless the user
opts in and the original rejects the theme. In that case it reads the native
capability predicate. Theme identity, theme ZIPs, native material mode and the
downstream power-saving policy are unchanged.

Profile work runs on an owned worker. Configuration, drawing and preference
callbacks do not parse DEX, query providers or wait for that worker. API 102
replacement releases old closures, and retirement rejects in-flight callbacks
or a worker that has not actually exited.

Changing a boolean alone does not invalidate every retained material view:

- Standalone volume marks the verified native reinitialization flag on its
  actual next `showH`. The original code waits until the panel is neither
  showing nor animating, then rebuilds it.
- Control Center reuses its actual STARTED owner, current material mode and
  existing child dispatch. Small tiles also update their native resources and
  current icon state, including the native cleanup branch on opt-out. A pending
  icon update finishes first; no queue, lifecycle state or configuration is
  fabricated.
- Slider holders restore their native progress and track drawables before the
  original blend update, once per holder and preference revision. The native
  glass path removes those drawables; clearing blend alone leaves an empty
  progress bar. Existing progress, disabled state and mirror behavior stay native.
- Real small tiles fill a missing background-geometry cache through the original
  state/resource selector and setter. A mutable native GradientDrawable supplies
  the outline; existing backgrounds, detail-transition fake icons and custom
  non-gradient shapes are not replaced.

These optional refresh contracts are resolved separately. An unknown contract
leaves that surface's original refresh behavior intact.

The host notification shade, host notification glass and every Wi-Fi/Bluetooth
detail row are separate paths. This implementation does not globally change
`ThemeUtils`, pretend that custom resources are stock, or unlock every animation.

## Launcher

Both supplied launcher images have a narrower icon-highlight condition:

```text
!BlurUtilities.isMaterialDisabled() && DeviceConfig.isDefaultIcon
```

Only the theme-result tail is replaced. The native material-disabled branch
remains outside the patch. The resolver checks the linked material predicates,
theme-setting writer, receiver/field relationships and stroke consumer. Runtime
resolution does not use a version/RVA allowlist. The 7722 and 8593 binaries are
offline test inputs, not a claim about all future launchers.

The bridge replays the original field read and returns either its result or
canonical Dart true. It makes no Dart calls, writes no heap objects, retains no
managed references and does not change shared theme identity. The exact 16-byte
detour is installed only before the fresh image executes. Existing running
isolates are not repatched.

The system worker sends a separate authenticated material command over the
existing native channel. It does not alter Monet's policy layout or require
Monet to be enabled. Native feature capabilities are advertised independently;
a highlight-only endpoint cannot report Monet ready. The native worker checks a
45-second lease and returns the opt-in to off if its owner stops renewing.
This is not a hard timeout: synchronous Binder channel acquisition can delay
maintenance while system_server is blocked. Renewals do not trigger icon
sweeps; a changed acknowledged choice can request the existing package refresh.

Java preferences, monitor ownership and policy delivery support API 102 hot
reload. Updating machine code still needs a development native load, distinct
from ordinary preference/theme changes.

## Evidence and boundaries

The independent SystemUI comparison temporarily removed HyperMusicCover's
SystemUI scope: that module also unconditionally intercepts the same material
gate. Its effects cannot be counted as KeiHome validation. The user's scope is
restored after the comparison.

Release 1793454003 exercised volume on/off/on with a nonempty plugin-theme
fixture. The view changed between glass and solid native backgrounds. Launcher
9685/start 3565, SystemUI 16699/start 2311047 and system-server 8810/start 2708
were unchanged, including the preceding API 102 Java reload.

That build also applied Arcticons through Themes' native Icon detail. The
launcher and Dock displayed the pack's artwork without a launcher replacement.
The worker-side converted-rights hash took 11 ms. This does not close the
historical watchdog or disposed-image investigation.

Release 1793454007 passed Java API 102 reload with launcher 29116/start 2930413,
SystemUI 7121/start 3462801 and system-server 8810/start 2708 unchanged.
After a separate development SystemUI restart to load the isolated fixture,
SystemUI 13339/start 4431890 remained unchanged through the final comparison;
launcher and system-server remained unchanged as well. Control Center restored
both brightness and nonzero music-volume fills on opt-out. Standalone volume
switched between solid and glass. The follow-up cold opt-in restored round
small-tile geometry on the first open, and the captured full panel retained
round tiles, filled sliders and glass surfaces. Two APP_SCOUT_WARNING reports
remain recorded. The first captured main in the host battery Compose
recomposition, not in a KeiHome hook; this single stack does not establish the
cause of the delayed frame.

Evidence: `/tmp/keihome-theme-research/material-fixture/` and the versioned
deployment folders. Synthetic component fixtures establish specific contracts;
they are not acceptance of arbitrary full community themes.
