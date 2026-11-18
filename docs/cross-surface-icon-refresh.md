# Cross-surface icon refresh

KeiHome keeps the launcher, recent tasks, and SystemUI icon paths separate. A
launcher icon cache update does not automatically replace a `TaskDescription`
bitmap already held by the recent-task surface, and SystemUI may keep an
independent application-icon bitmap cache.

The current implementation uses one bounded worker cache for verified static
resources and the closed dynamic templates already emitted by KeiHome
(ColorOS and Apple calendar/clock). It reads only bounded PNG/XML entries from the
installed theme archive. Foreign MAML documents, binders, expressions, and
arbitrary content providers are never executed. Dynamic snapshots are
appearance- and locale-aware; minute changes invalidate only the dynamic
entry.

The recent-task bridge checks that the base intent and available task roots
identify the same package for the current user. It rejects document,
multiple-task, excluded, alternate-identity, and cross-package entries.
An Intent action or URI can still lead to a normal package task. The worker
prefers artwork for a verified launcher entry, including a dialer alias,
then shares the package artwork across other entry points. Package fallback
requires that the task has no explicit icon resource, bitmap, or filename.
Explicit icons retain the existing plain launcher-intent and verified
launcher-entry checks. The bridge replaces the outgoing copied
`TaskDescription` icon and preserves the title, colors, description and other
task fields. A cache miss leaves the system result unchanged.
Native description notifications retain `RunningTaskInfo`. Incoming events use
the platform Parcelable copy; refresh replays reproduce the system's lite event
with task ID, user/display IDs, token and a copied description. A
`RecentTaskInfo` is never sent to a listener that casts to `RunningTaskInfo`.
After installation, one worker-side native recent-task query also registers
existing tasks, so previously cached offscreen icons can be invalidated without
reopening their apps. No periodic task query is added. Retention is bounded to
64 tasks, whose 192px ARGB snapshots fit within the 9 MiB pixel cache budget and avoid
cache-ready eviction/reload churn; tasks outside the bound retain native behavior.
Inactive tasks may have no live `baseActivity`. Retained roots must belong to
the same package. A direct launch of a declared launcher component can have
neither a `MAIN` action nor `LAUNCHER` category. The cache worker confirms the
component against the package's launcher entries before rendering either kind
of task. Document intents, routes with data, and unrelated activities retain
their native task icons.

Package-only artwork can supply the icon when the activity has no usable
theme resource. When the installed theme has no KeiHome icon, the recent-task
bridge uses KeiHome's appearance-qualified APK drawable. Other apps without
artwork retain the system icon.

Returning a copy from a query and notifying task listeners are separate
operations. Only the latter records a delivered snapshot. The bridge keeps at
most one pending replay per task, reads the current cache entry on delivery,
and replays the saved native description when a replacement no longer exists.
Delivery tracking uses weak bitmap references so older pixels can be released
after cache invalidation. The worker yields after eight render requests, and
minute ticks target only dynamic artwork.

November 19, 2026 verification used Debug `112000736`. Phone AVD Home 9261
and physical Home 9259 both updated the existing Bilibili task with its
original task ID and app process. The physical recent-task query returned 21
entries and tracked 15 eligible candidates for resolution. KeiHome and
Bilibili both showed the active theme's dark artwork on the physical phone.
On the AVD, both cards also followed a light-to-dark round trip without
reopening either app. Both devices retained their original theme hash, palette,
night setting, and critical process IDs. Captures and deployment receipts are
under `.artifacts/research/recents-retained-20261119/`.

Debug `112000739` extended the same path to retained tasks opened through
Dial, theme details, and Security Center alerts. On physical Home 9259,
the original tasks 15548, 15526, and 15518 received dark icons without
reopening the underlying activities. The dialer used its launcher alias.
Themes and Security Center had no enabled launcher entry and used their
package artwork. The same scroll through Recents confirmed KeiHome and
Bilibili remained dark. Captures and task records are under
`.artifacts/research/recents-app-identity-20261119/`.

