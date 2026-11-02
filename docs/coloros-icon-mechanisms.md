# ColorOS icon resource and time invalidation

Reference: the running ColorOS AVD `emulator-5574`. Target: HyperOS
`emulator-5580`. The source APK/JAR hashes were compared with the live files,
not inferred from another firmware release.

| Input | Live path | SHA-256 |
| --- | --- | --- |
| Launcher 170030011 | `/system_ext/priv-app/OplusLauncher/OplusLauncher.apk` | `b67118677ed9f6bc23ecbd22a2f7f4826b4f31c08641347cc58648fa986196fc` |
| Framework extension | `/system/framework/oplus-framework.jar` | `52c4783bc6027756b56dcb7780d838dd4b36b9726b28f4db2f0a03d49237a0ae` |

The inspected classes include `BaseIconCache`, `IconCacheUpdateHandler`,
`IconCacheExtImplV2` and `IconProvider.IconChangeReceiver`. Prior artwork,
normalization, night filter and dynamic resource work is in
[coloros-port.md](coloros-port.md) and
[coloros-dynamic-contract.md](coloros-dynamic-contract.md).

## Findings and corresponding changes

| ColorOS mechanism | KeiHome behavior |
| --- | --- |
| Separate `icon`, `icon_dark` and `mono_icon`, with component/profile ownership | Keep colored day/night artwork separate from the monochrome policy. Pack coverage continues to veto an unrelated APK monochrome layer. |
| Resource/source version and configuration participate in cache validity | Mask verdicts now include all base/public/split APK paths, lengths and modification times. Resource-affecting configurations invalidate the relevant policy. |
| `OVERLAY_CHANGED`, including overlays targeting `android` | Protected package-URI observers in the App and system worker. A package overlay invalidates that package; an Android framework overlay invalidates the whole resource policy. |
| Locale and resource qualifiers affect system state | Locale, layout direction, density, color mode and asset paths schedule resource reconciliation. Orientation, keyboard and font scale alone do not trigger that system-wide mask probe. Night mode retains its dedicated existing path. |
| Date, clock setting and timezone events invalidate time-dependent icons | Resolve the exact owners of KeiHome's canonical calendar/clock manifests and enqueue those packages through the existing refresh generation. |
| Clock/calendar ownership is explicit | No prefix matching, foreign MAML execution, or additional minute timer. Existing native MAML owns ordinary animation and minute updates. |

Resource-only owners without a launcher activity are retained after a successful
`LauncherApps.getApplicationInfo` check. Different source/icon identities across
profiles are denied by the package-keyed monochrome selector rather than merged
into an ambiguous result.

`IconMaskCache` keeps four configuration/user buckets and at most 2,048 package
verdicts per bucket. Usable, absent and unusable masks are reused until their
source changes or an explicit invalidation arrives. Unknown probes retry after
60 seconds. The cache retains verdicts and fingerprints, not bitmaps or Android
resource objects. Revision checks and cancellation prevent an invalidated probe
from publishing an obsolete result.

## Device evidence

Evidence is local and ignored under
`.artifacts/research/coloros-icon-mechanisms/`.

- A permission-free resource fixture changed from a red icon with an empty
  monochrome layer to a blue icon with a usable layer through an RRO.
  App previews and the native policy updated without manual refresh. Initial
  validation probed 99 masks; subsequent fixture changes probed 1 and reused 99.
- `wm density 481`, followed by `wm density reset`, exercised full resource
  revalidation and restored the original physical density of 480.
- Changing `Asia/Singapore` to `Etc/GMT+12` crossed from November 2 to November 1.
  The desktop calendar previously retained the old day. With targeted time
  invalidation it showed Sunday/1, then Monday/2 after timezone restoration.
- The launcher, System UI and system-server PID/start identities remained
  unchanged. All deployment used API 102 Java reload.

`prepare-icon-resource-fixtures.py` builds the fixture and RRO without installing
anything. The overlay resource contract follows the
[Android RRO documentation](https://source.android.com/docs/core/runtime/rros).

The overlay acceptance above proves the App preview and native mask/refresh
policy. It does not establish a photographed desktop transition for the fixture
or visual coverage of every resource-only package. Unrecognized dynamic scripts
remain outside the admitted canonical template set. Historical native launcher
watchdog and Flutter image-lifetime investigations are separate.
