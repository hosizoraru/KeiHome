# HyperOS share-sheet icons

KeiHome adapts application targets in HyperOS's `MiIntentResolver`, in the
`com.android.intentresolver:chooser` process. This is an additional static scope,
not a hook on the AOSP chooser or on all applications' PackageManager calls.
Authorize **System share sheet** in the module's static-scope card. A process
that ran before its first authorization must load the module on its next start.
Subsequent Kotlin updates use API 102 retirement/adoption.

## Contract and ownership

The runtime authenticates the installed host APK on a background thread with
DexKit, using method/field relationships rather than version codes or offsets:

- OEM `TargetPresentationGetterStubImpl` unbadged loading and user badging;
- `GroupTargetAdapter.onBindGroupTargetItem`, its native icon-holder field,
  `mBoundInfo` write and native ImageView binding;
- the ResolveInfo, display-holder and drawable getters.

The common `ResolverListAdapter.ViewHolder.bindIcon` is not the actual HyperOS
share-grid path. The implementation hooks the OEM group adapter instead.

Only ordinary `DisplayResolveInfo` and a verified, getter-inheriting
`MultiDisplayResolveInfo` are admitted. Direct-share contacts, shortcut artwork,
the OEM "all apps" item and Xiaomi Share discovery controls remain native.
Explicit ResolveInfo resource overrides, activity-specific icons and other-user
targets also retain their originals, including profile badges. Labels, action
intents, enabled/suspended state, click handling and native animations are not
changed.

## Work and cache bounds

The native bind runs first. The hook performs only identity/field reads and an
in-memory cache lookup. Archive reads, palette reads and image decoding run on the
existing cross-surface worker. A cold miss keeps the native icon; completion replays
visible attached views on the main thread. There are at most 64 cached icons and 128 weak
view bindings. No source MAML program, animation timer, polling task or Binder
query is created by a bind.

Theme, configuration and completed palette publication invalidate cached
snapshots. Palette publication explicitly targets the resolver package. Its
separate exported receiver requires `INTERACT_ACROSS_USERS_FULL` from the sender
and checks the shared sender UID is system UID 1000. Other cache receivers keep
their existing registration rules.

Retirement rejects new callbacks, drains in-flight work, stops the cache worker,
unregisters both receiver registrations, removes queued UI callbacks and unhooks
without holding the handle monitor over framework calls. Only the live host
loader crosses generations. The successor adopts attached OEM rows using a
one-time, bounded view traversal (512 nodes, up to eight activity records), and
reads their untouched native icon holders. It does not retain the previous
module's callbacks, classes or binding records.

## Shared renderer fixes

Component-to-package fallback must authenticate the Monet manifest against the
actual resource root. Previously it regenerated a template for the requested
component name while comparing it to the package manifest, causing a map-key
exception and native-icon fallback.

Monet snapshots now tint the foreground/background alpha masks independently
with a source color filter and use the native adaptive mask. This preserves
transparent pixels and distinct palette colors, and removes two temporary
full-size bitmaps. Tests verify both exact colors and component/package parity.

## Share-sheet workload balance

The share process uses demand-driven invalidation. A theme, palette or appearance
change clears its snapshots and coalesces one notification, without decoding all
previously requested targets. Replay requests only attached, shown views whose
window is visible. An existing native `Activity.onPostResume` hook queues replay
when the panel returns, including when appearance changed while it was hidden.
Normal native binding may still prepare a newly created, unattached row before it
appears. There is no new polling loop or network request.

Each binding reuses its `BitmapDrawable` while the cached bitmap stays the same,
and skips `setImageDrawable` when that drawable is already installed. A valid old
snapshot remains visible during asynchronous replacement; a definitive miss
notifies the owner once and restores the original native drawable. Native
rebinding can change that original drawable without changing the target identity,
and is respected. This avoids flashing the native icon during refresh while
retaining failure fallback and native panel animations.

These changes preserve the 192px snapshot resolution, 64-target/9MiB bitmap-cache
budget and 128-binding limit. The other cross-surface cache owners retain their
existing eager invalidation by default. Device tests exercise hidden invalidation
bursts without palette reads, on-demand rebuilding, miss completion, repeated
cache-hit presentation, native rebinding and retirement.

Follow-up evidence is stored under
`.artifacts/research/share-balance-20261110/acceptance.json`. Emulator frame and
memory observations are diagnostic samples, not physical-device performance
claims or a general percentage improvement.

## Acceptance checkpoint

Evidence: `.artifacts/research/hyperos-share-20261110/`.

Phone AVD only: HyperOS Home 8593, MiIntentResolver from the installed system
partition. Actual Gallery share panels were inspected with ColorOS Classic ART+
in day/night modes and with Monet enabled. No share was sent. Unmapped targets
retain their native artwork.

Repeated normal API 102 updates kept resolver PID 18880, with retired/adopted
receipts and 11 visible rows adopted. The deployment gate also verified unchanged
Launcher, SystemUI and system_server identities. The first newly authorized
resolver scope was activated separately by restarting only that package.

JVM checks: 1218 tests, 1212 passed and six skipped. Focused device results and
the final installed version are recorded in `acceptance.json`. The diagnostic
APK retains the previously validated Phone native payload; this Kotlin work
does not claim validation of the newer Pad native source or a new public release.
