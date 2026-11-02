# Native icon packs

KeiHome adds an Icon packs tab alongside Status, Apps, Monet and Settings.
The source card queries the applied **Icon component**, not the encompassing theme.
Local import, native-library import and the system-applied component are separate states.

## Source and ownership

The system module reads the icon provider with its supported projection, and
cross-checks the native default flag/title against a second identity read. A
missing identity is unavailable, not an assumed default. The native component
localId links an imported pack to the applied source; metadata SHA-1 is never
compared to the archive's SHA-256. Runtime receipts are private and scoped to the
requesting user. The existing theme inventory protects recognized package or
activity artwork from APK monochrome replacement. An incomplete replacing theme
withdraws guessed source decisions until it can be indexed.

## Import path

- Installed APKs and SAF-imported APK/ZIP/MTZ files are cataloged without broad
  storage permissions. The all-installed Apps browser uses `QUERY_ALL_PACKAGES`
  to include non-launcher and system apps; pack discovery still uses its existing
  package queries. Foreign APK code is not loaded by the previewer.
- APK/classic ZIP conversion exports static icons for current launcher entries.
  Both light and dark trees are emitted; new apps or pack updates require updating
  the imported snapshot. Unsupported masks/clock/calendar effects remain notices.
- Native MTZ/icons ZIPs retain the entire inner module byte-for-byte, including
  MAML, filters, aliases and night resources. KeiHome does not execute MAML previews.
