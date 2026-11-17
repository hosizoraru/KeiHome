# Splash icon fallback research prototype

This is an opt-in research prototype, not a public feature. It does not change the
published `v1.12.0` tag or expose a setting in the application.

## Current implementation

The existing SystemUI compatibility runtime installs the prototype only when built
with `-Pkeihome.splashIconPrototype=true`. The default is false, so production R8
removes the prototype and its policy. A research build also needs the runtime
property `debug.keihome.splash_icon`:

- Empty: disabled, no per-request observation or mutation.
- `observe`: record Shell's decision without changing it.
- `settings`: permit fallback for `com.android.settings` only.

These are development controls; they are not a substitute for the eventual
user-facing preference and per-application exclusion UI.

The hook reuses Shell's existing splash worker and icon builder. It records the
initial suggested type, QS descriptor presence and effective window attributes,
then checks the final builder state. Only types 3 and 6 with opaque, known
single-color backgrounds, no authored splash icon or branding, no protected
window flags and no QS descriptor can qualify. Other packages and starting types
are excluded. Unknown drawables fail closed.

For an eligible Settings launch, it temporarily selects the ordinary icon type
and removes the solid-color overlay, preserving its effective background color.
The original builder loads the icon. Fields are restored after construction,
including optional diagnostic failures. Nested request state is restored too.
The prototype does not keep a splash visible longer or alter exit-animation code.

It does not yet resolve KeiHome's final icon source/customization priorities,
derive optional colors or expose a production setting.

## Verified on HyperOS AVD

Evidence: `.artifacts/research/splash-icon-prototype-20261116/`.

- Device: `emulator-5580`, `KeiOS_HyperOS4_Phone`, Launcher 9258.
- Installed signed R8 research build: `1.12.0+g7765405.b0516.dirty` /
  `112000516`; identity and SHA-256 are in `build-identity.json`.
- Current `Miui-WindowManager-Shell.jar` SHA-256 matches the locally analyzed
  DEX input: `fdc3f1bbec45673489c7ea3c16a1a9a28ece45eb9eae6d257d2667f207c46e24`.
- Settings: initial type 1 becomes OEM type 6; no QS descriptor, authored icon
  or branding; effective background is a ColorDrawable.
- Observe mode returns no icon view. Settings mode constructs an ImageView.
  Light and dark cold-start recordings show the icon.
- Returning to the existing Settings task did not produce another prototype
  builder event in the recorded warm-return case.
- File Manager logs `outside-prototype` and retains its native no-icon path.
- Turning the property off restores the native no-icon Settings launch.
- Four focused unit test methods passed: new fallback exclusions plus the
  existing background policy. Release build and Lint passed.
- Rebuilding without the Gradle property produced
  `SPLASH_ICON_PROTOTYPE = false`; no prototype/policy class entries remain in
  that build's R8 mapping. That default build was not installed.

The recordings use the emulator console at 60 fps. A 10 fps overview and an
inner-80%-of-screen black-pixel test supplement visual inspection. In the dark
baseline the latter detects a 1.244 s near-black interval; no matching interval
was detected in the recorded enabled cases. This is a pixel heuristic, not proof
that every possible flash or animation discontinuity is absent.

## Performance limits

The three enabled Settings samples report approximately 133, 146 and 270 ms in
Shell's icon-building call. Eligibility preparation is approximately 0.08-0.12 ms.
Native no-icon construction is approximately 0.2-0.6 ms in the observed samples.
These are prototype instrumentation measurements, not statistically controlled
end-to-end latency results.

Displayed/TTID observations vary in both directions between runs; they do not
establish that the feature speeds up startup or adds no blocking. The icon path
needs tracing and a bounded, correctly invalidated resource/cache strategy before
production. Do not claim this prototype is release ready.

## Remaining work

1. Identify and reduce icon decoding/theme lookup/color analysis costs without
   transferring them to the startup critical path.
2. Reuse KeiHome's current effective icon source and customization precedence,
   with cache invalidation for package, icon-pack, theme and configuration changes.
3. Add the real opt-in setting, per-application exclusions and honest help text.
4. Exercise real QS/task snapshots, authored animated/branding artwork,
   translucent/floating windows and more cold/warm launch paths. Unit exclusions
   are not a substitute for that device matrix.
5. Measure repeated release-mode startup latency and inspect complete entrance
   and exit motion at frame granularity.

The system-owned splash cannot fix a blank page that an application draws after
its own first frame. Do not fabricate historical snapshots or hold the starting
window over application content.

## References

- [Android SplashScreen](https://developer.android.com/develop/ui/views/launch/splash-screen)
- [AOSP task snapshots](https://source.android.com/docs/core/perf/task-snapshots)
- Prior ROM-specific research:
  `.artifacts/research/splash-fallback-20261116/FINDINGS.md`
