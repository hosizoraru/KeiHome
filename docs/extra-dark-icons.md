# KeiHome extra dark icons

In **Icon packs > Icon completion and fallback > KeiHome extra icons**,
compare the current pack and KeiHome dark artwork in each app row. The search
field matches app names and package names. Each switch defaults off, and the
help button explains when the selection takes effect.
When the current pack has no preview for an app, the comparison uses a
labeled original from the installed APK.
Update the current icon pack after changing the switches, then apply it
through the existing Themes import flow.

KeiHome renders the artwork from the installed APK while preparing a preview
or icon pack. The APK copies, upstream artwork, and reference theme are local
test fixtures.

| App | APK version checked | Dark treatment |
| --- | --- | --- |
| Scene | `9.3.11` | Slate gradient and the original colored robot. The separate App Freezer activity keeps its own icon. |
| MT Manager | `2.26.11` | Blue-gray gradient with the original multicolor mark. |
| [LibChecker][libchecker] | `2.5.5.dev.f9e0ca2` | Plum gradient behind the original light chip. |
| [KernelSU][kernelsu] | `v3.3.1-54-gff70c90d` | Charcoal gradient and a light version of its geometric mark. |
| [Updater][updater] | `1.7.0` | Navy gradient with a pale-blue arrow. |
| [MeiloX][meilox] | `1.55.1` | Muted rose gradient with the original artwork colors. |
| Downloads | `126.10.08.806002` | Pale green arrow on a dark olive background. |
| Download Manager | `137.26.07.800040` | Green arrow on a dark forest background. Uses the package icon because the provider has no launcher activity. |
| Wallet | `6.115.2.5777.2755` | Original cards on a dark violet background. Removes the blue plate baked into the adaptive foreground. |
| Xiaomi Community | `6.7.20260410` | Original white mark on a dark red background. |
| Services & feedback | `18.0.2.0` | Blue speech bubble on a dark blue-gray background. |
| IT Home | `9.38` | Original adaptive foreground on a dark burgundy background. |
| [Shizuku][shizuku] | `13.6.0.r1086.2650830c` | Original adaptive foreground on a dark navy background. |

For icons with baked backgrounds in the second batch, the renderer isolates the
foreground from the installed artwork and checks its coverage and margins.
An icon that fails those checks retains the pack's existing artwork.

Archive preparation retains light artwork and recognized palette masks.
Pinned icons, preserved originals, and authored live icon programs retain
precedence. Converted packs with Always light selected keep their light
branches. The generation manifest records the selected packages so toggling
an option in either direction can request a new application.

## Physical check

On a Xiaomi 17 Pro running `OS4.0.8.0.XBLCNXM`, Home `801030308`, and the
KeiOS-signed Debug `114000714`, the current ColorOS Classic ART+ pack
rendered all six pairs in the comparison sheet. Instrumented preparation
checked all-off, all-on, MeiloX left off, and Always light archives
through the existing converted-archive validator. The six enabled night
images changed and the corresponding light images matched byte for byte.
The authored-program and pinned-icon regressions passed.

The new screen was also used to enable Scene alone and update the active
pack through Themes. The installed icons archive changed, Scene's night
image changed, and the other five night images and all six day images
matched the original archive. The launcher showed the new Scene icon.
The original archive digest and the Scene switch were restored. Home,
SystemUI, and system_server retained their PIDs during the check.

Evidence is in `.artifacts/research/keihome-extra-icons/`. The wide Pad
layout and active live Monet coloring remain outside this device run.

The inline comparison layout was checked on the same phone with the
KeiOS-signed Debug `114000716`. All six icon pairs fit in portrait. In
landscape, scrolling exposed all six switches clear of the update button.
The toolbar help opened and dismissed in place. See
`.artifacts/research/extra-icons-inline/` for screenshots and UI checks.

