# Super Island app identity icons (2026-11-14)

## Installed-code boundary

The public template/style guide does not describe all private icon paths.
This investigation follows the installed AVD host and plugin DEX:

| Component | Version | SHA-256 |
| --- | --- | --- |
| `com.android.systemui` | `17.03.260226.r` / `202602260` | `067556deaa7ad2791debcef6a13903a8f6aa76b79a29a9c3a7aea9107ec5672b` |
| `miui.systemui.plugin` | `18.3.2.40.1` / `183024001` | `2a6308d02d625bc72f8344ff3db8c1a9a9b06b23b69f1696bce55972f0435b90` |

The default application-icon path passes through
`DynamicIslandUtils.getAppIcon(Context, String, Integer)` and
`miui.systemui.drawable.AppIconsManager.getAppIcon(Context, ApplicationInfo,
String, Integer)`. The latter has its own per-user weak **Bitmap** cache.
This is separate from the ordinary notification-header manager.
Actual callers, the native loader, cache methods and factory are checked as a
single profile before installing the hook.

The holder's explicit-picture, Lottie and video branches do not belong to this
factory. The new hook does not inspect or replace their supplied pictures,
media artwork or custom focus views. The system remains responsible for
resolving proxy package identity before the factory call.

## Integration and ownership

The adapter accepts only an exact requested-package / ApplicationInfo match
with consistent UID, requested user and current process user. A ready published
bitmap can replace this application-icon result. A miss executes native code
once and records its returned Drawable identity for the existing view-binding
path. Native failures are not retried.

The existing bounded icon cache, source invalidation and ImageView replay
machinery are reused. No new polling worker, frame hook, global view-tree walk,
notification repost or permission setting is introduced. Retirement fences
publication before clearing the shared bindings. A cancelled module lookup
keeps the native bind and does not disable later lookups.

## Acceptance limits

The AVD module log confirms that this exact factory profile was enabled after
ordinary Java hot reload, with critical process identities preserved.
Six focused JVM profile tests and nine Android adapter tests passed. They
cover wrong identity/user, cold-cache fallback, cancelled lookups, duplicate
native invocation, stop races and native exceptions. The original cancellation
test failure and the corrected native-fallback rerun are both retained.

This is a **future-bind** integration. No proven native holder registry was
found for replaying an island that was already visible before module
initialization. Such holders are not forcibly rebuilt. A surviving expanded
holder is therefore not an accepted immediate-hot-reload refresh case.

The owned fixture was admitted and displayed as a real compact island on the
AVD. A normal short tap expands it through the native `ClickDynamicIsland`
route; long press belongs to drag/share handling. The default Weather
application icon shows the generated complete night cloud in both forms.
The explicit-picture negative control instead keeps the supplied blue day
artwork in both forms. The four captures are retained; this is observed
picture preservation, not only an inference from the factory call. These
observations cover the owned template fixture, not every media/custom template.

The task artifact directory `super-island-audit` contains exact APK provenance,
DEX queries/disassembly and targeted decompilations. `island-qa-runtime`
retains the fixture attempts and outcomes, including failed attempts.
