# KeiHome extra dark icons

In **Icon packs > Icon completion and fallback > KeiHome extra icons**,
compare the current pack and KeiHome dark artwork in each app row. The search
field matches app names and package names. Each switch defaults off, and the
help button explains when the selection takes effect.
Update the current icon pack after changing the switches, then apply it
through the existing Themes import flow.

The first six adaptations were designed against these installed adaptive
foregrounds. KeiHome draws them from the installed APK at runtime. The
APK copies, upstream artwork, and reference theme are local test fixtures.

| App | APK version checked | Dark treatment |
| --- | --- | --- |
| Scene | `9.3.11` | Slate gradient and the original colored robot. The separate App Freezer activity keeps its own icon. |
| MT Manager | `2.26.11` | Blue-gray gradient with the original multicolor mark. |
| [LibChecker][libchecker] | `2.5.5.dev.f9e0ca2` | Plum gradient behind the original light chip. |
| [KernelSU][kernelsu] | `v3.3.1-54-gff70c90d` | Charcoal gradient and a light version of its geometric mark. |
| [Updater][updater] | `1.7.0` | Navy gradient with a pale-blue arrow. |
| [MeiloX][meilox] | `1.55.1` | Muted rose gradient with the original artwork colors. |

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

[libchecker]: https://github.com/LibChecker/LibChecker/tree/dbe030504bbb3a449a426940bbda9b76de6017b3
[kernelsu]: https://github.com/tiann/KernelSU/tree/2a60b2893b8d0d885d1ceff2287d8ad854f0e0f4
[updater]: https://github.com/YuKongA/Updater-KMP/tree/f895e07e307ab48ad042a74cc60f831a08c5c2f0
[meilox]: https://github.com/MeiloX/MeiloX/tree/9e85247d54e389002c9fdaf989b34838c4ec30bb
