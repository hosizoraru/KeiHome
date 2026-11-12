# Application icons, launcher aliases and dynamic snapshots

This investigation follows reports against `1.8.0+g61f1cc6.b0352`:
square icons outside the launcher, differences between selectable launcher
icons, and a dark calendar on the launcher remaining light in notifications.

## Responsibility and resource findings

The installed KeiOS Release APK uses a valid adaptive XML icon with a gradient
background, an inset vector foreground and a monochrome layer. Rendering the
complete `AdaptiveIconDrawable` preserves transparent corners. Drawing its
background and foreground without the enclosing adaptive mask reproduces the
reported square background. This is not evidence that an opaque adaptive
background, or an `activity-alias`, violates Android's resource rules.

ErinAO's selected icon is also adaptive and has an opaque background. The
published test theme already includes exact resources for its selected alias
and another alternative. The same theme lacks an exact resource for KeiOS's
Release alias. These are different fallback cases, so ErinAO's appearance
does not establish that KeiOS needs transparent backgrounds or renamed aliases.

The retained launcher 8593 establishes this native filename rule:

* A class beginning with its package string uses the full class name.
* A class outside that prefix uses `package#full.class.Name`.
* Missing activity information uses the package name.

The raw prefix behavior is intentional compatibility with the native reader;
it is not a general Java namespace rule. A legacy foreign bare-class lookup
can be read where the native reader supports it. Newly generated foreign
aliases use the owner-qualified `#` form.

The native Fancy reader also restricts package-level fallback for some
activity-specific icons. A package resource alone therefore does not guarantee
the same result for every alias. No version/RVA whitelist or native patch was
added for this fix.

## KeiHome changes

Generated static and dynamic resources use the native component filename,
including monochrome, foreground/background and fallback metadata. Distinct
packages sharing a class name are no longer discarded as a globally shared
activity. Differing artwork cannot silently overwrite a filename collision.

Generation inventories use current launcher entries. Disabled alternative
styles must not suppress the package fallback needed by foreign-prefix aliases:
the native fancy-icon path does not try `pkg#cls` roots directly. Exact static
alias resources remain distinct, while a package-level fancy fallback is
created only when current entries share identical artwork. Preview and
published snapshot lookup use the same component naming rule.

The generation schema is now 6, recording the active launcher entries when
the scheme was generated. An alias switch in a managed package can request
regeneration even if an old package fallback or offline candidate exists.
Older converted sources request regeneration once; native MTZ/icons imports
retain their authored layout. Schemas 1-5 remain readable.
Existing generated themes must be updated to acquire missing alias
resources; loading a new module cannot retroactively create those files.
The converted-theme approval boundary accepts the same qualified filenames
for ordinary PNGs and canonical local MAML. It still rejects multiple `#`
separators, unqualified sides, traversal and altered MAML; accepting a new
filename does not authorize arbitrary programs.

Source images remain unclipped while composing their layers. Final static PNG
exports receive the effective mask, including native system fallback additions:
a native static loader can bypass MAML and cannot be assumed to clip those
files. Every successful cross-surface snapshot branch now passes through final alpha
clipping using the published theme's `transform_config.xml`. Pending preview
preferences do not change an already applied theme. The clip preserves existing
transparent edges instead of multiplying their alpha twice. It adds no white
matte. Per-archive mask parsing and bounded coverage caching keep this work off
binding and scrolling paths.

SystemUI's asynchronous cache previously had no ready callback. A first native
fallback could remain on an already bound notification after the requested
snapshot was ready. The notification bridge now associates authenticated
application-header Icon objects with weakly held image slots and replays ready
snapshots on the main thread. Native binding still runs first. Recycled slots
and stale asynchronous work cannot receive the old application's image.
The bridge has bounds of 96 Icon provenance tokens, 64 view bindings and 64
snapshot entries. Date/configuration/theme changes invalidate the existing
cache; minute refresh is restricted to resources recognized as dynamic.

The cached native Drawable path is authenticated separately. DexKit checks the
notification utility's argument shape, cached package/Drawable reads and image
setter call, and verifies the native UID/Drawable producer. Runtime binding
reads those verified fields without PackageManager calls. A native header is
eligible when its final Drawable is the exact cached application icon.
The separately authenticated custom application-icon producer can also supply
provenance while that same notification is inside the verified header binder.
Its declared `miui.opPkg` must match the native package/UID identity. Hybrid
senders and CallKit category images are excluded. The original notification
layout, large-picture content, avatar slots and actions are retained.