The SystemUI bridge is deliberately narrower. It resolves
`AppIconsManager.getAppIconBitmap(int, String): Bitmap` and replaces only a cache hit
for the current user. Misses, badges, work-profile presentation, and every
other SystemUI path remain native. The bridge has a bounded worker, a
generation guard for hot reload, and no image decoding or Binder work on the
hook thread. If the current SystemUI plugin loader does not expose the
authenticated method, the bridge disables itself and keeps native behavior.
The live host loader is reused and a `Bitmap` is returned, rather than a
`BitmapDrawable`. Existing notification rows are not forcibly rebound.

## Bounded work and refresh policy

Each host uses a background-priority worker. Repeated invalidations share one
drain runnable instead of retaining one queued job per icon per event. Each turn
renders at most eight entries and yields to appearance events and retirement.
A contiguous drain reuses its ZIP directory and reads locale/night/palette state
once. New appearance generations close the old archive and discard obsolete
unpublished results.

The bitmap budgets are 64 recent-task and 32 SystemUI application icons.
At 192px ARGB, the respective pixel budgets are 9 MiB and 4.5 MiB. Recents
can retain up to 128 lightweight lookup identities, since a task may need
both a launcher-entry check and a package snapshot. Remembering the negative
entry results keeps queries from repeating as older cards are visited.
Access-order retention allows new apps to replace old entries. Native owners
may retain delivered bitmaps, which are never recycled on eviction.

Only recent tasks need a coalesced ready notification. SystemUI has no empty
ready callback or forced row rebind. Neither host prewarms unrelated applications.
Font-scale and orientation-only changes retain the fixed-pixel snapshots;
night/locale changes still invalidate them. Status-bar notification glyphs,
custom/conversation artwork, badges and animation ownership stay native.

Real-worker device tests cover 100 invalidations with one shared palette read
for 32 retained icons, eight-item yielding, memory-only cache hits, replacement
across 160 names without recycling an externally held bitmap, configuration
filtering and idle startup without prewarming. These deterministic bounds do not
establish a universal CPU/FPS or battery improvement. The November 10 cost pass
passed 59 focused JVM tests and 20 Phone AVD device tests. Its receipt is
`.artifacts/research/surface-icon-cost-20261110/acceptance.json`.

## November 10 repair

Three independent faults explained the reported symptoms:

1. Cache shutdown posted cleanup and immediately checked worker liveness, so
   the first retirement could fail. Shutdown now confirms cleanup and thread
   exit within a bounded deadline, retains retry state, and never joins from
   the main/cache thread.
   Runtime retirement also closes admission before bounded callback draining,
   avoiding immediate rejection simply because a short callback is in flight.
2. Recent-task initialization used the `android` context's nonexistent
   application code cache. Its optional parser fallback now uses a module-owned
   system directory. Source authentication also follows
   `fillTaskInfo → getTaskDescription → mTaskDescription`, rather than requiring
   a direct field read in the caller.
3. Older ColorOS calendars used a different fixed color pair. The renderer now
   recognizes that complete historical template and draws the date and localized
   weekday, instead of publishing only its empty background.

Phone AVD verification includes visible light/dark Calendar date/weekday changes
in existing recent tasks, successful activation of the narrow SystemUI bridge,
and consecutive API 102 deployments with unchanged Launcher, SystemUI and
system-server identities. No scope restart was needed during this repair.
The inactive-task repair increased admitted tasks from 4 to 11 out of the same
15 returned tasks, and refreshed an older KeiHome card without reopening it.
The November 9 development restart remains separate historical evidence.
Other launcher versions, physical devices and every notification layout are
outside this acceptance claim.

Receipts: `.artifacts/research/cross-surface-icons-20261110/acceptance.json`.

The optional `RecentTaskNotificationCopyDeviceTest` uses
`-e surfaceSystemContracts true`. Its token fixture requires hidden-API access,
matching the privileged production caller; the Phone AVD test temporarily
relaxes `hidden_api_policy` and restores its exact previous value afterward.
Ordinary app instrumentation does not exercise this privileged fixture.
