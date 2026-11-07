# Monet icon appearance during app transitions

Status: investigation only. No production fix or third-party compatibility
branch has been added. HyperCeiler was not installed for this investigation.

## Reported environment and visual evidence

The supplied About screenshot identifies KeiHome `1.5.0+g1bf0c4c.b0218`,
launcher 8593, Rust 3.9.0 and Flutter 3.38.3. The Monet screenshot selects the
home-screen wallpaper as the color source. The active icon pack is not identified
by the supplied screenshots.

In the first recording, the KeiHome icon changes from its pink monochrome
appearance to the app's original multicolor artwork and then back. The temporary
icon includes both mint and coral details, matching the original launcher
resources in `app/src/main/res/values-night/colors.xml`. This is stronger evidence
of switching icon representations than of a global palette changing.

The surrounding icons do not show the same collective color change. The second
recording documents Telegram transitions, but it does not establish which artwork
or per-app Monet policy the selected icon pack is supposed to use.

Extracted first-recording comparison, approximately 0.0, 3.6 and 4.1 seconds:

```text
.artifacts/research/monet-transition-20261107/keihome-color-source-comparison.png
```

The recordings alone cannot identify whether the representation switch occurs in
the transition snapshot, drawable/cache selection, or the resumed MAML first
frame. They also cannot identify which module caused it.

## Log coverage and module scope

In `LSPosed_20261107_155549.zip`:

- The current module log is 21 bytes: only its starting marker.
- The current verbose log is 309 bytes: a native SIGABRT for a process named
  `init` and a failed crash-dump attachment. No stack identifies a launcher or
  KeiHome failure.
- Older logs explicitly report `Logd maybe crashed` and retries.
- Current module, verbose and `full.log` files contain no KeiHome/Monet runtime
  records covering the reported operation.

The exported scope table assigns `com.miui.home` to all three of:

```text
com.sevtinge.hyperceiler
dev.codex.miuibackgesturehook
os.kei.hyper.home
```

Scope membership does not reveal which feature switches were enabled or which
hooks actually ran. The supplied logs cannot attribute this report to
HyperCeiler, MiuiBackGestureHook, or KeiHome.

## Relevant upstream and local behavior

HyperCeiler source at commit `293cbc08d65fafe0179a49a7b0d7338261572b87`
contains two potentially overlapping features:

- [EnableIconMonoChrome](https://github.com/ReChronoRain/HyperCeiler/blob/293cbc08d65fafe0179a49a7b0d7338261572b87/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/home/title/EnableIconMonoChrome.java)
  forces `MonochromeUtils.isSupportMonochrome()` to return true.
- [EnableIconMonetColor](https://github.com/ReChronoRain/HyperCeiler/blob/293cbc08d65fafe0179a49a7b0d7338261572b87/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/home/title/EnableIconMonetColor.kt)
  replaces the launcher's `monochrome_default` color using the system accent or
  its own configured color.

These are potential overlap points, conditional on those features being enabled
and applicable to the installed launcher. This upstream revision is not proof of
the user's installed module revision or configuration.

KeiHome also has separate state-consumption paths: generated MAML reads the
packed `keihome_maml_palette_state`, while native icon selection uses the native
Monet settings/policy. The generated palette expression selects base artwork
when its palette state is zero. A new/uninitialized variable context or an
animation path selecting a different drawable is therefore worth checking.
Neither event has been established in the user's runtime by these logs.

If isolated reproduction identifies a KeiHome defect, investigate its final-icon
snapshot selection and last-valid-frame reuse before adding refreshes. Avoid
extra per-frame queries, higher idle frame rates, repeated full icon refreshes,
or package-specific compatibility hooks as speculative remedies.

## Bounded Phone AVD check

On `emulator-5580`, the same KeiHome release and launcher 8593 were checked with
system colors / AOSP standard and the existing ColorOS Classic ART+ pack.
Programmatic launch/Home cycles for LibChecker, Settings and KeiHome did not show
the reported clear multicolor fallback in the sampled recordings.

This is a limited smoke test: HyperCeiler is absent, but MiuiBackGestureHook
remains in the AVD's launcher scope. The test does not cover a native icon tap or
the user's complete icon-pack/wallpaper configuration. It is not an isolated
KeiHome-only reproduction and does not establish a third-party cause.

Launcher PID 9732, system_server 8850 and SystemUI 9699 remained unchanged.
Monet was restored OFF, the packed/native enable fields returned to zero, the
original native color field was retained, and the previously foreground HQ Icon
activity was restored. No APK was installed and no process was forcibly restarted.

Local evidence, source checksums, recordings and scope/restoration receipts:

```text
.artifacts/research/monet-transition-20261107/evidence.json
.artifacts/research/monet-transition-20261107/source-references.json
.artifacts/research/monet-transition-20261107/avd-check.json
```

The next discriminating user-side check is the same pack, wallpaper and Monet
configuration with only KeiHome in the launcher scope, after restarting the
launcher so other modules' startup hooks are absent. If that reproduces, treat
it as a KeiHome defect. If it disappears, document the overlap and recommend
disabling the competing icon/color/animation feature, without adding bespoke
third-party compatibility. Functional diagnosis requires working LSPosed logs
covering the reproduction; App-only logs cannot replace them.