Host contexts can retain a configuration override from an earlier appearance.
The worker therefore snapshots configuration callbacks, observes system
configuration broadcasts, and reads an explicit global night setting once per
cache generation. Automatic/scheduled mode uses the effective global resource
configuration. Image hooks still read only the memory cache. A regression test
pins the host context to the opposite mode and checks the actual rendered
day/night pixels against the system setting.

## Dynamic resources and appearance

Calendar and clock snapshot tests cover appearance-dependent backgrounds,
foreground/layer composition, localized dates and time-dependent changes.
Only independently recognized local templates are interpreted; unknown
programs keep their static fallback.

Layered resources are selected as a complete light or dark group, including
their declared quiet snapshots. An incomplete night group falls back to the
complete light group rather than mixing its background and foreground.
Tests cover fixed calendar appearance, additional system-night dimming,
all clock hand/background night assets and time advancement. Third-party
weather programs retain their declared quiet/static artwork; this change
does not execute arbitrary weather MAML or promise live weather outside
the launcher's own runtime.

A read-only copy of the actual applied ColorOS Classic ART+ theme was checked
separately. Its calendar renders a different, darker night background and
changes its date for the following day, with transparent final corners.
This demonstrates that the supplied resource can render correctly; it does
not by itself demonstrate that every physical-device notification has rebound.

## Guidance for future KeiOS icon work

Keep stable launcher component names and an explicit package application icon.
Keep each selectable alias's resource reference unambiguous. An opaque adaptive
background is valid: retain separate background, foreground and monochrome
resources, with deliberate safe insets for foreground artwork.

When updating resource names, keep references valid for the base application
icon and each alias, including legacy-density resources where provided.
Repainting the dark design can improve its appearance but is not a substitute
for clipping the complete adaptive icon at the consumer.

Regression acceptance should include Release/debug/capture channels installed
together, switching aliases, a cold load, light/dark changes, launcher folders,
recents, notification headers, Settings application details and sharing.
Compare both exact-theme coverage and an unmapped original adaptive fallback.
Do not treat one mapped ErinAO or Telegram style as proof of every branch.

## Evidence and limits

Final diagnostic: `1.8.0+g61f1cc6.b0373.dirty` / `108000373`, signed with
KeiOS Release. The installed APK hash matches the retained diagnostic.
The validated native payload remains unchanged.

- The earlier runtime batch ran 209 JVM tests (one skip) and 54 device
  tests (one environment assumption skip), with no failures. Those device results belong
  to diagnostic `108000366`.
- After final export/metadata changes, 81 focused JVM tests and 11 device
  tests passed; Debug and AndroidTest assembly and Release lint passed.
- Actual applied theme contains `KeiHomeIconGeneration6`. Release, debug,
  diagnostic and profile-capture KeiOS channels each have correct canonical
  static entries, transparent corners in both appearances and package-level
  dynamic fallback manifests. All four visible icons are rounded in the
  settled launcher screenshot. Existing pink artwork is preserved; rounding
  does not invent a missing dark design.
- On the final diagnostic and newly applied theme, the same retained QA
  calendar notification displayed date 12 and switched dark → light → dark
  without reposting. LibChecker stayed rounded throughout. Screenshots
  `notifications-final-{night,day,night-return}.png` record this sequence.
- API 102 retired/adopted reload passed. Launcher PID 9692, SystemUI PID 9660
  and system_server PID 8860 were retained. No critical scope was stopped.
  Night mode was restored and the owned calendar notification/channel removed.

Research inputs, native call-chain evidence, diagnostic images, build logs and
device results are retained under:

* `.artifacts/research/keios-square-icon-20261112/`
* `.artifacts/research/keios-erinao-alias-20261112/`
* `.artifacts/research/icon-chain-20261112/`

The physical reports remain user evidence. This work does not replace the
physical launcher or restart its scopes. Native disassembly does not establish
that every upstream adaptive fallback branch is responsible for the reported
square icons. Final acceptance records distinguish direct renderer tests,
real published-resource tests, live hook activation and visible UI results.