The second batch was verified on the same phone with the KeiOS-signed Debug
`114000719`. The publication check
compared all-off, selective, all-on, and Always light archives. In the
Classic ART+ fixture, Download Manager was absent before enabling its
completion. Enabling it added the original day artwork and its dark variant.
All seven comparisons were visible, including the labeled original fallback.
Searching by package name and toggling Shizuku left the other selections
intact. The original-preservation regression suite also passed. The applied
theme archive hash matched the pre-test hash. Device receipts and comparisons
are in `.artifacts/research/extra-icons-batch2/`.

[libchecker]: https://github.com/LibChecker/LibChecker/tree/dbe030504bbb3a449a426940bbda9b76de6017b3
[kernelsu]: https://github.com/tiann/KernelSU/tree/2a60b2893b8d0d885d1ceff2287d8ad854f0e0f4
[updater]: https://github.com/YuKongA/Updater-KMP/tree/f895e07e307ab48ad042a74cc60f831a08c5c2f0
[meilox]: https://github.com/MeiloX/MeiloX/tree/9e85247d54e389002c9fdaf989b34838c4ec30bb
[shizuku]: https://github.com/RikkaApps/Shizuku/blob/2650830c/manager/src/main/AndroidManifest.xml

## Third batch

The renderer reads the artwork from each installed APK. Open-source references
identify the apps and their upstream projects. No app artwork is bundled.

| App | Package | Reference build | Artwork |
| --- | --- | --- | --- |
| [Keyguard](https://github.com/AChep/keyguard-app) | `com.artemchep.keyguard` | `2.14.2` | Adaptive foreground |
| [Key Attestation](https://github.com/vvb2060/KeyAttestation) | `io.github.vvb2060.keyattestation` | `1.8.4` | Tinted adaptive foreground |
| [VPN Hotspot](https://github.com/mygod/vpnhotspot) | `be.mygod.vpnhotspot` | `3.0.8` | Adaptive foreground |
| Music | `com.miui.player` | `4.48.0.1` | Colored mark isolated from the baked icon |
| [Salt Player](https://github.com/Moriafly/SaltPlayerSource) | `com.salt.music` | `12.5.1` | Adaptive foreground |
| [Anywhere-](https://github.com/zhaobozhen/anywhere-) | `com.absinthe.anywhere_` | `2.5.5` | Main launcher alias foreground |
| [LowLevelDetector](https://github.com/imknown/AndroidLowLevelDetector) | `net.imknown.android.forefrontinfo` | `1.18.7` | Adaptive foreground |
| Fileyan | `com.mgenware.fileyan` | `6.32.0` | Adaptive foreground |
| DevCheck | `flar2.devcheck` | `6.67` | Adaptive foreground |
| UU Remote | `com.netease.uuremote` | `4.48.1` | Adaptive foreground |
| [OpenDisplay](https://github.com/peetzweg/opendisplay) | `com.peetzweg.opendisplay` | `1.30.2` | Remove the baked white field, lighten the screen frames, and retain the blue faces with dark eyes and smiles |

OpenDisplay's frames and facial strokes share the original dark ink. The
renderer uses the vertical span of the blue fill in each column to keep the eye
and mouth strokes dark. It reconstructs antialiased edges against the new
background from the installed raster. A substantially different foreground
that fails the coverage check falls back to the pack artwork.

The Xiaomi 17 Pro visual check used `114000723`. The compact preview and its
enlargement show both smiles continuous, including the ends next to the
frames. The Classic ART+ generation test used all 24 installed apps and
compared all-off, all-on, a subset, and Always light output. Existing day
resources were byte-identical and the enabled selections produced dark
variants. The test generated and inspected the archive without applying it
through Themes.

## Weibo Lite recommended mapping

Weibo Lite (`com.weico.international`) can use the current pack's Weibo
(`com.sina.weibo`) artwork when the pack has no dedicated Lite entry. The switch
is on by default. A dedicated Lite icon, a pinned custom choice, or a preserved
original takes precedence. Weibo is never a target of this mapping.

Generation metadata schema 18 records the new choice. Older metadata retains
its original choices. Changing the Weibo Lite switch asks for a pack update
when a current Lite target can use the donor. Device tests cover preview and
archive output with the switch on and off for both day and night in static
conversion and native theme imports.
