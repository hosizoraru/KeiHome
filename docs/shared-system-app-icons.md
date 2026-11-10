# Shared system application icons

System icon compatibility is implemented by shared policy and rendering, with
small adapters at the points where a host actually receives application images.
It is not an unrestricted hook of every Drawable or every installed package.

## Common pipeline

`SurfaceIconSnapshotRenderer` resolves the applied native theme, appearance,
Monet palette and supported calendar/clock resources. `CrossSurfaceIconCache`
owns bounded background decoding, generation invalidation, negative results and
coalesced completion notifications. Existing Recents, SystemUI and HyperOS Share
adapters reuse this pipeline.

`HostAppIconRuntime` adds the same pipeline to Settings and SecurityCenter:

- The framework's unbadged application-item API and the MAML application-item API
  provide an explicit package and `ApplicationInfo`. User identity is checked
  before substitution. Ordinary resource Drawable requests are not intercepted.
- Returned Drawable instances and renderer-owned snapshots can carry application
  identity into an ImageView. Native shared loading Bitmaps are not labelled.
  Conflicting identity hints become ambiguous instead of selecting the last
  application; bitmap generation IDs invalidate hints after pixel mutation.
  An explicit native application request takes precedence over these hints.
- SecurityCenter's shared `pkg_icon` loader covers multiple consumers, including
  application management, application details and the Sidebar application grid.
  It is identified through native instruction-independent DEX relationships.
  Non-application URIs clear ownership instead of replacing photos or banners.
- Sidebar's already-prefetched rail Bitmaps and the all-apps grid also receive
  identity directly from their native models. The grid's inherited image getter
  and package getter must resolve to local field reads; an inherited concrete
  getter is supported even when its declaring base class is abstract.
  This keeps ownership available independently of image-loader cache hits.
  Its actual UI process, `com.miui.securitycenter:ui`, is admitted explicitly.
  Unrelated remote, power and cache processes are not given icon workers.

This reduces scene-specific code; it does not make every OEM consumer use the
same API. A consumer that copies/transcodes an image and bypasses both common
interfaces may still require a thin adapter. Adding such an adapter must not
duplicate theme parsing, palette logic or decoding.

## Cost and lifecycle

Successful snapshot insertion explicitly clears any negative result. In
particular, `LruCache.put` returning `null` means there was no previous value; it
does not indicate failure. Treating that return as failure restored native
placeholders after a valid first render, including blank Sidebar clock/calendar
backgrounds. A real worker/ZIP/bitmap regression covers first insert, memory
reuse and appearance invalidation.

The new hosts use a 48-entry bitmap LRU (about 6.75 MiB at 192 px), at most 96 weakly tracked views and
bounded weak identity maps. Attached views can retain their displayed snapshots
after eviction; the LRU limit is not a limit on the host's total bitmap memory.
Public image delivery performs no ZIP decoding,
network request or synchronous palette Binder query on the UI thread.
Replaying an unchanged bitmap reuses its Drawable wrapper. The native ImageView,
touch handling, scaling, transitions and Sidebar material remain host-owned.

Minute broadcasts refresh supported dynamic snapshots only when a dynamic
application has been requested. Hidden views are not decoded merely to satisfy
that broadcast. There is no new frame callback, second ticker or polling service.
The desktop's original live icon implementation is unchanged.

Hot reload closes callback admission, drains in-flight hooks and stops workers
before publishing retirement. View handoff tags contain bootstrap containers and
the native fallback Drawable; they do not contain this module's listeners,
closures, runtime instances or ClassLoaders.
The new generation resolves host members through its current host loader.
Only bounded descriptor strings and APK source identities survive between
generations. The Sidebar descriptor schema is versioned; member or source
changes require discovery again, while unchanged sources restore members through
the current loader without reopening DexKit.

### Updates without version changes

Sidebar discovery does not select an implementation by `versionCode`,
`versionName`, an APK hash allowlist or obfuscated member names. DexKit follows
the `pkg_icon` URI producers, request-wrapper/native-loader edges, model field
reads, ImageView delivery and Dock caller relationships. Every role must be
unique and its reflected signature must agree. Missing or ambiguous contracts
leave the native host image in place.