- The icons-only MTZ uses description.xml, icons and preview/preview_icons_0.png.
  HyperOS night resources use **nightmode/res/drawable-xxhdpi/**, verified against
  framework ThemeResources and launcher 8593's ThemeResources.getFileStream.
- The app offers its generated file through a system-UID-only private provider.
  An owned import worker copies it to system storage and uses Themes' original
  PC importer through a private bound-service bridge. Themes is an additional
  module scope. The bridge discovers the original Binder by descriptor, permits
  only system UID transactions 2/5 plus a private converted-icon registration
  transaction, and rejects hot reload during active calls.
  The old miui.os.servicemanager registry is deprecated and absent on this AVD.
  It does not run on a configuration hook, UI thread or color worker.
- Native import is currently validated structurally for Themes 11.5.4.0/11540.
  Other versions report unsupported instead of guessing a Binder contract.
- The system Themes apply task owns application. The optional
  [automatic application flow](icon-pack-actions.md) authorizes one imported
  component, runs that native task and returns to KeiHome. KeiHome never
  overwrites the active icons archive directly.

The original transaction can take up to ten minutes. Operation receipts persist
before/after submission, across API 102 owner replacement. Uncertain completion
keeps the source and stage ownership; Check import status only queries the native
library and never repeats the import. Client navigation/timeout does not release
an unresolved operation. A hot reload waits for owned work or rejects retirement
while it is still in flight. A report delivery error cannot turn native success
into failure.

## Converted local resources

The verified Themes build requires store rights even for user-created local
icons. APK/classic-ZIP conversions therefore register a narrowly scoped local
resource record after native import. The registration accepts only the
converter's static PNG envelope, checks its system-owned staging copy, and
binds the native Icon and parent identities to SHA-1 and SHA-256 digests.
The record persists in Themes' private storage. Opaque native MTZ/icon archives
do not receive this conversion eligibility.

The Themes-process hooks keep the original UI and apply task. Rights preflight
recognizes only an exact registered icons component with unchanged content and
no store identifiers. Related-wallpaper application is disabled for that
operation. Post-apply validation recognizes only the corresponding actual
`/data/system/theme/icons` bytes. Other components and store resources follow
the original checks. A failed registration leaves the successful library import
intact and is shown separately in KeiHome.

These are inspected 11540 contracts, not a universal Themes API. Hooks share
the import bridge's retirement gate and use API 102 replacement. An active
Binder/apply/check callback prevents retirement; late retired callbacks return
typed rejection without using an inactive Xposed chain.

The framework also performs a separate periodic validation in `system_server`.
Its `miui.drm.ThemeReceiver` calls `DrmManager.isLegal(Context, File, File)` and
resets every rejected active component. Approval in the Themes process alone
does not survive that check.

The system runtime now registers the completed conversion proof after the
import receipt is committed. A private system-owned AtomicFile stores the
operation token, user, native localId and both archive/component digests.
Only the exact canonical `/data/system/theme/icons`, with a matching full
component SHA-256 and unchanged file identity, receives `DRM_SUCCESS`.
All other paths, unknown hashes, inspection failures and opaque MTZs retain
the original validation. No reset/broadcast suppression is installed.
Registration participates in retirement, and publication follows verified
receipt persistence. An old import lacking proof remains imported but reports
conversion readiness as false until the user updates that import.

## Samples and licensing

AVD-only samples were downloaded from official GitHub release assets and checked
against the published SHA-256. No upstream artwork is bundled in KeiHome.

- [Arcticons Day & Night 15.3.2](https://github.com/Arcticons-Team/Arcticons/releases/tag/15.3.2):
  GPL-3.0 application code; CC BY-SA 4.0 icons. 49,252 component mappings.
- [Lawnicons 2.19.0](https://github.com/LawnchairLauncher/lawnicons/releases/tag/v2.19.0):
  Apache-2.0. Its compiled/obfuscated appfilter is resolved via Android resources.
- [IconPack-to-MTZ format notes](https://github.com/Adiker/IconPack-to-MTZ/blob/main/docs/COMPATIBILITY.md)
  informed packaging research, not a universal apply contract.

See the iconpack package README for parser, resource and archive boundaries.
## AVD integration observations

Release 1793391308 imported Arcticons Day & Night through the new private Themes
bridge. The system receipt was IMPORTED with localId
`11b87d78-a72c-4398-a94a-394d33cb9868`; the app opened that exact Icon detail.
The preceding legacy registry attempt returned UNSUPPORTED before submission.

The first native Apply attempt did not change the active icons. Themes returned
`DRM_ERROR_RIGHT_FILE_NOT_EXISTS` and attempted to fetch rights with a null online
product ID. Import success is not application success. The source card correctly
continued reporting Default. Evidence: `/tmp/keihome-icon-packs/themes-apply.log`.

Launcher 9698/start 3925, system-server 8820/start 2936 and SystemUI 9674/start
3921 remained unchanged across import and the rejected Apply operation. Temporary
SystemUI debugger clones briefly made deployment `pidof` ambiguous; subsequent
stat reads confirmed the original process. The deployment helper now retries
bounded ambiguous observations rather than selecting an arbitrary PID.

The portrait native preview replaces the initial landscape cover, which Themes
center-cropped to unusable fragments.


### Release-only host reflection correction

1793393734 initially failed Themes hot reload with ClassNotFoundException x61.
Its R8 mapping identifies x61 as KeiHome's androidx.lifecycle.MutableLiveData;
R8 rewrote the Class.forName string even though the requested ClassLoader
belonged to Themes. Resolve that argument from the verified host apply signature
instead. The APK DEX class definitions retain the actual host MutableLiveData
name. A current Application class-loader fallback also lets a subsequent reload
recover when a failed predecessor has neither saved state nor a live hook handle.
Evidence: `/tmp/keihome-icon-packs/themes-reload-full.log`.

### Imported APK resource lifetime

At 05:05:58 the app crashed in AssetManager2::FindEntryInternal while
`listImported` read an APK label, after a manually created Resources instance
added/removed providers over system assets. The launcher survived. Imported
APKs now use their ApplicationInfo archive paths with PackageManager's resource
loader, matching the already working installed-pack path. No foreign code or
Context is created, and platform resource management owns the asset lifetime.
Evidence: `/tmp/keihome-icon-packs/catalog-native-crash.log`.

The first converted registration also exposed platform-added Binder flags.
Synchronous registration now rejects FLAG_ONEWAY rather than all nonzero flags;
bookkeeping/AppOps flags do not make a transaction asynchronous. The native
import remained successful, and its separate convertedReady=false receipt
correctly retained the localId for retry.

### October 31 Phone follow-up

Phone was started through `Start KeiOS Phone.command` with its existing userdata.
No physical target was used. Launcher 8593, Themes 11.5.4.0/11540 and the enabled
reference module were retained.

- Before the new framework hook, the 18:46 periodic `ThemeReceiver` validation
  removed the applied Arcticons archive. The source card's return to Default
  reflected a real native reset, although the desktop temporarily retained
  cached artwork. Evidence: `/tmp/keihome-pack-followup/after-pack-status.log`.
- Release `1793448125` replaced `1793395157`; release `1793448956` then replaced
  that build. Both system and Themes Java hooks reloaded through API 102 with
  no deployment restart. The second reload retained the converted receipt.
- Arcticons update registered localId
  `cb76283a-d516-4510-8195-cb36b56a38b1`, component SHA-256
  `f73774d462bd2c94f5382f4e8d82977dcd6cc30b45111e775fffb8c85877c55f`.
  A directed `CHECK_TIME_UP` broadcast exercised the real framework validator;
  logs show it checked the active icons and continued through the remaining
  components. The active archive hash stayed identical.
- Lawnicons imported as localId `e546d1d4-1cfb-46c8-b7bd-43a7b497aaf3`.
  Native Apply selected component SHA-256
  `3331813b282252c753649c87d6fee965348ed5ab43b22d06c70f4a885d4f90e3`,
  and the source card subsequently reported Lawnicons. Covered desktop, folder
  and Dock entries visibly used the converted artwork; uncovered entries kept
  their original sources.
- Across both native applications, observation windows longer than 90 seconds,
  the two module installs and the periodic-validation check, launcher remained
  PID 9685/start 3565, system-server 8810/start 2708 and SystemUI 9676/start 3561.
  Native theme application did recreate the Flutter isolate inside that same
  launcher process. This is not a claim that theme application avoids all
  reload animation or temporary task delay.

The release build, R8 and required lint passed. Fifty-three selected JVM tests
covered archive parsing, conversion receipts, validator failure behavior and
retirement. They are not substitutes for icon rendering acceptance.

### Open rendering boundaries

Arcticons contains distinct black day and white night PNGs, and KeiHome's paired
previews show them correctly. A plain static archive still follows launcher
8593's day-only icon chain; generated conversion archives add the canonical
MAML selector described above so the tested Settings entry switches at runtime.
The experimental native resolver/bridge is retained under
`scripts/tests/theme-night/candidate`, outside the APK build. It is **not
enabled**: resource/archive replacement can occur in place, and the candidate
has no proven way to bind asynchronous archive admission to the live Dart
archive. See that directory's README.

Lawnicons currently exports its appfilter's static color artwork. This does not
implement Lawnchair's separate themed-icon-source protocol or dynamic Monet
recoloring. Its applied output also needs further shape/renderer acceptance.
Neither these limitations nor prior disposed-image/SystemUI stalls are marked
fixed by the successful native import and validation checks.

The APK adaptive preview path retains each `AdaptiveIconDrawable`'s own mask.
It reads the stock `ConfigIconMask` only for a demonstrably rectangular
adaptive source, so rounded adaptive artwork is not clipped a second time.
The AVD Lawnicons resource was inspected in both modes: it remained rounded
and its exported corners were transparent. Ordinary PNG, vector, native MAML
and unknown resources do not use this clipping path.
