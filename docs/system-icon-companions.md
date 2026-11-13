# System icon companion audit

Release planning: this audit belongs to **1.10.0 preparation**. It does not
update the published **1.9.0** changelog or claim a release/deployment.

Evidence captured on **2026-11-13**, exclusively from **emulator-5580**, user 0,
Chinese locale `zh-Hans-CN`, Xiaomi `hongkong`, Android 17/API 37, CN region.
Fingerprint:

```text
Xiaomi/hongkong/hongkong:17/CP2A.260605.016/17OS4.0.261009.103256994.QCPECN.S:user/release-keys
```

This is an observed package/default-icon inventory, not a new mapping list.
No app source or mapping was changed. No physical device, app installation,
reboot, setting change, theme change, or notification posting was used.

## Method and evidence

The uninstalled Java probe follows the existing `PreloadInventory.java` and
`KeiOsIconProbe.java` approach: compile with `javac`/`d8`, execute with shell
`app_process`, and query framework PackageManager/resources. It does not call
KeiHome or a custom icon-pack renderer.

- Inventory: **348 system/updated-system packages**, including disabled
  components; no per-package inventory failures. **205** returned the framework
  default activity icon and are excluded from meaningful artwork candidates.
- Detail: **32 packages**, including named phone targets, duplicate-icon groups,
  and a few role-related controls. **21** exact package/component comparisons.
- **PM pixels** means SHA-256 of rendered ARGB bytes from
  `PackageManager.getApplicationIcon()` or `getActivityIcon()`.
  Detailed equality requires all four samples: **96 and 192 px**, each with
  context-local day/night configurations. Captured resource `uiMode` values
  were 16/32. Adaptive foreground/background hashes are also retained.
- **Resolved pixels** use the currently resolved icon resource directly,
  without the PM icon-loading path. OEM overlays can participate here.
- **APK bytes** use a standalone Resources instance with only the package base
  APK/splits added, then hash the resolved icon ZIP entry on-device. The base
  resource is also rendered at 96 px as a separate control. This excludes
  package-specific resource overlays; XML entry equality alone does not prove
  equality of referenced artwork. Numeric resource IDs are not compared across
  packages.
- The phone launcher and People launcher are inspected separately from the
  combined Contacts package's default application icon.

Evidence lives in
[`.artifacts/research/system-icon-companions-20261113/`](../.artifacts/research/system-icon-companions-20261113/):
[device identity](../.artifacts/research/system-icon-companions-20261113/device.json),
[system inventory](../.artifacts/research/system-icon-companions-20261113/scan.json),
[package/component details](../.artifacts/research/system-icon-companions-20261113/detail.json),
[comparison results and duplicate groups](../.artifacts/research/system-icon-companions-20261113/summary.json),
[compact CSV](../.artifacts/research/system-icon-companions-20261113/pairs.csv), and
[rendered PNGs](../.artifacts/research/system-icon-companions-20261113/renders/).
Pair IDs below refer to `summary.json`/`pairs.csv`; full hashes, paths, signers,
component names, and day/night samples are available there.

## Verified phone identities

| Package | PM label | UID | Version | Base APK |
| --- | --- | --- | --- | --- |
| `com.android.incallui` | 电话 | 10125 | `40.01.57`, code 6026 | `/product/priv-app/InCallUI/InCallUI.apk` |
| `com.android.server.telecom` | 通话管理 | 1000 | `17`, code 37 | `/system/priv-app/Telecom/Telecom.apk` |
| `com.android.phone` | 电话服务 | 1001 | `17`, code 37 | `/system/priv-app/TeleService/TeleService.apk` |

`incallui` is signed by certificate SHA-256
`e4431422e25ae61fa3655e9f908a9b40da2acd67fab99b737027c21fd22b7d40`.
Telecom and Phone share signer SHA-256
`c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`.
Their UIDs differ. These facts establish the observed installed identities,
not an authorization to alias other packages signed by the same certificate.

At 96 px their native PM icon hashes begin:

| Package | PM ARGB SHA-256 prefix | Observed artwork |
| --- | --- | --- |
| `com.android.incallui` | `57b214e0801e8b0d` | Flat green phone on a white backplate |
| `com.android.server.telecom` | `40250a2e325823a9` | White phone on dark green |
| `com.android.phone` | `3d9389aab7a36b12` | White phone on bright green with a soft background |

All three are **pairwise different** in PM pixels, resolved-resource pixels,
and base APK entry bytes (P01-P03). Telecom's effective icon is supplied by
`/product/overlay/MiuiTelecomResOverlay.apk`, entry
`res/mipmap-xxhdpi-v4/ic_launcher_phone.webp`; its base APK instead supplies
`res/mipmap-xxhdpi-v4/ic_launcher_phone.png`.

`com.android.contacts/.activities.TwelveKeyDialer`, label **电话**, is a different
subject from the Contacts application icon. Its PM pixels **exactly match
`com.android.phone` in all four samples**, including adaptive layers (P06).
Their resolved pixels and base APK entries differ. Neither `incallui` nor
Telecom matches that launcher (P04-P05).

Consequently, a main-owned exact phone-companion mapping can be justified by
phone semantics, with direct artwork-equivalence evidence specifically for
`com.android.phone` to `TwelveKeyDialer`. It must not describe all three phone
packages as identical native artwork.