Cache schema 3 authenticates the canonical source path and SHA-256 of the
actual APK bytes, including all DEX entries. ZIP entry CRCs/sizes are checked
for shape but are no longer the content identity. The archive must fit the
512 MiB input budget; hashing is streamed with a 64 KiB buffer on the discovery
worker. A cold discovery checks the source again before publishing its proof.
Cached generations hash once before restoring members through their own loader.
Neither rendering nor image binding hashes an APK or opens DexKit.

Settings uses explicit application identity at the framework/MAML API boundary.
It does not currently cache or select any obfuscated Settings member, so a
Settings-only APK update cannot reuse such a descriptor accidentally. Its
method signatures are validated on the actual host. A future private Settings
adapter should use structural discovery and content identity if those shared
APIs are insufficient; it must not add a version-number lookup table.

Seven archived SecurityCenter APKs from 13.6.0 through 13.8.0 pass independent
DexSQL structure checks and actual AVD DexKit resolution/reflection/restoration.
Both supplied 13.6.6 builds declare versionCode 40001366; 13.8.0 renames the
grid and rail classes. The device matrix atomically replaces the APK at one
fixed path while preserving the previous proof, then restores through a fresh
loader. A separate regression changes DEX bytes while preserving archive
length and modification time. Raw inputs and SHA-256 receipts live under
`.artifacts/inputs/securitycenter/`; the reproducible offline runner is
`scripts/tests/sidebar-profile/securitycenter_matrix.py`. Evidence is retained
under `.artifacts/research/securitycenter-matrix-20261111/`.

This matrix does not install all seven SecurityCenter applications or establish
their full visual behavior on seven devices. The existing Sidebar visual
acceptance remains for the installed Phone AVD host.

New scope authorization and first target loading are separate from ordinary
API 102 updates. Initial activation may require restarting the newly authorized
target. This is not a requirement to restart Launcher, SystemUI or the system
for every setting change.

## Why not one system-server hook?

PackageManager frequently returns application metadata and resource identifiers;
the client process decodes and caches the image locally. Native image loaders can
add another cache. One hook in system_server therefore cannot observe every
local cache read or update every already-bound view.

Other approaches have different costs:

- Injecting into every application increases scope, memory and compatibility
  exposure, including processes that never show a themed application icon.
- Replacing arbitrary Drawables or notification small icons loses semantic
  boundaries and risks changing avatars, settings symbols and profile badges.
- Rewriting or switching the entire applied theme archive on every appearance
  change adds filesystem/application work and still does not invalidate every
  client's already-decoded image. This is not used as a shortcut.
- A synchronous Binder fetch for each image moves decoding and contention onto
  another process without solving local view ownership. It is not the hot path.

## Verification boundary

Phone AVD verification covers dark/light Sidebar rail and all-apps grid,
localized calendar date and minute-level clock snapshots, application management
and application details, and the native HyperOS share sheet without selecting a
recipient. The first-insert regression fails on diagnostic build 535 and passes
with the cache correction. The related 47 device tests pass, including descriptor
rejection/cache restoration, source identity, image ambiguity, worker shutdown,
bounded workload, rendering and resolver presentation. The JVM suite has 1,236
tests, with six skipped and no failures.

The previously running phone-manager UI subprocess needed its first development
activation to load the new target. Subsequent ordinary API 102 updates preserved
its identity as well as Launcher, SystemUI, system_server, Settings and the
phone-manager main process; no native payload activation is part of this change.

The original framework DEX and a root `app_process` read-only probe authenticate
the exact `ApplicationPackageManager.loadUnbadgedItemIcon(PackageItemInfo,
ApplicationInfo): Drawable` signature. Reflection from an ordinary test APK may
hide this non-SDK API; that restriction is not evidence that the system-host
method is absent. The privileged host/module path must be verified separately.

Device evidence, version numbers, screenshots, reload process identities and
known limits are retained under
`.artifacts/research/sidebar-icons-20261110/`. Host/API matching alone is not
visual acceptance, and AVD results do not establish physical-device performance
or universal support for all OEM versions.