## Companion candidates and controls

“Yes” in a pixel column means all four detailed samples matched. “APK bytes”
compares the unoverlaid package icon entry in the captured configuration.
These are candidates for review, not implemented or approved additional mappings.

| Evidence | Exact subjects | PM pixels | Resolved pixels | APK bytes | Interpretation and risk |
| --- | --- | --- | --- | --- | --- |
| P07-P08 | `com.android.providers.contacts` / `com.android.contacts` | Yes, application default only | No | No | Contacts storage is a strong companion candidate. It **does not** match `.activities.PeopleActivity`; the combined app default must not be confused with the People launcher preset. |
| P09 | `com.xiaomi.misettings` / `com.android.settings` | Yes | No | No | Settings companion candidate with native PM substitution/normalization. Do not infer equality of their packaged artwork or all settings subfeatures. |
| P10 | `com.miui.micloudsync` / `com.miui.cloudservice` | Yes | Yes | No | Sync/cloud pair; base resource renders also match at 96 px, while compiled XML entries differ. Notification action/account routing still needs separate acceptance. |
| P11 | `com.miui.nextpay` / `com.miui.tsmclient` | Yes | Yes | No | Labels 智能卡网页组件 / 智能卡 support a web-component/card companion relationship. Resource renders match; XML bytes differ. Payment-specific surfaces may need intentional distinctions. |
| P12 | `com.android.printspooler` / `com.android.bips` | Yes | No | No | Print processing/system-print service pair. PM equality is verified; spooler and backend remain distinct components. |
| P13 | `com.xiaomi.hypercomm` / `com.xiaomi.phone` | Yes | Yes | Yes | Branded HyperComm/HyperPhone pair, including matching packaged artwork. The phone icon has a stacked badge; this is **not** evidence for merging it into the ordinary Phone preset. |
| P18 | `com.android.providers.downloads` / `com.android.providers.downloads.ui` | Yes | No | No | Download manager/UI companion candidate, but this AVD's UI package is a signed `/data/app` install with **neither system flag**, not evidence about the stock UI installation on another image. |
| P19-P20 | `com.android.providers.telephony` / `com.android.phone` or `com.android.mms` | No | No | No | Related telephony/SMS storage roles do not establish shared artwork. Keep outside the phone equality claim. |
| P14 | `com.miui.misightservice` / `com.xiaomi.ab` | Yes | Yes | No | Shared orange Mi brand logo; labels 系统质量监测 / 售后支持. Generic branding is insufficient to merge monitoring and after-sales roles. |
| P15 | `com.qualcomm.wfd.service` / `com.wapi.wapicertmanage` | Yes | Yes | Yes | Shared Qualcomm logo/bytes across Wi-Fi display and WAPI certificate roles. **Reject as a semantic alias**, despite matching pixels, signer and bytes. |
| P16 | `android.ext.services` / `com.android.adservices.api` | Yes | Yes | No | Android services/ad-privacy packages share artwork. Different platform roles; no companion mapping follows from that equality. |
| P17 | `com.miui.cit` / `com.xiaomi.mtb` | No | No | Yes | A resource-byte trap: the same 5,700-byte PNG is assigned density 240 vs 320, and rendered pixels differ. Do not infer native equality from ZIP hashes. |
| P21 + inventory | `com.android.credentialmanager`, `com.android.devicediagnostics`, `com.os.callservice`, `com.xiaomi.barrage`, `com.xiaomi.gnss.polaris` | Yes within group | Yes within group | No for P21 | Shared green Android/grid placeholder across unrelated packages. Reject as a mapping signal; base renders also match for P21 despite differing entry bytes. |

There is **no shared-UID expansion recommendation**. UID 1000/1001, a shared
platform signer, a common vendor logo, or the framework default icon can each
join unrelated packages. Any later implementation should enumerate verified
package/component identities and retain deliberate product distinctions.

## Limits and reproduction

This establishes default PM/resource artwork on this one HyperOS image. It
does not prove which icon a live notification selects: notifications may supply
an explicit `smallIcon`, `largeIcon`, channel artwork, or another component.
No live call, notification, service action, account flow, or alternate theme
was triggered. Day/night configurations were local to the temporary process,
not persistent device settings. Other regions, firmware, packages and themes
remain unverified.

No APK was pulled. Only the owned probe dex and rendered PNGs were transferred.
Probe classes, logs, JSON, CSV and PNGs total about **6.2 MiB** locally. The owned
remote directory `/data/local/tmp/keihome-system-icon-companions-20261113` was
removed after each execution and its absence was verified.

The retained scripts are restricted to `adb -s emulator-5580`:

```bash
python3 .artifacts/research/system-icon-companions-20261113/run_probe.py scan
# With no explicit packages, reuse the 32 names already retained in detail.json.
python3 .artifacts/research/system-icon-companions-20261113/run_probe.py detail
python3 .artifacts/research/system-icon-companions-20261113/summarize.py
```

`run_probe.py` always removes only its owned remote directory in `finally`.
Its Java source and compilation logs are retained; no Gradle build, DexSQL
inspection, application install, or commit was required.
